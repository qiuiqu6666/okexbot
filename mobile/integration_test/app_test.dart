import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:okx_pilot/main.dart' as app;

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets('Android connects to Java/MySQL and displays all four pages', (
    tester,
  ) async {
    const token = String.fromEnvironment('TEST_OPERATOR_TOKEN');
    const url = String.fromEnvironment(
      'TEST_BACKEND_URL',
      defaultValue: 'http://10.0.2.2:18081',
    );
    expect(token.length, greaterThanOrEqualTo(32));
    app.main();
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).at(0), url);
    await tester.enterText(find.byType(TextField).at(1), token);
    FocusManager.instance.primaryFocus?.unfocus();
    await SystemChannels.textInput.invokeMethod<void>('TextInput.hide');
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.text('连接控制台'));
    await tester.tap(find.text('连接控制台'));
    // Network completion is not represented by animation settling.
    for (var i = 0; i < 60 && find.text('USDT 账户权益').evaluate().isEmpty; i++) {
      await tester.pump(const Duration(milliseconds: 250));
    }
    if (find.text('USDT 账户权益').evaluate().isEmpty) {
      await binding.convertFlutterSurfaceToImage();
      await tester.pumpAndSettle();
      await binding.takeScreenshot('connection-error');
    }
    expect(find.text('USDT 账户权益'), findsOneWidget);
    expect(find.text('自动交易已暂停'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await binding.convertFlutterSurfaceToImage();
    await tester.pumpAndSettle();
    await binding.takeScreenshot('overview');
    await tester.tap(find.text('仓位'));
    await tester.pumpAndSettle();
    expect(find.text('等待账户同步'), findsOneWidget);
    await tester.tap(find.text('记录'));
    await tester.pumpAndSettle();
    expect(find.text('交易与决策记录'), findsOneWidget);
    await tester.tap(find.text('设置'));
    await tester.pumpAndSettle();
    expect(find.text('运行设置'), findsOneWidget);
    expect(find.text('未配置'), findsNWidgets(2));
    expect(tester.takeException(), isNull);
    await binding.takeScreenshot('settings');
    await tester.tap(find.text('总览'));
    await tester.pumpAndSettle();
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pumpAndSettle();
  });
}
