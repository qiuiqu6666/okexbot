package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static com.okxpilot.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:pilot;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "spring.datasource.username=sa","spring.datasource.password=", "pilot.operator-token=01234567890123456789012345678901", "pilot.poll-ms=3600000"})
@AutoConfigureMockMvc
class TradingIntegrationTest {
    @Autowired Store stores;
    @Autowired AuthService auth;
    Store store;
    TradingService trading;
    AuthService.LoginResult account;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    OkxClient okx;
    AiClient ai;
    NewsService news;
    static BigDecimal n(String x){return new BigDecimal(x);}
    Decision open(){return new Decision(Action.OPEN_LONG,"BTC-USDT-SWAP",n("100"),null,n("62000"),n("59000"),"结构验证");}
    @BeforeEach void setup() throws Exception {
        String username="t"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,24);
        account=auth.register(new AuthService.Credentials(username,"test-password-123"),username);
        store=stores.forUser(account.user().id());
        store.settings(new Settings(List.of("BTC-USDT-SWAP","ETH-USDT-SWAP"),n("100"),n("300"),n("3"),2,1,300));
        okx=mock(OkxClient.class);ai=mock(AiClient.class);news=mock(NewsService.class);
        when(news.evidence(anyList())).thenReturn(new NewsService.Evidence(Instant.now(),false,"test news unavailable",List.of(),List.of()));
        trading=new TradingService(store,okx,ai,new RiskEngine(),json,news);
        when(okx.configured()).thenReturn(true);when(ai.configured()).thenReturn(true);when(ai.model()).thenReturn("test-model");
        when(okx.accountId()).thenReturn(username);
        when(okx.book(anyString())).thenAnswer(x->json.readTree("{\"asks\":[[\"60001\",\"100\"]],\"bids\":[[\"59999\",\"100\"]],\"ts\":\""+System.currentTimeMillis()+"\"}"));
        when(okx.snapshot()).thenAnswer(x->new Snapshot(Instant.now(),n("1000"),n("500"),List.of()));
        when(okx.instrument(anyString())).thenAnswer(x->new Instrument(x.getArgument(0),n("0.01"),n("0.01"),n("0.01"),n("0.1"),n("1000")));
        when(okx.price(anyString())).thenReturn(n("60000"));when(okx.candles(anyString())).thenReturn(json.readTree("[]"));when(okx.candles(anyString(),anyString())).thenReturn(json.readTree("[]"));
        when(okx.pendingOrders()).thenReturn(json.readTree("[]"));when(okx.stops(anyString())).thenReturn(json.readTree("[]"));
        when(ai.decide(any())).thenReturn(open());when(okx.place(anyMap())).thenReturn(json.readTree("{\"ordId\":\"123\"}"));
    }
    @Test void protectedOrderIsPersistedBeforeSendAndAcceptedIsNotFilled() {
        when(okx.place(anyMap())).thenAnswer(invocation->{
            Map<String,Object> body=invocation.getArgument(0);
            assertThat(store.orders()).hasSize(1);assertThat(store.orders().get(0).get("state")).isEqualTo("SUBMITTING");
            assertThat(body.get("sz")).isEqualTo("0.16");assertThat(body.get("reduceOnly")).isEqualTo(false);
            assertThat(body).containsKey("attachAlgoOrds");return json.readTree("{\"ordId\":\"123\"}");
        });
        trading.enable();trading.tick();assertThat(store.orders().get(0).get("state")).isEqualTo("ACCEPTED");
    }
    @Test void uncertainSubmissionSurvivesRestartAndIsNeverRetried(){
        when(okx.place(anyMap())).thenThrow(new IllegalStateException("网络超时"));
        when(okx.order(anyString(),anyString())).thenThrow(new IllegalStateException("查询暂不可用"));
        trading.enable();trading.tick();assertThat(trading.status().get("enabled")).isEqualTo(false);
        assertThat(store.unsettled()).hasSize(1);
        TradingService restarted=new TradingService(store,okx,ai,new RiskEngine(),json,news);
        assertThat(restarted.status().get("enabled")).isEqualTo(false);
        assertThatThrownBy(restarted::enable).hasMessageContaining("待确认");
        restarted.tick();verify(okx,times(1)).place(anyMap());
    }
    @Test void restartRestoresEnabledAutoTrading() {
        trading.enable();
        TradingService restarted=new TradingService(store,okx,ai,new RiskEngine(),json,news);
        assertThat(restarted.status().get("enabled")).isEqualTo(true);
        restarted.tick();verify(okx,times(1)).place(anyMap());
    }
    @Test void switchingEnvironmentKeepsTheOtherSideRunning() throws Exception {
        db.update("UPDATE user_trading_control SET enabled=1 WHERE user_id=?",account.user().id());
        String token="Bearer "+account.token();
        mvc.perform(post("/api/pause").header("Authorization",token).header("X-Trading-Environment","DEMO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/status").header("Authorization",token).header("X-Trading-Environment","LIVE")).andExpect(status().isOk());
        mvc.perform(get("/api/status").header("Authorization",token).header("X-Trading-Environment","DEMO"))
                .andExpect(jsonPath("$.enabled").value(true));
    }
    @Test void pausingTwiceThenSwitchingLeavesTradingOff() throws Exception {
        db.update("UPDATE user_trading_control SET enabled=1 WHERE user_id=?",account.user().id());
        String token="Bearer "+account.token();
        mvc.perform(post("/api/pause").header("Authorization",token).header("X-Trading-Environment","DEMO")).andExpect(status().isOk());
        mvc.perform(post("/api/pause").header("Authorization",token).header("X-Trading-Environment","DEMO")).andExpect(status().isOk());
        mvc.perform(get("/api/status").header("Authorization",token).header("X-Trading-Environment","LIVE")).andExpect(status().isOk());
        mvc.perform(get("/api/status").header("Authorization",token).header("X-Trading-Environment","DEMO"))
                .andExpect(jsonPath("$.enabled").value(false));
    }
    @Test void priceShockAnalyzesAgainBeforeTheInterval() {
        when(ai.decide(any())).thenReturn(new Decision(Action.HOLD,"BTC-USDT-SWAP",null,null,null,null,"没有足够机会"));
        trading.enable();trading.tick();
        trading.rememberAnalyzedAt(java.time.Instant.now().minusSeconds(61));
        when(okx.lastPrices(any())).thenReturn(Map.of("BTC-USDT-SWAP",n("61000")));
        trading.tick();
        verify(ai,times(2)).decide(any());
        verify(okx,never()).place(anyMap());
    }
    @Test void settingsCanChangeWhileAutoTrading() {
        trading.enable();
        trading.settings(new Settings(List.of("BTC-USDT-SWAP","ETH-USDT-SWAP"),n("100"),n("1000"),n("10"),5,20,60));
        assertThat(store.settings().leverage()).isEqualTo(20);
        assertThat(store.settings().maxPositions()).isEqualTo(5);
        assertThat(trading.status().get("enabled")).isEqualTo(true);
    }
    @Test void explicitPauseStaysOffAfterRestart() {
        trading.enable();trading.pause();
        TradingService restarted=new TradingService(store,okx,ai,new RiskEngine(),json,news);
        assertThat(restarted.status().get("enabled")).isEqualTo(false);
        restarted.tick();verify(okx,never()).place(anyMap());
    }
    @Test void pauseDuringModelCallPreventsOrder(){
        when(ai.decide(any())).thenAnswer(x->{trading.pause();return open();});
        trading.enable();trading.tick();verify(okx,never()).place(anyMap());assertThat(store.orders()).isEmpty();
    }
    @Test void partialFillPausesAndDoesNotProduceAnotherOrder() throws Exception {
        trading.enable();trading.tick();
        when(okx.order(anyString(),anyString())).thenReturn(json.readTree("{\"ordId\":\"123\",\"state\":\"partially_filled\",\"tradeId\":\"t1\",\"accFillSz\":\"0.08\",\"avgPx\":\"60000\"}"));
        trading.tick();assertThat(trading.status().get("enabled")).isEqualTo(false);verify(okx,times(1)).place(anyMap());
        assertThat(store.unsettled()).hasSize(1);
    }
    @Test void previewDoesNotSubmitOrChangeLeverage(){
        trading.preview();verify(okx,never()).place(anyMap());verify(okx,never()).leverage(anyString(),anyInt());assertThat(store.orders()).isEmpty();
    }
    @Test void dailyHaltPersistsThroughRestartRecoveryAndMidnight() {
        TradingGuard guard=new TradingGuard(store);
        guard.observeEquity(new Snapshot(Instant.now(),n("1000"),n("900"),List.of()));
        guard.observeEquity(new Snapshot(Instant.now(),n("970"),n("900"),List.of()));
        guard.observeEquity(new Snapshot(Instant.now(),n("1100"),n("900"),List.of()));
        Store fresh=stores.forUser(account.user().id());
        assertThat(fresh.riskState().haltReason).contains("当日");
        assertThatThrownBy(()->new TradingGuard(fresh).opening(open(),new Plan("buy",n("0.1"),false,n("60")))).hasMessageContaining("熔断");
        assertThatThrownBy(()->trading.resetRisk(false)).hasMessageContaining("明确确认");
        trading.resetRisk(true);
        assertThat(store.riskState().haltReason).isEmpty();assertThat(trading.status().get("enabled")).isEqualTo(false);
    }
    @Test void drawdownAndConsecutiveLossesLatchIndependently() {
        RiskState state=store.riskState();state.peakEquity=n("1200");store.riskState(state);
        new TradingGuard(store).observeEquity(new Snapshot(Instant.now(),n("1000"),n("900"),List.of()));
        assertThat(store.riskState().haltReason).contains("回撤");
        state=new RiskState();state.consecutiveLosses=3;store.riskState(state);
        new TradingGuard(store).observeEquity(new Snapshot(Instant.now(),n("1000"),n("900"),List.of()));
        assertThat(store.riskState().haltReason).contains("连续亏损");
    }
    @Test void haltDoesNotBlockOwnedRiskReducingOrders() {
        seedOwned();RiskState state=store.riskState();state.haltReason="日损熔断";store.riskState(state);
        trading.close("BTC-USDT-SWAP",true);
        var argument=org.mockito.ArgumentCaptor.forClass(Map.class);verify(okx).place(argument.capture());
        assertThat(argument.getValue()).containsEntry("reduceOnly",true).containsEntry("side","sell").containsEntry("sz","0.08");
    }
    @Test void externalIsolatedPositionCanBeClosedManually() {
        when(okx.snapshot()).thenReturn(new Snapshot(Instant.now(),n("1000"),n("900"),List.of(
                new Position("BTC-USDT-SWAP",n("0.16"),n("60000"),n("96"),n("0"),"isolated","foreign",System.currentTimeMillis()))));
        trading.close("BTC-USDT-SWAP",false);
        var argument=org.mockito.ArgumentCaptor.forClass(Map.class);verify(okx).place(argument.capture());
        assertThat(argument.getValue()).containsEntry("reduceOnly",true).containsEntry("side","sell").containsEntry("sz","0.16");
    }
    @Test void frequencyCooldownAndLossSizingAreServerSide() {
        TradingGuard guard=new TradingGuard(store);Plan plan=new Plan("buy",n("0.1"),false,n("60"));
        RiskState state=store.riskState();state.cooldownUntil.put(open().instrument(),System.currentTimeMillis()+60000);store.riskState(state);
        assertThatThrownBy(()->guard.opening(open(),plan)).hasMessageContaining("冷却");
        state.cooldownUntil.clear();state.lossNotionalCap=n("50");store.riskState(state);
        assertThatThrownBy(()->guard.opening(open(),plan)).hasMessageContaining("放大");
        state.lossNotionalCap=null;store.riskState(state);
        for(int i=0;i<3;i++)store.intent("pfreq"+i,open(),Map.of());
        assertThatThrownBy(()->guard.opening(open(),plan)).hasMessageContaining("每小时");
    }
    @Test void timedOutLiveOrderRequestsCancelButWaitsForExchangeConfirmation() throws Exception {
        trading.enable();trading.tick();String id=store.orders().get(0).get("client_id").toString();
        db.update("UPDATE user_order_intent SET created_at=? WHERE client_id=?",Instant.now().minusSeconds(120).toString(),id);
        when(okx.order(anyString(),anyString())).thenReturn(json.readTree("{\"ordId\":\"123\",\"state\":\"live\",\"accFillSz\":\"0\",\"avgPx\":\"\"}"));
        trading.reconcileNow();verify(okx).cancel(open().instrument(),id);assertThat(store.unsettled()).hasSize(1);
        when(okx.order(anyString(),anyString())).thenReturn(json.readTree("{\"ordId\":\"123\",\"state\":\"canceled\",\"accFillSz\":\"0\",\"avgPx\":\"\"}"));
        trading.reconcileNow();assertThat(store.unsettled()).isEmpty();verify(okx,times(1)).place(anyMap());
    }
    @Test void fillLedgerIsIdempotentAndRestartBindsOnlyTheActualPosition() throws Exception {
        trading.enable();trading.tick();
        when(okx.order(anyString(),anyString())).thenReturn(json.readTree("{\"ordId\":\"123\",\"state\":\"partially_filled\",\"tradeId\":\"t1\",\"accFillSz\":\"0.08\",\"avgPx\":\"60000\"}"));
        long created=System.currentTimeMillis();
        when(okx.snapshot()).thenReturn(new Snapshot(Instant.now(),n("1000"),n("900"),List.of(
                new Position(open().instrument(),n("0.08"),n("60000"),n("48"),n("0"),"isolated","pos1",created,n("1"),"t1"))));
        trading.reconcileNow();trading.reconcileNow();
        assertThat(store.riskState().positions.get(open().instrument()).contracts).isEqualByComparingTo("0.08");
        assertThat(store.riskState().positions.get(open().instrument()).notional).isEqualByComparingTo("48");
        new TradingGuard(stores.forUser(account.user().id())).requireOwned(okx.snapshot().positions().get(0));
        when(okx.snapshot()).thenReturn(new Snapshot(Instant.now(),n("1000"),n("900"),List.of(
                new Position(open().instrument(),n("0.09"),n("60000"),n("54"),n("0"),"isolated","pos1",created,n("1"),"t1"))));
        assertThatThrownBy(trading::reconcileNow).hasMessageContaining("外部变更");
        when(okx.snapshot()).thenReturn(new Snapshot(Instant.now(),n("1000"),n("900"),List.of(
                new Position(open().instrument(),n("0.08"),n("60000"),n("48"),n("0"),"isolated","pos1",created,n("1"),"foreign-trade"))));
        assertThatThrownBy(trading::reconcileNow).hasMessageContaining("外部变更");
    }
    @Test void closedLossIsCountedOnceAndKeepsCooldownAcrossRestart() throws Exception {
        seedOwned();RiskState state=store.riskState();state.consecutiveLosses=2;store.riskState(state);
        RiskState.Managed managed=state.positions.get(open().instrument());
        when(okx.snapshot()).thenReturn(new Snapshot(Instant.now(),n("990"),n("990"),List.of()));
        when(okx.positionHistory(anyString())).thenReturn(json.readTree("[{\"instId\":\"BTC-USDT-SWAP\",\"posId\":\"owned-pos\",\"cTime\":\""+managed.positionCreatedAt+"\",\"uTime\":\""+System.currentTimeMillis()+"\",\"type\":\"2\",\"closeTotalPos\":\"0.16\",\"realizedPnl\":\"-1.5\"}]"));
        trading.reconcileNow();trading.reconcileNow();
        RiskState reloaded=stores.forUser(account.user().id()).riskState();
        assertThat(reloaded.consecutiveLosses).isEqualTo(3);assertThat(reloaded.haltReason).contains("连续亏损");
        assertThat(reloaded.positions).isEmpty();assertThat(reloaded.cooldownUntil.get(open().instrument())).isGreaterThan(System.currentTimeMillis());
        assertThat(reloaded.lossNotionalCap).isEqualByComparingTo("96");
    }
    @Test void protectionQuantityMustMatchAndStopsCannotBeLoosened() throws Exception {
        seedOwned();
        when(okx.stops(anyString())).thenReturn(json.readTree("[{\"algoClOrdId\":\"sowned-open\",\"sz\":\"0.2\",\"slTriggerPx\":\"59000\",\"tpTriggerPx\":\"62000\",\"side\":\"sell\"}]"));
        assertThatThrownBy(trading::enable).hasMessageContaining("保护单");
        when(okx.stops(anyString())).thenReturn(json.readTree("[{\"algoClOrdId\":\"sowned-open\",\"sz\":\"0.16\",\"slTriggerPx\":\"58000\",\"tpTriggerPx\":\"62000\",\"side\":\"sell\"}]"));
        assertThatThrownBy(trading::enable).hasMessageContaining("放宽");
    }
    @Test void invalidModelOutputSkipsWithoutAnOrder() {
        when(ai.decide(any())).thenThrow(new IllegalStateException("AI 返回结构无效"));
        trading.enable();trading.tick();
        assertThat(trading.status().get("enabled")).isEqualTo(true);verify(okx,never()).place(anyMap());
        assertThat(store.events()).anyMatch(e->"AI_SKIPPED".equals(e.get("kind")));
    }
    @Test void structurallyValidButUnsafeModelDecisionIsSkipped() {
        when(ai.decide(any())).thenReturn(new Decision(Action.OPEN_LONG,open().instrument(),n("100"),null,n("62000"),null,"缺少止损"));
        trading.enable();trading.tick();assertThat(trading.status().get("enabled")).isEqualTo(true);
        verify(okx,never()).place(anyMap());assertThat(trading.status().get("lastError").toString()).contains("止损");
        assertThat(store.analyses()).anyMatch(row->"AI_REJECTED".equals(row.get("kind")) && row.get("payload").toString().contains("缺少止损"));
    }
    @Test void analysisFeedKeepsFullExplanationAndExcludesOtherUsersEnvironmentsAndNoise() throws Exception {
        String reason="完整分析依据".repeat(150);
        store.audit("PREVIEW",reason,Map.of("decision",new Decision(Action.HOLD,open().instrument(),null,null,null,null,reason)));
        for(int i=0;i<101;i++)store.audit("ERROR","unrelated",Map.of());
        mvc.perform(get("/api/analysis").header("Authorization","Bearer "+account.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("PREVIEW"));
        assertThat(json.readTree(store.analyses().get(0).get("payload").toString()).path("decision").path("reason").asText()).isEqualTo(reason);
        mvc.perform(get("/api/analysis").header("Authorization","Bearer "+account.token()).header("X-Trading-Environment","LIVE"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        String name="feed"+java.util.UUID.randomUUID().toString().replace("-","").substring(0,20);
        var other=auth.register(new AuthService.Credentials(name,"test-password-123"),name);
        mvc.perform(get("/api/analysis").header("Authorization","Bearer "+other.token())).andExpect(content().json("[]"));
        mvc.perform(get("/api/analysis")).andExpect(status().isUnauthorized());
    }
    @Test void riskPolicyAndHaltAreIsolatedByUserAndEnvironment() {
        RiskState state=store.riskState();state.haltReason="test";store.riskState(state);
        assertThat(stores.forUser(account.user().id(),TradingEnvironment.LIVE).riskState().haltReason).isEmpty();
        assertThatCode(()->store.bindAccount("different")).doesNotThrowAnyException();
        assertThatThrownBy(()->store.bindAccount("changed")).hasMessageContaining("禁止切换");
    }
    @Test void algorithmicEntryOrdersAlsoBlockNewExposure() throws Exception {
        when(okx.pendingAlgos()).thenReturn(List.of(json.readTree("{\"instId\":\"SOL-USDT-SWAP\",\"ordType\":\"trigger\"}")));
        trading.enable();trading.tick();verify(okx,never()).place(anyMap());
        assertThat(trading.status().get("lastError").toString()).contains("算法挂单");
    }
    @Test void whitelistCannotDropInstrumentWithPendingOpening() {
        store.intent("pendingwhitelist",open(),Map.of());
        Settings old=store.settings();
        Settings changed=new Settings(List.of("ETH-USDT-SWAP"),old.maxOrderUsdt(),old.maxExposureUsdt(),old.maxDailyLossPct(),old.maxPositions(),old.leverage(),old.intervalSeconds());
        assertThatThrownBy(()->trading.settings(changed)).hasMessageContaining("未确认订单");
        assertThat(store.settings().instruments()).contains(open().instrument());
    }
    @Test void policyApiRejectsInvalidValuesAndIsolatesEnvironments() throws Exception {
        var body=json.valueToTree(RiskPolicy.defaults());((com.fasterxml.jackson.databind.node.ObjectNode)body).put("maxTradeRiskPct",1);
        mvc.perform(put("/api/risk-policy").header("Authorization","Bearer "+account.token()).header("X-Trading-Environment","LIVE")
                .contentType("application/json").content(body.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.riskPolicy.maxTradeRiskPct").value(1));
        assertThat(store.riskPolicy().maxTradeRiskPct()).isEqualByComparingTo("0.5");
        ((com.fasterxml.jackson.databind.node.ObjectNode)body).put("maxTradeRiskPct",99);
        mvc.perform(put("/api/risk-policy").header("Authorization","Bearer "+account.token()).contentType("application/json").content(body.toString())).andExpect(status().isConflict());
        mvc.perform(post("/api/risk/reset").contentType("application/json").content("{\"confirm\":true}")).andExpect(status().isUnauthorized());
    }
    void seedOwned() {
        long created=System.currentTimeMillis();RiskState state=store.riskState();RiskState.Managed m=new RiskState.Managed();
        m.latestTradeId="seed";m.openingId="owned-open";m.positionId="owned-pos";m.submittedAt=created;m.positionCreatedAt=created;
        m.contracts=n("0.16");m.openedContracts=n("0.16");m.notional=n("96");m.stopLoss=n("59000");state.positions.put(open().instrument(),m);store.riskState(state);
        when(okx.snapshot()).thenAnswer(x->new Snapshot(Instant.now(),n("1000"),n("900"),List.of(
                new Position(open().instrument(),n("0.16"),n("60000"),n("96"),n("0"),"isolated","owned-pos",created,n("1"),"seed"))));
    }
    @Test void unprotectedPositionStopsAutomation(){
        Position p=new Position("BTC-USDT-SWAP",n("0.2"),n("60000"),n("120"),n("0"),"isolated");
        when(okx.snapshot()).thenReturn(new Snapshot(Instant.now(),n("1000"),n("500"),List.of(p)));
        assertThatThrownBy(trading::enable).hasMessageContaining("保护单");verify(okx,never()).place(anyMap());
    }
    @Test void externalPendingOrderBlocksNewExposure() throws Exception {
        when(okx.pendingOrders()).thenReturn(json.readTree("[{\"ordId\":\"external\"}]"));
        trading.enable();trading.tick();verify(okx,never()).place(anyMap());assertThat(trading.status().get("enabled")).isEqualTo(false);
    }
    @Test void unauthorizedRequestsCannotControlOrReadAccount() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/start")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/status").header("Authorization","Bearer "+account.token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.environment").value("DEMO"));
    }
    @Test void onlyOneProcessCanHoldExecutionLease(){
        Store other=stores.forUser(account.user().id());assertThat(store.acquire()).isTrue();assertThat(other.acquire()).isFalse();
        store.release();assertThat(other.acquire()).isTrue();other.release();
    }
    @Test void stopAmendmentMustMatchExchangeBeforeSettling() throws Exception {
        Decision d=new Decision(Action.UPDATE_STOPS,"BTC-USDT-SWAP",null,null,n("62000"),n("59000"),"调整");
        store.intent("pstoptest",d,Map.of("algoId","9","takeProfit",n("62000"),"stopLoss",n("59000")));
        when(okx.stops(anyString())).thenReturn(json.readTree("[{\"algoId\":\"9\",\"tpTriggerPx\":\"62000\",\"slTriggerPx\":\"59000\"}]"));
        trading.reconcileNow();assertThat(store.orders().get(0).get("state")).isEqualTo("APPLIED");
    }

    @Test void unavailableNewsPreventsAiOpeningAndIsAudited() {
        var realNews=new NewsService(java.time.Clock.systemUTC(),List.of(),source->{throw new java.io.IOException();});
        try {
            trading=new TradingService(store,okx,ai,new RiskEngine(),json,realNews);
            trading.enable();trading.tick();verify(okx,never()).place(anyMap());
            assertThat(trading.status().get("lastError").toString()).contains("禁止新开仓");
            assertThat(store.events()).anyMatch(e->"NEWS_EVIDENCE".equals(e.get("kind")));
        }finally{realNews.close();}
    }
    @Test void realNewsEvidenceReachesModelAndPersistedDecision() {
        var source=NewsService.SOURCES.get(0);
        var realNews=new NewsService(java.time.Clock.systemUTC(),List.of(source),ignored->NewsServiceTest.feed(NewsServiceTest.item("Bitcoin ETF event","https://www.coindesk.com/test-evidence",Instant.now().toString())));
        try {
            var evidence=realNews.evidence(List.of("BTC-USDT-SWAP"));String id=evidence.articles().get(0).id();
            when(ai.decide(any())).thenReturn(new Decision(Action.OPEN_LONG,"BTC-USDT-SWAP",n("100"),null,n("62000"),n("59000"),"行情与资讯一致 ["+id+"]"));
            trading=new TradingService(store,okx,ai,new RiskEngine(),json,realNews);
            trading.enable();trading.tick();verify(okx).place(anyMap());
            var context=org.mockito.ArgumentCaptor.forClass(Object.class);verify(ai).decide(context.capture());
            assertThat(((Map<?,?>)context.getValue()).containsKey("news")).isTrue();
            assertThat(store.events()).anyMatch(e->"DECISION".equals(e.get("kind")) && e.get("payload").toString().contains("Bitcoin ETF event") && e.get("payload").toString().contains(id));
        }finally{realNews.close();}
    }
}
