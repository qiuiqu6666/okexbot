package com.okxpilot;

import java.math.BigDecimal;
import java.util.*;

/** Persisted per user/environment under the execution lease. Never populated from model data. */
public class RiskState {
    public BigDecimal peakEquity=BigDecimal.ZERO;
    public String haltReason="";
    public int consecutiveLosses;
    public BigDecimal lossNotionalCap;
    public Map<String,Long> cooldownUntil=new HashMap<>();
    public Map<String,BigDecimal> fills=new HashMap<>();
    public Map<String,Managed> positions=new HashMap<>();
    public static class Managed {
        public String openingId,positionId="",latestTradeId="";
        public long submittedAt,positionCreatedAt;
        public BigDecimal contracts=BigDecimal.ZERO,openedContracts=BigDecimal.ZERO,notional=BigDecimal.ZERO,stopLoss;
    }
}
