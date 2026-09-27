package com.okxpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds higher-timeframe candles from newer-first OKX rows. OKX has no native 10m or 3H bar. */
final class CandleBars {
    private CandleBars() {}
    static ArrayNode aggregate(JsonNode newestFirst,long bucketMs,long sourceMs) {
        ArrayNode out=JsonNodeFactory.instance.arrayNode();
        if(newestFirst==null || !newestFirst.isArray() || bucketMs<=0 || sourceMs<=0 || bucketMs%sourceMs!=0) return out;
        int need=(int)(bucketMs/sourceMs);
        Map<Long,List<JsonNode>> groups=new LinkedHashMap<>();
        for(JsonNode row:newestFirst) {
            if(row==null || row.size()<6) continue;
            long ts=row.path(0).asLong();
            if(ts<=0) continue;
            long bucket=ts-Math.floorMod(ts,bucketMs);
            groups.computeIfAbsent(bucket,key->new ArrayList<>()).add(row);
        }
        boolean newest=true;
        for(var entry:groups.entrySet()) {
            List<JsonNode> parts=entry.getValue();
            parts.sort(Comparator.comparingLong(row->row.get(0).asLong()));
            if(parts.size()<need && !newest) continue;
            if(parts.isEmpty()) continue;
            newest=false;
            out.add(merge(entry.getKey(),parts));
        }
        return out;
    }
    private static ArrayNode merge(long bucket,List<JsonNode> parts) {
        BigDecimal high=null,low=null,volume=BigDecimal.ZERO;
        for(JsonNode row:parts) {
            BigDecimal h=new BigDecimal(row.get(2).asText()),l=new BigDecimal(row.get(3).asText()),v=new BigDecimal(row.get(5).asText());
            high=high==null?h:high.max(h);
            low=low==null?l:low.min(l);
            volume=volume.add(v);
        }
        JsonNode first=parts.get(0),last=parts.get(parts.size()-1);
        ArrayNode candle=JsonNodeFactory.instance.arrayNode();
        candle.add(Long.toString(bucket));
        candle.add(first.get(1).asText());
        candle.add(high.toPlainString());
        candle.add(low.toPlainString());
        candle.add(last.get(4).asText());
        candle.add(volume.stripTrailingZeros().toPlainString());
        return candle;
    }
}
