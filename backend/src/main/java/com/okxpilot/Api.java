package com.okxpilot;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.Map;
import static com.okxpilot.Domain.*;

@RestController
@RequestMapping("/api")
public class Api {
    private final TradingRegistry trading;
    private final Store stores;
    private final AuthService auth;
    private final ConnectionService connections;
    public Api(TradingRegistry trading,Store stores,AuthService auth,ConnectionService connections){this.trading=trading;this.stores=stores;this.auth=auth;this.connections=connections;}
    static AuthService.User user(HttpServletRequest r){return (AuthService.User)r.getAttribute("pilot.user");}
    private TradingEnvironment environment(HttpServletRequest r){return TradingEnvironment.parse(r.getHeader("X-Trading-Environment"));}
    private TradingService engine(HttpServletRequest r){return trading.forUser(user(r).id(),environment(r));}
    @GetMapping("/health") public Object health(){return Map.of("status","UP","environment","DEMO","auth","username-password","tradingEnvironments",java.util.List.of("DEMO","LIVE"));}
    @PostMapping("/auth/register") public Object register(@RequestBody AuthService.Credentials c,HttpServletRequest r){return auth.register(c,r.getRemoteAddr());}
    @PostMapping("/auth/login") public Object login(@RequestBody AuthService.Credentials c,HttpServletRequest r){return auth.login(c,r.getRemoteAddr());}
    @PostMapping("/auth/logout") public Object logout(HttpServletRequest r){auth.logout(r.getHeader("Authorization"));return Map.of("loggedOut",true);}
    @GetMapping("/auth/me") public Object me(HttpServletRequest r){return user(r);}
    @GetMapping("/status") public Object status(HttpServletRequest r){return engine(r).status();}
    @PostMapping("/sync") public Object sync(HttpServletRequest r){return engine(r).refresh();}
    @PutMapping("/settings") public Object settings(@RequestBody Settings s,HttpServletRequest r){engine(r).settings(s);return engine(r).status();}
    @GetMapping("/connections") public Object connections(HttpServletRequest r){return connections.forEnvironment(environment(r)).view(user(r).id());}
    @PutMapping("/connections") public Object connections(@RequestBody ConnectionService.Update u,HttpServletRequest r){trading.configure(user(r).id(),environment(r),u);return connections.forEnvironment(environment(r)).view(user(r).id());}
    @GetMapping("/news") public Object news(HttpServletRequest r){return engine(r).news();}
    @GetMapping("/instruments") public Object instruments(HttpServletRequest r){return engine(r).instruments();}
    @PostMapping("/start") public Object start(@RequestBody(required=false) StartRequest body,HttpServletRequest r){
        if(environment(r)==TradingEnvironment.LIVE && (body==null || !body.confirmLive())) throw new IllegalArgumentException("开启实盘自动交易必须明确确认真实资金交易");
        engine(r).enable();return engine(r).status();}
    public record StartRequest(boolean confirmLive) {}
    @PostMapping("/pause") public Object pause(HttpServletRequest r){engine(r).pause();return engine(r).status();}
    @PostMapping("/preview") public Decision preview(HttpServletRequest r){return engine(r).preview();}
    @PostMapping("/reconcile") public Object reconcile(HttpServletRequest r){engine(r).reconcileNow();return engine(r).status();}
    @PostMapping("/positions/{instrument}/close") public Object close(@PathVariable String instrument,HttpServletRequest r){engine(r).close(instrument,false);return Map.of("accepted",true);}
    @PostMapping("/positions/{instrument}/reduce") public Object reduce(@PathVariable String instrument,HttpServletRequest r){engine(r).close(instrument,true);return Map.of("accepted",true);}
    @GetMapping("/orders") public Object orders(HttpServletRequest r){return stores.forUser(user(r).id(),environment(r)).orders();}
    @GetMapping("/events") public Object events(HttpServletRequest r){return stores.forUser(user(r).id(),environment(r)).events();}
}

@Component
class SessionAuth extends OncePerRequestFilter {
    private final AuthService auth;
    SessionAuth(AuthService auth){this.auth=auth;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
        String path=req.getRequestURI();
        boolean anonymous=(req.getMethod().equals("GET") && path.equals("/api/health")) ||
                (req.getMethod().equals("POST") && (path.equals("/api/auth/login") || path.equals("/api/auth/register")));
        if(!anonymous) {
            try{req.setAttribute("pilot.user",auth.authenticate(req.getHeader("Authorization")));}
            catch(ResponseStatusException e){res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\"登录已失效，请重新登录\"}");return;}
        }
        chain.doFilter(req,res);
    }
}

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String,String>> auth(ResponseStatusException e){return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"请求失败":e.getReason()));}
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class,OkxClient.Rejected.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String,String> expected(Exception e){return Map.of("message",TradingService.safeMessage(e));}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String,String> badBody(){return Map.of("message","请求格式无效或存在未知字段");}
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    Map<String,String> unknown(){return Map.of("message","服务暂时无法处理请求，请检查服务端数据库与日志");}
}
