package com.okxpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import static com.okxpilot.Domain.*;

public class OkxClient {
    private final String key, secret, passphrase;
    private final TradingEnvironment environment;
    private final ObjectMapper json;
    private String accountId="";
    private boolean hedge;
    public String accountId(){return accountId;}
    public boolean hedge(){return hedge;}
    public static BigDecimal signedSize(String posSide,BigDecimal size) {
        if("net".equals(posSide)) return size;
        if("long".equals(posSide)) return size.abs();
        if("short".equals(posSide)) return size.abs().negate();
        throw new IllegalStateException("不支持的持仓方向");
    }
    public static String orderPosSide(boolean hedge,String side,boolean reduceOnly) {
        if(!hedge) return "net";
        if(!reduceOnly) return "buy".equals(side)?"long":"short";
        return "sell".equals(side)?"long":"short";
    }
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    public OkxClient(String key,String secret,String passphrase,ObjectMapper json) {
        this(key,secret,passphrase,json,TradingEnvironment.DEMO);
    }
    public OkxClient(String key,String secret,String passphrase,ObjectMapper json,TradingEnvironment environment) {
        this.environment=environment;this.key=key; this.secret=secret; this.passphrase=passphrase; this.json=json;
    }
    Map<String,String> environmentHeaders(){return environment==TradingEnvironment.DEMO?Map.of("x-simulated-trading","1"):Map.of();}
    public List<String> instruments() {
        var ids=new java.util.TreeSet<String>();
        for(JsonNode row:call("GET","/api/v5/public/instruments?instType=SWAP",null,false)) {
            String id=row.path("instId").asText();
            if(id.matches("[A-Z0-9]{2,15}-USDT-SWAP") && "live".equals(row.path("state").asText()) &&
                    "linear".equals(row.path("ctType").asText()) && "USDT".equals(row.path("settleCcy").asText()) &&
                    id.split("-")[0].equals(row.path("ctValCcy").asText())) ids.add(id);
        }
        if(ids.isEmpty()) throw new IllegalStateException("当前环境未返回可交易币种，请稍后重试");
        return List.copyOf(ids);
    }
    public boolean configured() { return !key.isBlank() && !secret.isBlank() && !passphrase.isBlank(); }
    private static final DateTimeFormatter OKX_TIMESTAMP=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    public static String timestamp(Instant instant){return OKX_TIMESTAMP.format(instant);}
    public static String sign(String secret, String timestamp, String method, String path, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal((timestamp+method+path+body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("签名失败"); }
    }
    public JsonNode call(String method, String path, Object payload, boolean authenticated) {
        if (authenticated && !configured()) throw new IllegalStateException("请在设置中配置当前环境的 OKX 凭据");
        try {
            String body = payload == null ? "" : json.writeValueAsString(payload);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://www.okx.com"+path))
                    .timeout(Duration.ofSeconds(12)).header("Content-Type","application/json");
            environmentHeaders().forEach(request::header);
            if (authenticated) {
                String timestamp = timestamp(Instant.now());
                request.header("OK-ACCESS-KEY",key).header("OK-ACCESS-PASSPHRASE",passphrase)
                        .header("OK-ACCESS-TIMESTAMP",timestamp).header("OK-ACCESS-SIGN",sign(secret,timestamp,method,path,body));
            }
            if (method.equals("POST")) request.header("expTime", Long.toString(System.currentTimeMillis()+10000));
            request.method(method,body.isEmpty()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()!=200) throw new IllegalStateException("OKX HTTP "+response.statusCode()+"；结果需查单确认");
            JsonNode root = json.readTree(response.body());
            if (!root.path("code").asText().equals("0")) throw new Rejected("OKX 拒绝请求，代码 "+root.path("code").asText());
            JsonNode data = root.path("data");
            if (!data.isArray()) throw new IllegalStateException("OKX 响应格式异常");
            for (JsonNode item : data) if (item.has("sCode") && !item.path("sCode").asText().equals("0"))
                throw new Rejected("OKX 拒绝指令，代码 "+item.path("sCode").asText());
            return data;
        } catch (Rejected | IllegalStateException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("OKX 请求被中断；结果需查单确认"); }
        catch (Exception e) { throw new IllegalStateException("OKX 通信失败；结果需查单确认"); }
    }
    public static class Rejected extends RuntimeException { public Rejected(String message) { super(message); } }
    public void checkAccountMode() {
        JsonNode config = first(call("GET","/api/v5/account/config",null,true));
        String mode=config.path("posMode").asText();
        hedge="long_short_mode".equals(mode);
        if(!"2".equals(config.path("acctLv").asText()) || !(hedge || "net_mode".equals(mode)))
            throw new IllegalStateException("仅支持合约模式，持仓模式须为单向或双向");
        accountId=config.path("uid").asText();
        if(accountId.isBlank()) throw new IllegalStateException("无法确认交易所账户身份");
    }
    public Snapshot snapshot() {
        JsonNode balance = first(call("GET","/api/v5/account/balance?ccy=USDT",null,true));
        JsonNode usdt = null;
        for (JsonNode item : balance.path("details")) if ("USDT".equals(item.path("ccy").asText())) usdt=item;
        if (usdt==null) throw new IllegalStateException("未找到 USDT 账户余额");
        List<Position> positions = new ArrayList<>();
        for (JsonNode p:call("GET","/api/v5/account/positions?instType=SWAP",null,true)) {
            BigDecimal size=number(p,"pos");
            if (size.signum()==0) continue;
            if (!p.path("instId").asText().endsWith("-USDT-SWAP"))
                throw new IllegalStateException("账户存在不支持的合约，停止自动交易");
            String avgPx=p.path("avgPx").asText("");
            BigDecimal entry=avgPx.isBlank()?null:new BigDecimal(avgPx);
            positions.add(new Position(p.path("instId").asText(),signedSize(p.path("posSide").asText(),size),number(p,"markPx"),number(p,"notionalUsd").abs(),number(p,"upl"),p.path("mgnMode").asText(),p.path("posId").asText(),p.path("cTime").asLong(),number(p,"lever"),p.path("tradeId").asText(),entry));
        }
        return new Snapshot(Instant.now(),number(usdt,"eq"),number(usdt,usdt.path("availBal").asText().isBlank()?"availEq":"availBal"),positions);
    }
    public Instrument instrument(String id) {
        validateId(id);
        JsonNode i=first(call("GET","/api/v5/public/instruments?instType=SWAP&instId="+id,null,false));
        if (!"live".equals(i.path("state").asText()) || !"linear".equals(i.path("ctType").asText()) ||
                !"USDT".equals(i.path("settleCcy").asText()) || !id.split("-")[0].equals(i.path("ctValCcy").asText()))
            throw new IllegalStateException("仅支持正常交易、币本位面值的线性 USDT 永续合约");
        return new Instrument(id,number(i,"ctVal"),number(i,"lotSz"),number(i,"minSz"),number(i,"tickSz"),number(i,"maxMktSz"));
    }
    public Map<String,BigDecimal> lastPrices(java.util.Collection<String> ids) {
        JsonNode rows=call("GET","/api/v5/market/tickers?instType=SWAP",null,false);
        var want=new java.util.HashSet<>(ids);
        var out=new java.util.LinkedHashMap<String,BigDecimal>();
        for(JsonNode row:rows) {
            String id=row.path("instId").asText();
            if(!want.contains(id)) continue;
            String last=row.path("last").asText("");
            if(!last.isBlank()) out.put(id,new BigDecimal(last));
        }
        return out;
    }
    public BigDecimal price(String id) {
        validateId(id);
        JsonNode t=first(call("GET","/api/v5/market/ticker?instId="+id,null,false));
        long age=System.currentTimeMillis()-t.path("ts").asLong();
        if(age< -5000 || age>30000 || !positive(number(t,"last"))) throw new IllegalStateException("行情已过期或无效");
        return number(t,"last");
    }
    public JsonNode candles(String id) { return candles(id,"5m"); }
    public JsonNode candles(String id,String bar) {
        validateId(id);
        JsonNode rows=switch(bar) {
            case "5m" -> fetchCandles(id,"5m",80);
            case "15m" -> fetchCandles(id,"15m",80);
            case "1H" -> fetchCandles(id,"1H",100);
            case "3H" -> CandleBars.aggregate(fetchCandles(id,"1H",240),10_800_000L,3_600_000L);
            default -> throw new IllegalArgumentException("不支持的K线周期");
        };
        long maxAge=switch(bar) {
            case "5m" -> 600_000L;
            case "15m" -> 1_800_000L;
            case "1H" -> 7_200_000L;
            default -> 14_400_000L;
        };
        if(rows.size()<35 || System.currentTimeMillis()-rows.get(0).path(0).asLong()>maxAge)
            throw new IllegalStateException("K 线缺失或已过期");
        return rows;
    }
    private JsonNode fetchCandles(String id,String bar,int limit) {
        if(!"5m".equals(bar) && !"15m".equals(bar) && !"1H".equals(bar)) throw new IllegalArgumentException("不支持的K线周期");
        return call("GET","/api/v5/market/candles?instId="+id+"&bar="+bar+"&limit="+limit,null,false);
    }
    public JsonNode pendingOrders() { return call("GET","/api/v5/trade/orders-pending?instType=SWAP",null,true); }
    public List<JsonNode> pendingAlgos() {
        List<JsonNode> result=new ArrayList<>();
        for(String type:List.of("conditional","oco","trigger","move_order_stop"))
            for(JsonNode row:call("GET","/api/v5/trade/orders-algo-pending?ordType="+type+"&instType=SWAP",null,true)) result.add(row);
        return result;
    }
    public JsonNode book(String id) {
        validateId(id);return first(call("GET","/api/v5/market/books?instId="+id+"&sz=400",null,false));
    }
    public JsonNode positionHistory(String id) {
        validateId(id);return call("GET","/api/v5/account/positions-history?instType=SWAP&instId="+id+"&limit=100",null,true);
    }
    public void cancel(String id,String clientId) {
        validateId(id);call("POST","/api/v5/trade/cancel-order",Map.of("instId",id,"clOrdId",clientId),true);
    }
    public void leverage(String id, int leverage) { leverage(id,leverage,null); }
    public void leverage(String id, int leverage, String posSide) {
        Map<String,Object> body=new java.util.LinkedHashMap<>();
        body.put("instId",id);body.put("lever",Integer.toString(leverage));body.put("mgnMode","isolated");
        if(posSide!=null) body.put("posSide",posSide);
        call("POST","/api/v5/account/set-leverage",body,true);
    }
    public JsonNode place(Map<String,Object> body) { return first(call("POST","/api/v5/trade/order",body,true)); }
    public JsonNode order(String id, String clientId) {
        validateId(id);
        return first(call("GET","/api/v5/trade/order?instId="+id+"&clOrdId="+clientId,null,true));
    }
    public JsonNode stops(String id) {
        validateId(id);
        var result=json.createArrayNode();
        for(String type:List.of("oco","conditional")) {
            for(JsonNode row:call("GET","/api/v5/trade/orders-algo-pending?ordType="+type+"&instId="+id,null,true)) result.add(row);
        }
        return result;
    }
    public JsonNode amendStop(String id,String algoId,Decision d) {
        return first(call("POST","/api/v5/trade/amend-algos",Map.of("instId",id,"algoId",algoId,
                "newTpTriggerPx",d.takeProfit().toPlainString(),"newTpOrdPx","-1",
                "newSlTriggerPx",d.stopLoss().toPlainString(),"newSlOrdPx","-1","cxlOnFail",false),true));
    }
    static JsonNode first(JsonNode rows) { if (rows.isEmpty()) throw new IllegalStateException("OKX 返回空数据"); return rows.get(0); }
    static BigDecimal number(JsonNode node,String key) {
        String value=node.path(key).asText();
        if(value.isBlank()) throw new IllegalStateException("OKX 缺少字段 "+key);
        return new BigDecimal(value);
    }
    static void validateId(String id) {
        if(id==null || !id.matches("[A-Z0-9]{2,15}-USDT-SWAP")) throw new IllegalArgumentException("合约名称无效");
    }
}
