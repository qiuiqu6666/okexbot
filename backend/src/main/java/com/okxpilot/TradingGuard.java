package com.okxpilot;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.okxpilot.Domain.*;

/** All mutations run under the account execution lease. */
public class TradingGuard {
    private final Store store;
    public TradingGuard(Store store){this.store=store;}
    public void observeEquity(Snapshot account) {
        RiskState s=store.riskState();RiskPolicy p=store.riskPolicy();
        if(!positive(account.equity())) {s.haltReason="账户权益非正";store.riskState(s);return;}
        s.peakEquity=s.peakEquity.max(account.equity());
        BigDecimal baseline=store.baseline(account.equity());
        if(s.haltReason.isBlank()) {
            if(baseline.subtract(account.equity()).multiply(new BigDecimal("100")).compareTo(baseline.multiply(store.settings().maxDailyLossPct()))>=0)
                s.haltReason="触发 UTC 当日权益损失上限";
            else if(s.peakEquity.subtract(account.equity()).multiply(new BigDecimal("100")).compareTo(s.peakEquity.multiply(p.maxDrawdownPct()))>=0)
                s.haltReason="触发账户峰值回撤上限";
            else if(s.consecutiveLosses>=p.maxConsecutiveLosses()) s.haltReason="触发连续亏损上限";
        }
        store.riskState(s);
    }
    public static String trackKey(String instrument,String posSide) {
        if("long".equals(posSide)) return instrument+"#L";
        if("short".equals(posSide)) return instrument+"#S";
        return instrument;
    }
    static String base(String key){return key.replaceFirst("#[LS]$","");}
    public RiskState.Managed managed(Position p) {
        RiskState s=store.riskState();
        RiskState.Managed sided=s.positions.get(p.instrument()+(p.contracts().signum()>0?"#L":"#S"));
        return sided!=null?sided:s.positions.get(p.instrument());
    }
    public void opening(Decision d,Plan plan) { opening(d,plan,"net"); }
    public void opening(Decision d,Plan plan,String posSide) {
        RiskState s=store.riskState();RiskPolicy p=store.riskPolicy();
        if(!s.haltReason.isBlank()) throw new IllegalStateException("风控熔断："+s.haltReason+"；需人工复核解除");
        if(store.recentOpenCount()>=p.maxOpensPerHour()) throw new IllegalStateException("每小时开仓次数超限");
        if(store.recentlyOpened(d.instrument(),p.cooldownSeconds()) || s.cooldownUntil.getOrDefault(d.instrument(),0L)>System.currentTimeMillis())
            throw new IllegalStateException("合约处于交易冷却期，禁止立即反手");
        if(s.positions.containsKey(trackKey(d.instrument(),posSide))) throw new IllegalStateException("已有策略仓位或待核对平仓，禁止补仓");
        if(s.lossNotionalCap!=null && plan.notionalUsdt().compareTo(s.lossNotionalCap)>0)
            throw new IllegalStateException("亏损后禁止放大开仓金额");
    }
    public void requireOwned(Position p) {
        RiskState.Managed m=managed(p);
        if(m==null || m.contracts.compareTo(p.contracts())!=0 || m.positionId.isBlank() ||
                !m.positionId.equals(p.positionId()) || m.positionCreatedAt!=p.createdAt() || !"isolated".equals(p.marginMode()) ||
                m.latestTradeId.isBlank() || !m.latestTradeId.equals(p.tradeId()))
            throw new IllegalStateException(p.instrument()+" 仓位归属或数量不匹配，禁止操作非本策略仓位；请核对保护单与成交记录");
    }
    public boolean ownsStop(Position p,JsonNode stop) {
        RiskState.Managed m=managed(p);
        return m!=null && ("s"+m.openingId).equals(stop.path("algoClOrdId").asText());
    }
    public void stopUpdated(String instrument,BigDecimal stop) {
        RiskState s=store.riskState();boolean changed=false;
        for(var e:s.positions.entrySet()) if(base(e.getKey()).equals(instrument)){e.getValue().stopLoss=stop;changed=true;}
        if(changed) store.riskState(s);
    }
    public void stopUpdated(Position p,BigDecimal stop) {
        RiskState s=store.riskState();
        RiskState.Managed m=s.positions.get(p.instrument()+(p.contracts().signum()>0?"#L":"#S"));
        if(m==null) m=s.positions.get(p.instrument());
        if(m!=null){m.stopLoss=stop;store.riskState(s);}
    }
    public void filled(Map<String,Object> row,JsonNode order,JsonNode body) {
        BigDecimal total=OkxClient.number(order,"accFillSz");RiskState s=store.riskState();
        String id=row.get("client_id").toString(),instrument=row.get("instrument").toString();
        BigDecimal previous=s.fills.getOrDefault(id,BigDecimal.ZERO),delta=total.subtract(previous);
        if(delta.signum()<0) throw new IllegalStateException("累计成交量倒退，停止对账");
        if(delta.signum()==0) return;
        String tradeId=order.path("tradeId").asText();
        if(tradeId.isBlank()) throw new IllegalStateException("成交缺少交易标识，无法核对仓位归属");
        BigDecimal requested=new BigDecimal(body.path("sz").asText());
        if(total.compareTo(requested)>0) throw new IllegalStateException("累计成交量超过委托量");
        boolean opening=row.get("action").toString().startsWith("OPEN_");
        String posSide=body.path("posSide").asText("net");
        String key=trackKey(instrument,posSide);
        RiskState.Managed m=s.positions.get(key);
        if(opening) {
            if(m==null) {
                m=new RiskState.Managed();m.openingId=id;m.submittedAt=Instant.parse(row.get("created_at").toString()).toEpochMilli();
                m.stopLoss=new BigDecimal(body.path("attachAlgoOrds").path(0).path("slTriggerPx").asText());
                s.positions.put(key,m);
            }
            if(!m.openingId.equals(id)) throw new IllegalStateException("同一合约存在另一笔策略开仓");
            int direction="OPEN_LONG".equals(row.get("action"))?1:-1;
            m.contracts=m.contracts.add(delta.multiply(BigDecimal.valueOf(direction)));m.openedContracts=total;
            // Cap future exposure using actual fills, not the unfilled portion of the original request.
            if(body.has("riskContractValue")) m.notional=total.multiply(OkxClient.number(order,"avgPx")).multiply(new BigDecimal(body.path("riskContractValue").asText()));
            else m.notional=(body.has("riskNotional")?new BigDecimal(body.path("riskNotional").asText()):store.settings().maxOrderUsdt())
                    .multiply(total).divide(requested,16,java.math.RoundingMode.DOWN);
        } else {
            if(m==null || delta.compareTo(m.contracts.abs())>0) throw new IllegalStateException("减仓成交与策略仓位不匹配");
            m.contracts=m.contracts.subtract(delta.multiply(BigDecimal.valueOf(m.contracts.signum())));
        }
        if(!positive(m.notional)) throw new IllegalStateException("成交名义金额无效");
        m.latestTradeId=tradeId;s.fills.put(id,total);store.riskState(s);
    }
    public void sync(Snapshot account,OkxClient okx) {
        RiskState s=store.riskState();boolean changed=false;
        record Closed(String key,String instrument,RiskState.Managed managed,JsonNode history) {}
        List<Closed> closed=new ArrayList<>();
        for(var entry:new ArrayList<>(s.positions.entrySet())) {
            String key=entry.getKey();String instrument=base(key);RiskState.Managed m=entry.getValue();
            int sign=key.endsWith("#L")?1:key.endsWith("#S")?-1:0;
            Position actual=account.positions().stream().filter(p->p.instrument().equals(instrument) && (sign==0 || p.contracts().signum()==sign)).findFirst().orElse(null);
            if(actual!=null) {
                if(m.positionId.isBlank()) {
                    if(actual.positionId().isBlank() || actual.createdAt()<=0 || actual.createdAt()>System.currentTimeMillis()+5000 || actual.contracts().compareTo(m.contracts)!=0 ||
                            m.latestTradeId.isBlank() || !m.latestTradeId.equals(actual.tradeId()))
                        throw new IllegalStateException("新成交仓位身份或数量无法核实");
                    m.positionId=actual.positionId();m.positionCreatedAt=actual.createdAt();changed=true;
                }
                if(actual.contracts().compareTo(m.contracts)!=0 || !actual.positionId().equals(m.positionId) || actual.createdAt()!=m.positionCreatedAt ||
                        !m.latestTradeId.equals(actual.tradeId()))
                    throw new IllegalStateException("策略仓位出现外部变更，停止自动操作");
            } else {
                JsonNode match=null;
                for(JsonNode h:okx.positionHistory(instrument)) {
                    boolean identity=m.positionId.isBlank()?h.path("cTime").asLong()>=m.submittedAt-5000:
                            h.path("posId").asText().equals(m.positionId) && h.path("cTime").asLong()==m.positionCreatedAt;
                    if(identity && h.path("instId").asText().equals(instrument) && Set.of("2","3","6").contains(h.path("type").asText()) &&
                            h.path("uTime").asLong()>=m.submittedAt && OkxClient.number(h,"closeTotalPos").compareTo(m.openedContracts)==0) {
                        if(match!=null) throw new IllegalStateException("平仓历史匹配不唯一");match=h;
                    }
                }
                // Exchange stop/take-profit can remove the position before history is visible. Keep the key so new risk stays blocked, and retry next poll.
                if(match==null) continue;
                closed.add(new Closed(key,instrument,m,match));
            }
        }
        closed.sort(Comparator.comparingLong(c->c.history().path("uTime").asLong()));
        for(Closed c:closed) {
                String instrument=c.instrument();RiskState.Managed m=c.managed();
                BigDecimal pnl=OkxClient.number(c.history(),"realizedPnl");
                boolean loss=pnl.signum()<0;
                s.consecutiveLosses=loss?s.consecutiveLosses+1:0;
                if(loss) s.lossNotionalCap=s.lossNotionalCap==null?m.notional:s.lossNotionalCap.min(m.notional);
                else if(pnl.signum()>0) s.lossNotionalCap=null;
                // Every close receives the longer cooldown, including profitable trailing-stop exits.
                s.cooldownUntil.put(instrument,System.currentTimeMillis()+store.riskPolicy().lossCooldownSeconds()*1000L);
                if(s.consecutiveLosses>=store.riskPolicy().maxConsecutiveLosses() && s.haltReason.isBlank()) s.haltReason="触发连续亏损上限";
                s.positions.remove(c.key());changed=true;
        }
        if(changed) store.riskState(s);
    }
}
