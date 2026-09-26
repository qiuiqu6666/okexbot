import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:okx_pilot/api.dart';
import 'package:okx_pilot/news_panel.dart';

void main() {
  final article = {
    'id': 'N0123456789abcdef',
    'source': 'Publisher',
    'url': 'https://publisher.example/report',
    'title': 'Bitcoin market event',
    'excerpt': 'Short publisher excerpt',
    'publishedAt': '2026-09-26T13:00:00Z',
    'fetchedAt': '2026-09-26T13:01:00Z',
    'category': 'ASSET',
    'symbols': ['BTC'],
    'marketWide': false,
  };
  testWidgets('news displays sources timestamps headlines and original link', (
    tester,
  ) async {
    final api = PilotApi(
      'http://localhost',
      'token',
      MockClient(
        (r) async => http.Response(
          jsonEncode({
            'usable': true,
            'checkedAt': '2026-09-26T13:01:00Z',
            'message': 'Fresh evidence',
            'sources': [
              {
                'source': 'Publisher',
                'status': 'OK',
                'lastSuccess': '2026-09-26T13:01:00Z',
                'official': false,
              },
            ],
            'articles': [article],
          }),
          200,
          headers: {'content-type': 'application/json'},
        ),
      ),
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: NewsPanel(api: api)),
        ),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.text('Bitcoin market event'), findsOneWidget);
    expect(find.text('查看原文'), findsOneWidget);
    expect(find.textContaining('连接正常'), findsOneWidget);
    expect(find.textContaining('N0123456789abcdef'), findsOneWidget);
    await tester.pumpWidget(const SizedBox.shrink());
    api.close();
  });
  testWidgets(
    'unavailable source marks cached story and empty result does not invent headlines',
    (tester) async {
      var calls = 0;
      final api = PilotApi(
        'http://localhost',
        'token',
        MockClient(
          (r) async => http.Response(
            jsonEncode({
              'usable': false,
              'checkedAt': '2026-09-26T13:01:00Z',
              'message': 'No new positions',
              'sources': [
                {
                  'source': 'Publisher',
                  'status': 'UNAVAILABLE',
                  'official': false,
                },
              ],
              'articles': calls++ == 0 ? [article] : [],
            }),
            200,
            headers: {'content-type': 'application/json'},
          ),
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(child: NewsPanel(api: api)),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('来源不可用，缓存仅供阅读'), findsOneWidget);
      await tester.tap(find.byTooltip('刷新资讯'));
      await tester.pumpAndSettle();
      expect(find.textContaining('暂无近期相关资讯'), findsOneWidget);
      expect(find.text('Bitcoin market event'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      api.close();
    },
  );
  testWidgets('decision expands only articles cited by its reason', (
    tester,
  ) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: NewsEvidence(
              payload: jsonEncode({
                'decision': {'reason': 'Evidence [N0123456789abcdef]'},
                'news': {
                  'message': 'Audited snapshot',
                  'articles': [
                    article,
                    {
                      ...article,
                      'id': 'Nffffffffffffffff',
                      'title': 'Uncited report',
                    },
                  ],
                },
              }),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('AI 引用资讯（1）'));
    await tester.pumpAndSettle();
    expect(find.text('Bitcoin market event'), findsOneWidget);
    expect(find.text('Uncited report'), findsNothing);
  });
}
