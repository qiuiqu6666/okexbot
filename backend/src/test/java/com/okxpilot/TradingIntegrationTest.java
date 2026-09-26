package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
    @Autowired Store store;
    @Autowired TradingService trading;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean OkxClient okx;
    @MockitoBean AiClient ai;
    static BigDecimal n(String x){return new BigDecimal(x);}
    Decision open(){return new Decision(Action.OPEN_LONG,"BTC-USDT-SWAP",n("100"),null,n("62000"),n("59000"),"结构验证");}
    @BeforeEach void setup() throws Exception {
        trading.pause();
        for(String table:List.of("order_intent","audit_event","bot_config","equity_baseline")) db.update("DELETE FROM "+table);
        db.update("UPDATE execution_lease SET owner='',expires_at=0");
        reset(okx,ai);
        when(okx.configured()).thenReturn(true);when(ai.configured()).thenReturn(true);when(ai.model()).thenReturn("test-model");
        when(okx.snapshot()).thenAnswer(x->new Snapshot(Instant.now(),n("1000"),n("500"),List.of()));
        when(okx.instrument(anyString())).thenAnswer(x->new Instrument(x.getArgument(0),n("0.01"),n("0.01"),n("0.01"),n("0.1"),n("1000")));
        when(okx.price(anyString())).thenReturn(n("60000"));when(okx.candles(anyString())).thenReturn(json.readTree("[]"));
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
        TradingService restarted=new TradingService(store,okx,ai,new RiskEngine(),json);
        assertThat(restarted.status().get("enabled")).isEqualTo(false);
        assertThatThrownBy(restarted::enable).hasMessageContaining("待确认");
        restarted.tick();verify(okx,times(1)).place(anyMap());
    }
    @Test void pauseDuringModelCallPreventsOrder(){
        when(ai.decide(any())).thenAnswer(x->{trading.pause();return open();});
        trading.enable();trading.tick();verify(okx,never()).place(anyMap());assertThat(store.orders()).isEmpty();
    }
    @Test void partialFillPausesAndDoesNotProduceAnotherOrder() throws Exception {
        trading.enable();trading.tick();
        when(okx.order(anyString(),anyString())).thenReturn(json.readTree("{\"ordId\":\"123\",\"state\":\"partially_filled\",\"accFillSz\":\"0.08\",\"avgPx\":\"60000\"}"));
        trading.tick();assertThat(trading.status().get("enabled")).isEqualTo(false);verify(okx,times(1)).place(anyMap());
        assertThat(store.unsettled()).hasSize(1);
    }
    @Test void previewDoesNotSubmitOrChangeLeverage(){
        trading.preview();verify(okx,never()).place(anyMap());verify(okx,never()).leverage(anyString(),anyInt());assertThat(store.orders()).isEmpty();
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
        mvc.perform(get("/api/status").header("Authorization","Bearer 01234567890123456789012345678901"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.environment").value("DEMO"));
    }
    @Test void onlyOneProcessCanHoldExecutionLease(){
        Store other=new Store(db,json);assertThat(store.acquire()).isTrue();assertThat(other.acquire()).isFalse();
        store.release();assertThat(other.acquire()).isTrue();other.release();
    }
    @Test void stopAmendmentMustMatchExchangeBeforeSettling() throws Exception {
        Decision d=new Decision(Action.UPDATE_STOPS,"BTC-USDT-SWAP",null,null,n("62000"),n("59000"),"调整");
        store.intent("pstoptest",d,Map.of("algoId","9","takeProfit",n("62000"),"stopLoss",n("59000")));
        when(okx.stops(anyString())).thenReturn(json.readTree("[{\"algoId\":\"9\",\"tpTriggerPx\":\"62000\",\"slTriggerPx\":\"59000\"}]"));
        trading.reconcileNow();assertThat(store.orders().get(0).get("state")).isEqualTo("APPLIED");
    }
}
