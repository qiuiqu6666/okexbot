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
    private final ConcurrentMap<Long,TradingService> engines=new ConcurrentHashMap<>();
    private final Set<Long> running=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64));
    public TradingRegistry(Store stores,ConnectionService connections,ObjectMapper json,RiskEngine risk){this.stores=stores;this.connections=connections;this.json=json;this.risk=risk;}
    public TradingService forUser(long user){return engines.computeIfAbsent(user,id->{
        var c=connections.load(id);return new TradingService(stores.forUser(id),okx(c),ai(c),risk,json);
    });}
    public void configure(long user,ConnectionService.Update update) {
        TradingService engine=forUser(user);
        synchronized(engine) {
            if(Boolean.TRUE.equals(engine.status().get("enabled"))) throw new IllegalStateException("请先暂停自动交易再修改连接配置");
            connections.save(user,update);var c=connections.load(user);engine.reconfigure(okx(c),ai(c));
            stores.forUser(user).audit("CONNECTION","已更新个人连接配置",java.util.Map.of());
        }
    }
    private OkxClient okx(ConnectionService.Connection c){return new OkxClient(c.okxKey(),c.okxSecret(),c.okxPassphrase(),json);}
    private AiClient ai(ConnectionService.Connection c){return new AiClient(json,c.aiBaseUrl(),c.aiKey(),c.aiModel());}
    @Scheduled(fixedDelayString="${pilot.poll-ms}")
    public void tick() {
        for(long user:connections.configuredUsers()) {
            if(!running.add(user)) continue;
            try {workers.execute(()->{try{forUser(user).tick();}finally{running.remove(user);}});}
            catch(RejectedExecutionException e){running.remove(user);}
        }
    }
    @PreDestroy public void stop(){workers.shutdownNow();}
}
