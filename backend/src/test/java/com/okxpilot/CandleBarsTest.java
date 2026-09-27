package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CandleBarsTest {
    @Test void twoFiveMinuteBarsBecomeOneTenMinuteBar() throws Exception {
        long open=1_700_000_400_000L;
        var rows=new ObjectMapper().readTree("[[%d,\"1.5\",\"3\",\"1\",\"2.5\",\"20\",\"0\"],[%d,\"1\",\"2\",\"0.5\",\"1.5\",\"10\",\"0\"]]".formatted(open+300000,open));
        var bars=CandleBars.aggregate(rows,600000,300000);
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).get(0).asLong()).isEqualTo(open);
        assertThat(bars.get(0).get(1).asText()).isEqualTo("1");
        assertThat(bars.get(0).get(2).asText()).isEqualTo("3");
        assertThat(bars.get(0).get(3).asText()).isEqualTo("0.5");
        assertThat(bars.get(0).get(4).asText()).isEqualTo("2.5");
        assertThat(bars.get(0).get(5).asText()).isEqualTo("30");
    }
}
