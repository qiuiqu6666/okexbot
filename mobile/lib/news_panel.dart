import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';
import 'api.dart';

class NewsPanel extends StatefulWidget {
  final PilotApi api;
  const NewsPanel({super.key, required this.api});
  @override
  State<NewsPanel> createState() => _NewsPanelState();
}

class _NewsPanelState extends State<NewsPanel> {
  Map<String, dynamic>? data;
  bool loading = false;
  String? error;
  Timer? timer;
  @override
  void initState() {
    super.initState();
    load();
    timer = Timer.periodic(const Duration(minutes: 5), (_) => load());
  }

  @override
  void dispose() {
    timer?.cancel();
    super.dispose();
  }

  Future<void> load() async {
    if (loading) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final value = await widget.api.request('/news');
      if (value is! Map<String, dynamic> || value['articles'] is! List) {
        throw ApiError('资讯接口尚未就绪，请升级后端');
      }
      if (mounted) setState(() => data = value);
    } catch (e) {
      if (mounted) setState(() => error = '$e');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final sources = (data?['sources'] as List? ?? [])
        .cast<Map<String, dynamic>>();
    final articles = (data?['articles'] as List? ?? [])
        .cast<Map<String, dynamic>>();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Expanded(
              child: Text(
                '市场资讯',
                style: TextStyle(fontSize: 26, fontWeight: FontWeight.w600),
              ),
            ),
            IconButton(
              tooltip: '刷新资讯',
              onPressed: loading ? null : load,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        const Text(
          '所选币种与市场事件 · 最近 48 小时',
          style: TextStyle(color: Color(0xFF98A6AC)),
        ),
        const SizedBox(height: 12),
        const Text(
          '以下为来源站点的原标题与短摘要，不代表事实已获独立证实。AI 结合行情分析并保留引用；资讯不足时禁止新开仓。',
          style: TextStyle(height: 1.5, color: Color(0xFF98A6AC)),
        ),
        if (loading)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 16),
            child: LinearProgressIndicator(),
          ),
        if (error != null)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text(
              error!,
              style: const TextStyle(color: Colors.orangeAccent),
            ),
          ),
        if (data != null) ...[
          const SizedBox(height: 12),
          Text(
            '${data!['message']}',
            style: TextStyle(
              color: data!['usable'] == true
                  ? const Color(0xFF9EEBC5)
                  : Colors.orangeAccent,
            ),
          ),
          Text(
            '检查时间：${newsTime(data!['checkedAt'])}',
            style: const TextStyle(fontSize: 12, color: Color(0xFF98A6AC)),
          ),
          const SizedBox(height: 8),
          for (final source in sources)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Text(
                '${source['source']}${source['official'] == true ? ' · 官方' : ''} · ${source['status'] == 'OK' ? '连接正常' : '获取失败'}${source['lastSuccess'] == null ? '' : ' · 上次成功 ${newsTime(source['lastSuccess'])}'}',
                style: TextStyle(
                  fontSize: 12,
                  color: source['status'] == 'OK'
                      ? const Color(0xFF98A6AC)
                      : Colors.orangeAccent,
                ),
              ),
            ),
          const SizedBox(height: 14),
          if (articles.isEmpty)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Text('暂无近期相关资讯。不会生成虚构新闻，请稍后刷新。'),
            ),
          for (final article in articles)
            NewsArticle(
              article: article,
              available:
                  error == null &&
                  sources.any(
                    (s) =>
                        s['source'] == article['source'] && s['status'] == 'OK',
                  ),
            ),
        ],
      ],
    );
  }
}

String newsTime(dynamic value) {
  final time = DateTime.tryParse('$value')?.toLocal();
  return time == null
      ? '—'
      : '${time.month}/${time.day} ${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}';
}

class NewsArticle extends StatelessWidget {
  final Map<String, dynamic> article;
  final bool available;
  const NewsArticle({super.key, required this.article, this.available = true});
  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 12),
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '${article['source']} · ${newsTime(article['publishedAt'])} · ${{'MACRO': '宏观', 'REGULATION': '监管', 'SECURITY': '安全', 'ASSET': '币种'}[article['category']] ?? '市场'}',
            style: const TextStyle(color: Color(0xFF98A6AC), fontSize: 12),
          ),
          const SizedBox(height: 8),
          Text(
            '${article['title']}',
            style: const TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              height: 1.4,
            ),
          ),
          if ('${article['excerpt'] ?? ''}'.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              '${article['excerpt']}',
              style: const TextStyle(color: Color(0xFF98A6AC), height: 1.5),
            ),
          ],
          const SizedBox(height: 8),
          Text(
            article['marketWide'] == true
                ? '市场整体事件'
                : (article['symbols'] as List? ?? []).join(' / '),
            style: const TextStyle(color: Color(0xFF9EEBC5), fontSize: 12),
          ),
          Text(
            '采集：${newsTime(article['fetchedAt'])} · ${article['id']}',
            style: const TextStyle(fontSize: 11, color: Color(0xFF98A6AC)),
          ),
          if (!available)
            const Text(
              '来源不可用，缓存仅供阅读',
              style: TextStyle(color: Colors.orangeAccent, fontSize: 12),
            ),
          TextButton.icon(
            icon: const Icon(Icons.open_in_new, size: 16),
            label: const Text('查看原文'),
            onPressed: () async {
              final uri = Uri.tryParse('${article['url']}');
              try {
                if (uri == null ||
                    uri.scheme != 'https' ||
                    uri.host.isEmpty ||
                    !await launchUrl(
                      uri,
                      mode: LaunchMode.externalApplication,
                    )) {
                  throw Exception();
                }
              } catch (_) {
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(content: Text('无法打开原文链接，请检查浏览器或网络。')),
                  );
                }
              }
            },
          ),
        ],
      ),
    ),
  );
}

class NewsEvidence extends StatelessWidget {
  final dynamic payload;
  const NewsEvidence({super.key, required this.payload});
  @override
  Widget build(BuildContext context) {
    try {
      final parsed = payload is String ? jsonDecode(payload) : payload;
      if (parsed is! Map) return const SizedBox.shrink();
      final evidence =
          parsed['news'] ?? (parsed.containsKey('articles') ? parsed : null);
      if (evidence is! Map) return const SizedBox.shrink();
      final reason = parsed['decision'] is Map
          ? '${parsed['decision']['reason']}'
          : null;
      final articles = (evidence['articles'] as List? ?? [])
          .whereType<Map<String, dynamic>>()
          .where((a) => reason == null || reason.contains('[${a['id']}]'))
          .toList();
      return ExpansionTile(
        tilePadding: EdgeInsets.zero,
        title: Text(
          reason == null
              ? '本轮资讯快照（${articles.length}）'
              : 'AI 引用资讯（${articles.length}）',
          style: const TextStyle(fontSize: 13),
        ),
        children: [
          Text(
            '${evidence['message'] ?? ''}',
            style: const TextStyle(fontSize: 12, color: Color(0xFF98A6AC)),
          ),
          for (final article in articles) NewsArticle(article: article),
        ],
      );
    } catch (_) {
      return const SizedBox.shrink();
    }
  }
}
