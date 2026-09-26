package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.*;
import static com.okxpilot.Domain.*;
import static com.okxpilot.TradingEnvironment.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:live;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","spring.datasource.username=sa","spring.datasource.password=","pilot.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","pilot.poll-ms=3600000"})
@AutoConfigureMockMvc
class LiveEnvironmentTest {
    @Autowired Store stores;
    @Autowired AuthService auth;
    @Autowired ConnectionService connections;
    @Autowired TradingRegistry registry;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    AuthService.LoginResult account(){String name="live"+UUID.randomUUID().toString().replace("-","").substring(0,20);return auth.register(new AuthService.Credentials(name,"local-test-password"),name);}
    @Test void demoRemainsDefaultAndLiveMustBeExplicitlyConfirmed() throws Exception {
        var a=account();String token="Bearer "+a.token();
        mvc.perform(get("/api/status").header("Authorization",token)).andExpect(jsonPath("$.environment").value("DEMO"));
        mvc.perform(get("/api/status").header("Authorization",token).header("X-Trading-Environment","LIVE")).andExpect(jsonPath("$.environment").value("LIVE")).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/status").header("Authorization",token).header("X-Trading-Environment","typo")).andExpect(status().isConflict());
        mvc.perform(post("/api/start").header("Authorization",token).header("X-Trading-Environment","LIVE")).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("开启实盘自动交易必须明确确认真实资金交易"));
        mvc.perform(post("/api/start").header("Authorization",token).header("X-Trading-Environment","LIVE").contentType("application/json").content("{\"confirmLive\":true}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("请先配置当前环境的 OKX 和 AI 凭据"));
    }
    @Test void credentialsAreSeparateAndCannotBeCopiedAcrossEnvironments() throws Exception {
        var a=account();long id=a.user().id();var live=connections.forEnvironment(LIVE);
        connections.save(id,new ConnectionService.Update("demo-key","demo-secret","pass","https://nxaiapp.com/v1","demo-ai","gpt-6-luna"));
        assertThat(live.load(id).okxConfigured()).isFalse();
        live.save(id,new ConnectionService.Update("live-key","live-secret","pass","https://nxaiapp.com/v1","live-ai","gpt-6-luna"));
        assertThat(connections.load(id).okxKey()).isEqualTo("demo-key");assertThat(live.load(id).okxKey()).isEqualTo("live-key");
        mvc.perform(get("/api/connections").header("Authorization","Bearer "+a.token()).header("X-Trading-Environment","LIVE"))
            .andExpect(jsonPath("$.environment").value("LIVE")).andExpect(jsonPath("$.okxKey").doesNotExist());
        db.update("UPDATE live_user_connection SET ciphertext=(SELECT ciphertext FROM user_connection WHERE user_id=?) WHERE user_id=?",id,id);
        assertThatThrownBy(()->live.load(id)).hasMessageContaining("无法解密");
    }
    @Test void ordersSettingsBaselinesAndLeasesNeverCrossEnvironments() throws Exception {
        var a=account();Store demo=stores.forUser(a.user().id()),live=stores.forUser(a.user().id(),LIVE);
        var d=new Decision(Action.OPEN_LONG,"BTC-USDT-SWAP",null,null,null,null,"test");
        demo.intent("demoorder",d,Map.of());live.state("demoorder","filled","bad","bad");
        assertThat(demo.unsettled()).hasSize(1);assertThat(live.unsettled()).isEmpty();assertThat(live.ownsStop("sdemoorder")).isFalse();
        demo.audit("TEST","demo-only",Map.of());
        mvc.perform(get("/api/orders").header("Authorization","Bearer "+a.token()).header("X-Trading-Environment","LIVE")).andExpect(content().json("[]"));
        mvc.perform(get("/api/events").header("Authorization","Bearer "+a.token()).header("X-Trading-Environment","LIVE")).andExpect(content().json("[]"));
        live.settings(new Settings(List.of("SOL-USDT-SWAP"),new BigDecimal("20"),new BigDecimal("100"),new BigDecimal("2"),1,1,600));
        assertThat(demo.settings()).isEqualTo(Settings.defaults());
        assertThat(demo.baseline(new BigDecimal("1000"))).isEqualByComparingTo("1000");assertThat(live.baseline(new BigDecimal("50"))).isEqualByComparingTo("50");
        assertThat(demo.acquire()).isTrue();assertThat(live.acquire()).isTrue();demo.release();live.release();
        assertThat(registry.forUser(a.user().id(),DEMO)).isNotSameAs(registry.forUser(a.user().id(),LIVE));
    }
    @Test void liveRequestsNeverCarrySimulationHeaderAndCatalogFiltersUnsupportedContracts() throws Exception {
        var demo=new OkxClient("key","secret","pass",json,DEMO);var live=spy(new OkxClient("key","secret","pass",json,LIVE));
        assertThat(demo.environmentHeaders()).containsEntry("x-simulated-trading","1");assertThat(live.environmentHeaders()).doesNotContainKey("x-simulated-trading");
        var rows=json.createArrayNode();
        for(String id:List.of("BTC-USDT-SWAP","ETH-USDT-SWAP","DOGE-USDT-SWAP","BTC-USD-SWAP")) rows.addObject().put("instId",id).put("state",id.startsWith("DOGE")?"suspend":"live").put("ctType","linear").put("settleCcy","USDT").put("ctValCcy",id.split("-")[0]);
        doReturn(rows).when(live).call("GET","/api/v5/public/instruments?instType=SWAP",null,false);
        assertThat(live.instruments()).containsExactly("BTC-USDT-SWAP","ETH-USDT-SWAP");
    }
}
