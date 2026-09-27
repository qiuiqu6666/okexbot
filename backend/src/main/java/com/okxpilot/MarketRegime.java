package com.okxpilot;

import com.fasterxml.jackson.databind.JsonNode;

/** Local regime and setup filter. A blank strategy means do not call the model for a new entry. */
final class MarketRegime {
    record Assessment(String regime,String strategy,String direction,String summary) {
        boolean actionable() { return strategy!=null && !strategy.isBlank() && direction!=null && !direction.isBlank(); }
    }
    private MarketRegime() {}
    static Assessment scan(String instrument,JsonNode m5,JsonNode m15,JsonNode h1,JsonNode h3) {
        Series five=Series.of(m5),fifteen=Series.of(m15),hour=Series.of(h1),three=Series.of(h3);
        if(five==null || fifteen==null || hour==null || three==null) return null;
        String higher=trend(three);
        String mid=trend(hour);
        String regime;
        if("CHAOS".equals(higher) || "CHAOS".equals(mid) || hour.atrRatio()>0.03) regime="CHAOS";
        else if("BULLISH".equals(higher) && "BULLISH".equals(mid)) regime="BULLISH";
        else if("BEARISH".equals(higher) && "BEARISH".equals(mid)) regime="BEARISH";
        else if("RANGE".equals(higher) && "RANGE".equals(mid)) regime="RANGE";
        else regime="MIXED";
        String strategy="",direction="";
        if("BULLISH".equals(regime) && breakout(fifteen,five,true)) {strategy="TREND_BREAKOUT";direction="LONG";}
        else if("BEARISH".equals(regime) && breakout(fifteen,five,false)) {strategy="TREND_BREAKOUT";direction="SHORT";}
        else if("BULLISH".equals(regime) && pullback(fifteen,five,hour,true)) {strategy="TREND_PULLBACK";direction="LONG";}
        else if("BEARISH".equals(regime) && pullback(fifteen,five,hour,false)) {strategy="TREND_PULLBACK";direction="SHORT";}
        else if("RANGE".equals(regime) && reversal(fifteen,five,true)) {strategy="RANGE_REVERSAL";direction="LONG";}
        else if("RANGE".equals(regime) && reversal(fifteen,five,false)) {strategy="RANGE_REVERSAL";direction="SHORT";}
        String summary=instrument+" "+regime+(strategy.isBlank()?" 无进场结构":" "+strategy+" "+direction);
        return new Assessment(regime,strategy,direction,summary);
    }
    static boolean opposes(String regime,int positionSign) {
        return positionSign>0 && "BEARISH".equals(regime) || positionSign<0 && "BULLISH".equals(regime);
    }
    private static String trend(Series s) {
        double ema20=ema(s.close,20),ema50=ema(s.close,50),price=s.close[s.close.length-1];
        double gap=Math.abs(ema20-ema50)/price;
        double span=(max(s.high,s.high.length-20,s.high.length)-min(s.low,s.low.length-20,s.low.length))/price;
        if(s.atrRatio()>0.035) return "CHAOS";
        if(gap<0.004 && span<0.03) return "RANGE";
        if(ema20>ema50 && price>ema50) return "BULLISH";
        if(ema20<ema50 && price<ema50) return "BEARISH";
        return "MIXED";
    }
    private static boolean breakout(Series setup,Series trigger,boolean up) {
        int n=setup.close.length;
        double level=up?max(setup.high,n-21,n-1):min(setup.low,n-21,n-1);
        double close=setup.close[n-1];
        boolean crossed=up?close>level:close<level;
        boolean volume=setup.volume[n-1]>average(setup.volume,n-21,n-1)*1.3;
        int t=trigger.close.length;
        boolean confirmed=up?trigger.close[t-1]>trigger.high[t-2]:trigger.close[t-1]<trigger.low[t-2];
        return crossed && volume && confirmed;
    }
    private static boolean pullback(Series setup,Series trigger,Series hour,boolean up) {
        int n=setup.close.length;
        double ema20=ema(setup.close,20),close=setup.close[n-1],low=setup.low[n-1],high=setup.high[n-1];
        boolean touched=up?low<=ema20*1.004 && close>ema20:high>=ema20*0.996 && close<ema20;
        boolean quiet=setup.volume[n-1]<average(setup.volume,n-21,n-1);
        int t=trigger.close.length;
        boolean resumed=up?trigger.close[t-1]>trigger.high[t-2]:trigger.close[t-1]<trigger.low[t-2];
        double rsi=rsi(hour.close);
        boolean room=up?rsi>=45 && rsi<=72:rsi>=28 && rsi<=55;
        return touched && quiet && resumed && room;
    }
    private static boolean reversal(Series setup,Series trigger,boolean up) {
        double rsi=rsi(setup.close);
        int t=trigger.close.length;
        double ema9=ema(trigger.close,9);
        boolean extreme=up?rsi<35:rsi>65;
        boolean reclaimed=up?trigger.close[t-1]>ema9 && trigger.close[t-1]>trigger.close[t-2]
                :trigger.close[t-1]<ema9 && trigger.close[t-1]<trigger.close[t-2];
        return extreme && reclaimed;
    }
    private static double ema(double[] values,int period) {
        double k=2d/(period+1),ema=values[0];
        for(int i=1;i<values.length;i++) ema=values[i]*k+ema*(1-k);
        return ema;
    }
    private static double rsi(double[] values) {
        double gain=0,loss=0;
        int start=Math.max(1,values.length-14);
        for(int i=start;i<values.length;i++) {
            double diff=values[i]-values[i-1];
            if(diff>=0) gain+=diff; else loss-=diff;
        }
        if(gain==0 && loss==0) return 50;
        if(loss==0) return 100;
        double rs=gain/loss;
        return 100-100/(1+rs);
    }
    private static double average(double[] values,int from,int to) {
        double sum=0;int n=0;
        for(int i=Math.max(0,from);i<to;i++) {sum+=values[i];n++;}
        return n==0?0:sum/n;
    }
    private static double max(double[] values,int from,int to) {
        double v=Double.NEGATIVE_INFINITY;
        for(int i=Math.max(0,from);i<to;i++) v=Math.max(v,values[i]);
        return v;
    }
    private static double min(double[] values,int from,int to) {
        double v=Double.POSITIVE_INFINITY;
        for(int i=Math.max(0,from);i<to;i++) v=Math.min(v,values[i]);
        return v;
    }
    private static final class Series {
        final double[] close,high,low,volume;
        private Series(double[] close,double[] high,double[] low,double[] volume) {this.close=close;this.high=high;this.low=low;this.volume=volume;}
        double atrRatio() {
            double sum=0;int n=0;
            for(int i=Math.max(1,close.length-14);i<close.length;i++) {sum+=high[i]-low[i];n++;}
            return n==0?0:sum/n/close[close.length-1];
        }
        static Series of(JsonNode newestFirst) {
            if(newestFirst==null || !newestFirst.isArray() || newestFirst.size()<55) return null;
            int n=newestFirst.size();
            double[] close=new double[n],high=new double[n],low=new double[n],volume=new double[n];
            for(int i=0;i<n;i++) {
                JsonNode row=newestFirst.get(n-1-i);
                if(row==null || row.size()<6) return null;
                close[i]=row.get(4).asDouble();high[i]=row.get(2).asDouble();low[i]=row.get(3).asDouble();volume[i]=row.get(5).asDouble();
                if(!(close[i]>0) || !(high[i]>0) || !(low[i]>0)) return null;
            }
            return new Series(close,high,low,volume);
        }
    }
}
