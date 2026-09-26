package com.okxpilot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class Domain {
    private Domain() {}
    public enum Action { HOLD, OPEN_LONG, OPEN_SHORT, REDUCE, CLOSE, UPDATE_STOPS }
    public record Settings(List<String> instruments, BigDecimal maxOrderUsdt, BigDecimal maxExposureUsdt,
                           BigDecimal maxDailyLossPct, int maxPositions, int leverage, int intervalSeconds) {
        public static List<String> mainstream() {
            return List.of(
                    "BTC","ETH","SOL","LTC","XRP","DOGE","BNB","ADA","AVAX","DOT",
                    "LINK","TRX","BCH","UNI","NEAR","APT","SUI","ATOM","FIL","ICP",
                    "ETC","HBAR","XLM","ARB","OP","INJ","AAVE","SHIB","PEPE","WLD",
                    "SEI","TIA","FET","RENDER","IMX","GRT","LDO","CRV","SAND","MANA",
                    "ALGO","XTZ","STX","WIF","BONK","ORDI","JUP","ONDO","ENA","TAO")
                    .stream().map(s -> s + "-USDT-SWAP").toList();
        }
        public static Settings defaults() {
            return new Settings(mainstream(), new BigDecimal("50"),
                    new BigDecimal("100"), new BigDecimal("2"), 1, 1, 600);
        }
        public void validate() {
            if (instruments == null || instruments.isEmpty() ||
                    instruments.stream().anyMatch(s -> s == null || !s.matches("[A-Z0-9]{2,15}-USDT-SWAP")) ||
                    instruments.stream().distinct().count() != instruments.size()) throw new IllegalArgumentException("请选择至少 1 个不重复的 USDT 永续合约");
            if (!positive(maxOrderUsdt) || !positive(maxExposureUsdt) || maxOrderUsdt.compareTo(maxExposureUsdt) > 0)
                throw new IllegalArgumentException("单笔金额须大于 0 且不超过总敞口");
            if (!positive(maxDailyLossPct) || maxPositions < 1 || leverage < 1 || intervalSeconds < 60)
                throw new IllegalArgumentException("持仓和杠杆至少为 1，日内损失须大于 0，分析间隔至少 60 秒");
        }
    }
    public record Decision(Action action, String instrument, BigDecimal notionalUsdt, BigDecimal reduceFraction,
                           BigDecimal takeProfit, BigDecimal stopLoss, String reason) {}
    public record Instrument(String id, BigDecimal contractValue, BigDecimal lotSize, BigDecimal minSize,
                             BigDecimal tickSize, BigDecimal maxMarketSize) {}
    public record Position(String instrument, BigDecimal contracts, BigDecimal markPrice, BigDecimal notionalUsdt,
                           BigDecimal unrealizedPnl, String marginMode, String positionId, long createdAt,BigDecimal leverage,String tradeId) {
        public Position(String instrument,BigDecimal contracts,BigDecimal markPrice,BigDecimal notionalUsdt,
                        BigDecimal unrealizedPnl,String marginMode) {
            this(instrument,contracts,markPrice,notionalUsdt,unrealizedPnl,marginMode,"",0,BigDecimal.ONE,"");
        }
        public Position(String instrument,BigDecimal contracts,BigDecimal markPrice,BigDecimal notionalUsdt,
                        BigDecimal unrealizedPnl,String marginMode,String positionId,long createdAt) {
            this(instrument,contracts,markPrice,notionalUsdt,unrealizedPnl,marginMode,positionId,createdAt,BigDecimal.ONE,"");
        }
    }
    public record Snapshot(Instant fetchedAt, BigDecimal equity, BigDecimal available,
                           List<Position> positions) {}
    public record Plan(String side, BigDecimal contracts, boolean reduceOnly, BigDecimal notionalUsdt) {}
    public static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
}
