package com.okxpilot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
    public record User(long id,String username) {}
    public record LoginResult(String token,String expiresAt,User user) {}
    public record Credentials(String username,String password) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
    private final String dummyHash=encoder.encode("not-a-real-user-password");
    private final SecureRandom random=new SecureRandom();
    private final long sessionMillis;
    private final Map<String,Bucket> attempts=new HashMap<>();
    private record Bucket(long until,int count) {}
    public AuthService(JdbcTemplate db,TransactionTemplate tx,@Value("${pilot.session-hours:168}") int hours) {
        this.db=db;this.tx=tx;
        if(hours<1 || hours>720) throw new IllegalArgumentException("会话有效期应为 1–720 小时");
        sessionMillis=hours*3600000L;
    }
    public LoginResult register(Credentials credentials,String address) {
        limit("register:"+address,5,3600000);
        String username=validate(credentials);
        String hash=encoder.encode(credentials.password());
        try {
            return tx.execute(status->{
                db.update("INSERT INTO app_user(username,password_hash,created_at) VALUES(?,?,?)",username,hash,Instant.now().toString());
                Long id=db.queryForObject("SELECT id FROM app_user WHERE username=?",Long.class,username);
                db.update("INSERT INTO user_execution_lease(user_id,owner,expires_at) VALUES(?,'',0)",id);
                db.update("INSERT INTO live_user_execution_lease(user_id,owner,expires_at) VALUES(?,'',0)",id);
                return issue(new User(id,username));
            });
        } catch(DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT,"用户名已被注册"); }
    }
    public LoginResult login(Credentials credentials,String address) {
        limit("login-ip:"+address,40,60000);
        String username=validate(credentials);
        limit("login-user:"+username,10,300000);
        var rows=db.queryForList("SELECT id,username,password_hash FROM app_user WHERE username=?",username);
        String hash=rows.isEmpty()?dummyHash:rows.get(0).get("password_hash").toString();
        boolean matches=encoder.matches(credentials.password(),hash);
        if(rows.isEmpty() || !matches) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"用户名或密码错误");
        synchronized(attempts) { attempts.remove("login-user:"+username); }
        return issue(new User(((Number)rows.get(0).get("id")).longValue(),username));
    }
    private String validate(Credentials c) {
        if(c==null || c.username()==null || !c.username().trim().matches("[A-Za-z0-9_]{3,32}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"用户名须为 3–32 位字母、数字或下划线");
        if(c.password()==null || c.password().length()<8 || c.password().length()>64 || c.password().getBytes(StandardCharsets.UTF_8).length>72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"密码须为 8–64 位，UTF-8 编码不超过 72 字节");
        return c.username().trim().toLowerCase(Locale.ROOT);
    }
    private LoginResult issue(User user) {
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expires=System.currentTimeMillis()+sessionMillis;
        db.update("DELETE FROM auth_session WHERE expires_at<=?",System.currentTimeMillis());
        db.update("INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(?,?,?)",hash(token),user.id(),expires);
        return new LoginResult(token,Instant.ofEpochMilli(expires).toString(),user);
    }
    public User authenticate(String bearer) {
        String token=extract(bearer);
        var users=db.query("SELECT u.id,u.username FROM auth_session s JOIN app_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>?",
                (rs,n)->new User(rs.getLong(1),rs.getString(2)),hash(token),System.currentTimeMillis());
        if(users.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"登录已失效，请重新登录");
        return users.get(0);
    }
    public void logout(String bearer) { db.update("DELETE FROM auth_session WHERE token_hash=?",hash(extract(bearer))); }
    private static String extract(String bearer) {
        if(bearer==null || !bearer.matches("Bearer [A-Za-z0-9_-]{43}"))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录");
        return bearer.substring(7);
    }
    static String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e) {throw new IllegalStateException("摘要计算失败");}
    }
    private void limit(String key,int max,long duration) {
        synchronized(attempts) {
            long now=System.currentTimeMillis();attempts.entrySet().removeIf(e->e.getValue().until()<=now);
            Bucket old=attempts.getOrDefault(key,new Bucket(now+duration,0));
            if(old.count()>=max || (!attempts.containsKey(key) && attempts.size()>=8192))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"尝试次数过多，请稍后重试");
            attempts.put(key,new Bucket(old.until(),old.count()+1));
        }
    }
}
