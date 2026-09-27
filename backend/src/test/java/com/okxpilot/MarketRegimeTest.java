package com.okxpilot;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MarketRegimeTest {
    @Test void risingCloseThroughPriorHighIsABullishBreakout() {
        var bars=rising(80);
        var signal=MarketRegime.scan("BTC-USDT-SWAP",bars,bars,bars,bars);
        assertThat(signal).isNotNull();
        assertThat(signal.regime()).isEqualTo("BULLISH");
        assertThat(signal.strategy()).isEqualTo("TREND_BREAKOUT");
        assertThat(signal.direction()).isEqualTo("LONG");
    }

    @Test void flatMarketWithoutAReclaimIsNotATrendTrade() {
        var bars=flat(80);
        var signal=MarketRegime.scan("BTC-USDT-SWAP",bars,bars,bars,bars);
        assertThat(signal).isNotNull();
        assertThat(signal.strategy()).isNotEqualTo("TREND_BREAKOUT");
        assertThat(signal.actionable()).isFalse();
    }

    private static ArrayNode rising(int count) {
        ArrayNode rows=JsonNodeFactory.instance.arrayNode();
        for(int i=count-1;i>=0;i--) {
            double close=100+i*0.8;
            double high=close+0.2,low=close-0.2,volume=10;
            if(i==count-1) {close=100+(count-2)*0.8+6;high=close;low=close-0.2;volume=80;}
            rows.add(candle(1_700_000_000_000L+(count-1-i)*300_000L,close-0.3,high,low,close,volume));
        }
        return rows;
    }

    private static ArrayNode flat(int count) {
        ArrayNode rows=JsonNodeFactory.instance.arrayNode();
        for(int i=count-1;i>=0;i--) rows.add(candle(1_700_000_000_000L+i*300_000L,100,100.05,99.95,100,10));
        return rows;
    }

    private static ArrayNode candle(long ts,double open,double high,double low,double close,double volume) {
        ArrayNode row=JsonNodeFactory.instance.arrayNode();
        row.add(Long.toString(ts));
        row.add(Double.toString(open));row.add(Double.toString(high));row.add(Double.toString(low));
        row.add(Double.toString(close));row.add(Double.toString(volume));
        return row;
    }
}
