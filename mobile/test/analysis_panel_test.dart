import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:okx_pilot/analysis_panel.dart';
import 'package:okx_pilot/main.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  testWidgets(
    'dedicated tab generates analysis inline without a result dialog',
    (tester) async {
      var generated = false;
      final status = {
        'enabled': false,
        'model': 'test-model',
        'settings': {
          'instruments': ['BTC-USDT-SWAP'],
          'intervalSeconds': 300,
        },
      };
      final decision = {
        'action': 'HOLD',
        'instrument': 'BTC-USDT-SWAP',
        'reason': '等待更明确的信号',
      };
      final api = PilotApi(
        'http://localhost:8080',
        'test-token',
        MockClient((request) async {
          Object data = [];
          if (request.url.path == '/api/status') data = status;
          if (request.url.path == '/api/connections') {
            data = <String, dynamic>{};
          }
          if (request.url.path == '/api/preview') {
            generated = true;
            data = decision;
          }
          if (request.url.path == '/api/analysis' && generated) {
            data = [
              {
                'kind': 'PREVIEW',
                'payload': {'decision': decision},
              },
            ];
          }
          return http.Response(
            jsonEncode(data),
            200,
            headers: {'content-type': 'application/json; charset=utf-8'},
          );
        }),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Dashboard(api: api, initialStatus: status),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('AI 分析'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('生成一次分析 · 不下单'));
      await tester.pumpAndSettle();
      expect(generated, isTrue);
      expect(find.text('等待更明确的信号'), findsOneWidget);
      expect(find.byType(AlertDialog), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
  testWidgets(
    'shows full persisted explanation and distinguishes suggestion from execution',
    (tester) async {
      final explanation = List.filled(45, '行情依据与风险说明。').join();
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: AnalysisPanel(
                model: 'test-model',
                environment: '模拟盘',
                analyzing: false,
                records: [
                  {
                    'kind': 'PREVIEW',
                    'summary': '截断的摘要',
                    'created_at': '2026-09-26T12:00:00Z',
                    'payload': jsonEncode({
                      'decision': {
                        'action': 'OPEN_LONG',
                        'instrument': 'BTC-USDT-SWAP',
                        'reason': explanation,
                        'notionalUsdt': 100,
                        'stopLoss': 59000,
                      },
                      'plan': {'notionalUsdt': 96, 'reduceOnly': false},
                    }),
                  },
                ],
              ),
            ),
          ),
        ),
      );
      expect(find.text(explanation), findsOneWidget);
      expect(find.textContaining('仅分析 · 未下单'), findsOneWidget);
      expect(find.text('模型建议敞口：100 USDT'), findsOneWidget);
      expect(find.text('风控计算敞口：96 USDT'), findsOneWidget);
      expect(find.text('截断的摘要'), findsNothing);
    },
  );
  testWidgets(
    'rejected suggestion shows server reason and malformed history is safe',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: AnalysisPanel(
                model: '',
                environment: '实盘',
                analyzing: true,
                records: [
                  {
                    'kind': 'AI_REJECTED',
                    'payload': {
                      'decision': {'action': 'OPEN_LONG', 'reason': '模型建议开仓'},
                      'reason': '超过风险上限',
                    },
                  },
                  {
                    'kind': 'AI_SKIPPED',
                    'payload': 'invalid json',
                    'summary': '接口超时',
                  },
                ],
              ),
            ),
          ),
        ),
      );
      expect(find.text('模型建议开仓'), findsOneWidget);
      expect(find.text('超过风险上限'), findsOneWidget);
      expect(find.textContaining('已拦截'), findsOneWidget);
      expect(find.byType(LinearProgressIndicator), findsOneWidget);
      expect(tester.takeException(), isNull);
    },
  );
}
