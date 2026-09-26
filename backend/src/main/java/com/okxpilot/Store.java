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
    private TradingEnvironment environment=TradingEnvironment.DEMO;
    public TradingEnvironment environment(){return environment;}
    private final String owner=UUID.randomUUID().toString();
    private final TelegramNotifier telegram;
    @Autowired public Store(JdbcTemplate db,ObjectMapper json,TelegramNotifier telegram) { this(db,json,0,telegram); }
    private Store(JdbcTemplate db,ObjectMapper json,long userId,TelegramNotifier telegram) {this.db=db;this.json=json;this.userId=userId;this.telegram=telegram;}
    public Store forUser(long id,TradingEnvironment env) {Store store=forUser(id);store.environment=env;return store;}
    private String sql(String value){return environment.tableSql(value);}
    public Store forUser(long id) {if(id<=0) throw new IllegalArgumentException("用户无效");return new Store(db,json,id,telegram);}
    private long user() {if(userId<=0) throw new IllegalStateException("必须指定当前用户");return userId;}
    public String encode(Object value) {
        try {return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("无法序列化数据");}
    }
    public Settings settings() {
        List<String> rows=db.query(sql("SELECT payload FROM user_bot_config WHERE user_id=?"),(r,n)->r.getString(1),user());
        if(rows.isEmpty()) return Settings.defaults();
        try{return json.readValue(rows.get(0),Settings.class);}catch(Exception e){throw new IllegalStateException("配置数据无效");}
    }
    public void settings(Settings value) {
        value.validate();
        if(db.update(sql("UPDATE user_bot_config SET payload=? WHERE user_id=?"),encode(value),user())==0)
            db.update(sql("INSERT INTO user_bot_config(user_id,payload) VALUES(?,?)"),user(),encode(value));
    }
    public boolean autoTrading() {
        List<Boolean> rows=db.query(sql("SELECT enabled FROM user_trading_control WHERE user_id=?"),(r,n)->r.getBoolean(1),user());
        if(!rows.isEmpty()) return rows.get(0);
        boolean enabled=inferredAutoTrading();
        autoTrading(enabled);
        return enabled;
    }
    public void autoTrading(boolean enabled) {
        if(db.update(sql("UPDATE user_trading_control SET enabled=? WHERE user_id=?"),enabled?1:0,user())==0)
            db.update(sql("INSERT INTO user_trading_control(user_id,enabled) VALUES(?,?)"),user(),enabled?1:0);
    }
    private boolean inferredAutoTrading() {
        List<Long> opened=db.query(sql("SELECT id FROM user_audit_event WHERE user_id=? AND kind='CONTROL' AND summary LIKE '已开启%' ORDER BY id DESC LIMIT 1"),(r,n)->r.getLong(1),user());
        if(opened.isEmpty()) return false;
        Long later=db.queryForObject(sql("SELECT COUNT(*) FROM user_audit_event WHERE user_id=? AND id>? AND ((kind='CONTROL' AND summary LIKE '已暂停%') OR kind='ERROR')"),Long.class,user(),opened.get(0));
        return later==0;
    }
    public void audit(String kind,String summary,Object data) {
        String text=summary==null?"":summary.substring(0,Math.min(500,summary.length()));
        db.update(sql("INSERT INTO user_audit_event(user_id,kind,summary,payload,created_at) VALUES(?,?,?,?,?)"),user(),kind,
                text,encode(data),Instant.now().toString());
        if(telegram!=null) telegram.operation(environment,kind,text);
    }
    public void intent(String id,Decision d,Object body) {
        String now=Instant.now().toString();
        db.update(sql("INSERT INTO user_order_intent(client_id,user_id,instrument,action,state,payload,created_at,updated_at) VALUES(?,?,?,?,'SUBMITTING',?,?,?)"),
                id,user(),d.instrument(),d.action().name(),encode(body),now,now);
    }
    public void state(String id,String state,String exchangeId,String detail) {
        List<Map<String,Object>> rows=db.queryForList(sql("SELECT state,instrument,action FROM user_order_intent WHERE client_id=? AND user_id=?"),id,user());
        String previous=rows.isEmpty()?"":String.valueOf(rows.get(0).get("state"));
        db.update(sql("UPDATE user_order_intent SET state=?,exchange_id=?,detail=?,updated_at=? WHERE client_id=? AND user_id=?"),state,exchangeId,detail,Instant.now().toString(),id,user());
        if(telegram!=null && !state.equals(previous)) {
            String instrument=rows.isEmpty()?id:String.valueOf(rows.get(0).get("instrument"));
            String action=rows.isEmpty()?"":String.valueOf(rows.get(0).get("action"));
            telegram.orderState(environment,instrument,action,state,detail);
        }
    }
    public List<Map<String,Object>> orders(){return db.queryForList(sql("SELECT * FROM user_order_intent WHERE user_id=? ORDER BY created_at DESC LIMIT 100"),user());}
    public List<Map<String,Object>> events(){return db.queryForList(sql("SELECT * FROM user_audit_event WHERE user_id=? ORDER BY id DESC LIMIT 100"),user());}
    public List<Map<String,Object>> analyses(){return db.queryForList(sql("SELECT * FROM user_audit_event WHERE user_id=? AND kind IN ('DECISION','PREVIEW','AI_SKIPPED','AI_REJECTED','AI_EXECUTION_FAILED') ORDER BY id DESC LIMIT 100"),user());}
    public List<Map<String,Object>> unsettled(){return db.queryForList(sql("SELECT * FROM user_order_intent WHERE user_id=? AND state IN ('SUBMITTING','UNKNOWN','ACCEPTED','live','partially_filled') ORDER BY created_at"),user());}
    public boolean hasOrders(){return db.queryForObject(sql("SELECT COUNT(*) FROM user_order_intent WHERE user_id=?"),Long.class,user())>0;}
    public RiskPolicy riskPolicy(){return document("user_risk_policy",RiskPolicy.class,RiskPolicy.defaults());}
    public void bindAccount(String uid) {
        if(uid==null || uid.isBlank()) throw new IllegalStateException("无法确认交易所账户身份");
        List<String> rows=db.query(sql("SELECT exchange_uid FROM user_exchange_account WHERE user_id=?"),(r,n)->r.getString(1),user());
        if(!rows.isEmpty()) {
            if(!rows.get(0).equals(uid)) throw new IllegalStateException("禁止切换已绑定的交易所账户");
            return;
        }
        try {db.update(sql("INSERT INTO user_exchange_account(user_id,exchange_uid) VALUES(?,?)"),user(),uid);}
        catch(org.springframework.dao.DuplicateKeyException e){throw new IllegalStateException("该交易所账户已绑定其他用户，禁止并发管理");}
    }
    public void riskPolicy(RiskPolicy policy){policy.validate();document("user_risk_policy",policy);}
    public RiskState riskState(){return document("user_risk_state",RiskState.class,new RiskState());}
    public void riskState(RiskState state){document("user_risk_state",state);}
    private <T> T document(String table,Class<T> type,T fallback) {
        List<String> rows=db.query(sql("SELECT payload FROM "+table+" WHERE user_id=?"),(r,n)->r.getString(1),user());
        if(rows.isEmpty()) return fallback;
        try{return json.readValue(rows.get(0),type);}catch(Exception e){throw new IllegalStateException("风险状态无法读取，禁止交易");}
    }
    private void document(String table,Object value) {
        if(db.update(sql("UPDATE "+table+" SET payload=? WHERE user_id=?"),encode(value),user())==0)
            db.update(sql("INSERT INTO "+table+"(user_id,payload) VALUES(?,?)"),user(),encode(value));
    }
    public long recentOpenCount() {
        return db.queryForObject(sql("SELECT COUNT(*) FROM user_order_intent WHERE user_id=? AND action IN ('OPEN_LONG','OPEN_SHORT') AND created_at>=?"),
                Long.class,user(),Instant.now().minusSeconds(3600).toString());
    }
    public boolean recentlyOpened(String instrument,int seconds) {
        return db.queryForObject(sql("SELECT COUNT(*) FROM user_order_intent WHERE user_id=? AND instrument=? AND action IN ('OPEN_LONG','OPEN_SHORT') AND created_at>=?"),
                Long.class,user(),instrument,Instant.now().minusSeconds(seconds).toString())>0;
    }
    public boolean ownsStop(String id) {
        return id!=null && id.startsWith("s") && db.queryForObject(sql("SELECT COUNT(*) FROM user_order_intent WHERE user_id=? AND client_id=? AND action IN ('OPEN_LONG','OPEN_SHORT')"),Integer.class,user(),id.substring(1))>0;
    }
    public BigDecimal baseline(BigDecimal equity) {
        String day=LocalDate.now(ZoneOffset.UTC).toString();
        List<BigDecimal> rows=db.query(sql("SELECT equity FROM user_equity_baseline WHERE user_id=? AND day_utc=?"),(r,n)->r.getBigDecimal(1),user(),day);
        if(!rows.isEmpty()) return rows.get(0);
        if(!positive(equity)) throw new IllegalStateException("账户权益必须大于 0");
        db.update(sql("INSERT INTO user_equity_baseline(user_id,day_utc,equity) VALUES(?,?,?)"),user(),day,equity);return equity;
    }
    public boolean acquire() {
        long now=System.currentTimeMillis();
        return db.update(sql("UPDATE user_execution_lease SET owner=?,expires_at=? WHERE user_id=? AND (expires_at<? OR owner=?)"),owner,now+180000,user(),now,owner)==1;
    }
    public void renew() {
        long now=System.currentTimeMillis();
        if(db.update(sql("UPDATE user_execution_lease SET expires_at=? WHERE user_id=? AND owner=? AND expires_at>?"),now+180000,user(),owner,now)!=1)
            throw new IllegalStateException("执行锁已失效，停止提交指令");
    }
    public void release(){db.update(sql("UPDATE user_execution_lease SET expires_at=0,owner='' WHERE user_id=? AND owner=?"),user(),owner);}
}
