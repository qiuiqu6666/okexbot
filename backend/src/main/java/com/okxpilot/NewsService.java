package com.okxpilot;

import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static com.okxpilot.Domain.*;

/** Public publisher feeds only. News text is untrusted evidence, never instructions. */
@Service
public class NewsService {
    public record Source(String name,String url,String host,boolean official) {}
    public record Article(String id,String source,String url,String title,String excerpt,Instant publishedAt,Instant fetchedAt,String category,List<String> symbols,boolean marketWide) {}
    public record SourceStatus(String source,String url,boolean official,String status,Instant lastSuccess,String message) {}
    public record Evidence(Instant checkedAt,boolean usable,String message,List<SourceStatus> sources,List<Article> articles) {}
    private record Feed(List<Article> articles,SourceStatus status) {}
    interface Fetcher {byte[] get(Source source) throws Exception;}
    static final List<Source> SOURCES=List.of(
        new Source("CoinDesk","https://www.coindesk.com/arc/outboundfeeds/rss","coindesk.com",false),
        new Source("Cointelegraph","https://cointelegraph.com/rss","cointelegraph.com",false),
        new Source("Federal Reserve","https://www.federalreserve.gov/feeds/press_monetary.xml","federalreserve.gov",true));
    private static final Duration MAX_AGE=Duration.ofHours(48),MAX_FETCH_AGE=Duration.ofMinutes(15);
    private static final Map<String,List<String>> ALIASES=Map.ofEntries(
        Map.entry("BTC",List.of("bitcoin","btc")),Map.entry("ETH",List.of("ethereum","ether","eth")),
        Map.entry("SOL",List.of("solana","sol")),Map.entry("XRP",List.of("ripple","xrp")),
        Map.entry("DOGE",List.of("dogecoin","doge")),Map.entry("ADA",List.of("cardano","ada")),
        Map.entry("BNB",List.of("bnb")),Map.entry("AVAX",List.of("avalanche","avax")),
        Map.entry("LINK",List.of("chainlink")),Map.entry("DOT",List.of("polkadot","dot")));
    private final Clock clock;
    private final Fetcher fetcher;
    private final List<Source> sources;
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private volatile List<Feed> cached=List.of();
    private volatile Instant nextRefresh=Instant.EPOCH;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
    public NewsService(){clock=Clock.systemUTC();sources=SOURCES;fetcher=this::download;}
    NewsService(Clock clock,List<Source> sources,Fetcher fetcher){this.clock=clock;this.sources=sources;this.fetcher=fetcher;}
    private byte[] download(Source source) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(source.url())).timeout(Duration.ofSeconds(8))
            .header("User-Agent","OKX-Pilot/1.0 RSS reader").header("Accept","application/rss+xml, application/xml, text/xml").GET().build();
        var response=http.send(request,info->new LimitedBody());
        if(response.statusCode()!=200) throw new IOException("HTTP "+response.statusCode());
        return response.body();
    }
    private synchronized void refreshIfDue() {
        Instant now=clock.instant();if(now.isBefore(nextRefresh)) return;
        List<Feed> previous=cached;
        var tasks=sources.stream().map(source->CompletableFuture.supplyAsync(()->{
            try {
                var items=parse(source,fetcher.get(source),clock.instant());
                return new Feed(items,new SourceStatus(source.name(),source.url(),source.official(),"OK",clock.instant(),"来源连接正常；不代表报道内容已被独立证实"));
            } catch(Exception e) {
                Feed old=previous.stream().filter(f->f.status().source().equals(source.name())).findFirst().orElse(null);
                return new Feed(old==null?List.of():old.articles(),new SourceStatus(source.name(),source.url(),source.official(),"UNAVAILABLE",old==null?null:old.status().lastSuccess(),failureMessage(e)));
            }
        },workers)).toList();
        cached=tasks.stream().map(CompletableFuture::join).toList();nextRefresh=clock.instant().plusSeconds(300);
    }
    private static String failureMessage(Exception e) {
        String reason=e.getMessage()!=null && e.getMessage().matches("HTTP [0-9]{3}")?e.getMessage():
            e instanceof java.net.http.HttpTimeoutException?"连接超时":"网络或订阅格式异常";
        return reason+"；旧缓存仅展示，不作为新开仓依据";
    }
    public Evidence evidence(List<String> instruments) {
        refreshIfDue();Instant now=clock.instant();var feeds=cached;
        List<String> symbols=instruments.stream().map(id->id.split("-")[0]).toList();
        var unique=new LinkedHashMap<String,Article>();
        var titles=new HashSet<String>();
        feeds.stream().flatMap(f->f.articles().stream()).filter(a->fresh(a,now))
            .map(a->tag(a,symbols)).filter(a->a.marketWide() || !a.symbols().isEmpty())
            .sorted(Comparator.comparing(Article::publishedAt).reversed()).forEach(a->{String title=a.title().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");if(!unique.containsKey(a.url()) && titles.add(title)) unique.put(a.url(),a);});
        List<Article> articles=unique.values().stream().limit(20).toList();
        List<SourceStatus> statuses=feeds.stream().map(Feed::status).toList();
        boolean usable=articles.stream().anyMatch(a->sourceOk(a,statuses) && freshFetch(a,now));
        String message=usable?"已取得近期相关资讯；媒体报道不等于事实确认，AI 须结合行情并引用来源":"没有可用于新开仓的近期相关资讯；本轮不允许 AI 新开仓，已有仓位仍可减仓和平仓";
        return new Evidence(now,usable,message,statuses,articles);
    }
    public void validate(Decision decision,Evidence evidence) {
        boolean opening=decision.action()==Action.OPEN_LONG || decision.action()==Action.OPEN_SHORT;
        // Reject fabricated bracketed citations for any action, including HOLD.
        var refs=Pattern.compile("\\[(N[0-9a-f]{16})\\]").matcher(Objects.toString(decision.reason(),""));
        Set<String> cited=new HashSet<>();while(refs.find()) cited.add(refs.group(1));
        Set<String> known=new HashSet<>();evidence.articles().forEach(a->known.add(a.id()));
        if(!known.containsAll(cited)) throw new IllegalStateException("AI 引用了不存在的资讯，本轮不执行");
        if(!opening) return;
        Instant now=clock.instant();String symbol=decision.instrument()==null?"":decision.instrument().split("-")[0];
        boolean supported=evidence.usable() && evidence.articles().stream().anyMatch(a->cited.contains(a.id()) &&
            (a.marketWide() || a.symbols().contains(symbol)) && fresh(a,now) && freshFetch(a,now) && sourceOk(a,evidence.sources()));
        if(!supported) throw new IllegalStateException("缺少新鲜、相关且可追溯的资讯引用，本轮禁止新开仓");
    }
    private static boolean sourceOk(Article a,List<SourceStatus> statuses){return statuses.stream().anyMatch(s->s.source().equals(a.source()) && s.status().equals("OK"));}
    static boolean fresh(Article a,Instant now){return !a.publishedAt().isAfter(now.plusSeconds(300)) && !a.publishedAt().isBefore(now.minus(MAX_AGE));}
    static boolean freshFetch(Article a,Instant now){return !a.fetchedAt().isAfter(now.plusSeconds(300)) && !a.fetchedAt().isBefore(now.minus(MAX_FETCH_AGE));}
    private static Article tag(Article a,List<String> wanted) {
        String text=a.title()+" "+a.excerpt();
        List<String> matched=wanted.stream().filter(s->{
            List<String> names=ALIASES.get(s);
            if(names!=null) return names.stream().anyMatch(n->Pattern.compile("(?i)(?<![a-z0-9])"+Pattern.quote(n)+"(?![a-z0-9])").matcher(text).find());
            return Pattern.compile("(?<![A-Za-z0-9])"+Pattern.quote(s)+"(?![A-Za-z0-9])").matcher(text).find();
        }).toList();
        return new Article(a.id(),a.source(),a.url(),a.title(),a.excerpt(),a.publishedAt(),a.fetchedAt(),a.category(),matched,a.marketWide());
    }
    static List<Article> parse(Source source,byte[] bytes,Instant fetched) throws Exception {
        if(bytes.length>1048576) throw new IOException("Feed exceeds limit");
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        Document document=builder.parse(new ByteArrayInputStream(bytes));
        if(!document.getDocumentElement().getNodeName().equals("rss")) throw new IOException("Not an RSS feed");
        var nodes=document.getElementsByTagName("item");var articles=new ArrayList<Article>();
        for(int i=0;i<Math.min(nodes.getLength(),200);i++) try {
            Element item=(Element)nodes.item(i);String title=clean(value(item,"title"),240),excerpt=clean(value(item,"description"),320);
            String text=(title+" "+excerpt).toLowerCase(Locale.ROOT);
            if(title.isBlank() || text.matches("(?s).*(sponsored|press release|price prediction|how to buy|partner content|advertorial).*") ) continue;
            URI uri=URI.create(value(item,"link").trim());String host=uri.getHost();
            if(!"https".equals(uri.getScheme()) || host==null || !(host.equals(source.host()) || host.endsWith("."+source.host())) || uri.getUserInfo()!=null) continue;
            String url=new URI("https",null,host,uri.getPort(),uri.getPath(),null,null).toString();
            if(url.length()>512) continue;
            Instant published=date(value(item,"pubDate"));
            String category=text.matches("(?s).*(interest rate|federal reserve|inflation|monetary policy|fomc|treasury yield).*")?"MACRO":
                text.matches("(?s).*(regulat|sec |securities and exchange|legislation|crypto law).*")?"REGULATION":
                text.matches("(?s).*(hack|exploit|breach|insolven|withdrawal halt).*")?"SECURITY":"ASSET";
            boolean marketWide=category.equals("MACRO") || (category.equals("REGULATION") && text.matches("(?s).*(crypto|digital asset|stablecoin|bitcoin|ethereum).*")) || text.matches("(?s).*(crypto market|market-wide|exchange hack|exchange insolvency).*");
            var a=new Article("N"+AuthService.hash(source.name()+"|"+url).substring(0,16),source.name(),url,title,excerpt,published,fetched,category,List.of(),marketWide);
            if(fresh(a,fetched)) articles.add(a);
        }catch(Exception ignored){/* Missing dates and malformed or off-domain links are not evidence. */}
        return List.copyOf(articles);
    }
    private static String value(Element node,String name){var list=node.getElementsByTagName(name);return list.getLength()==0?"":list.item(0).getTextContent();}
    static Instant date(String text){try{return ZonedDateTime.parse(text.trim(),DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();}catch(Exception e){return Instant.parse(text.trim());}}
    static String clean(String text,int max){String value=text.replaceAll("(?is)<(script|style)\\b[^>]*>.*?</\\1>"," ").replaceAll("<[^>]*>"," ").replace("&nbsp;"," ").replace("&amp;","&").replace("&quot;","\"").replaceAll("[\\p{Cc}\\p{Cf}]"," ").replaceAll("\\s+"," ").trim();return value.substring(0,Math.min(max,value.length()));}
    @PreDestroy public void close(){workers.shutdownNow();}
    private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(var b:buffers){if(bytes.size()+b.remaining()>1048576){subscription.cancel();result.completeExceptionally(new IOException("Feed exceeds limit"));return;}byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}subscription.request(1);}
        public void onError(Throwable t){result.completeExceptionally(t);}public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
