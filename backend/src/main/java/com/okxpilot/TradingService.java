package com.okxpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.okxpilot.Domain.*;

public class TradingService {
    private final Store store;
    private volatile OkxClient okx;
    private volatile AiClient ai;
    private final RiskEngine risk;
    private final ObjectMapper json;
    private volatile boolean enabled=false;
    private volatile String lastError="";
    private volatile Instant lastCycle;
    private volatile Instant nextCycle=Instant.EPOCH;
    private volatile Snapshot snapshot;
    public TradingService(Store store,OkxClient okx,AiClient ai,RiskEngine risk,ObjectMapper json) {
        this.store=store;this.okx=okx;this.ai=ai;this.risk=risk;this.json=json;
    }
    public synchronized void reconfigure(OkxClient okx,AiClient ai) {
        if(enabled) throw new IllegalStateException("请先暂停自动交易");
        this.okx=okx;this.ai=ai;snapshot=null;lastError="";
    }
    public Map<String,Object> status() {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("environment","DEMO");result.put("enabled",enabled);result.put("okxConfigured",okx.configured());
        result.put("aiConfigured",ai.configured());result.put("model",ai.model());result.put("lastError",lastError);
        result.put("lastCycle",lastCycle);result.put("nextCycle",enabled?nextCycle:null);result.put("snapshot",snapshot);
        result.put("settings",store.settings());return result;
    }
    public synchronized void settings(Settings settings) {
        if(enabled) throw new IllegalStateException("请先暂停自动交易再修改参数");
        store.settings(settings);store.audit("SETTINGS","已更新风险设置",settings);
    }
    public synchronized Map<String,Object> refresh() {
        okx.checkAccountMode();snapshot=okx.snapshot();return status();
    }
    // Stopping is intentionally not synchronized: it must be visible while an AI request is in flight.
    public void pause() {
        enabled=false;store.audit("CONTROL","已暂停自动交易，已有保护单保留",Map.of());
    }
    public synchronized void enable() {
        if(!okx.configured() || !ai.configured()) throw new IllegalStateException("请先配置 OKX 模拟盘和 AI 凭据");
        withLease(()->{
            okx.checkAccountMode();reconcile();
            if(!store.unsettled().isEmpty()) throw new IllegalStateException("有待确认订单，需先核对订单状态");
            snapshot=okx.snapshot();verifyProtection(snapshot);
            store.settings().validate();store.baseline(snapshot.equity());
            store.audit("CONTROL","已开启模拟盘自动交易",Map.of());lastError="";nextCycle=Instant.now();enabled=true;
            return null;
        });
    }
    public synchronized void tick() {
        if(!okx.configured()) return;
        try {
            withLease(()->{
                boolean monitor=enabled || snapshot==null || !store.unsettled().isEmpty() || !snapshot.positions().isEmpty();
                if(monitor) {
                    reconcile();snapshot=okx.snapshot();verifyProtection(snapshot);
                    if(!store.unsettled().isEmpty()) throw new IllegalStateException("订单尚未最终确认，已暂停；后台继续查单");
                }
                if(enabled && !Instant.now().isBefore(nextCycle)) {
                    cycle(true);nextCycle=Instant.now().plusSeconds(store.settings().intervalSeconds());
                }
                return null;
            });
        } catch(Exception e) {
            enabled=false;lastError=safeMessage(e);
            try {store.audit("ERROR",lastError,Map.of());} catch(Exception ignored) { /* Remain paused if persistence fails. */ }
        }
    }
    public synchronized Decision preview() { return withLease(()->cycle(false)); }
    private Decision cycle(boolean execute) {
        if(execute && !enabled) throw new IllegalStateException("自动交易已暂停");
        Settings settings=store.settings();okx.checkAccountMode();reconcile();
        if(!store.unsettled().isEmpty()) throw new IllegalStateException("存在未确认订单，本轮不产生新订单");
        snapshot=okx.snapshot();
        Map<String,Object> markets=new LinkedHashMap<>();
        for(String id:settings.instruments()) {
            store.renew();
            markets.put(id,Map.of("instrument",okx.instrument(id),"price",okx.price(id),"candlesNewestFirst",okx.candles(id)));
        }
        store.renew();
        Decision d=ai.decide(Map.of("settings",settings,"account",snapshot,"markets",markets,"now",Instant.now()));
        lastCycle=Instant.now();
        // Refresh after model latency; no stale model-supplied balances or order sizes are trusted.
        store.renew();snapshot=okx.snapshot();
        if(d.instrument()==null || !settings.instruments().contains(d.instrument())) throw new IllegalArgumentException("模型选择了白名单外合约");
        Instrument instrument=okx.instrument(d.instrument());BigDecimal price=okx.price(d.instrument());
        BigDecimal baseline=(d.action()==Action.OPEN_LONG || d.action()==Action.OPEN_SHORT)?store.baseline(snapshot.equity()):BigDecimal.ONE;
        Plan plan=risk.validate(d,settings,snapshot,instrument,price,baseline);
        store.audit(execute?"DECISION":"PREVIEW",d.reason(),Map.of("decision",d,"plan",plan,"account",snapshot,"price",price));
        if(execute && d.action()!=Action.HOLD) {
            if(!enabled) throw new IllegalStateException("用户已暂停，模型结果不执行");
            if(!plan.reduceOnly()) verifyProtection(snapshot);
            execute(d,plan,settings,true);
        }
        lastError="";return d;
    }
    private void execute(Decision d,Plan plan,Settings settings,boolean automatic) {
        store.renew();
        if(automatic && !enabled) throw new IllegalStateException("用户已暂停，本次指令取消");
        String clientId="p"+UUID.randomUUID().toString().replace("-","").substring(0,29);
        if(d.action()==Action.UPDATE_STOPS) { amend(d,clientId,automatic);return; }
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("instId",d.instrument());body.put("tdMode","isolated");body.put("posSide","net");
        body.put("side",plan.side());body.put("ordType","market");body.put("sz",plan.contracts().toPlainString());
        body.put("clOrdId",clientId);body.put("reduceOnly",plan.reduceOnly());
        if(!plan.reduceOnly()) {
            if(!okx.pendingOrders().isEmpty()) throw new IllegalStateException("账户存在挂单，禁止新增风险");
            if(!okx.stops(d.instrument()).isEmpty()) throw new IllegalStateException("该合约有残留保护单，请在 OKX 清理并核对后再开仓");
            okx.leverage(d.instrument(),settings.leverage());
            body.put("attachAlgoOrds",List.of(Map.of("attachAlgoClOrdId","s"+clientId,
                    "tpTriggerPx",d.takeProfit().toPlainString(),"tpOrdPx","-1","tpTriggerPxType","last",
                    "slTriggerPx",d.stopLoss().toPlainString(),"slOrdPx","-1","slTriggerPxType","last")));
        }
        // Earlier exchange checks may have taken time. Validate again immediately before submission.
        snapshot=okx.snapshot();
        Plan current=risk.validate(d,settings,snapshot,okx.instrument(d.instrument()),okx.price(d.instrument()),plan.reduceOnly()?BigDecimal.ONE:store.baseline(snapshot.equity()));
        body.put("sz",current.contracts().toPlainString());
        body.put("side",current.side());
        store.renew();
        if(automatic && !enabled) throw new IllegalStateException("用户已暂停，本次指令取消");
        store.intent(clientId,d,body);
        try {
            JsonNode response=okx.place(body);
            String orderId=response.path("ordId").asText();
            if(orderId.isBlank()) throw new IllegalStateException("OKX 未返回订单号");
            store.state(clientId,"ACCEPTED",orderId,"交易所已接受，尚未确认成交");
            store.audit("ORDER","订单已提交，等待成交对账",Map.of("clientId",clientId,"orderId",orderId));
        } catch(OkxClient.Rejected e) {store.state(clientId,"REJECTED",null,e.getMessage());throw e;}
        catch(Exception e) {store.state(clientId,"UNKNOWN",null,"结果不明确，禁止自动重试");throw e;}
    }
    private void amend(Decision d,String clientId,boolean automatic) {
        List<JsonNode> owned=new ArrayList<>();
        for(JsonNode stop:okx.stops(d.instrument())) if(store.ownsStop(stop.path("algoClOrdId").asText())) owned.add(stop);
        if(owned.size()!=1) throw new IllegalStateException("需要恰好一组本程序创建的保护单才能自动修改");
        JsonNode stop=owned.get(0);String algoId=stop.path("algoId").asText();
        Position position=snapshot.positions().stream().filter(p->p.instrument().equals(d.instrument())).findFirst().orElseThrow();
        BigDecimal oldSl=OkxClient.number(stop,"slTriggerPx");
        if(position.contracts().signum()>0?d.stopLoss().compareTo(oldSl)<0:d.stopLoss().compareTo(oldSl)>0)
            throw new IllegalArgumentException("自动调整只允许收紧止损，不允许扩大亏损范围");
        store.renew();
        if(automatic && !enabled) throw new IllegalStateException("用户已暂停，本次指令取消");
        store.intent(clientId,d,Map.of("algoId",algoId,"takeProfit",d.takeProfit(),"stopLoss",d.stopLoss()));
        try {
            okx.amendStop(d.instrument(),algoId,d);
            store.state(clientId,"ACCEPTED",algoId,"修改已接受，等待对账");
        } catch(OkxClient.Rejected e) {store.state(clientId,"REJECTED",algoId,e.getMessage());throw e;}
        catch(Exception e) {store.state(clientId,"UNKNOWN",algoId,"修改结果待确认");throw e;}
    }
    public synchronized void reconcileNow() { withLease(()->{reconcile();snapshot=okx.snapshot();return null;}); }
    private void reconcile() {
        for(Map<String,Object> row:store.unsettled()) {
            store.renew();String id=row.get("client_id").toString();String instrument=row.get("instrument").toString();
            try {
                if("UPDATE_STOPS".equals(row.get("action"))) {
                    JsonNode body=json.readTree(row.get("payload").toString());boolean matched=false;
                    for(JsonNode stop:okx.stops(instrument)) if(stop.path("algoId").asText().equals(body.path("algoId").asText()) &&
                            OkxClient.number(stop,"tpTriggerPx").compareTo(body.path("takeProfit").decimalValue())==0 &&
                            OkxClient.number(stop,"slTriggerPx").compareTo(body.path("stopLoss").decimalValue())==0) matched=true;
                    store.state(id,matched?"APPLIED":"UNKNOWN",body.path("algoId").asText(),matched?"保护单已核对":"保护单未匹配，请在 OKX 核对");
                } else {
                    JsonNode order=okx.order(instrument,id);
                    String state=order.path("state").asText();
                    if(!Set.of("live","partially_filled","filled","canceled","mmp_canceled").contains(state)) throw new IllegalStateException("未知订单状态");
                    store.state(id,state,order.path("ordId").asText(),store.encode(Map.of("filledContracts",order.path("accFillSz").asText(),"averagePrice",order.path("avgPx").asText())));
                }
            } catch(Exception e) {store.state(id,"UNKNOWN",Objects.toString(row.get("exchange_id"),""),"暂时无法确认；不会重复下单");}
        }
    }
    private void verifyProtection(Snapshot account) {
        for(Position p:account.positions()) {
            store.renew();boolean protectedPosition=false;
            for(JsonNode s:okx.stops(p.instrument())) {
                if(store.ownsStop(s.path("algoClOrdId").asText()) &&
                        positive(OkxClient.number(s,"slTriggerPx")) && positive(OkxClient.number(s,"tpTriggerPx")) &&
                        OkxClient.number(s,"sz").compareTo(p.contracts().abs())>=0 &&
                        s.path("side").asText().equals(p.contracts().signum()>0?"sell":"buy")) protectedPosition=true;
            }
            if(!protectedPosition) throw new IllegalStateException(p.instrument()+" 未确认本程序完整保护单，禁止新增风险；可人工减仓/平仓");
        }
    }
    public synchronized void close(String id,boolean half) {
        OkxClient.validateId(id);
        withLease(()->{
            okx.checkAccountMode();reconcile();
            if(!store.unsettled().isEmpty()) throw new IllegalStateException("有待确认订单，先完成对账");
            Settings settings=store.settings();snapshot=okx.snapshot();
            Decision d=new Decision(half?Action.REDUCE:Action.CLOSE,id,null,half?new BigDecimal("0.5"):null,null,null,"用户主动"+(half?"减仓一半":"平仓"));
            Plan plan=risk.validate(d,settings,snapshot,okx.instrument(id),okx.price(id),BigDecimal.ONE);
            execute(d,plan,settings,false);return null;
        });
    }
    private <T> T withLease(java.util.function.Supplier<T> work) {
        if(!store.acquire()) throw new IllegalStateException("另一个服务正在操作该账户，请稍后重试");
        try{return work.get();}finally{store.release();}
    }
    static String safeMessage(Exception e) {
        return e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof OkxClient.Rejected
                ? Objects.toString(e.getMessage(),"操作失败") : "服务处理失败，自动交易已暂停，请检查服务端日志与数据库";
    }
}
