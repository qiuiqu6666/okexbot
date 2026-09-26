package com.okxpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.okxpilot.Domain.Position;
import static com.okxpilot.Domain.Snapshot;

@Component
public class TelegramNotifier {
    private static final Logger log = LoggerFactory.getLogger(TelegramNotifier.class);
    private static final Pattern JSON_FIELD = Pattern.compile("\"(filledContracts|averagePrice)\"\\s*:\\s*\"([^\"]*)\"");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String token;
    private final TradingRegistry trading;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "telegram-send");
        thread.setDaemon(true);
        return thread;
    });
    private long offset;

    public TelegramNotifier(JdbcTemplate db, ObjectMapper json, @Value("${pilot.telegram-token:}") String token, @Lazy TradingRegistry trading) {
        this.db = db;
        this.json = json;
        this.token = token == null ? "" : token.trim();
        this.trading = trading;
    }

    public static boolean shouldSend(String kind) { return kind != null && !"NEWS_EVIDENCE".equals(kind); }

    public static String text(TradingEnvironment environment, String kind, String summary) {
        String scope = environment == TradingEnvironment.LIVE ? "实盘" : environment == TradingEnvironment.DEMO ? "模拟盘" : "系统";
        String body = summary == null ? "" : summary;
        if (body.length() > 3500) body = body.substring(0, 3500);
        return "【" + scope + " · " + kindLabel(kind) + "】\n" + body;
    }

    public static String actionText(String action) {
        return switch (action == null ? "" : action) {
            case "OPEN_LONG" -> "开多";
            case "OPEN_SHORT" -> "开空";
            case "REDUCE" -> "减仓";
            case "CLOSE" -> "平仓";
            case "UPDATE_STOPS" -> "调整止盈止损";
            case "HOLD" -> "观望";
            default -> action == null ? "" : action;
        };
    }

    public static String stateText(String state, String detail) {
        String human = switch (state == null ? "" : state) {
            case "filled" -> "已成交";
            case "partially_filled" -> "部分成交";
            case "canceled", "mmp_canceled" -> "已撤销";
            case "live" -> "仍在挂单";
            case "REJECTED" -> "被交易所拒绝";
            case "UNKNOWN" -> "结果待确认";
            case "APPLIED" -> "保护单已核对";
            default -> state == null ? "" : state;
        };
        String extra = fillText(detail);
        if (extra.isBlank() && detail != null && !detail.isBlank() && !detail.startsWith("{")) extra = detail;
        if (extra.length() > 160) extra = extra.substring(0, 160);
        return extra.isBlank() ? human : human + "，" + extra;
    }

    public static boolean notableOrderState(String state) {
        return state != null && !"ACCEPTED".equals(state) && !"SUBMITTING".equals(state);
    }

    public static boolean asksPositions(String text) {
        if (text == null) return false;
        String value = text.trim();
        int at = value.indexOf('@');
        if (value.startsWith("/") && at > 0) value = value.substring(0, at);
        return "/positions".equals(value) || "/position".equals(value) || "当前仓位".equals(value) || "仓位".equals(value);
    }

    public static String positionsSection(TradingEnvironment environment, Snapshot snapshot) {
        String scope = environment == TradingEnvironment.LIVE ? "实盘" : "模拟盘";
        StringBuilder body = new StringBuilder("【").append(scope).append(" · 当前仓位】");
        if (snapshot == null) return body.append("\n暂时读不到仓位").toString();
        body.append("\n权益 ").append(plain(snapshot.equity())).append(" USDT，可用 ").append(plain(snapshot.available())).append(" USDT");
        if (snapshot.positions() == null || snapshot.positions().isEmpty()) return body.append("\n当前没有持仓").toString();
        for (Position position : snapshot.positions()) {
            BigDecimal contracts = position.contracts();
            String side = contracts != null && contracts.signum() < 0 ? "空" : "多";
            body.append("\n\n").append(position.instrument()).append(" ").append(side).append(" ").append(plain(contracts == null ? null : contracts.abs())).append(" 张");
            body.append("\n开仓价 ").append(plain(position.entryPrice())).append("，标记价 ").append(plain(position.markPrice()));
            body.append("\n名义敞口 ").append(plain(position.notionalUsdt())).append(" USDT，杠杆 ").append(plain(position.leverage()));
            body.append("\n未实现盈亏 ").append(plain(position.unrealizedPnl())).append(" USDT");
        }
        return body.toString();
    }

    private static String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    public void operation(TradingEnvironment environment, String kind, String summary) {
        if (!configured() || !shouldSend(kind)) return;
        deliver(text(environment, kind, summary));
    }

    public void orderState(TradingEnvironment environment, String instrument, String action, String state, String detail) {
        if (!configured() || !notableOrderState(state)) return;
        operation(environment, "ORDER_STATE", instrument + " " + actionText(action) + "：" + stateText(state, detail));
    }

    @Scheduled(fixedDelayString = "${pilot.telegram-poll-ms:5000}")
    public synchronized void poll() {
        if (!configured()) return;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timeout", 0);
            if (offset > 0) body.put("offset", offset);
            JsonNode result = post("getUpdates", body).path("result");
            if (!result.isArray()) return;
            for (JsonNode update : result) {
                offset = Math.max(offset, update.path("update_id").asLong() + 1);
                JsonNode message = update.path("message");
                JsonNode chat = message.path("chat");
                if (!"private".equals(chat.path("type").asText())) continue;
                long chatId = chat.path("id").asLong();
                if (chatId == 0) continue;
                String text = message.path("text").asText("");
                String label = chat.path("username").asText("");
                if (label.isBlank()) label = message.path("from").path("username").asText("user");
                if (text.startsWith("/stop")) {
                    db.update("DELETE FROM telegram_subscriber WHERE chat_id=?", chatId);
                    send(chatId, "已关闭仓位领航通知。再次发送 /start 可以重新订阅。");
                    continue;
                }
                boolean fresh = remember(chatId, label);
                if (asksPositions(text)) {
                    sender.execute(() -> send(chatId, positionReply()));
                    continue;
                }
                if (fresh || text.startsWith("/start")) send(chatId, welcome());
            }
        } catch (Exception e) {
            log.warn("读取 Telegram 订阅失败");
        }
    }

    @PreDestroy public void stop() { sender.shutdownNow(); }

    private boolean configured() { return !token.isBlank(); }

    private void deliver(String text) {
        sender.execute(() -> {
            try {
                for (long chatId : chats()) send(chatId, text);
            } catch (Exception e) {
                log.warn("Telegram 通知失败");
            }
        });
    }

    private List<Long> chats() {
        return db.query("SELECT chat_id FROM telegram_subscriber", (row, n) -> row.getLong(1));
    }

    private boolean remember(long chatId, String label) {
        String safe = label == null ? "user" : label.substring(0, Math.min(80, label.length()));
        if (db.update("UPDATE telegram_subscriber SET label=? WHERE chat_id=?", safe, chatId) == 1) return false;
        db.update("INSERT INTO telegram_subscriber(chat_id,label,created_at) VALUES(?,?,?)", chatId, safe, Instant.now().toString());
        log.info("Telegram 新增订阅");
        return true;
    }

    private void send(long chatId, String text) {
        try {
            JsonNode response = post("sendMessage", Map.of("chat_id", chatId, "text", text));
            if (!response.path("ok").asBoolean(false) && response.path("error_code").asInt() == 403)
                db.update("DELETE FROM telegram_subscriber WHERE chat_id=?", chatId);
        } catch (Exception e) {
            log.warn("Telegram 发送失败");
        }
    }

    private JsonNode post(String method, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + method))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return json.readTree(response.body());
    }

    private String positionReply() {
        try { return trading.positionReport(); }
        catch (RuntimeException e) { log.warn("读取仓位失败"); return "暂时读不到当前仓位，请稍后再试"; }
    }

    private static String welcome() {
        return "已订阅仓位领航通知。实盘和模拟盘的开停、下单、平仓、减仓、成交、撤单、参数修改、AI 决策和异常都会发到这里。发送「当前仓位」可查询持仓，发送 /stop 可关闭。";
    }

    private static String kindLabel(String kind) {
        return switch (kind == null ? "" : kind) {
            case "CONTROL" -> "交易开关";
            case "ERROR" -> "异常";
            case "ORDER" -> "下单";
            case "ORDER_STATE" -> "订单";
            case "CANCEL_REQUEST" -> "撤单";
            case "DECISION" -> "AI 决策";
            case "PREVIEW" -> "AI 预览";
            case "AI_SKIPPED" -> "AI 跳过";
            case "AI_REJECTED" -> "AI 拒绝";
            case "AI_EXECUTION_FAILED" -> "执行失败";
            case "SETTINGS" -> "参数";
            case "RISK_POLICY" -> "风险约束";
            case "RISK_RESET" -> "熔断复核";
            case "CONNECTION" -> "连接配置";
            default -> "操作";
        };
    }

    private static String fillText(String detail) {
        if (detail == null || !detail.contains("filledContracts")) return "";
        String size = "", price = "";
        Matcher matcher = JSON_FIELD.matcher(detail);
        while (matcher.find()) {
            if ("filledContracts".equals(matcher.group(1))) size = matcher.group(2);
            if ("averagePrice".equals(matcher.group(1))) price = matcher.group(2);
        }
        if (size.isBlank() && price.isBlank()) return "";
        return "成交张数 " + size + "，均价 " + price;
    }
}
