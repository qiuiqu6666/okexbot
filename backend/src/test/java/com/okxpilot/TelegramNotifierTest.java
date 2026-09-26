package com.okxpilot;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static com.okxpilot.Domain.*;
import static org.assertj.core.api.Assertions.assertThat;

class TelegramNotifierTest {
    @Test void operationsAreSentAndBackgroundNewsIsNot() {
        assertThat(TelegramNotifier.shouldSend("ORDER")).isTrue();
        assertThat(TelegramNotifier.shouldSend("CONTROL")).isTrue();
        assertThat(TelegramNotifier.shouldSend("NEWS_EVIDENCE")).isFalse();
        assertThat(TelegramNotifier.notableOrderState("filled")).isTrue();
        assertThat(TelegramNotifier.notableOrderState("ACCEPTED")).isFalse();
    }

    @Test void messageNamesEnvironmentAndOrderResult() {
        assertThat(TelegramNotifier.text(TradingEnvironment.LIVE, "CONTROL", "已开启自动交易"))
                .isEqualTo("【实盘 · 交易开关】\n已开启自动交易");
        assertThat(TelegramNotifier.stateText("filled", "{\"filledContracts\":\"0.16\",\"averagePrice\":\"60000\"}"))
                .isEqualTo("已成交，成交张数 0.16，均价 60000");
        assertThat(TelegramNotifier.actionText("CLOSE")).isEqualTo("平仓");
    }

    @Test void positionQueryRecognizesTheCommandAndFormatsHoldings() {
        assertThat(TelegramNotifier.asksPositions("当前仓位")).isTrue();
        assertThat(TelegramNotifier.asksPositions("/positions@okexqiu_bot")).isTrue();
        assertThat(TelegramNotifier.asksPositions("现在怎么样")).isFalse();
        Snapshot empty = new Snapshot(Instant.now(), new BigDecimal("1000"), new BigDecimal("900"), List.of());
        assertThat(TelegramNotifier.positionsSection(TradingEnvironment.DEMO, empty)).contains("当前没有持仓");
        Position position = new Position("BTC-USDT-SWAP", new BigDecimal("0.11"), new BigDecimal("84103.2"), new BigDecimal("92.47"), new BigDecimal("-0.01"), "isolated", "1", 0, new BigDecimal("20"), "", new BigDecimal("84010"));
        String text = TelegramNotifier.positionsSection(TradingEnvironment.LIVE, new Snapshot(Instant.now(), new BigDecimal("1000"), new BigDecimal("900"), List.of(position)));
        assertThat(text).contains("实盘").contains("BTC-USDT-SWAP 多 0.11 张").contains("开仓价 84010").contains("未实现盈亏 -0.01");
    }
}
