package com.okxpilot;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import static com.okxpilot.Domain.*;

@RestController
@RequestMapping("/api")
public class Api {
    private final TradingService trading;
    private final Store store;
    public Api(TradingService trading,Store store) {this.trading=trading;this.store=store;}
    @GetMapping("/health") public Map<String,Object> health() {return Map.of("status","UP","environment","DEMO");}
    @GetMapping("/status") public Object status(){return trading.status();}
    @PostMapping("/sync") public Object sync(){return trading.refresh();}
    @PutMapping("/settings") public Object settings(@RequestBody Settings s){trading.settings(s);return trading.status();}
    @PostMapping("/start") public Object start(){trading.enable();return trading.status();}
    @PostMapping("/pause") public Object pause(){trading.pause();return trading.status();}
    @PostMapping("/preview") public Decision preview(){return trading.preview();}
    @PostMapping("/reconcile") public Object reconcile(){trading.reconcileNow();return trading.status();}
    @PostMapping("/positions/{instrument}/close") public Object close(@PathVariable String instrument){trading.close(instrument,false);return Map.of("accepted",true);}
    @PostMapping("/positions/{instrument}/reduce") public Object reduce(@PathVariable String instrument){trading.close(instrument,true);return Map.of("accepted",true);}
    @GetMapping("/orders") public List<Map<String,Object>> orders(){return store.orders();}
    @GetMapping("/events") public List<Map<String,Object>> events(){return store.events();}
}

@Component
class OperatorAuth extends OncePerRequestFilter {
    private final byte[] token;
    OperatorAuth(@Value("${pilot.operator-token}") String token) {
        if(token.length()<32) throw new IllegalStateException("PILOT_OPERATOR_TOKEN 必须配置为至少 32 字符的随机令牌");
        this.token=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws IOException,ServletException {
        res.setHeader("Cache-Control","no-store");res.setHeader("X-Content-Type-Options","nosniff");
        if(!req.getRequestURI().equals("/api/health")) {
            String auth=req.getHeader("Authorization");
            if(auth==null || !MessageDigest.isEqual(token,auth.getBytes(StandardCharsets.UTF_8))) {
                res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"message\":\"访问令牌无效\"}");return;
            }
        }
        chain.doFilter(req,res);
    }
}

@RestControllerAdvice
class ApiErrors {
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
