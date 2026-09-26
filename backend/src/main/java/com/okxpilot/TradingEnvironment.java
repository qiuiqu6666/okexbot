package com.okxpilot;

public enum TradingEnvironment {
    DEMO, LIVE;
    public static TradingEnvironment parse(String value) {
        if(value==null || value.isBlank()) return DEMO;
        try{return valueOf(value);}catch(IllegalArgumentException e){throw new IllegalArgumentException("交易环境必须是 DEMO 或 LIVE");}
    }
    public String tableSql(String sql){return this==LIVE?sql.replaceAll("\\buser_(bot_config|order_intent|audit_event|equity_baseline|execution_lease|connection)\\b","live_user_$1"):sql;}
}
