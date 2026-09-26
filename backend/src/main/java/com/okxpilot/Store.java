package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static com.okxpilot.Domain.*;

@Repository
public class Store {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final long userId;
    private final String owner=UUID.randomUUID().toString();
    @Autowired public Store(JdbcTemplate db,ObjectMapper json) { this(db,json,0); }
    private Store(JdbcTemplate db,ObjectMapper json,long userId) {this.db=db;this.json=json;this.userId=userId;}
    public Store forUser(long id) {if(id<=0) throw new IllegalArgumentException("用户无效");return new Store(db,json,id);}
    private long user() {if(userId<=0) throw new IllegalStateException("必须指定当前用户");return userId;}
    public String encode(Object value) {
        try {return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("无法序列化数据");}
    }
    public Settings settings() {
        List<String> rows=db.query("SELECT payload FROM user_bot_config WHERE user_id=?",(r,n)->r.getString(1),user());
        if(rows.isEmpty()) return Settings.defaults();
        try{return json.readValue(rows.get(0),Settings.class);}catch(Exception e){throw new IllegalStateException("配置数据无效");}
    }
    public void settings(Settings value) {
        value.validate();
        if(db.update("UPDATE user_bot_config SET payload=? WHERE user_id=?",encode(value),user())==0)
            db.update("INSERT INTO user_bot_config(user_id,payload) VALUES(?,?)",user(),encode(value));
    }
    public void audit(String kind,String summary,Object data) {
        db.update("INSERT INTO user_audit_event(user_id,kind,summary,payload,created_at) VALUES(?,?,?,?,?)",user(),kind,
                summary.substring(0,Math.min(500,summary.length())),encode(data),Instant.now().toString());
    }
    public void intent(String id,Decision d,Object body) {
        String now=Instant.now().toString();
        db.update("INSERT INTO user_order_intent(client_id,user_id,instrument,action,state,payload,created_at,updated_at) VALUES(?,?,?,?,'SUBMITTING',?,?,?)",
                id,user(),d.instrument(),d.action().name(),encode(body),now,now);
    }
    public void state(String id,String state,String exchangeId,String detail) {
        db.update("UPDATE user_order_intent SET state=?,exchange_id=?,detail=?,updated_at=? WHERE client_id=? AND user_id=?",state,exchangeId,detail,Instant.now().toString(),id,user());
    }
    public List<Map<String,Object>> orders(){return db.queryForList("SELECT * FROM user_order_intent WHERE user_id=? ORDER BY created_at DESC LIMIT 100",user());}
    public List<Map<String,Object>> events(){return db.queryForList("SELECT * FROM user_audit_event WHERE user_id=? ORDER BY id DESC LIMIT 100",user());}
    public List<Map<String,Object>> unsettled(){return db.queryForList("SELECT * FROM user_order_intent WHERE user_id=? AND state IN ('SUBMITTING','UNKNOWN','ACCEPTED','live','partially_filled') ORDER BY created_at",user());}
    public boolean hasOrders(){return db.queryForObject("SELECT COUNT(*) FROM user_order_intent WHERE user_id=?",Long.class,user())>0;}
    public boolean ownsStop(String id) {
        return id!=null && id.startsWith("s") && db.queryForObject("SELECT COUNT(*) FROM user_order_intent WHERE user_id=? AND client_id=? AND action IN ('OPEN_LONG','OPEN_SHORT')",Integer.class,user(),id.substring(1))>0;
    }
    public BigDecimal baseline(BigDecimal equity) {
        String day=LocalDate.now(ZoneOffset.UTC).toString();
        List<BigDecimal> rows=db.query("SELECT equity FROM user_equity_baseline WHERE user_id=? AND day_utc=?",(r,n)->r.getBigDecimal(1),user(),day);
        if(!rows.isEmpty()) return rows.get(0);
        if(!positive(equity)) throw new IllegalStateException("账户权益必须大于 0");
        db.update("INSERT INTO user_equity_baseline(user_id,day_utc,equity) VALUES(?,?,?)",user(),day,equity);return equity;
    }
    public boolean acquire() {
        long now=System.currentTimeMillis();
        return db.update("UPDATE user_execution_lease SET owner=?,expires_at=? WHERE user_id=? AND (expires_at<? OR owner=?)",owner,now+180000,user(),now,owner)==1;
    }
    public void renew() {
        long now=System.currentTimeMillis();
        if(db.update("UPDATE user_execution_lease SET expires_at=? WHERE user_id=? AND owner=? AND expires_at>?",now+180000,user(),owner,now)!=1)
            throw new IllegalStateException("执行锁已失效，停止提交指令");
    }
    public void release(){db.update("UPDATE user_execution_lease SET expires_at=0,owner='' WHERE user_id=? AND owner=?",user(),owner);}
}
