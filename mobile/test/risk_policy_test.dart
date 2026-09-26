import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:okx_pilot/main.dart';

void main() {
  const policy = <String, dynamic>{
    'maxTradeRiskPct': 0.5,
    'maxMarginPct': 60,
    'maxDirectionalExposureUsdt': 200,
    'maxCorrelatedExposureUsdt': 250,
    'maxDrawdownPct': 5,
    'maxConsecutiveLosses': 3,
    'maxOpensPerHour': 3,
    'cooldownSeconds': 900,
    'lossCooldownSeconds': 3600,
    'orderTimeoutSeconds': 60,
    'takerFeeBps': 10,
    'maxSlippageBps': 20,
    'maxSpreadBps': 10,
  };
  testWidgets(
    'risk configuration preserves fields and reset requires explicit review',
    (tester) async {
      Map<String, dynamic>? saved;
      var resets = 0;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: RiskPolicyEditor(
                initial: policy,
                riskStatus: const {
                  'haltReason': '连续亏损',
                  'consecutiveLosses': 3,
                },
                enabled: true,
                onSave: (value) async {
                  saved = value;
                },
                onReset: () async {
                  resets++;
                },
              ),
            ),
          ),
        ),
      );
      await tester.ensureVisible(find.text('保存硬性约束'));
      await tester.tap(find.text('保存硬性约束'));
      await tester.pumpAndSettle();
      expect(saved, policy);
      await tester.ensureVisible(find.text('人工复核解除熔断'));
      await tester.tap(find.text('人工复核解除熔断'));
      await tester.pumpAndSettle();
      expect(resets, 0);
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(resets, 0);
      await tester.tap(find.text('人工复核解除熔断'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('已复核，解除熔断'));
      await tester.pumpAndSettle();
      expect(resets, 1);
    },
  );
}
