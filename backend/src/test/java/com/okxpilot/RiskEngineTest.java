package com.okxpilot;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static com.okxpilot.Domain.*;
import static org.assertj.core.api.Assertions.*;

class RiskEngineTest {
    final RiskEngine risk=new RiskEngine();
    final Instrument instrument=new Instrument("BTC-USDT-SWAP",n("0.01"),n("0.01"),n("0.01"),n("0.1"),n("1000"));
    static BigDecimal n(String x){return new BigDecimal(x);}
    Snapshot account(List<Position> positions){return new Snapshot(Instant.now(),n("1000"),n("500"),positions);}
    Decision open(){return new Decision(Action.OPEN_LONG,"BTC-USDT-SWAP",n("100"),null,n("62000"),n("59000"),"测试信号");}
    Plan validate(Decision d,Snapshot s){return risk.validate(d,Settings.defaults(),s,instrument,n("60000"),n("1000"));}
    @Test void convertsNotionalToContractsRoundingDown(){
        Plan p=validate(open(),account(List.of()));assertThat(p.contracts()).isEqualByComparingTo("0.16");
        assertThat(p.notionalUsdt()).isEqualByComparingTo("96");assertThat(p.reduceOnly()).isFalse();
    }
    @Test void reducesShortByBuyingReduceOnly(){
        Position shortPos=new Position(instrument.id(),n("-0.35"),n("60000"),n("210"),n("0"),"isolated");
        Plan p=validate(new Decision(Action.REDUCE,instrument.id(),null,n("0.5"),null,null,"减仓"),account(List.of(shortPos)));
        assertThat(p.side()).isEqualTo("buy");assertThat(p.reduceOnly()).isTrue();assertThat(p.contracts()).isEqualByComparingTo("0.17");
    }
    @Test void closeNeverOpensReversePosition(){
        Position p=new Position(instrument.id(),n("0.2"),n("60000"),n("120"),n("0"),"isolated");
        Plan result=validate(new Decision(Action.CLOSE,instrument.id(),null,null,null,null,"平仓"),account(List.of(p)));
        assertThat(result.side()).isEqualTo("sell");assertThat(result.reduceOnly()).isTrue();assertThat(result.contracts()).isEqualByComparingTo("0.2");
        assertThatThrownBy(()->validate(open(),account(List.of(p)))).hasMessageContaining("重复开仓");
    }
    @Test void blocksMissingStopAndWrongPriceTick(){
        assertThatThrownBy(()->validate(new Decision(Action.OPEN_LONG,instrument.id(),n("100"),null,n("62000"),null,"开仓"),account(List.of()))).hasMessageContaining("止损");
        assertThatThrownBy(()->validate(new Decision(Action.OPEN_LONG,instrument.id(),n("100"),null,n("62000"),n("59000.01"),"开仓"),account(List.of()))).hasMessageContaining("精度");
    }
    @Test void drawdownBlocksOpeningButAllowsClosing(){
        Snapshot loss=new Snapshot(Instant.now(),n("970"),n("500"),List.of());
        assertThatThrownBy(()->validate(open(),loss)).hasMessageContaining("损失上限");
        Position p=new Position(instrument.id(),n("0.2"),n("60000"),n("120"),n("-30"),"isolated");
        assertThat(validate(new Decision(Action.CLOSE,instrument.id(),null,null,null,null,"平仓"),new Snapshot(Instant.now(),n("970"),n("500"),List.of(p))).reduceOnly()).isTrue();
    }
    @Test void rejectsStaleSnapshotAndInsufficientMargin(){
        assertThatThrownBy(()->validate(open(),new Snapshot(Instant.now().minusSeconds(40),n("1000"),n("500"),List.of()))).hasMessageContaining("过期");
        assertThatThrownBy(()->validate(open(),new Snapshot(Instant.now(),n("1000"),n("10"),List.of()))).hasMessageContaining("保证金");
    }
    @Test void capsWholeAccountExposureIncludingOtherInstruments(){
        Position p=new Position("SOL-USDT-SWAP",n("10"),n("25"),n("250"),n("0"),"isolated");
        assertThatThrownBy(()->validate(open(),account(List.of(p)))).hasMessageContaining("总敞口");
    }
    @Test void rejectsZeroContractSizeAndOversizedRequest(){
        assertThatThrownBy(()->validate(new Decision(Action.OPEN_LONG,instrument.id(),n("1"),null,n("62000"),n("59000"),"开仓"),account(List.of()))).hasMessageContaining("最小下单量");
        assertThatThrownBy(()->validate(new Decision(Action.OPEN_LONG,instrument.id(),n("101"),null,n("62000"),n("59000"),"开仓"),account(List.of()))).hasMessageContaining("单笔");
    }
    @Test void verifiesIndependentHmacVector(){
        assertThat(OkxClient.sign("key","","","The quick brown fox jumps over the lazy dog",""))
                .isEqualTo("97yD9DBThCSxMpjmqm+xQ+9NWaFJRhdZl0edvC0aPNg=");
    }
}
