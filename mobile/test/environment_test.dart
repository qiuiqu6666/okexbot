import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:okx_pilot/main.dart';
import 'package:okx_pilot/instrument_picker.dart';

void main() {
  testWidgets(
    'coin picker searches checks caps selection and validates empty',
    (tester) async {
      final form = GlobalKey<FormState>();
      Set<String> saved = {'BTC-USDT-SWAP'};
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Form(
              key: form,
              child: InstrumentPicker(
                initial: saved,
                enabled: true,
                load: () async => [
                  'BTC',
                  'ETH',
                  'SOL',
                  'DOGE',
                  'XRP',
                  'ADA',
                ].map((s) => '$s-USDT-SWAP').toList(),
                onChanged: (value) => saved = value,
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('选择交易币种'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'ETH');
      await tester.pumpAndSettle();
      expect(find.byType(CheckboxListTile), findsOneWidget);
      await tester.tap(find.byType(CheckboxListTile));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), '');
      await tester.pumpAndSettle();
      for (final coin in ['ADA', 'DOGE', 'SOL']) {
        await tester.ensureVisible(find.widgetWithText(CheckboxListTile, coin));
        await tester.tap(find.widgetWithText(CheckboxListTile, coin));
        await tester.pumpAndSettle();
      }
      final xrp = tester.widget<CheckboxListTile>(
        find.widgetWithText(CheckboxListTile, 'XRP'),
      );
      expect(xrp.onChanged, isNull);
      await tester.tap(find.text('确定'));
      await tester.pumpAndSettle();
      expect(saved.length, 5);
      expect(saved, contains('ETH-USDT-SWAP'));
      expect(form.currentState!.validate(), isTrue);
      while (find.byType(InputChip).evaluate().isNotEmpty) {
        tester.widget<InputChip>(find.byType(InputChip).first).onDeleted!();
        await tester.pump();
      }
      expect(form.currentState!.validate(), isFalse);
      await tester.pump();
      expect(find.text('请选择 1–5 个交易币种'), findsOneWidget);
    },
  );
  testWidgets(
    'failed catalog request preserves selected instruments and allows retry',
    (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: InstrumentPicker(
              initial: {'BTC-USDT-SWAP'},
              enabled: true,
              load: () async => throw Exception('offline'),
              onChanged: (_) => fail('must retain selection'),
            ),
          ),
        ),
      );
      await tester.tap(find.text('选择交易币种'));
      await tester.pumpAndSettle();
      expect(find.byType(InputChip), findsOneWidget);
      expect(find.textContaining('币种列表加载失败'), findsOneWidget);
      expect(
        tester.widget<OutlinedButton>(find.byType(OutlinedButton)).onPressed,
        isNotNull,
      );
    },
  );
  testWidgets(
    'switch pauses demo and keeps live disabled until confirmed start',
    (tester) async {
      final calls = <http.Request>[];
      final settings = {
        'instruments': ['BTC-USDT-SWAP'],
        'maxOrderUsdt': 100,
        'maxExposureUsdt': 300,
        'maxDailyLossPct': 3,
        'maxPositions': 2,
        'leverage': 1,
        'intervalSeconds': 300,
      };
      Map<String, dynamic> status(String env) => {
        'environment': env,
        'enabled': false,
        'settings': settings,
        'model': '',
        'okxConfigured': false,
        'aiConfigured': false,
      };
      final api = PilotApi(
        'http://localhost',
        'test-token',
        MockClient((req) async {
          calls.add(req);
          final env = req.headers['X-Trading-Environment']!;
          Object data = [];
          if (req.url.path.endsWith('/status') ||
              req.url.path.endsWith('/pause') ||
              req.url.path.endsWith('/start')) {
            data = status(env);
          }
          if (req.url.path.endsWith('/connections')) {
            data = {
              'environment': env,
              'encryptionReady': true,
              'aiBaseUrl': 'https://nxaiapp.com/v1',
              'aiModel': 'gpt-6-luna',
            };
          }
          return http.Response(
            jsonEncode(data),
            200,
            headers: {'content-type': 'application/json'},
          );
        }),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Dashboard(api: api, initialStatus: status('DEMO')),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('设置'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('实盘'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(api.environment, 'DEMO');
      await tester.tap(find.text('实盘'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('确认'));
      await tester.pumpAndSettle();
      expect(api.environment, 'LIVE');
      expect(
        calls
            .where((r) => r.url.path.endsWith('/pause'))
            .single
            .headers['X-Trading-Environment'],
        'DEMO',
      );
      expect(calls.where((r) => r.url.path.endsWith('/start')), isEmpty);
      expect(find.textContaining('当前为实盘'), findsOneWidget);
      await tester.tap(find.text('总览'));
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.text('开启自动交易'));
      await tester.tap(find.text('开启自动交易'));
      await tester.pumpAndSettle();
      expect(find.textContaining('这会使用真实资金'), findsOneWidget);
      await tester.tap(find.text('确认'));
      await tester.pumpAndSettle();
      final start = calls.where((r) => r.url.path.endsWith('/start')).single;
      expect(start.headers['X-Trading-Environment'], 'LIVE');
      expect(jsonDecode(start.body), {'confirmLive': true});
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
