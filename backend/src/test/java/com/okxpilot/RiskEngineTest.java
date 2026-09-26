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
    Settings limits(){return new Settings(List.of("BTC-USDT-SWAP","ETH-USDT-SWAP","SOL-USDT-SWAP"),n("100"),n("300"),n("3"),2,1,300);}
    Plan validate(Decision d,Snapshot s){return risk.validate(d,limits(),s,instrument,n("60000"),n("1000"));}
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
    @Test void switchesToGpt6SolWhenLunaChannelIsDown(){
        assertThat(AiClient.fallbackModel("gpt-6-luna",503,"{\"error\":{\"code\":\"model_not_found\"}}")).isEqualTo("gpt-5.6-luna");
        assertThat(AiClient.fallbackModel("gpt-5.6-luna",503,"model_not_found")).isEqualTo("gpt-6-sol");
        assertThat(AiClient.fallbackModel("gpt-6-sol",503,"model_not_found")).isEqualTo("deepseek-v4-pro");
        assertThat(AiClient.fallbackModel("deepseek-v4-pro",503,"model_not_found")).isNull();
        assertThat(AiClient.fallbackModel("gpt-6-luna",401,"unauthorized")).isNull();
        assertThat(AiClient.fallbackModel("gpt-4o",503,"model_not_found")).isNull();
    }
    @Test void mapsHedgePositionSideAndOrderSide(){
        assertThat(OkxClient.signedSize("long",n("2"))).isEqualByComparingTo("2");
        assertThat(OkxClient.signedSize("short",n("2"))).isEqualByComparingTo("-2");
        assertThat(OkxClient.signedSize("net",n("-2"))).isEqualByComparingTo("-2");
        assertThat(OkxClient.orderPosSide(false,"buy",false)).isEqualTo("net");
        assertThat(OkxClient.orderPosSide(true,"buy",false)).isEqualTo("long");
        assertThat(OkxClient.orderPosSide(true,"sell",false)).isEqualTo("short");
        assertThat(OkxClient.orderPosSide(true,"sell",true)).isEqualTo("long");
        assertThat(OkxClient.orderPosSide(true,"buy",true)).isEqualTo("short");
    }
    @Test void formatsOkxTimestampWithMilliseconds(){
        assertThat(OkxClient.timestamp(Instant.parse("2020-12-08T09:08:57.715123456Z"))).isEqualTo("2020-12-08T09:08:57.715Z");
        assertThat(OkxClient.timestamp(Instant.parse("2020-12-08T09:08:57Z"))).isEqualTo("2020-12-08T09:08:57.000Z");
    }
    @Test void verifiesIndependentHmacVector(){
        assertThat(OkxClient.sign("key","","","The quick brown fox jumps over the lazy dog",""))
                .isEqualTo("97yD9DBThCSxMpjmqm+xQ+9NWaFJRhdZl0edvC0aPNg=");
    }
    @Test void sizesFromStopRiskAndIncludesRoundTripCosts(){
        Snapshot small=new Snapshot(Instant.now(),n("200"),n("200"),List.of());
        Plan plan=risk.validate(open(),limits(),small,instrument,n("60000"),n("200"));
        assertThat(plan.contracts()).isEqualByComparingTo("0.07");
        BigDecimal estimatedLoss=plan.contracts().multiply(n("0.01")).multiply(n("1000").add(n("60000").multiply(n("0.006"))));
        assertThat(estimatedLoss).isLessThanOrEqualTo(n("1"));
        Decision tighter=new Decision(Action.OPEN_LONG,instrument.id(),n("100"),null,n("62000"),n("59900"),"测试");
        assertThat(risk.validate(tighter,limits(),small,instrument,n("60000"),n("200")).contracts()).isGreaterThan(plan.contracts());
    }
    @Test void directionalCorrelatedAndMarginLimitsAreIndependent(){
        Position same=new Position("ETH-USDT-SWAP",n("1"),n("100"),n("150"),n("0"),"isolated");
        assertThatThrownBy(()->validate(open(),account(List.of(same)))).hasMessageContaining("同方向");
        Position opposite=new Position("ETH-USDT-SWAP",n("-1"),n("100"),n("180"),n("0"),"isolated");
        assertThatThrownBy(()->validate(open(),account(List.of(opposite)))).hasMessageContaining("相关币种");
        assertThatThrownBy(()->validate(open(),new Snapshot(Instant.now(),n("1000"),n("450"),List.of()))).hasMessageContaining("占用比例");
    }
    @Test void bookChecksFreshnessSpreadDepthAndSlippage() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var book=json.createObjectNode().put("ts",System.currentTimeMillis());
        book.set("asks",json.readTree("[[\"60001\",\"0.1\"],[\"60002\",\"0.1\"]]"));
        book.set("bids",json.readTree("[[\"59999\",\"1\"]]"));
        Plan plan=new Plan("buy",n("0.16"),false,n("96"));
        risk.validateBook(book,plan,n("60000"),RiskPolicy.defaults());
        book.put("ts",System.currentTimeMillis()-6000);
        assertThatThrownBy(()->risk.validateBook(book,plan,n("60000"),RiskPolicy.defaults())).hasMessageContaining("过期");
        book.put("ts",System.currentTimeMillis());
        assertThatThrownBy(()->risk.validateBook(book,new Plan("buy",n("1"),false,n("600")),n("60000"),RiskPolicy.defaults())).hasMessageContaining("深度");
        assertThatThrownBy(()->risk.validateBook(book,plan,n("59000"),RiskPolicy.defaults())).hasMessageContaining("滑点");
        book.set("bids",json.readTree("[[\"59000\",\"1\"]]"));
        assertThatThrownBy(()->risk.validateBook(book,plan,n("60000"),RiskPolicy.defaults())).hasMessageContaining("价差");
    }
}
