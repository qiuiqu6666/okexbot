package com.okxpilot;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static com.okxpilot.Domain.Position;
import static org.assertj.core.api.Assertions.assertThat;

class MarketAttentionTest {
    @Test void scheduledAnalysisIsNotDelayedByTheUrgentCooldown() {
        Instant now=Instant.parse("2026-09-27T00:10:00Z");
        assertThat(MarketAttention.shouldAnalyze(true,false,now.minusSeconds(10),now)).isTrue();
        assertThat(MarketAttention.shouldAnalyze(false,true,now.minusSeconds(10),now)).isFalse();
        assertThat(MarketAttention.shouldAnalyze(false,true,now.minusSeconds(61),now)).isTrue();
    }

    @Test void onePercentMoveAndFastPnlRequestAttention() {
        assertThat(MarketAttention.priceShock(Map.of("BTC-USDT-SWAP",new BigDecimal("84000")),Map.of("BTC-USDT-SWAP",new BigDecimal("84900"))))
                .contains("1.07%");
        assertThat(MarketAttention.priceShock(Map.of("BTC-USDT-SWAP",new BigDecimal("84000")),Map.of("BTC-USDT-SWAP",new BigDecimal("84200"))))
                .isNull();
        Position position=new Position("BTC-USDT-SWAP",new BigDecimal("0.11"),new BigDecimal("84000"),new BigDecimal("92"),new BigDecimal("8"),"isolated");
        assertThat(MarketAttention.pnlShock(new BigDecimal("1000"),Map.of("BTC-USDT-SWAP",new BigDecimal("1")),List.of(position)))
                .contains("浮盈亏");
        assertThat(MarketAttention.near(new BigDecimal("84000"),new BigDecimal("84100"))).isTrue();
        assertThat(MarketAttention.near(new BigDecimal("84000"),new BigDecimal("85000"))).isFalse();
    }

    @Test void nextAnalysisLandsOnTheIntervalBoundary() {
        assertThat(MarketAttention.nextSlot(Instant.parse("2026-09-27T00:05:40Z"),300))
                .isEqualTo(Instant.parse("2026-09-27T00:10:00Z"));
    }
}
