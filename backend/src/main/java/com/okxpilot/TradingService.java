package com.okxpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static com.okxpilot.Domain.*;

public class TradingService {
    private final Store store;
    private volatile OkxClient okx;
    private volatile AiClient ai;
    private final RiskEngine risk;
    private final ObjectMapper json;
    private final NewsService news;
    private final TradingGuard guard;
    private volatile boolean enabled=false;
    private volatile boolean analyzing=false;
    private volatile String lastError="";
    private volatile Instant lastCycle;
    private volatile Instant nextCycle=Instant.EPOCH;
    private final Map<String,BigDecimal> analyzedPrice=new HashMap<>();
    private final Map<String,BigDecimal> analyzedPnl=new HashMap<>();
    private String attentionReason="到达分析间隔";
    private String nearStop="";
    private volatile Snapshot snapshot;
    private volatile Instant quotedAt=Instant.EPOCH;
    private volatile List<Map<String,Object>> quotes=List.of();
    private final Object quoteLock=new Object();
    private final AtomicInteger pauseGeneration=new AtomicInteger();
    private static final ScheduledExecutorService pauseAudit=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"pause-audit");t.setDaemon(true);return t;});
    public TradingService(Store store,OkxClient okx,AiClient ai,RiskEngine risk,ObjectMapper json,NewsService news) {
        this.store=store;this.okx=okx;this.ai=ai;this.risk=risk;this.json=json;this.news=news;this.guard=new TradingGuard(store);
        if(store.autoTrading()) {enabled=true;nextCycle=Instant.now();store.audit("CONTROL","服务重启，已恢复自动交易",Map.of());}
    }
    public synchronized void reconfigure(OkxClient okx,AiClient ai) {
        if(enabled) throw new IllegalStateException("请先暂停自动交易");
        this.okx=okx;this.ai=ai;snapshot=null;lastError="";
    }
    public Map<String,Object> status() {
        refreshQuotes();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("environment",store.environment().name());result.put("enabled",enabled);result.put("okxConfigured",okx.configured());
        result.put("aiConfigured",ai.configured());result.put("model",ai.model());result.put("lastError",lastError);
        result.put("lastCycle",lastCycle);result.put("nextCycle",enabled?nextCycle:null);result.put("snapshot",snapshot);result.put("quotes",quotes);
        result.put("settings",store.settings());result.put("riskPolicy",store.riskPolicy());
        result.put("analyzing",analyzing);
        RiskState state=store.riskState();result.put("riskStatus",Map.of("haltReason",state.haltReason,"peakEquity",state.peakEquity,
                "consecutiveLosses",state.consecutiveLosses,"cooldownUntil",state.cooldownUntil));return result;
    }
    public synchronized void riskPolicy(RiskPolicy policy) {
        if(enabled) throw new IllegalStateException("请先暂停自动交易再修改风险约束");
        withLease(()->{policy.validate();store.riskPolicy(policy);store.audit("RISK_POLICY","已更新硬性风险约束",policy);return null;});
    }
    public synchronized void resetRisk(boolean confirmed) {
        if(!confirmed || enabled) throw new IllegalStateException("请暂停交易并明确确认人工复核熔断");
        withLease(()->{
            checkAccount();reconcile();snapshot=okx.snapshot();guard.sync(snapshot,okx);
            if(!snapshot.positions().isEmpty() || !store.unsettled().isEmpty() || !okx.pendingOrders().isEmpty() || !okx.pendingAlgos().isEmpty())
                throw new IllegalStateException("仅在空仓且所有订单核对完成后允许解除熔断");
            if(!positive(snapshot.equity())) throw new IllegalStateException("账户权益必须为正");
            BigDecimal baseline=store.baseline(snapshot.equity());
            if(baseline.subtract(snapshot.equity()).multiply(new BigDecimal("100")).compareTo(baseline.multiply(store.settings().maxDailyLossPct()))>=0)
                throw new IllegalStateException("当日亏损仍超限，不能解除熔断");
            RiskState s=store.riskState();String reason=s.haltReason;s.haltReason="";s.peakEquity=snapshot.equity();s.consecutiveLosses=0;
            store.audit("RISK_RESET","用户人工复核解除熔断",Map.of("previousReason",reason,"equity",snapshot.equity()));store.riskState(s);return null;
        });
    }
    public NewsService.Evidence news(){return news.evidence(store.settings().instruments());}
    public java.util.List<String> instruments(){return okx.instruments();}
    public synchronized void settings(Settings settings) {
        settings.validate();
        withLease(()->{
            if(!settings.instruments().containsAll(store.riskState().positions.keySet())) throw new IllegalStateException("不能移除仍有策略仓位的合约");
            if(store.unsettled().stream().anyMatch(row->!settings.instruments().contains(row.get("instrument").toString())))
                throw new IllegalStateException("不能移除仍有未确认订单的合约");
            store.settings(settings);store.audit("SETTINGS","已更新风险设置",settings);return null;
        });
    }
    public synchronized Map<String,Object> refresh() {
        return withLease(()->{checkAccount();reconcile();snapshot=okx.snapshot();guard.sync(snapshot,okx);guard.observeEquity(snapshot);return status();});
    }
    // Stopping is intentionally not synchronized: it must be visible while an AI request is in flight.
    public boolean running(){return enabled;}
    public void pause() {
        boolean was=enabled;enabled=false;store.autoTrading(false);
        int generation=pauseGeneration.incrementAndGet();
        if(!was){store.audit("CONTROL","已暂停自动交易，已有保护单保留",Map.of());return;}
        pauseAudit.schedule(()->{if(pauseGeneration.get()==generation && !enabled) store.audit("CONTROL","已暂停自动交易，已有保护单保留",Map.of());},3,TimeUnit.SECONDS);
    }
    public void keepRunning() {
        pauseGeneration.incrementAndGet();
        if(enabled) return;
        enabled=true;lastError="";nextCycle=Instant.now();store.autoTrading(true);
        store.audit("CONTROL","切换查看环境，自动交易继续",Map.of());
    }
    public synchronized void enable() {
        if(!okx.configured() || !ai.configured()) throw new IllegalStateException("请先配置当前环境的 OKX 和 AI 凭据");
        withLease(()->{
            checkAccount();reconcile();
            if(!store.unsettled().isEmpty()) throw new IllegalStateException("有待确认订单，需先核对订单状态");
            snapshot=okx.snapshot();guard.sync(snapshot,okx);guard.observeEquity(snapshot);verifyProtection(snapshot);
            store.settings().validate();store.baseline(snapshot.equity());
            store.audit("CONTROL","已开启自动交易",Map.of());store.autoTrading(true);lastError="";nextCycle=Instant.now();enabled=true;
            return null;
        });
    }
    public synchronized void tick() {
        if(!okx.configured()) return;
        try {
            withLease(()->{
                checkAccount();reconcile();snapshot=okx.snapshot();guard.sync(snapshot,okx);guard.observeEquity(snapshot);verifyProtection(snapshot);
                if(!store.unsettled().isEmpty()) throw new IllegalStateException("订单尚未最终确认，已暂停；后台继续查单");
                boolean scheduled=!Instant.now().isBefore(nextCycle);
                boolean urgent=enabled && !scheduled && attentionNeeded(store.settings());
                if(enabled && MarketAttention.shouldAnalyze(scheduled,urgent,lastCycle,Instant.now())) {
                    if(scheduled) attentionReason="到达分析间隔";
                    cycle(true);nextCycle=MarketAttention.nextSlot(Instant.now(),store.settings().intervalSeconds());
                }
                return null;
            });
        } catch(Exception e) {
            enabled=false;lastError=safeMessage(e);
            try {store.autoTrading(false);store.audit("ERROR",lastError,Map.of());} catch(Exception ignored) { /* Remain paused if persistence fails. */ }
        }
    }
    public synchronized Decision preview() { return withLease(()->{attentionReason="手动预览";return cycle(false);}); }
    void rememberAnalyzedAt(Instant when) { lastCycle=when; }
    private Decision cycle(boolean execute) {
        if(execute && !enabled) throw new IllegalStateException("自动交易已暂停");
        Settings settings=store.settings();checkAccount();reconcile();
        if(!store.unsettled().isEmpty()) throw new IllegalStateException("存在未确认订单，本轮不产生新订单");
        snapshot=okx.snapshot();guard.sync(snapshot,okx);guard.observeEquity(snapshot);
        Map<String,Object> markets=new LinkedHashMap<>();
        for(String id:settings.instruments()) {
            store.renew();
            var frames=new LinkedHashMap<String,Object>();
            frames.put("5m",timeframe(okx.candles(id,"5m")));
            for(String bar:List.of("15m","1H")) {try{frames.put(bar,timeframe(okx.candles(id,bar)));}catch(RuntimeException e){frames.put(bar,Map.of("available",false));}}
            var market=new LinkedHashMap<String,Object>();
            market.put("instrument",okx.instrument(id));market.put("price",okx.price(id));market.put("timeframes",frames);
            markets.put(id,market);
        }
        rememberMarket(markets);
        store.renew();
        var evidence=news.evidence(settings.instruments());
        store.renew();
        store.audit("NEWS_EVIDENCE",evidence.message(),evidence);
        Decision d;
        analyzing=true;
        try {d=ai.decide(Map.of("settings",settings,"riskPolicy",store.riskPolicy(),"account",snapshot,"markets",markets,"news",evidence,"environment",store.environment(),"now",Instant.now(),"trigger",attentionReason));}
        catch(IllegalArgumentException | IllegalStateException e) {
            return skipped(settings,safeMessage(e));
        }
        finally {analyzing=false;}
        if(d==null || d.action()==null) {
            return skipped(settings,"模型动作无效，本轮跳过");
        }
        try {news.validate(d,evidence);}catch(IllegalArgumentException | IllegalStateException e){return rejected(settings,d,safeMessage(e));}
        lastCycle=Instant.now();
        // Refresh after model latency; no stale model-supplied balances or order sizes are trusted.
        store.renew();snapshot=okx.snapshot();guard.sync(snapshot,okx);guard.observeEquity(snapshot);
        if(d.instrument()==null || !settings.instruments().contains(d.instrument())) return rejected(settings,d,"模型选择了白名单外合约");
        Instrument instrument=okx.instrument(d.instrument());BigDecimal price=okx.price(d.instrument());
        BigDecimal baseline=(d.action()==Action.OPEN_LONG || d.action()==Action.OPEN_SHORT)?store.baseline(snapshot.equity()):BigDecimal.ONE;
        Plan plan;
        try {plan=risk.validate(d,settings,snapshot,instrument,price,baseline,store.riskPolicy(),okx.hedge());}
        catch(IllegalArgumentException e){return rejected(settings,d,safeMessage(e));}
        try {if(!plan.reduceOnly()) guard.opening(d,plan,OkxClient.orderPosSide(okx.hedge(),plan.side(),false));}
        catch(IllegalStateException e){rejected(settings,d,safeMessage(e));throw e;}
        store.audit(execute?"DECISION":"PREVIEW",d.reason(),Map.of("decision",d,"plan",plan,"account",snapshot,"price",price,"news",evidence));
        if(execute && d.action()!=Action.HOLD) {
            try {
            if(!enabled) throw new IllegalStateException("用户已暂停，模型结果不执行");
            if(!plan.reduceOnly()) verifyProtection(snapshot);
            execute(d,plan,settings,true,false,null);
            } catch(RuntimeException e) {
                store.audit("AI_EXECUTION_FAILED","执行未完成，请核对订单状态",Map.of("decision",d,"reason",safeMessage(e)));throw e;
            }
        }
        lastError="";return d;
    }
    private Decision skipped(Settings settings,String message) {
        lastError=message;lastCycle=Instant.now();store.audit("AI_SKIPPED",message,Map.of("reason",message));
        return new Decision(Action.HOLD,settings.instruments().get(0),null,null,null,null,"本轮跳过："+message);
    }
    private Decision rejected(Settings settings,Decision d,String message) {
        lastError=message;lastCycle=Instant.now();
        store.audit("AI_REJECTED",message,Map.of("decision",d,"reason",message));
        return new Decision(Action.HOLD,settings.instruments().get(0),null,null,null,null,"本轮跳过："+message);
    }
    private void execute(Decision d,Plan plan,Settings settings,boolean automatic) { execute(d,plan,settings,automatic,false,null); }
    private void execute(Decision d,Plan plan,Settings settings,boolean automatic,boolean manual,String side) {
        store.renew();
        if(automatic && !enabled) throw new IllegalStateException("用户已暂停，本次指令取消");
        String clientId="p"+UUID.randomUUID().toString().replace("-","").substring(0,29);
        if(d.action()==Action.UPDATE_STOPS) {
            snapshot=okx.snapshot();guard.sync(snapshot,okx);
            risk.validate(d,settings,snapshot,okx.instrument(d.instrument()),okx.price(d.instrument()),BigDecimal.ONE,store.riskPolicy());
            guard.requireOwned(position(d.instrument()));amend(d,clientId,automatic);return;
        }
        Map<String,Object> body=new LinkedHashMap<>();
        String posSide=OkxClient.orderPosSide(okx.hedge(),plan.side(),plan.reduceOnly());
        body.put("instId",d.instrument());body.put("tdMode","isolated");body.put("posSide",posSide);
        body.put("side",plan.side());body.put("ordType","market");body.put("sz",plan.contracts().toPlainString());
        body.put("clOrdId",clientId);body.put("reduceOnly",okx.hedge()?false:plan.reduceOnly());
        if(!plan.reduceOnly()) {
            if(!okx.pendingOrders().isEmpty()) throw new IllegalStateException("账户存在挂单，禁止新增风险");
            if(!okx.stops(d.instrument()).isEmpty()) throw new IllegalStateException("该合约有残留保护单，请在 OKX 清理并核对后再开仓");
            okx.leverage(d.instrument(),settings.leverage(),okx.hedge()?posSide:null);
            body.put("attachAlgoOrds",List.of(Map.of("attachAlgoClOrdId","s"+clientId,
                    "tpTriggerPx",d.takeProfit().toPlainString(),"tpOrdPx","-1","tpTriggerPxType","last",
                    "slTriggerPx",d.stopLoss().toPlainString(),"slOrdPx","-1","slTriggerPxType","last")));
        }
        // Earlier exchange checks may have taken time. Validate again immediately before submission.
        snapshot=okx.snapshot();guard.sync(snapshot,okx);guard.observeEquity(snapshot);
        BigDecimal currentPrice=okx.price(d.instrument());
        Instrument currentInstrument=okx.instrument(d.instrument());
        Plan current=manual?risk.manualClose(position(d.instrument(),side),currentInstrument,d.action()==Action.CLOSE?BigDecimal.ONE:d.reduceFraction(),currentPrice)
                :risk.validate(d,settings,snapshot,currentInstrument,currentPrice,plan.reduceOnly()?BigDecimal.ONE:store.baseline(snapshot.equity()),store.riskPolicy(),okx.hedge());
        if(!current.reduceOnly()) {
            guard.opening(d,current,OkxClient.orderPosSide(okx.hedge(),current.side(),false));verifyProtection(snapshot);
            if(!store.unsettled().isEmpty() || !okx.pendingOrders().isEmpty()) throw new IllegalStateException("账户存在挂单或未确认订单，禁止新增风险");
            for(JsonNode algo:okx.pendingAlgos()) {
                Position p=snapshot.positions().stream().filter(v->v.instrument().equals(algo.path("instId").asText())).findFirst().orElse(null);
                if(p==null || !guard.ownsStop(p,algo) || !algo.path("side").asText().equals(p.contracts().signum()>0?"sell":"buy") ||
                        OkxClient.number(algo,"sz").compareTo(p.contracts().abs())!=0)
                    throw new IllegalStateException("账户存在非当前策略保护单的算法挂单，禁止新增风险");
            }
        } else if(!manual) guard.requireOwned(position(d.instrument()));
        risk.validateBook(okx.book(d.instrument()),current,currentPrice,store.riskPolicy());
        body.put("sz",current.contracts().toPlainString());
        body.put("side",current.side());
        body.put("posSide",OkxClient.orderPosSide(okx.hedge(),current.side(),current.reduceOnly()));
        body.put("reduceOnly",okx.hedge()?false:current.reduceOnly());
        store.renew();
        if(automatic && !enabled) throw new IllegalStateException("用户已暂停，本次指令取消");
        Map<String,Object> persisted=new LinkedHashMap<>(body);persisted.put("riskNotional",current.notionalUsdt().toPlainString());
        persisted.put("riskContractValue",currentInstrument.contractValue().toPlainString());
        if(java.time.Duration.between(snapshot.fetchedAt(),Instant.now()).abs().getSeconds()>30)
            throw new IllegalStateException("下单检查期间账户数据已过期，本轮不提交");
        store.intent(clientId,d,persisted);
        try {
            JsonNode response=okx.place(body);
            String orderId=response.path("ordId").asText();
            if(orderId.isBlank()) throw new IllegalStateException("OKX 未返回订单号");
            store.state(clientId,"ACCEPTED",orderId,"交易所已接受，尚未确认成交");
            store.audit("ORDER",d.instrument()+" "+TelegramNotifier.actionText(d.action().name())+"，订单已提交，等待成交对账",Map.of("clientId",clientId,"orderId",orderId));
        } catch(OkxClient.Rejected e) {store.state(clientId,"REJECTED",null,e.getMessage());throw e;}
        catch(Exception e) {store.state(clientId,"UNKNOWN",null,"结果不明确，禁止自动重试");throw e;}
    }
    private void amend(Decision d,String clientId,boolean automatic) {
        List<JsonNode> owned=new ArrayList<>();
        for(JsonNode stop:okx.stops(d.instrument())) if(guard.ownsStop(position(d.instrument()),stop)) owned.add(stop);
        if(owned.size()!=1) throw new IllegalStateException("需要恰好一组本程序创建的保护单才能自动修改");
        JsonNode stop=owned.get(0);String algoId=stop.path("algoId").asText();
        Position position=snapshot.positions().stream().filter(p->p.instrument().equals(d.instrument())).findFirst().orElseThrow();
        BigDecimal oldSl=OkxClient.number(stop,"slTriggerPx");
        BigDecimal remembered=guard.managed(position).stopLoss;
        if(position.contracts().signum()>0?d.stopLoss().compareTo(oldSl.max(remembered))<0:d.stopLoss().compareTo(oldSl.min(remembered))>0)
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
    public synchronized void reconcileNow() { withLease(()->{checkAccount();reconcile();snapshot=okx.snapshot();guard.sync(snapshot,okx);guard.observeEquity(snapshot);return null;}); }
    private void reconcile() {
        for(Map<String,Object> row:store.unsettled()) {
            store.renew();String id=row.get("client_id").toString();String instrument=row.get("instrument").toString();
            try {
                if("UPDATE_STOPS".equals(row.get("action"))) {
                    JsonNode body=json.readTree(row.get("payload").toString());boolean matched=false;
                    for(JsonNode stop:okx.stops(instrument)) if(stop.path("algoId").asText().equals(body.path("algoId").asText()) &&
                            OkxClient.number(stop,"tpTriggerPx").compareTo(body.path("takeProfit").decimalValue())==0 &&
                            OkxClient.number(stop,"slTriggerPx").compareTo(body.path("stopLoss").decimalValue())==0) matched=true;
                    if(matched) guard.stopUpdated(instrument,body.path("stopLoss").decimalValue());
                    store.state(id,matched?"APPLIED":"UNKNOWN",body.path("algoId").asText(),matched?"保护单已核对":"保护单未匹配，请在 OKX 核对");
                } else {
                    JsonNode order=okx.order(instrument,id);
                    String state=order.path("state").asText();
                    if(!Set.of("live","partially_filled","filled","canceled","mmp_canceled").contains(state)) throw new IllegalStateException("未知订单状态");
                    guard.filled(row,order,json.readTree(row.get("payload").toString()));
                    if(Set.of("live","partially_filled").contains(state) &&
                            Instant.parse(row.get("created_at").toString()).plusSeconds(store.riskPolicy().orderTimeoutSeconds()).isBefore(Instant.now())) {
                        okx.cancel(instrument,id);
                        store.audit("CANCEL_REQUEST","超时订单已请求撤销，继续查单确认",Map.of("clientId",id));
                    }
                    store.state(id,state,order.path("ordId").asText(),store.encode(Map.of("filledContracts",order.path("accFillSz").asText(),"averagePrice",order.path("avgPx").asText())));
                }
            } catch(Exception e) {store.state(id,"UNKNOWN",Objects.toString(row.get("exchange_id"),""),"暂时无法确认；不会重复下单");}
        }
    }
    private void verifyProtection(Snapshot account) {
        for(Position p:account.positions()) {
            guard.requireOwned(p);
            if(!positive(p.leverage()) || p.leverage().compareTo(BigDecimal.valueOf(store.settings().leverage()))>0)
                throw new IllegalStateException("实际持仓杠杆超过配置上限");
            store.renew();boolean protectedPosition=false;
            for(JsonNode s:okx.stops(p.instrument())) {
                if(guard.ownsStop(p,s) &&
                        positive(OkxClient.number(s,"slTriggerPx")) && positive(OkxClient.number(s,"tpTriggerPx")) &&
                        OkxClient.number(s,"sz").compareTo(p.contracts().abs())==0 &&
                        s.path("side").asText().equals(p.contracts().signum()>0?"sell":"buy")) {
                    BigDecimal previous=guard.managed(p).stopLoss;
                    BigDecimal current=OkxClient.number(s,"slTriggerPx");
                    if(p.contracts().signum()>0?current.compareTo(previous)<0:current.compareTo(previous)>0)
                        throw new IllegalStateException("保护单止损被放宽，禁止新增风险");
                    if(p.contracts().signum()>0?current.compareTo(p.markPrice())>=0:current.compareTo(p.markPrice())<=0)
                        throw new IllegalStateException("止损已越过当前价格，需确认触发状态");
                    guard.stopUpdated(p,current);
                    protectedPosition=true;
                }
            }
            if(!protectedPosition) throw new IllegalStateException(p.instrument()+" 未确认本程序完整保护单，禁止新增风险；可人工减仓/平仓");
        }
    }
    public synchronized void close(String id,boolean half) { close(id,half,null); }
    public synchronized void close(String id,boolean half,String side) {
        OkxClient.validateId(id);
        if(side!=null && !side.equals("long") && !side.equals("short")) throw new IllegalArgumentException("持仓方向无效");
        withLease(()->{
            checkAccount();reconcile();
            if(!store.unsettled().isEmpty()) throw new IllegalStateException("有待确认订单，先完成对账");
            snapshot=okx.snapshot();guard.sync(snapshot,okx);guard.observeEquity(snapshot);
            Position chosen=position(id,side);
            Decision d=new Decision(half?Action.REDUCE:Action.CLOSE,id,null,half?new BigDecimal("0.5"):null,null,null,"用户主动"+(half?"减仓一半":"平仓"));
            Plan plan=risk.manualClose(chosen,okx.instrument(id),d.reduceFraction(),okx.price(id));
            execute(d,plan,store.settings(),false,true,side);return null;
        });
    }
    private void refreshQuotes() {
        if(!okx.configured() || java.time.Duration.between(quotedAt,Instant.now()).abs().toMillis()<2000) return;
        synchronized(quoteLock) {
            if(java.time.Duration.between(quotedAt,Instant.now()).abs().toMillis()<2000) return;
            try {
                Snapshot next=okx.snapshot();
                List<Map<String,Object>> rows=new ArrayList<>();
                for(Position p:next.positions()) {
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put("instrument",p.instrument());row.put("contracts",p.contracts());row.put("markPrice",p.markPrice());
                    row.put("entryPrice",p.entryPrice());
                    row.put("unrealizedPnl",p.unrealizedPnl());row.put("notionalUsdt",p.notionalUsdt());row.put("leverage",p.leverage());
                    row.put("side",p.contracts().signum()>0?"long":"short");
                    try { row.put("lastPrice",okx.price(p.instrument())); } catch(RuntimeException e) { row.put("lastPrice",p.markPrice()); }
                    BigDecimal takeProfit=null,stopLoss=null;
                    try {
                        JsonNode stops=okx.stops(p.instrument());
                        if(stops!=null) for(JsonNode stop:stops) {
                            String posSide=stop.path("posSide").asText("");
                            boolean longPos=p.contracts().signum()>0;
                            boolean same=posSide.isBlank() || "net".equals(posSide) || (longPos && "long".equals(posSide)) || (!longPos && "short".equals(posSide));
                            if(!same || "sell".equals(stop.path("side").asText())!=longPos) continue;
                            BigDecimal tp=optionalNumber(stop,"tpTriggerPx"),sl=optionalNumber(stop,"slTriggerPx");
                            if(tp!=null) takeProfit=tp;
                            if(sl!=null) stopLoss=sl;
                        }
                    } catch(RuntimeException ignored) { /* Keep the position visible when protection orders cannot be read. */ }
                    row.put("takeProfit",takeProfit);row.put("stopLoss",stopLoss);rows.add(row);
                }
                snapshot=next;quotes=List.copyOf(rows);
            } catch(RuntimeException ignored) { /* Keep the previous snapshot when the exchange read fails. */ }
            finally { quotedAt=Instant.now(); }
        }
    }
    private boolean attentionNeeded(Settings settings) {
        try {
            String shock=MarketAttention.priceShock(analyzedPrice,okx.lastPrices(settings.instruments()));
            if(shock!=null) {attentionReason=shock;return true;}
            if(snapshot!=null) {
                String pnl=MarketAttention.pnlShock(snapshot.equity(),analyzedPnl,snapshot.positions());
                if(pnl!=null) {attentionReason=pnl;return true;}
                String near=nearProtection();
                if(near!=null) {attentionReason=near;return true;}
            }
        } catch(RuntimeException ignored) {}
        return false;
    }
    private String nearProtection() {
        String hit=null;
        for(Position position:snapshot.positions()) {
            JsonNode stops=okx.stops(position.instrument());
            if(stops==null) continue;
            for(JsonNode stop:stops) {
                if(MarketAttention.near(position.markPrice(),optionalNumber(stop,"tpTriggerPx"))) hit=position.instrument()+" 价格接近止盈";
                if(MarketAttention.near(position.markPrice(),optionalNumber(stop,"slTriggerPx"))) hit=position.instrument()+" 价格接近止损";
            }
        }
        if(hit==null) {nearStop="";return null;}
        if(hit.equals(nearStop)) return null;
        nearStop=hit;return hit;
    }
    private void rememberMarket(Map<String,Object> markets) {
        analyzedPrice.clear();
        for(var entry:markets.entrySet()) {
            if(entry.getValue() instanceof Map<?,?> market && market.get("price") instanceof BigDecimal price) analyzedPrice.put(entry.getKey(),price);
        }
        analyzedPnl.clear();
        if(snapshot==null) return;
        for(Position position:snapshot.positions()) if(position.unrealizedPnl()!=null) analyzedPnl.put(position.instrument(),position.unrealizedPnl());
    }
    private static Map<String,Object> timeframe(JsonNode candles){return Map.of("indicators",Indicators.from(candles));}
    private static BigDecimal optionalNumber(JsonNode node,String key) {
        String value=node.path(key).asText();
        if(value.isBlank()) return null;
        try { return new BigDecimal(value); } catch(NumberFormatException e) { return null; }
    }
    private void checkAccount(){okx.checkAccountMode();store.bindAccount(okx.accountId());}
    private Position position(String id) { return position(id,null); }
    private Position position(String id,String side) {
        List<Position> rows=snapshot.positions().stream().filter(p->p.instrument().equals(id)).toList();
        if("long".equals(side)) rows=rows.stream().filter(p->p.contracts().signum()>0).toList();
        if("short".equals(side)) rows=rows.stream().filter(p->p.contracts().signum()<0).toList();
        if(rows.size()>1) throw new IllegalStateException("该合约同时存在多空仓位，无法自动选择");
        if(rows.isEmpty()) throw new IllegalStateException("没有可管理的仓位");
        return rows.get(0);
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
