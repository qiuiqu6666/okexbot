import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:okx_pilot/main.dart' as app;

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('register logout and login against Java MySQL', (tester) async {
    const url = String.fromEnvironment('TEST_BACKEND_URL');
    expect(
      url.startsWith('http://127.0.0.1:'),
      isTrue,
      reason: 'Acceptance must only use the local forwarded backend',
    );
    final username = 'android${DateTime.now().millisecondsSinceEpoch}';
    const password = 'local-acceptance-password';
    Future<void> submit() async {
      FocusManager.instance.primaryFocus?.unfocus();
      await SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const Key('authSubmit')));
      await tester.tap(find.byKey(const Key('authSubmit')));
      for (
        var i = 0;
        i < 80 && find.text('USDT 账户权益').evaluate().isEmpty;
        i++
      ) {
        await tester.pump(const Duration(milliseconds: 250));
      }
      await tester.pumpAndSettle();
    }

    app.main();
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('服务器设置'));
    await tester.tap(find.text('服务器设置'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('serverAddress')), url);
    await tester.tap(find.text('服务器设置'));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('authToggle')));
    await tester.tap(find.byKey(const Key('authToggle')));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('username')), username);
    await tester.enterText(find.byKey(const Key('password')), password);
    await tester.enterText(find.byKey(const Key('confirmPassword')), password);
    await submit();
    expect(find.text('USDT 账户权益'), findsOneWidget);
    expect(find.text('自动交易已暂停'), findsOneWidget);
    await binding.convertFlutterSurfaceToImage();
    await tester.pumpAndSettle();
    await binding.takeScreenshot('overview');
    await tester.tap(find.text('仓位'));
    await tester.pumpAndSettle();
    expect(find.text('等待账户同步'), findsOneWidget);
    await tester.tap(find.text('记录'));
    await tester.pumpAndSettle();
    expect(find.text('交易与决策记录'), findsOneWidget);
    await tester.tap(find.text('资讯'));
    await tester.pumpAndSettle();
    expect(find.text('市场资讯'), findsOneWidget);
    for (
      var i = 0;
      i < 80 && find.byType(LinearProgressIndicator).evaluate().isNotEmpty;
      i++
    ) {
      await tester.pump(const Duration(milliseconds: 250));
    }
    await tester.pumpAndSettle();
    expect(find.textContaining('检查时间'), findsOneWidget);
    await binding.takeScreenshot('news');
    await tester.tap(find.text('设置'));
    await tester.pumpAndSettle();
    expect(find.text(username), findsOneWidget);
    expect(find.text('未配置'), findsNWidgets(2));
    await binding.takeScreenshot('settings');
    await tester.tap(find.text('实盘'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    for (
      var i = 0;
      i < 80 && find.textContaining('当前为实盘').evaluate().isEmpty;
      i++
    ) {
      await tester.pump(const Duration(milliseconds: 250));
    }
    await tester.pumpAndSettle();
    expect(find.textContaining('当前为实盘'), findsOneWidget);
    await binding.takeScreenshot('live-settings');
    await tester.tap(find.text('总览'));
    await tester.pumpAndSettle();
    expect(find.text('自动交易已暂停'), findsOneWidget);
    await tester.tap(find.text('设置'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('模拟盘'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    for (
      var i = 0;
      i < 80 && find.textContaining('当前为实盘').evaluate().isNotEmpty;
      i++
    ) {
      await tester.pump(const Duration(milliseconds: 250));
    }
    await tester.pumpAndSettle();
    expect(find.textContaining('当前为实盘'), findsNothing);
    await tester.tap(find.byTooltip('退出登录'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    for (var i = 0; i < 40 && find.text('欢迎回来').evaluate().isEmpty; i++) {
      await tester.pump(const Duration(milliseconds: 250));
    }
    await tester.pumpAndSettle();
    expect(find.text('欢迎回来'), findsOneWidget);
    await tester.enterText(find.byKey(const Key('password')), password);
    await submit();
    expect(find.text('USDT 账户权益'), findsOneWidget);
    await tester.tap(find.byTooltip('退出登录'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('确认'));
    for (var i = 0; i < 40 && find.text('欢迎回来').evaluate().isEmpty; i++) {
      await tester.pump(const Duration(milliseconds: 250));
    }
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('username')), '');
    await tester.pumpAndSettle();
    await binding.takeScreenshot('login');
    await tester.ensureVisible(find.byKey(const Key('authToggle')));
    await tester.tap(find.byKey(const Key('authToggle')));
    await tester.pumpAndSettle();
    await binding.takeScreenshot('register');
    expect(tester.takeException(), isNull);
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  });
}
