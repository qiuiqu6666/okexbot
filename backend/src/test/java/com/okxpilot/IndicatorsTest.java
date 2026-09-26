package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class IndicatorsTest {
    @Test void flatMarketHasNeutralMomentum() throws Exception {
        var rows=candles(40,i->100,i->5);
        var indicators=Indicators.from(rows);
        assertThat(indicators.get("available")).isEqualTo(true);
        assertThat((BigDecimal)indicators.get("ema12")).isEqualByComparingTo("100");
        assertThat((BigDecimal)indicators.get("ema26")).isEqualByComparingTo("100");
        assertThat((BigDecimal)indicators.get("macd")).isEqualByComparingTo("0");
        assertThat((BigDecimal)indicators.get("rsi14")).isEqualByComparingTo("50");
        assertThat((BigDecimal)indicators.get("volumeAverage20")).isEqualByComparingTo("5");
    }

    @Test void risingMarketHasStrongRsi() throws Exception {
        var indicators=Indicators.from(candles(40,i->100+i,i->8));
        assertThat(new BigDecimal(indicators.get("rsi14").toString())).isGreaterThan(new BigDecimal("70"));
        assertThat(new BigDecimal(indicators.get("ema12").toString()))
                .isGreaterThan(new BigDecimal(indicators.get("ema26").toString()));
    }

    @Test void shortHistoryIsUnavailable() throws Exception {
        assertThat(Indicators.from(candles(10,i->1,i->1)).get("available")).isEqualTo(false);
    }

    private static ArrayNode candles(int count,java.util.function.IntFunction<Integer> close,java.util.function.IntFunction<Integer> volume) throws Exception {
        var rows=new ObjectMapper().createArrayNode();
        for(int i=count-1;i>=0;i--) rows.add(rows.arrayNode().add(String.valueOf(i)).add("1").add("1").add("1").add(close.apply(i).toString()).add(volume.apply(i).toString()));
        return rows;
    }
}
