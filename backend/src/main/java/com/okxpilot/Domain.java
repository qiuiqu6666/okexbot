package com.okxpilot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Domain {
    private Domain() {}
    public enum Action { HOLD, OPEN_LONG, OPEN_SHORT, REDUCE, CLOSE, UPDATE_STOPS }
    public record Settings(List<String> instruments, BigDecimal maxOrderUsdt, BigDecimal maxExposureUsdt,
                           BigDecimal maxDailyLossPct, int maxPositions, int leverage, int intervalSeconds) {
        public static Settings defaults() {
            return new Settings(List.of("BTC-USDT-SWAP", "ETH-USDT-SWAP"), new BigDecimal("100"),
                    new BigDecimal("300"), new BigDecimal("3"), 2, 1, 300);
        }
        public void validate() {
            if (instruments == null || instruments.isEmpty() || instruments.size() > 5 ||
                    instruments.stream().anyMatch(s -> s == null || !s.matches("[A-Z0-9]{2,15}-USDT-SWAP")) ||
                    instruments.stream().distinct().count() != instruments.size()) throw new IllegalArgumentException("请选择 1–5 个不重复的 USDT 永续合约");
            if (!positive(maxOrderUsdt) || !positive(maxExposureUsdt) || maxOrderUsdt.compareTo(maxExposureUsdt) > 0 ||
                    maxExposureUsdt.compareTo(new BigDecimal("10000")) > 0) throw new IllegalArgumentException("单笔金额须大于 0 且不超过总敞口，总敞口上限 10000 USDT");
            if (!positive(maxDailyLossPct) || maxDailyLossPct.compareTo(new BigDecimal("10")) > 0 ||
                    maxPositions < 1 || maxPositions > 5 || leverage < 1 || leverage > 3 || intervalSeconds < 60 || intervalSeconds > 86400)
                throw new IllegalArgumentException("风险参数超出范围：杠杆 1–3、持仓 1–5、日损 0–10%、间隔 60–86400 秒");
        }
    }
    public record Decision(Action action, String instrument, BigDecimal notionalUsdt, BigDecimal reduceFraction,
                           BigDecimal takeProfit, BigDecimal stopLoss, String reason) {}
    public record Instrument(String id, BigDecimal contractValue, BigDecimal lotSize, BigDecimal minSize,
                             BigDecimal tickSize, BigDecimal maxMarketSize) {}
    public record Position(String instrument, BigDecimal contracts, BigDecimal markPrice, BigDecimal notionalUsdt,
                           BigDecimal unrealizedPnl, String marginMode) {}
    public record Snapshot(Instant fetchedAt, BigDecimal equity, BigDecimal available,
                           List<Position> positions) {}
    public record Plan(String side, BigDecimal contracts, boolean reduceOnly, BigDecimal notionalUsdt) {}
    public static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
}
