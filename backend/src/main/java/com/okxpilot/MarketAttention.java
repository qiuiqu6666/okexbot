package com.okxpilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static com.okxpilot.Domain.Position;

/** Local market checks. These never call an AI model. */
final class MarketAttention {
    private MarketAttention() {}
    static boolean shouldAnalyze(boolean scheduled, boolean urgent, Instant lastAnalysis, Instant now) {
        if(scheduled) return true;
        if(!urgent) return false;
        return lastAnalysis==null || Duration.between(lastAnalysis,now).getSeconds()>=60;
    }
    static Instant nextSlot(Instant now,int intervalSeconds) {
        long step=Math.max(60,intervalSeconds)*1000L;
        long next=(now.toEpochMilli()/step+1)*step;
        return Instant.ofEpochMilli(next);
    }
    static String priceShock(Map<String,BigDecimal> baseline,Map<String,BigDecimal> latest) {
        for(var row:latest.entrySet()) {
            BigDecimal before=baseline.get(row.getKey());
            if(before==null || before.signum()<=0 || row.getValue()==null || row.getValue().signum()<=0) continue;
            BigDecimal pct=row.getValue().subtract(before).abs().multiply(new BigDecimal("100")).divide(before,2,RoundingMode.HALF_UP);
            if(pct.compareTo(BigDecimal.ONE)>=0) return row.getKey()+" 价格较上次分析波动 "+pct.toPlainString()+"%";
        }
        return null;
    }
    static String pnlShock(BigDecimal equity,Map<String,BigDecimal> baseline,List<Position> positions) {
        if(equity==null || equity.signum()<=0) return null;
        for(Position position:positions) {
            BigDecimal before=baseline.get(position.instrument());
            if(before==null || position.unrealizedPnl()==null) continue;
            BigDecimal delta=position.unrealizedPnl().subtract(before).abs();
            if(delta.multiply(new BigDecimal("1000")).compareTo(equity.multiply(new BigDecimal("5")))>=0)
                return position.instrument()+" 浮盈亏快速变化";
        }
        return null;
    }
    static boolean near(BigDecimal mark,BigDecimal level) {
        if(mark==null || level==null || mark.signum()<=0) return false;
        return mark.subtract(level).abs().multiply(new BigDecimal("1000")).divide(mark,4,RoundingMode.HALF_UP).compareTo(new BigDecimal("2"))<=0;
    }
}
