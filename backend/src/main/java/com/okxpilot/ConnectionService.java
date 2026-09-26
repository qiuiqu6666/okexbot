package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class ConnectionService {
    public record Connection(String okxKey,String okxSecret,String okxPassphrase,String aiBaseUrl,String aiKey,String aiModel) {
        public static Connection empty(){return new Connection("","","","https://nxaiapp.com/v1","","gpt-6-luna");}
        public boolean okxConfigured(){return !okxKey.isBlank() && !okxSecret.isBlank() && !okxPassphrase.isBlank();}
        public boolean aiConfigured(){return !aiKey.isBlank() && !aiModel.isBlank();}
    }
    // Empty credential input means keep its existing encrypted value; credentials are never returned to the app.
    public record Update(String okxKey,String okxSecret,String okxPassphrase,String aiBaseUrl,String aiKey,String aiModel) {}
    private TradingEnvironment environment=TradingEnvironment.DEMO;
    private ConnectionService(ConnectionService source,TradingEnvironment env) {
        db=source.db;json=source.json;stores=source.stores;key=source.key;allowedUrls=source.allowedUrls;environment=env;
    }
    public ConnectionService forEnvironment(TradingEnvironment env){return new ConnectionService(this,env);}
    private String sql(String value){return environment.tableSql(value);}
    private byte[] aad(long user){return (environment==TradingEnvironment.DEMO?Long.toString(user):"LIVE:"+user).getBytes(StandardCharsets.UTF_8);}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Store stores;
    private final byte[] key;
    private final List<String> allowedUrls;
    private final SecureRandom random=new SecureRandom();
    @org.springframework.beans.factory.annotation.Autowired
    public ConnectionService(JdbcTemplate db,ObjectMapper json,Store stores,@Value("${pilot.master-key:}") String master,
                             @Value("${pilot.ai-allowed-base-urls}") String allowed) {
        this.db=db;this.json=json;this.stores=stores;
        try{key=master.isBlank()?null:Base64.getDecoder().decode(master);}catch(Exception e){throw new IllegalStateException("PILOT_MASTER_KEY 必须是 32 字节的 Base64 密钥");}
        if(key!=null && key.length!=32) throw new IllegalStateException("PILOT_MASTER_KEY 必须是 32 字节的 Base64 密钥");
        allowedUrls=Arrays.stream(allowed.split(",")).map(String::trim).filter(s->!s.isBlank()).map(s->s.replaceAll("/+$","")).toList();
    }
    public Connection load(long user) {
        List<String> rows=db.query(sql("SELECT ciphertext FROM user_connection WHERE user_id=?"),(r,n)->r.getString(1),user);
        if(rows.isEmpty()) return Connection.empty();
        requireKey();
        try {
            byte[] all=Base64.getDecoder().decode(rows.get(0));
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(all,0,12)));
            cipher.updateAAD(aad(user));
            return json.readValue(cipher.doFinal(Arrays.copyOfRange(all,12,all.length)),Connection.class);
        }catch(Exception e){throw new IllegalStateException("账户连接配置无法解密，请检查服务端主密钥");}
    }
    public Map<String,Object> view(long user) {
        Connection c=load(user);
        return Map.of("environment",environment.name(),"okxConfigured",c.okxConfigured(),"aiConfigured",c.aiConfigured(),"aiBaseUrl",c.aiBaseUrl(),"aiModel",c.aiModel(),
                "encryptionReady",key!=null,"allowedAiBaseUrls",allowedUrls);
    }
    public void save(long user,Update update) {
        requireKey();Connection old=load(user);
        Connection c=new Connection(keep(update.okxKey(),old.okxKey()),keep(update.okxSecret(),old.okxSecret()),keep(update.okxPassphrase(),old.okxPassphrase()),
                keep(update.aiBaseUrl(),old.aiBaseUrl()).replaceAll("/+$",""),keep(update.aiKey(),old.aiKey()),keep(update.aiModel(),old.aiModel()));
        if(!allowedUrls.contains(c.aiBaseUrl())) throw new IllegalArgumentException("模型地址未在服务器允许列表中，请联系管理员配置 AI_ALLOWED_BASE_URLS");
        if(!c.okxKey().isBlank() && !c.okxConfigured()) throw new IllegalArgumentException("请完整填写 OKX API Key、Secret 和 Passphrase");
        if(!c.okxKey().equals(old.okxKey()) && stores.forUser(user,environment).hasOrders()) throw new IllegalArgumentException("已有交易记录，不能切换 OKX API Key；请使用独立用户绑定另一账户");
        try {
            byte[] iv=new byte[12];random.nextBytes(iv);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            cipher.updateAAD(aad(user));
            byte[] encrypted=cipher.doFinal(json.writeValueAsBytes(c));byte[] output=new byte[iv.length+encrypted.length];
            System.arraycopy(iv,0,output,0,iv.length);System.arraycopy(encrypted,0,output,iv.length,encrypted.length);
            String value=Base64.getEncoder().encodeToString(output),fingerprint=c.okxKey().isBlank()?null:AuthService.hash(c.okxKey());
            if(db.update(sql("UPDATE user_connection SET ciphertext=?,okx_key_hash=?,updated_at=? WHERE user_id=?"),value,fingerprint,Instant.now().toString(),user)==0)
                db.update(sql("INSERT INTO user_connection(user_id,ciphertext,okx_key_hash,updated_at) VALUES(?,?,?,?)"),user,value,fingerprint,Instant.now().toString());
        }catch(DuplicateKeyException e){throw new IllegalArgumentException("该 OKX API Key 已绑定其他用户");}
        catch(Exception e){throw new IllegalStateException("保存连接配置失败");}
    }
    public List<Long> configuredUsers(){return db.query(sql("SELECT user_id FROM user_connection"),(r,n)->r.getLong(1));}
    private void requireKey(){if(key==null) throw new IllegalStateException("服务器尚未配置 PILOT_MASTER_KEY，请先配置加密主密钥");}
    private String keep(String value,String old) {
        if(value==null || value.isBlank()) return old;
        if(value.length()>2048 || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("连接参数无效或过长");
        return value.trim();
    }
}
