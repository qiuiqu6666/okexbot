import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:okx_pilot/main.dart';
import 'package:shared_preferences/shared_preferences.dart';

const token = 'abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG';
http.Response response(Object body, [int status = 200]) => http.Response(
  jsonEncode(body),
  status,
  headers: {'content-type': 'application/json; charset=utf-8'},
);
final status = {
  'enabled': false,
  'settings': {
    'instruments': ['BTC-USDT-SWAP'],
    'maxOrderUsdt': 100,
    'maxExposureUsdt': 300,
    'maxDailyLossPct': 3,
    'maxPositions': 2,
    'leverage': 1,
    'intervalSeconds': 300,
  },
  'model': '',
  'okxConfigured': false,
  'aiConfigured': false,
};

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));
  test('address supports direct IP and rejects paths/embedded credentials', () {
    expect(
      normalizeServerAddress(' 47.76.193.239:8080/ '),
      'http://47.76.193.239:8080',
    );
    for (final s in [
      'ftp://host',
      'https://user:pass@host',
      'http://host/api',
      'http://host?x=1',
      'http://host:99999',
    ]) {
      expect(() => normalizeServerAddress(s), throwsA(isA<ApiError>()));
    }
  });
  testWidgets('registration validates password confirmation without network', (
    tester,
  ) async {
    await tester.pumpWidget(const PilotApp());
    expect(find.text('http://47.76.193.239:8080'), findsOneWidget);
    await tester.ensureVisible(find.byKey(const Key('authToggle')));
    await tester.tap(find.byKey(const Key('authToggle')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('username')), 'tester');
    await tester.enterText(find.byKey(const Key('password')), 'password123');
    await tester.enterText(
      find.byKey(const Key('confirmPassword')),
      'different',
    );
    await tester.ensureVisible(find.byKey(const Key('authSubmit')));
    await tester.tap(find.byKey(const Key('authSubmit')));
    await tester.pumpAndSettle();
    expect(find.text('两次密码不一致'), findsOneWidget);
  });
  testWidgets('register enters dashboard and logout revokes session', (
    tester,
  ) async {
    var revoked = false;
    final client = MockClient((req) async {
      if (req.url.path == '/api/auth/register') {
        expect(jsonDecode(req.body), {
          'username': 'tester',
          'password': 'password123',
        });
        expect(req.headers['Authorization'], isNull);
        return response({
          'token': token,
          'user': {'id': 1, 'username': 'tester'},
        });
      }
      expect(req.headers['Authorization'], 'Bearer $token');
      if (req.url.path == '/api/auth/logout') {
        revoked = true;
        return response({'ok': true});
      }
      if (req.url.path == '/api/status') return response(status);
      if (req.url.path == '/api/connections') {
        return response({'encryptionReady': true});
      }
      return response([]);
    });
    await tester.pumpWidget(
      PilotApp(createApi: (url) => PilotApi(url, '', client)),
    );
    await tester.ensureVisible(find.byKey(const Key('authToggle')));
    await tester.tap(find.byKey(const Key('authToggle')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('username')), 'tester');
    await tester.enterText(find.byKey(const Key('password')), 'password123');
    await tester.enterText(
      find.byKey(const Key('confirmPassword')),
      'password123',
    );
    await tester.ensureVisible(find.byKey(const Key('authSubmit')));
    await tester.tap(find.byKey(const Key('authSubmit')));
    await tester.pumpAndSettle();
    expect(find.text('USDT 账户权益'), findsOneWidget);
    await tester.tap(find.byTooltip('退出登录'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    await tester.pumpAndSettle();
    expect(revoked, isTrue);
    expect(find.text('欢迎回来'), findsOneWidget);
    expect(
      tester
          .widget<TextFormField>(find.byKey(const Key('password')))
          .controller!
          .text,
      isEmpty,
    );
  });
  testWidgets('saved session opens the dashboard without logging in again', (
    tester,
  ) async {
    SharedPreferences.setMockInitialValues({
      SessionStore.urlKey: 'http://47.76.193.239:8080',
      SessionStore.tokenKey: token,
      SessionStore.userKey: 'tester',
      SessionStore.envKey: 'DEMO',
    });
    final client = MockClient((req) async {
      expect(req.headers['Authorization'], 'Bearer $token');
      if (req.url.path == '/api/auth/me') {
        return response({
          'id': 1,
          'username': 'tester',
        });
      }
      if (req.url.path == '/api/status') return response(status);
      return response([]);
    });
    await tester.pumpWidget(
      PilotApp(createApi: (url) => PilotApi(url, '', client)),
    );
    await tester.pumpAndSettle();
    expect(find.text('USDT 账户权益'), findsOneWidget);
    expect(find.text('欢迎回来'), findsNothing);
  });
  testWidgets('expired session returns to login once', (tester) async {
    final client = MockClient((req) async {
      if (req.url.path == '/api/auth/login') {
        return response({
          'token': token,
          'user': {'username': 'tester'},
        });
      }
      if (req.url.path == '/api/status') return response(status);
      return response({'message': 'expired'}, 401);
    });
    await tester.pumpWidget(
      PilotApp(createApi: (url) => PilotApi(url, '', client)),
    );
    await tester.enterText(find.byKey(const Key('username')), 'tester');
    await tester.enterText(find.byKey(const Key('password')), 'password123');
    await tester.ensureVisible(find.byKey(const Key('authSubmit')));
    await tester.tap(find.byKey(const Key('authSubmit')));
    await tester.pumpAndSettle();
    expect(find.text('登录已过期，请重新登录'), findsOneWidget);
    expect(find.text('欢迎回来'), findsOneWidget);
  });
  test('API never retries uncertain mutation or follows redirects', () async {
    var calls = 0;
    final api = PilotApi(
      'https://localhost',
      token,
      MockClient((req) async {
        calls++;
        expect(req.headers['Authorization'], 'Bearer $token');
        expect(req.followRedirects, isFalse);
        return response({'message': '结果待确认'}, 409);
      }),
    );
    await expectLater(
      api.request('/start', method: 'POST'),
      throwsA(isA<ApiError>()),
    );
    expect(calls, 1);
    api.close();
  });
}
