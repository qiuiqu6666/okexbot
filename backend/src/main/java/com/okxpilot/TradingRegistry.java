package com.okxpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.concurrent.*;
import static com.okxpilot.Domain.Snapshot;

@Service
public class TradingRegistry {
    private final Store stores;
    private final ConnectionService connections;
    private final ObjectMapper json;
    private final RiskEngine risk;
    private final NewsService news;
    private final ConcurrentMap<Account,TradingService> engines=new ConcurrentHashMap<>();
    private final Set<Account> running=ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Long,SwitchPause> switchPause=new ConcurrentHashMap<>();
    private record SwitchPause(TradingEnvironment environment,long expiresAt) {}
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
    public void armSwitchRestore(long user,TradingEnvironment environment){switchPause.put(user,new SwitchPause(environment,System.currentTimeMillis()+3000));}
    public void disarmSwitchRestore(long user){switchPause.remove(user);}
    public void restoreIfSwitched(long user,TradingEnvironment requested){
        SwitchPause mark=switchPause.get(user);
        if(mark==null) return;
        if(System.currentTimeMillis()>mark.expiresAt()){switchPause.remove(user,mark);return;}
        if(requested==mark.environment()) return;
        if(switchPause.remove(user,mark)) {try{forUser(user,mark.environment()).keepRunning();}catch(RuntimeException ignored){}}
    }
    public String positionReport() {
        StringBuilder out = new StringBuilder();
        for (TradingEnvironment env : java.util.List.of(TradingEnvironment.LIVE, TradingEnvironment.DEMO)) {
            if (!out.isEmpty()) out.append("\n\n");
            java.util.List<Long> users = connections.forEnvironment(env).configuredUsers();
            boolean configured = false;
            Snapshot snapshot = null;
            RuntimeException failure = null;
            for (long user : users) {
                ConnectionService.Connection connection = connections.forEnvironment(env).load(user);
                if (!connection.okxConfigured()) continue;
                configured = true;
                try { snapshot = okx(connection, env).snapshot(); }
                catch (RuntimeException e) { failure = e; }
            }
            if (!configured) out.append(TelegramNotifier.positionsSection(env, null).replace("暂时读不到仓位", "未配置交易所账户"));
            else if (failure != null && snapshot == null) out.append(TelegramNotifier.positionsSection(env, null));
            else out.append(TelegramNotifier.positionsSection(env, snapshot));
        }
        return out.toString();
    }
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
