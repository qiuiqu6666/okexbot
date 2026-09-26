package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.concurrent.*;

@Service
public class TradingRegistry {
    private final Store stores;
    private final ConnectionService connections;
    private final ObjectMapper json;
    private final RiskEngine risk;
    private final NewsService news;
    private final ConcurrentMap<Account,TradingService> engines=new ConcurrentHashMap<>();
    private final Set<Account> running=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64));
    public TradingRegistry(Store stores,ConnectionService connections,ObjectMapper json,RiskEngine risk,NewsService news){this.stores=stores;this.connections=connections;this.json=json;this.risk=risk;this.news=news;}
    private record Account(long user,TradingEnvironment environment) {}
    public TradingService forUser(long user){return forUser(user,TradingEnvironment.DEMO);}
    public TradingService forUser(long user,TradingEnvironment env){return engines.computeIfAbsent(new Account(user,env),account->{
        var c=connections.forEnvironment(env).load(user);
        return new TradingService(stores.forUser(user,env),okx(c,env),ai(c),risk,json,news);
    });}
    public void configure(long user,ConnectionService.Update update){configure(user,TradingEnvironment.DEMO,update);}
    public void configure(long user,TradingEnvironment env,ConnectionService.Update update) {
        TradingService engine=forUser(user,env);
        synchronized(engine) {
            if(Boolean.TRUE.equals(engine.status().get("enabled"))) throw new IllegalStateException("请先暂停自动交易再修改连接配置");
            var service=connections.forEnvironment(env);
            service.save(user,update);var c=service.load(user);engine.reconfigure(okx(c,env),ai(c));
            stores.forUser(user,env).audit("CONNECTION","已更新个人连接配置",java.util.Map.of());
        }
    }
    private OkxClient okx(ConnectionService.Connection c,TradingEnvironment env){return new OkxClient(c.okxKey(),c.okxSecret(),c.okxPassphrase(),json,env);}
    private AiClient ai(ConnectionService.Connection c){return new AiClient(json,c.aiBaseUrl(),c.aiKey(),c.aiModel());}
    @Scheduled(fixedDelayString="${pilot.poll-ms}")
    public void tick() {
        for(var env:TradingEnvironment.values()) for(long user:connections.forEnvironment(env).configuredUsers()) {
            var account=new Account(user,env);
            if(!running.add(account)) continue;
            try {workers.execute(()->{try{forUser(user,env).tick();}finally{running.remove(account);}});}
            catch(RejectedExecutionException e){running.remove(account);}
        }
    }
    @PreDestroy public void stop(){workers.shutdownNow();}
}
