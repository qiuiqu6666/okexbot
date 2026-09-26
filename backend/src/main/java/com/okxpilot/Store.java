package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final String owner = UUID.randomUUID().toString();
    public Store(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("无法序列化数据"); }
    }
    public Settings settings() {
        List<String> rows = db.query("SELECT payload FROM bot_config WHERE id=1", (r, n) -> r.getString(1));
        if (rows.isEmpty()) return Settings.defaults();
        try { return json.readValue(rows.get(0), Settings.class); } catch (Exception e) { throw new IllegalStateException("配置数据无效"); }
    }
    public void settings(Settings value) {
        value.validate();
        if (db.update("UPDATE bot_config SET payload=? WHERE id=1", encode(value)) == 0)
            db.update("INSERT INTO bot_config(id,payload) VALUES(1,?)", encode(value));
    }
    public void audit(String kind, String summary, Object data) {
        db.update("INSERT INTO audit_event(kind,summary,payload,created_at) VALUES(?,?,?,?)", kind,
                summary.substring(0, Math.min(500, summary.length())), encode(data), Instant.now().toString());
    }
    public void intent(String id, Decision d, Object body) {
        String now = Instant.now().toString();
        db.update("INSERT INTO order_intent(client_id,instrument,action,state,payload,created_at,updated_at) VALUES(?,?,?,'SUBMITTING',?,?,?)",
                id, d.instrument(), d.action().name(), encode(body), now, now);
    }
    public void state(String id, String state, String exchangeId, String detail) {
        db.update("UPDATE order_intent SET state=?,exchange_id=?,detail=?,updated_at=? WHERE client_id=?",
                state, exchangeId, detail, Instant.now().toString(), id);
    }
    public List<Map<String,Object>> orders() { return db.queryForList("SELECT * FROM order_intent ORDER BY created_at DESC LIMIT 100"); }
    public List<Map<String,Object>> events() { return db.queryForList("SELECT * FROM audit_event ORDER BY id DESC LIMIT 100"); }
    public List<Map<String,Object>> unsettled() {
        return db.queryForList("SELECT * FROM order_intent WHERE state IN ('SUBMITTING','UNKNOWN','ACCEPTED','live','partially_filled') ORDER BY created_at");
    }
    public boolean ownsStop(String stopId) {
        if (stopId == null || !stopId.startsWith("s")) return false;
        return db.queryForObject("SELECT COUNT(*) FROM order_intent WHERE client_id=? AND action IN ('OPEN_LONG','OPEN_SHORT')",
                Integer.class, stopId.substring(1)) > 0;
    }
    public BigDecimal baseline(BigDecimal equity) {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        List<BigDecimal> rows = db.query("SELECT equity FROM equity_baseline WHERE day_utc=?", (r,n) -> r.getBigDecimal(1), day);
        if (!rows.isEmpty()) return rows.get(0);
        if (!positive(equity)) throw new IllegalStateException("账户权益必须大于 0");
        db.update("INSERT INTO equity_baseline(day_utc,equity) VALUES(?,?)", day, equity);
        return equity;
    }
    // A lease covers the bounded HTTP calls. Each financial mutation renews ownership first.
    public boolean acquire() {
        long now = System.currentTimeMillis();
        return db.update("UPDATE execution_lease SET owner=?,expires_at=? WHERE id=1 AND (expires_at<? OR owner=?)", owner, now+180000, now, owner) == 1;
    }
    public void renew() {
        long now = System.currentTimeMillis();
        if (db.update("UPDATE execution_lease SET expires_at=? WHERE id=1 AND owner=? AND expires_at>?", now+180000, owner, now) != 1)
            throw new IllegalStateException("执行锁已失效，停止提交指令");
    }
    public void release() { db.update("UPDATE execution_lease SET expires_at=0,owner='' WHERE id=1 AND owner=?", owner); }
}
