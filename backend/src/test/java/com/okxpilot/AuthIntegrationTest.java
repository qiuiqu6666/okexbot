package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import static com.okxpilot.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:auth;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","spring.datasource.username=sa","spring.datasource.password=", "pilot.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","pilot.poll-ms=3600000"})
@AutoConfigureMockMvc
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;
    @Autowired Store stores;
    @Autowired ConnectionService connections;
    @Autowired TradingRegistry registry;
    String unique(){return "u"+UUID.randomUUID().toString().replace("-","").substring(0,20);}
    AuthService.LoginResult account(){String name=unique();return auth.register(new AuthService.Credentials(name,"test-password-123"),name);}
    String bearer(AuthService.LoginResult r){return "Bearer "+r.token();}
    @Test void registerAndLoginPersistOnlyPasswordAndSessionHashes() throws Exception {
        String name=unique(),password="password-12345";
        String body=json.writeValueAsString(Map.of("username",name,"password",password));
        var result=mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.username").value(name)).andExpect(jsonPath("$.token").exists()).andReturn();
        String token=json.readTree(result.getResponse().getContentAsString()).path("token").asText();
        String hash=db.queryForObject("SELECT password_hash FROM app_user WHERE username=?",String.class,name);
        assertThat(hash).startsWith("$2a$12$").doesNotContain(password);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM auth_session WHERE token_hash=?",Integer.class,token)).isZero();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value(name));
    }
    @Test void duplicateUsernameIsCaseInsensitiveAndNoUserIdCanBeInjected() throws Exception {
        var a=account();String body=json.writeValueAsString(Map.of("username",a.user().username().toUpperCase(),"password","password-123"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"new_user\",\"password\":\"password-123\",\"id\":1}"))
                .andExpect(status().isBadRequest());
    }
    @Test void invalidAndExpiredSessionsAndOldOperatorTokenAreDenied() throws Exception {
        var a=account();
        mvc.perform(get("/api/status").header("Authorization","Bearer 01234567890123456789012345678901")).andExpect(status().isUnauthorized());
        db.update("UPDATE auth_session SET expires_at=0 WHERE user_id=?",a.user().id());
        mvc.perform(get("/api/status").header("Authorization",bearer(a))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/start")).andExpect(status().isUnauthorized());
    }
    @Test void logoutRevokesSessionWithoutStoppingAnotherUser() throws Exception {
        var a=account();var b=account();
        mvc.perform(post("/api/auth/logout").header("Authorization",bearer(a))).andExpect(status().isOk());
        mvc.perform(get("/api/status").header("Authorization",bearer(a))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization",bearer(b))).andExpect(status().isOk());
    }
    @Test void usersCannotReadOrMutateOtherUsersTradingRecords() throws Exception {
        var a=account();var b=account();Store sa=stores.forUser(a.user().id()),sb=stores.forUser(b.user().id());
        sa.audit("TEST","alice-only-event",Map.of());
        Decision d=new Decision(Action.CLOSE,"BTC-USDT-SWAP",null,null,null,null,"test");
        String id="p"+UUID.randomUUID().toString().replace("-","").substring(0,20);sa.intent(id,d,Map.of());
        sb.state(id,"filled","foreign","forbidden");
        assertThat(sa.orders().get(0).get("state")).isEqualTo("SUBMITTING");assertThat(sb.ownsStop("s"+id)).isFalse();
        mvc.perform(get("/api/events").param("userId",Long.toString(a.user().id())).header("Authorization",bearer(b))).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/orders").header("Authorization",bearer(b))).andExpect(status().isOk()).andExpect(content().json("[]"));
        var config=new Settings(java.util.List.of("SOL-USDT-SWAP"),new java.math.BigDecimal("20"),new java.math.BigDecimal("50"),new java.math.BigDecimal("2"),1,1,600);
        mvc.perform(put("/api/settings").header("Authorization",bearer(a)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(config))).andExpect(status().isOk());
        assertThat(sa.settings()).isEqualTo(config);assertThat(sb.settings()).isEqualTo(Settings.defaults());
        mvc.perform(post("/api/pause").header("Authorization",bearer(b))).andExpect(status().isOk());
        assertThat(sa.events()).noneMatch(e->"CONTROL".equals(e.get("kind")));
        assertThat(registry.forUser(a.user().id())).isNotSameAs(registry.forUser(b.user().id()));
        assertThat(sa.acquire()).isTrue();assertThat(sb.acquire()).isTrue();sa.release();sb.release();
    }
    @Test void credentialsAreEncryptedBoundToUserAndNeverReturned() throws Exception {
        var a=account();var b=account();String apiKey=unique();
        var update=new ConnectionService.Update(apiKey,"secret-value","passphrase-value","https://api.openai.com/v1","model-secret","test-model");
        mvc.perform(put("/api/connections").header("Authorization",bearer(a)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(update)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.okxConfigured").value(true)).andExpect(jsonPath("$.okxKey").doesNotExist()).andExpect(jsonPath("$.aiKey").doesNotExist());
        String encrypted=db.queryForObject("SELECT ciphertext FROM user_connection WHERE user_id=?",String.class,a.user().id());
        assertThat(encrypted).doesNotContain("secret-value").doesNotContain("model-secret").doesNotContain(apiKey);
        assertThat(connections.load(a.user().id()).okxSecret()).isEqualTo("secret-value");
        assertThat(connections.load(b.user().id()).okxConfigured()).isFalse();
        assertThatThrownBy(()->connections.save(b.user().id(),update)).hasMessageContaining("其他用户");
        db.update("INSERT INTO user_connection(user_id,ciphertext,updated_at) VALUES(?,?,?)",b.user().id(),encrypted,"test");
        assertThatThrownBy(()->connections.load(b.user().id())).hasMessageContaining("无法解密");
    }
    @Test void userSuppliedModelEndpointCannotAccessArbitraryServerAddresses(){
        var a=account();
        assertThatThrownBy(()->connections.save(a.user().id(),new ConnectionService.Update(null,null,null,"http://127.0.0.1:8080", "key","model")))
                .hasMessageContaining("允许列表");
    }
    @Test void wrongPasswordsAreGenericAndRateLimited() throws Exception {
        var a=account();String body=json.writeValueAsString(Map.of("username",a.user().username(),"password","incorrect-password"));
        for(int i=0;i<10;i++) mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("用户名或密码错误"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isTooManyRequests());
    }
    @Test void shortPasswordAndInvalidUsernameAreRejected() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"ok_user\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"../bad\",\"password\":\"password123\"}"))
                .andExpect(status().isBadRequest());
    }
}
