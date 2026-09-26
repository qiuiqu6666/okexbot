package com.okxpilot;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.okxpilot.Domain.*;
import static org.assertj.core.api.Assertions.*;

class NewsServiceTest {
    static final Instant NOW=Instant.parse("2026-09-26T14:00:00Z");
    static final NewsService.Source SOURCE=new NewsService.Source("Publisher","https://publisher.example/rss","publisher.example",false);
    static String item(String title,String link,String date){return "<item><title>"+title+"</title><link>"+link+"</link><pubDate>"+date+"</pubDate><description><![CDATA[<p>Market report.</p>]]></description></item>";}
    static byte[] feed(String items){return ("<rss><channel>"+items+"</channel></rss>").getBytes(StandardCharsets.UTF_8);}
    static Decision decision(Action action,String instrument,String reason){return new Decision(action,instrument,null,null,null,null,reason);}
    static class MutableClock extends Clock {
        Instant now=NOW;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}
    }
    @Test void parserRejectsOldFutureUndatedPromotionalAndForeignLinks() throws Exception {
        String xml=item("Bitcoin ETF approved","https://publisher.example/news?utm_source=test",NOW.minusSeconds(300).toString())+
            item("Bitcoin old","https://publisher.example/old",NOW.minusSeconds(49*3600).toString())+
            item("Bitcoin future","https://publisher.example/future",NOW.plusSeconds(900).toString())+
            item("Bitcoin sponsored","https://publisher.example/ad",NOW.toString())+
            item("Bitcoin missing date","https://publisher.example/missing","")+
            item("Bitcoin offsite","https://publisher.example.evil.test/news",NOW.toString());
        var articles=NewsService.parse(SOURCE,feed(xml),NOW);
        assertThat(articles).hasSize(1);assertThat(articles.get(0).url()).isEqualTo("https://publisher.example/news");
        assertThat(articles.get(0).excerpt()).isEqualTo("Market report.");
    }
    @Test void externalEntitiesAndNonFeedsAreRejected() {
        byte[] malicious="<!DOCTYPE rss [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss><channel>&xxe;</channel></rss>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(()->NewsService.parse(SOURCE,malicious,NOW));
        assertThatThrownBy(()->NewsService.parse(SOURCE,"<html>Access denied</html>".getBytes(StandardCharsets.UTF_8),NOW));
        assertThatThrownBy(()->NewsService.parse(SOURCE,new byte[1048577],NOW));
    }
    @Test void portfolioFilteringDeduplicationAndFiveMinuteCache() {
        var clock=new MutableClock();var calls=new AtomicInteger();
        byte[] data=feed(item("Bitcoin market event","https://publisher.example/btc",NOW.toString())+item("Bitcoin market event duplicate","https://publisher.example/btc?utm_source=other",NOW.toString())+item("Solana outage","https://publisher.example/sol",NOW.toString()));
        try(varCloser closer=new varCloser(new NewsService(clock,List.of(SOURCE),s->{calls.incrementAndGet();return data;}))) {
            var btc=closer.news.evidence(List.of("BTC-USDT-SWAP"));assertThat(btc.usable()).isTrue();assertThat(btc.articles()).hasSize(1);
            var sol=closer.news.evidence(List.of("SOL-USDT-SWAP"));assertThat(sol.articles()).hasSize(1);assertThat(sol.articles().get(0).symbols()).containsExactly("SOL");assertThat(calls.get()).isEqualTo(1);
        }
    }
    @Test void openingRequiresExistingRelevantFreshCitationButRiskReductionDoesNot() {
        var clock=new MutableClock();var service=new NewsService(clock,List.of(SOURCE),s->feed(item("Bitcoin event","https://publisher.example/btc",NOW.toString())));
        try {
            var evidence=service.evidence(List.of("BTC-USDT-SWAP","SOL-USDT-SWAP"));String ref="["+evidence.articles().get(0).id()+"]";
            service.validate(decision(Action.OPEN_LONG,"BTC-USDT-SWAP",ref),evidence);
            assertThatThrownBy(()->service.validate(decision(Action.OPEN_LONG,"BTC-USDT-SWAP","no source"),evidence)).hasMessageContaining("禁止新开仓");
            assertThatThrownBy(()->service.validate(decision(Action.OPEN_SHORT,"SOL-USDT-SWAP",ref),evidence)).hasMessageContaining("禁止新开仓");
            assertThatThrownBy(()->service.validate(decision(Action.HOLD,"BTC-USDT-SWAP","[N0000000000000000]"),evidence)).hasMessageContaining("不存在");
            clock.now=NOW.plusSeconds(901);
            assertThatThrownBy(()->service.validate(decision(Action.OPEN_LONG,"BTC-USDT-SWAP",ref),evidence)).hasMessageContaining("禁止新开仓");
            service.validate(decision(Action.CLOSE,"BTC-USDT-SWAP","risk reduction"),new NewsService.Evidence(clock.instant(),false,"unavailable",List.of(),List.of()));
        }finally{service.close();}
    }
    @Test void failedRefreshRetainsDisplayCacheButCannotSupportNewRisk() {
        var clock=new MutableClock();var calls=new AtomicInteger();
        var service=new NewsService(clock,List.of(SOURCE),s->{if(calls.incrementAndGet()>1)throw new java.io.IOException();return feed(item("Bitcoin event","https://publisher.example/btc",NOW.toString()));});
        try {
            var initial=service.evidence(List.of("BTC-USDT-SWAP"));assertThat(initial.usable()).isTrue();clock.now=NOW.plusSeconds(301);
            var stale=service.evidence(List.of("BTC-USDT-SWAP"));assertThat(stale.articles()).hasSize(1);assertThat(stale.usable()).isFalse();assertThat(stale.sources().get(0).status()).isEqualTo("UNAVAILABLE");
            assertThatThrownBy(()->service.validate(decision(Action.OPEN_LONG,"BTC-USDT-SWAP","["+stale.articles().get(0).id()+"]"),stale)).hasMessageContaining("禁止新开仓");
        }finally{service.close();}
    }
    @Test void officialMacroNewsAppliesAcrossSelectedCoins() {
        var service=new NewsService(Clock.fixed(NOW,ZoneOffset.UTC),List.of(SOURCE),s->feed(item("Federal Reserve interest rate decision","https://publisher.example/macro",NOW.toString())));
        try {var evidence=service.evidence(List.of("SOL-USDT-SWAP"));assertThat(evidence.usable()).isTrue();assertThat(evidence.articles().get(0).marketWide()).isTrue();service.validate(decision(Action.OPEN_LONG,"SOL-USDT-SWAP","["+evidence.articles().get(0).id()+"]"),evidence);}finally{service.close();}
    }
    private record varCloser(NewsService news) implements AutoCloseable {public void close(){news.close();}}
}
