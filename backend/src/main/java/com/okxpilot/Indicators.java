package com.okxpilot;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

final class Indicators {
    private Indicators() {}
    static Map<String,Object> from(JsonNode rows) {
        if(rows==null || !rows.isArray() || rows.size()<35) return Map.of("available",false);
        int n=rows.size();
        double[] close=new double[n];
        double[] volume=new double[n];
        for(int i=0;i<n;i++) {
            JsonNode row=rows.get(n-1-i);
            if(row==null || row.size()<6) return Map.of("available",false);
            try {
                close[i]=Double.parseDouble(row.get(4).asText());
                volume[i]=Double.parseDouble(row.get(5).asText());
            } catch(NumberFormatException e) { return Map.of("available",false); }
            if(!(close[i]>0) || volume[i]<0) return Map.of("available",false);
        }
        double[] ema12=ema(close,12), ema26=ema(close,26);
        double[] macd=new double[n-25];
        for(int i=25;i<n;i++) macd[i-25]=ema12[i]-ema26[i];
        double[] signal=ema(macd,9);
        int last=n-1;
        double volAvg=0;
        for(int i=n-20;i<n;i++) volAvg+=volume[i];
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("available",true);
        out.put("close",num(close[last]));
        out.put("ema12",num(ema12[last]));
        out.put("ema26",num(ema26[last]));
        out.put("macd",num(macd[macd.length-1]));
        out.put("macdSignal",num(signal[signal.length-1]));
        out.put("macdHistogram",num(macd[macd.length-1]-signal[signal.length-1]));
        out.put("rsi14",num(rsi(close)));
        out.put("volume",num(volume[last]));
        out.put("volumeAverage20",num(volAvg/20));
        return out;
    }
    private static double[] ema(double[] values,int period) {
        double[] out=new double[values.length];
        double seed=0;
        for(int i=0;i<period;i++) seed+=values[i];
        seed/=period;
        double k=2d/(period+1);
        out[period-1]=seed;
        for(int i=period;i<values.length;i++) out[i]=values[i]*k+out[i-1]*(1-k);
        return out;
    }
    private static double rsi(double[] close) {
        double gain=0,loss=0;
        for(int i=1;i<=14;i++) {
            double diff=close[i]-close[i-1];
            if(diff>=0) gain+=diff; else loss-=diff;
        }
        gain/=14; loss/=14;
        for(int i=15;i<close.length;i++) {
            double diff=close[i]-close[i-1];
            gain=(gain*13+(diff>0?diff:0))/14;
            loss=(loss*13+(diff<0?-diff:0))/14;
        }
        if(loss==0) return gain==0?50:100;
        return 100-100/(1+gain/loss);
    }
    private static BigDecimal num(double value) {
        return BigDecimal.valueOf(value).setScale(8,RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
