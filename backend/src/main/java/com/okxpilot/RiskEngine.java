package com.okxpilot;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static com.okxpilot.Domain.*;

@Component
public class RiskEngine {
    public Plan validate(Decision d, Settings s, Snapshot account, Instrument i, BigDecimal price, BigDecimal baseline) {
        return validate(d,s,account,i,price,baseline,RiskPolicy.defaults());
    }
    public Plan validate(Decision d, Settings s, Snapshot account, Instrument i, BigDecimal price, BigDecimal baseline,RiskPolicy policy) {
        return validate(d,s,account,i,price,baseline,policy,false);
    }
    public Plan validate(Decision d, Settings s, Snapshot account, Instrument i, BigDecimal price, BigDecimal baseline,RiskPolicy policy,boolean hedge) {
        policy.validate();
        s.validate();
        if(d==null || d.action()==null || d.instrument()==null || !s.instruments().contains(d.instrument()) || !i.id().equals(d.instrument()))
            throw new IllegalArgumentException("模型动作或合约不在允许范围");
        if(d.reason()==null || d.reason().isBlank() || d.reason().length()>1500) throw new IllegalArgumentException("决策理由缺失或过长");
        if(Duration.between(account.fetchedAt(),Instant.now()).abs().compareTo(Duration.ofSeconds(30))>0 || !positive(price))
            throw new IllegalArgumentException("账户或行情数据过期");
        List<Position> rows=account.positions().stream().filter(p->p.instrument().equals(d.instrument())).toList();
        if(rows.stream().anyMatch(p->!p.marginMode().equals("isolated"))) throw new IllegalArgumentException("第一版只管理逐仓持仓");
        Position position=rows.size()==1?rows.get(0):null;
        if(d.action()==Action.HOLD) return new Plan("",BigDecimal.ZERO,true,BigDecimal.ZERO);
        boolean opening=d.action()==Action.OPEN_LONG || d.action()==Action.OPEN_SHORT;
        if(opening) {
            boolean sameDirection=rows.stream().anyMatch(p->(p.contracts().signum()>0)==(d.action()==Action.OPEN_LONG));
            if(sameDirection || (!hedge && !rows.isEmpty())) throw new IllegalArgumentException(hedge?"已有同方向仓位，禁止加仓":"已有仓位时禁止重复开仓或自动反手");
            if(!positive(d.notionalUsdt()) || d.notionalUsdt().compareTo(s.maxOrderUsdt())>0) throw new IllegalArgumentException("单笔金额超限");
            BigDecimal exposure=account.positions().stream().map(Position::notionalUsdt).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(exposure.add(d.notionalUsdt()).compareTo(s.maxExposureUsdt())>0 || account.positions().size()>=s.maxPositions())
                throw new IllegalArgumentException("总敞口或持仓数量超限");
            if(!positive(baseline) || !positive(account.equity()) || baseline.subtract(account.equity()).multiply(new BigDecimal("100"))
                    .compareTo(baseline.multiply(s.maxDailyLossPct()))>=0) throw new IllegalArgumentException("触发 UTC 当日权益损失上限");
            // Reserve 10% available margin for fees and price movement.
            if(d.notionalUsdt().divide(BigDecimal.valueOf(s.leverage()),10,RoundingMode.UP)
                    .compareTo(account.available().multiply(new BigDecimal("0.9")))>0) throw new IllegalArgumentException("可用保证金不足");
            validateStops(d,price,i,d.action()==Action.OPEN_LONG);
            BigDecimal oneWayCosts=policy.takerFeeBps().add(policy.maxSlippageBps()).divide(new BigDecimal("10000"));
            BigDecimal lossPerUnit=price.subtract(d.stopLoss()).abs().add(price.add(price.max(d.stopLoss())).multiply(oneWayCosts));
            BigDecimal budget=account.equity().multiply(policy.maxTradeRiskPct()).divide(new BigDecimal("100"));
            BigDecimal riskSize=budget.divide(lossPerUnit.multiply(i.contractValue()),16,RoundingMode.DOWN);
            BigDecimal size=roundDown(d.notionalUsdt().divide(price.multiply(i.contractValue()),16,RoundingMode.DOWN).min(riskSize),i.lotSize());
            checkSize(size,i);
            BigDecimal notional=size.multiply(i.contractValue()).multiply(price);
            BigDecimal directional=account.positions().stream().filter(p->(p.contracts().signum()>0)==(d.action()==Action.OPEN_LONG))
                    .map(p->p.notionalUsdt().abs()).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(directional.add(notional).compareTo(policy.maxDirectionalExposureUsdt())>0)
                throw new IllegalArgumentException("同方向合计敞口超限");
            // Treat every supported crypto contract as one correlated group; no offsetting longs against shorts.
            if(exposure.add(notional).compareTo(policy.maxCorrelatedExposureUsdt())>0)
                throw new IllegalArgumentException("相关币种合计敞口超限");
            BigDecimal used=account.equity().subtract(account.available()).max(BigDecimal.ZERO);
            if(used.add(notional.divide(BigDecimal.valueOf(s.leverage()),10,RoundingMode.UP)).compareTo(
                    account.equity().multiply(policy.maxMarginPct()).divide(new BigDecimal("100")))>0)
                throw new IllegalArgumentException("账户保证金占用比例超限");
            return new Plan(d.action()==Action.OPEN_LONG?"buy":"sell",size,false,notional);
        }
        if(rows.size()>1) throw new IllegalArgumentException("该合约同时存在多空仓位，无法自动选择");
        if(position==null) throw new IllegalArgumentException("没有可管理的仓位");
        if(d.action()==Action.UPDATE_STOPS) {
            validateStops(d,price,i,position.contracts().signum()>0);
            return new Plan("",BigDecimal.ZERO,true,BigDecimal.ZERO);
        }
        BigDecimal fraction=d.action()==Action.CLOSE?BigDecimal.ONE:d.reduceFraction();
        if(!positive(fraction) || fraction.compareTo(BigDecimal.ONE)>0) throw new IllegalArgumentException("减仓比例须在 0–1 之间");
        BigDecimal size=roundDown(position.contracts().abs().multiply(fraction),i.lotSize());
        checkSize(size,i);
        return new Plan(position.contracts().signum()>0?"sell":"buy",size,true,size.multiply(i.contractValue()).multiply(price));
    }
    public void validateBook(com.fasterxml.jackson.databind.JsonNode book,Plan plan,BigDecimal reference,RiskPolicy policy) {
        if(book==null || !book.path("asks").isArray() || !book.path("bids").isArray() || book.path("asks").isEmpty() || book.path("bids").isEmpty())
            throw new IllegalArgumentException("盘口数据缺失");
        long age=System.currentTimeMillis()-book.path("ts").asLong();
        if(age< -5000 || age>5000) throw new IllegalArgumentException("盘口行情过期");
        BigDecimal ask=new BigDecimal(book.path("asks").get(0).get(0).asText()),bid=new BigDecimal(book.path("bids").get(0).get(0).asText());
        if(!positive(bid) || ask.compareTo(bid)<0 || ask.subtract(bid).multiply(new BigDecimal("10000"))
                .compareTo(bid.add(ask).divide(new BigDecimal("2")).multiply(policy.maxSpreadBps()))>0)
            throw new IllegalArgumentException("买卖价差超限或盘口无效");
        BigDecimal remaining=plan.contracts(),cost=BigDecimal.ZERO;
        for(var level:book.path(plan.side().equals("buy")?"asks":"bids")) {
            BigDecimal px=new BigDecimal(level.path(0).asText()),qty=new BigDecimal(level.path(1).asText());
            if(!positive(px) || qty.signum()<0) throw new IllegalArgumentException("盘口档位无效");
            BigDecimal take=remaining.min(qty);cost=cost.add(take.multiply(px));remaining=remaining.subtract(take);
            if(remaining.signum()==0) break;
        }
        if(remaining.signum()>0) throw new IllegalArgumentException("盘口深度不足");
        BigDecimal average=cost.divide(plan.contracts(),16,RoundingMode.HALF_UP);
        // Absolute displacement also rejects a stale reference even if the move appears favourable.
        if(average.subtract(reference).abs().multiply(new BigDecimal("10000")).compareTo(reference.multiply(policy.maxSlippageBps()))>0)
            throw new IllegalArgumentException("预计滑点超限");
    }
    public void validateStops(Decision d,BigDecimal price,Instrument i,boolean longSide) {
        if(!positive(d.takeProfit()) || !positive(d.stopLoss())) throw new IllegalArgumentException("必须同时提供止盈和止损");
        if(d.takeProfit().remainder(i.tickSize()).signum()!=0 || d.stopLoss().remainder(i.tickSize()).signum()!=0)
            throw new IllegalArgumentException("止盈止损不符合交易所价格精度");
        if(longSide ? d.stopLoss().compareTo(price)>=0 || d.takeProfit().compareTo(price)<=0 : d.stopLoss().compareTo(price)<=0 || d.takeProfit().compareTo(price)>=0)
            throw new IllegalArgumentException("止盈止损方向错误");
        if(d.stopLoss().subtract(price).abs().compareTo(price.multiply(new BigDecimal("0.05")))>0)
            throw new IllegalArgumentException("止损距离不得超过当前价格的 5%");
    }
    static BigDecimal roundDown(BigDecimal value,BigDecimal step) {
        if(!positive(step)) throw new IllegalArgumentException("数量精度无效");
        return value.divide(step,0,RoundingMode.DOWN).multiply(step);
    }
    static void checkSize(BigDecimal size,Instrument i) {
        if(!positive(size) || size.compareTo(i.minSize())<0 || size.compareTo(i.maxMarketSize())>0)
            throw new IllegalArgumentException("张数低于最小下单量或超过市价单上限");
    }
}
