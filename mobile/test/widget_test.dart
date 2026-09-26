import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:okx_pilot/main.dart';

void main() {
  testWidgets('connection validates token before connecting', (tester) async {
    await tester.pumpWidget(const PilotApp());
    expect(find.text('仓位领航'), findsOneWidget);
    await tester.tap(find.text('连接控制台'));
    await tester.pump();
    expect(find.textContaining('至少 32 字符'), findsOneWidget);
    expect(find.byType(TextField), findsNWidgets(2));
  });
  test(
    'API sends bearer token and does not retry an uncertain mutation',
    () async {
      var calls = 0;
      final api = PilotApi(
        'https://localhost',
        'operator-token',
        client: MockClient((req) async {
          calls++;
          expect(req.headers['Authorization'], 'Bearer operator-token');
          expect(req.method, 'POST');
          return http.Response(
            jsonEncode({'message': '结果待确认'}),
            409,
            headers: {'content-type': 'application/json; charset=utf-8'},
          );
        }),
      );
      await expectLater(
        api.request('/start', method: 'POST'),
        throwsA(isA<ApiError>()),
      );
      expect(calls, 1);
      api.close();
    },
  );
}
