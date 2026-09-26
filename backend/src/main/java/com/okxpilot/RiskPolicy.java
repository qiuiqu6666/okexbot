package com.okxpilot;

import java.math.BigDecimal;
import static com.okxpilot.Domain.positive;

/** Independent from model output and legacy UI settings. Percent values are percentage points. */
public record RiskPolicy(BigDecimal maxTradeRiskPct, BigDecimal maxMarginPct,
                         BigDecimal maxDirectionalExposureUsdt, BigDecimal maxCorrelatedExposureUsdt,
                         BigDecimal maxDrawdownPct, int maxConsecutiveLosses, int maxOpensPerHour,
                         int cooldownSeconds, int lossCooldownSeconds, int orderTimeoutSeconds,
                         BigDecimal takerFeeBps, BigDecimal maxSlippageBps, BigDecimal maxSpreadBps) {
    public static RiskPolicy defaults() {
        return new RiskPolicy(n("0.5"),n("60"),n("200"),n("250"),n("5"),3,3,900,3600,60,n("10"),n("20"),n("10"));
    }
    public void validate() {
        bounded(maxTradeRiskPct,"2");bounded(maxMarginPct,"80");bounded(maxDirectionalExposureUsdt,"10000");
        bounded(maxCorrelatedExposureUsdt,"10000");bounded(maxDrawdownPct,"20");
        bounded(takerFeeBps,"100");bounded(maxSlippageBps,"100");bounded(maxSpreadBps,"100");
        if(maxConsecutiveLosses<1 || maxConsecutiveLosses>10 || maxOpensPerHour<1 || maxOpensPerHour>20 ||
                cooldownSeconds<60 || cooldownSeconds>86400 || lossCooldownSeconds<cooldownSeconds ||
                lossCooldownSeconds>604800 || orderTimeoutSeconds<10 || orderTimeoutSeconds>300)
            throw new IllegalArgumentException("风控次数、冷却或订单超时参数超出范围");
    }
    private static void bounded(BigDecimal v,String max) {
        if(!positive(v) || v.compareTo(n(max))>0) throw new IllegalArgumentException("风控数值必须为正且不超过安全上限");
    }
    private static BigDecimal n(String s){return new BigDecimal(s);}
}
