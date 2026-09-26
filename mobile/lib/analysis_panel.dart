import 'dart:convert';
import 'package:flutter/material.dart';
import 'news_panel.dart';

class AnalysisPanel extends StatelessWidget {
  final List<dynamic> records;
  final String model, environment;
  final bool analyzing;
  final VoidCallback? onAnalyze;
  const AnalysisPanel({
    super.key,
    required this.records,
    required this.model,
    required this.environment,
    required this.analyzing,
    this.onAnalyze,
  });

  static Map<String, dynamic> payload(dynamic raw) {
    try {
      final value = raw is String ? jsonDecode(raw) : raw;
      return value is Map<String, dynamic> ? value : {};
    } catch (_) {
      return {};
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text('AI 分析中心', style: Theme.of(context).textTheme.headlineSmall),
      const SizedBox(height: 8),
      Text('$environment · ${model.isEmpty ? '尚未配置模型' : model}'),
      const SizedBox(height: 8),
      const Text('查看模型的决策依据、建议和服务器校验结果。分析完成后更新；建议不代表已下单或成交。'),
      const SizedBox(height: 12),
      if (analyzing) ...[
        const LinearProgressIndicator(),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 10),
          child: Text('正在处理分析请求，请等待完整结果…'),
        ),
      ],
      OutlinedButton.icon(
        onPressed: onAnalyze,
        icon: const Icon(Icons.psychology_outlined),
        label: const Text('生成一次分析 · 不下单'),
      ),
      const SizedBox(height: 16),
      if (records.isEmpty)
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 24),
          child: Text('暂无 AI 分析。生成一次分析，或开启自动交易后在这里查看结果。'),
        ),
      for (var index = 0; index < records.length; index++)
        if (records[index] is Map<String, dynamic>)
          AnalysisCard(
            record: records[index] as Map<String, dynamic>,
            latest: index == 0,
          ),
      if (records.isNotEmpty) const Text('显示当前账户、当前环境最近 100 条分析记录，下拉可刷新。'),
    ],
  );
}

class AnalysisCard extends StatelessWidget {
  final Map<String, dynamic> record;
  final bool latest;
  const AnalysisCard({super.key, required this.record, this.latest = false});
  static const actions = {
    'HOLD': '观望',
    'OPEN_LONG': '开多',
    'OPEN_SHORT': '开空',
    'REDUCE': '减仓',
    'CLOSE': '平仓',
    'UPDATE_STOPS': '调整止盈止损',
  };
  @override
  Widget build(BuildContext context) {
    final data = AnalysisPanel.payload(record['payload']);
    final decision = AnalysisPanel.payload(data['decision']);
    final plan = AnalysisPanel.payload(data['plan']);
    final kind = '${record['kind']}';
    final label = switch (kind) {
      'PREVIEW' => '仅分析 · 未下单',
      'DECISION' => '自动决策 · 初步校验通过',
      'AI_REJECTED' => '已拦截 · 未提交该建议',
      'AI_EXECUTION_FAILED' => '执行异常 · 请核对订单',
      _ => '本轮跳过',
    };
    final when =
        DateTime.tryParse(
          '${record['created_at']}',
        )?.toLocal().toString().split('.').first ??
        '${record['created_at'] ?? ''}';
    final reason =
        '${decision['reason'] ?? data['reason'] ?? record['summary'] ?? '暂无分析说明'}';
    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (decision.isNotEmpty) ...[
          Text(
            '${decision['instrument'] ?? '未指定合约'} · ${actions[decision['action']] ?? '无效动作'}',
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 12),
        ],
        SelectableText(reason),
        if (data['reason'] != null && decision.isNotEmpty) ...[
          const SizedBox(height: 12),
          const Text('服务器处理结果', style: TextStyle(fontWeight: FontWeight.w600)),
          SelectableText('${data['reason']}'),
        ],
        const SizedBox(height: 12),
        if (decision['notionalUsdt'] != null)
          Text('模型建议敞口：${decision['notionalUsdt']} USDT'),
        if (plan['notionalUsdt'] != null && plan['reduceOnly'] == false)
          Text('风控计算敞口：${plan['notionalUsdt']} USDT'),
        if (decision['reduceFraction'] != null)
          Text('建议减仓比例：${decision['reduceFraction']}'),
        if (decision['takeProfit'] != null)
          Text('建议止盈：${decision['takeProfit']}'),
        if (decision['stopLoss'] != null) Text('建议止损：${decision['stopLoss']}'),
        if (data['price'] != null) Text('校验时参考价：${data['price']}'),
        if (kind == 'DECISION') const Text('最终提交与成交结果请查看「记录」，后续检查仍可能阻止执行。'),
        NewsEvidence(payload: data),
      ],
    );
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '${latest ? '最新分析 · ' : ''}$label',
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
            Text(when, style: Theme.of(context).textTheme.bodySmall),
            const SizedBox(height: 12),
            if (latest)
              content
            else
              ExpansionTile(
                tilePadding: EdgeInsets.zero,
                title: Text(
                  '${decision['instrument'] ?? label} · ${actions[decision['action']] ?? '查看说明'}',
                ),
                children: [content],
              ),
          ],
        ),
      ),
    );
  }
}
