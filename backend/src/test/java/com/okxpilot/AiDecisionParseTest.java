package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static com.okxpilot.Domain.Action;
import static org.assertj.core.api.Assertions.assertThat;

class AiDecisionParseTest {
    ObjectMapper json=new ObjectMapper();
    @Test void extraFieldsAndChineseActionStillParse() throws Exception {
        var decision=AiClient.parseDecision(json, """
                {"action":"开多","instrument":"BTC-USDT-SWAP","notionalUsdt":"50","leverage":1,"entryPrice":84000,
                 "takeProfit":86000,"stopLoss":82000,"reason":"多周期共振"}
                """);
        assertThat(decision.action()).isEqualTo(Action.OPEN_LONG);
        assertThat(decision.notionalUsdt()).isEqualByComparingTo(new BigDecimal("50"));
        assertThat(decision.takeProfit()).isEqualByComparingTo("86000");
        assertThat(decision.stopLoss()).isEqualByComparingTo("82000");
    }

    @Test void markdownHoldWithoutNumbersParses() throws Exception {
        var decision=AiClient.parseDecision(json, """
                ```json
                {"action":"持有","instrument":"ETH-USDT-SWAP","notionalUsdt":null,"reduceFraction":null,"takeProfit":null,"stopLoss":null,"reason":"没有足够机会"}
                ```
                """);
        assertThat(decision.action()).isEqualTo(Action.HOLD);
        assertThat(decision.notionalUsdt()).isNull();
        assertThat(decision.instrument()).isEqualTo("ETH-USDT-SWAP");
    }
}
