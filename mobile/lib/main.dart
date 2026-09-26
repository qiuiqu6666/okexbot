import 'dart:async';
import 'dart:convert';
import 'api.dart';
import 'auth_page.dart';
import 'connection_editor.dart';
import 'instrument_picker.dart';
import 'news_panel.dart';
import 'analysis_panel.dart';
export 'api.dart';
import 'package:flutter/material.dart';

void main() => runApp(const PilotApp());
const mint = Color(0xFF9EEBC5),
    muted = Color(0xFF98A6AC),
    panel = Color(0xFF182326);

class PilotApp extends StatelessWidget {
  final PilotApi Function(String)? createApi;
  const PilotApp({super.key, this.createApi});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: '仓位领航',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: const Color(0xFF0D171A),
      colorScheme: ColorScheme.fromSeed(
        seedColor: mint,
        brightness: Brightness.dark,
        primary: mint,
        surface: panel,
      ),
      useMaterial3: true,
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xFF101C20),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
      ),
      navigationBarTheme: const NavigationBarThemeData(
        backgroundColor: Color(0xFF111D20),
        indicatorColor: Color(0xFF2A4840),
      ),
    ),
    home: AuthPage(
      createApi: createApi,
      dashboardBuilder: (api, status) =>
          Dashboard(api: api, initialStatus: status),
    ),
  );
}

class Dashboard extends StatefulWidget {
  final PilotApi api;
  final Map<String, dynamic> initialStatus;
  const Dashboard({super.key, required this.api, required this.initialStatus});
  @override
  State<Dashboard> createState() => _DashboardState();
}

class _DashboardState extends State<Dashboard> {
  late Map<String, dynamic> status;
  Map<String, dynamic>? connections;
  List<dynamic> events = [], orders = [], analyses = [];
  Timer? timer;
  int tab = 0;
  bool busy = false, refreshing = false, stopping = false;
  bool previewing = false;
  bool leaving = false, switching = false;
  String get environmentLabel =>
      widget.api.environment == 'LIVE' ? '实盘' : '模拟盘';
  Future<void> switchEnvironment(String target) async {
    if (busy || refreshing || target == widget.api.environment) return;
    if (!await confirm(
      '切换到${target == 'LIVE' ? '实盘' : '模拟盘'}？',
      '将暂停当前环境的自动交易，保留已有仓位和保护单。两个环境的密钥、币种和记录独立保存。实盘使用真实资金。',
    )) {
      return;
    }
    if (!mounted || leaving) return;
    setState(() {
      busy = true;
      switching = true;
      error = null;
    });
    final previous = widget.api.environment;
    try {
      await widget.api.request('/pause', method: 'POST');
      widget.api.environment = target;
      final next = await widget.api.request('/status') as Map<String, dynamic>;
      if (next['environment'] != target) throw ApiError('后端尚未支持该交易环境，请升级后端');
      if (!mounted || leaving) return;
      setState(() {
        status = next;
        connections = null;
        orders = [];
        events = [];
        analyses = [];
        error = null;
        syncedAt = null;
      });
    } catch (e) {
      widget.api.environment = previous;
      if (mounted) setState(() => error = '$e');
    } finally {
      if (mounted) {
        final switchError = error;
        setState(() {
          busy = false;
          switching = false;
        });
        await refresh();
        if (mounted && switchError != null) setState(() => error = switchError);
      }
    }
  }

  String? error;
  DateTime? syncedAt;
  @override
  void initState() {
    super.initState();
    status = widget.initialStatus;
    widget.api.onUnauthorized = () => leave('登录已过期，请重新登录');
    refresh();
    timer = Timer.periodic(const Duration(seconds: 10), (_) => refresh());
  }

  void leave([String? message]) {
    if (!mounted || leaving) return;
    leaving = true;
    timer?.cancel();
    final route = ModalRoute.of(context);
    final navigator = Navigator.of(context);
    navigator.popUntil((r) => r == route || r.isFirst);
    navigator.pop(message);
  }

  Future<void> logout() async {
    if (!await confirm('退出登录？', '退出登录不会停止服务器上的自动交易。需要停止时请先点击暂停。')) return;
    if (!mounted || leaving) return;
    try {
      await widget.api.logout();
      leave();
    } catch (e) {
      if (mounted && !leaving) setState(() => error = '$e');
    }
  }

  @override
  void dispose() {
    timer?.cancel();
    widget.api.close();
    super.dispose();
  }

  Future<void> refresh() async {
    if (refreshing || busy || leaving) return;
    refreshing = true;
    final requestedEnvironment = widget.api.environment;
    try {
      final results = await Future.wait([
        widget.api.request('/status'),
        widget.api.request('/events'),
        widget.api.request('/orders'),
        widget.api.request('/connections'),
        widget.api.request('/analysis'),
      ]);
      if (mounted && !busy && requestedEnvironment == widget.api.environment) {
        setState(() {
          status = results[0] as Map<String, dynamic>;
          events = results[1] as List;
          orders = results[2] as List;
          connections = results[3] as Map<String, dynamic>;
          analyses = results[4] as List;
          syncedAt = DateTime.now();
          error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => error = '$e');
    } finally {
      refreshing = false;
    }
  }

  Future<bool> act(
    String path, {
    Object? body,
    String method = 'POST',
    String success = '操作已完成',
  }) async {
    if (busy || leaving) return false;
    setState(() {
      busy = true;
      previewing = path == '/preview';
      error = null;
    });
    try {
      final result = await widget.api.request(path, method: method, body: body);
      if (!mounted || leaving) return false;
      if (path == '/preview' && tab != 5) {
        await showDialog<void>(
          context: context,
          builder: (c) => AlertDialog(
            title: const Text('分析结果 · 未下单'),
            content: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${result['instrument']} · ${actionLabel('${result['action']}')}',
                  ),
                  const SizedBox(height: 12),
                  Text('${result['reason']}'),
                  if (result['notionalUsdt'] != null)
                    Text('建议敞口：${result['notionalUsdt']} USDT'),
                  if (result['takeProfit'] != null)
                    Text('止盈：${result['takeProfit']}'),
                  if (result['stopLoss'] != null)
                    Text('止损：${result['stopLoss']}'),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(c),
                child: const Text('知道了'),
              ),
            ],
          ),
        );
      } else {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(path == '/preview' ? '分析已完成，结果已更新到 AI 分析' : success),
          ),
        );
      }
      return true;
    } catch (e) {
      if (mounted) setState(() => error = '$e');
      return false;
    } finally {
      if (mounted) {
        final savedError = error;
        setState(() {
          busy = false;
          previewing = false;
        });
        await refresh();
        if (mounted && savedError != null) setState(() => error = savedError);
      }
    }
  }

  Future<void> pause() async {
    if (stopping || switching) return;
    setState(() => stopping = true);
    try {
      final result = await widget.api.request('/pause', method: 'POST');
      if (mounted) setState(() => status = result as Map<String, dynamic>);
    } catch (e) {
      if (mounted) setState(() => error = '$e');
    } finally {
      if (mounted) setState(() => stopping = false);
    }
  }

  Future<bool> confirm(String title, String message) async =>
      await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          title: Text(title),
          content: Text(message),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('确认'),
            ),
          ],
        ),
      ) ??
      false;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      automaticallyImplyLeading: false,
      backgroundColor: const Color(0xFF0D171A),
      title: const Text('仓位领航', style: TextStyle(fontWeight: FontWeight.w700)),
      actions: [
        BadgeLabel(environmentLabel),
        IconButton(
          tooltip: '退出登录',
          onPressed: logout,
          icon: const Icon(Icons.logout, size: 20),
        ),
      ],
    ),
    body: SafeArea(
      child: RefreshIndicator(
        onRefresh: refresh,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(20, 8, 20, 28),
          children: [
            if (busy)
              const Padding(
                padding: EdgeInsets.only(bottom: 12),
                child: LinearProgressIndicator(minHeight: 2),
              ),
            if (error != null) Notice(error!, error: true),
            if ('${status['lastError'] ?? ''}'.isNotEmpty)
              Notice('${status['lastError']}', error: true),
            ...switch (tab) {
              0 => overview(),
              1 => positions(),
              2 => history(),
              3 => [
                NewsPanel(
                  key: ValueKey(
                    '${widget.api.environment}:${jsonEncode(status["settings"])}',
                  ),
                  api: widget.api,
                ),
              ],
              5 => [
                AnalysisPanel(
                  records: analyses,
                  model: '${status['model'] ?? ''}',
                  environment: environmentLabel,
                  analyzing: status['analyzing'] == true || previewing,
                  onAnalyze: busy ? null : () => act('/preview'),
                ),
              ],
              _ => settings(),
            },
          ],
        ),
      ),
    ),
    bottomNavigationBar: NavigationBar(
      selectedIndex: tab,
      onDestinationSelected: (i) => setState(() => tab = i),
      destinations: const [
        NavigationDestination(
          icon: Icon(Icons.space_dashboard_outlined),
          label: '总览',
        ),
        NavigationDestination(
          icon: Icon(Icons.candlestick_chart_outlined),
          label: '仓位',
        ),
        NavigationDestination(
          icon: Icon(Icons.receipt_long_outlined),
          label: '记录',
        ),
        NavigationDestination(
          icon: Icon(Icons.newspaper_outlined),
          label: '资讯',
        ),
        NavigationDestination(icon: Icon(Icons.tune), label: '设置'),
        NavigationDestination(
          icon: Icon(Icons.psychology_outlined),
          label: 'AI 分析',
        ),
      ],
    ),
  );
  List<Widget> overview() {
    final snap = status['snapshot'] as Map<String, dynamic>?,
        active = status['enabled'] == true;
    final config = status['settings'] as Map<String, dynamic>;
    return [
      Row(
        children: [
          Icon(Icons.circle, size: 8, color: active ? mint : muted),
          const SizedBox(width: 8),
          Text(
            active ? '自动交易运行中' : '自动交易已暂停',
            style: const TextStyle(color: muted),
          ),
          const Spacer(),
          Text(
            syncedAt == null
                ? '等待同步'
                : '已更新 ${timeText(syncedAt!.toIso8601String())}',
            style: const TextStyle(color: muted, fontSize: 10),
          ),
        ],
      ),
      const SizedBox(height: 20),
      Container(
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            colors: [Color(0xFF203D35), Color(0xFF17282A)],
          ),
          borderRadius: BorderRadius.circular(24),
          border: Border.all(color: const Color(0xFF35554A)),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('USDT 账户权益', style: TextStyle(color: mint)),
            const SizedBox(height: 14),
            Text(
              snap == null ? '—' : money(snap['equity']),
              style: const TextStyle(
                fontSize: 38,
                fontWeight: FontWeight.w600,
                letterSpacing: -1,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              'USDT · OKX $environmentLabel账户',
              style: const TextStyle(color: muted),
            ),
            const SizedBox(height: 24),
            Row(
              children: [
                Expanded(
                  child: metric(
                    '可用余额',
                    snap == null ? '—' : money(snap['available']),
                  ),
                ),
                Expanded(
                  child: metric(
                    '持仓数量',
                    snap == null
                        ? '—'
                        : '${(snap['positions'] as List).length} / ${config['maxPositions']}',
                  ),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Text(
              snap == null
                  ? '点击同步账户获取数据'
                  : '账户快照 ${timeText('${snap['fetchedAt']}')} · 非实时行情',
              style: const TextStyle(color: muted, fontSize: 11),
            ),
          ],
        ),
      ),
      const SizedBox(height: 20),
      Row(
        children: [
          Expanded(
            child: FilledButton.icon(
              onPressed: active
                  ? (stopping ? null : pause)
                  : (busy
                        ? null
                        : () async {
                            if (await confirm(
                              '开启$environmentLabel自动交易？',
                              '$environmentLabel：AI 将按当前风控参数自动开仓、减仓、平仓及调整保护单。${widget.api.environment == 'LIVE' ? '这会使用真实资金。' : ''}关闭手机后服务器仍继续运行。',
                            )) {
                              await act(
                                '/start',
                                body: {
                                  'confirmLive':
                                      widget.api.environment == 'LIVE',
                                },
                                success: '$environmentLabel自动交易已开启',
                              );
                            }
                          }),
              icon: Icon(active ? Icons.pause : Icons.play_arrow),
              label: Text(active ? '暂停自动交易' : '开启自动交易'),
            ),
          ),
          const SizedBox(width: 10),
          OutlinedButton(
            onPressed: busy ? null : () => act('/sync', success: '账户已同步'),
            child: const Text('同步账户'),
          ),
        ],
      ),
      const SizedBox(height: 24),
      const Text(
        '决策与边界',
        style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
      ),
      const SizedBox(height: 12),
      Surface(
        child: Column(
          children: [
            infoRow(
              'AI 模型',
              '${status['model']}'.isEmpty ? '尚未配置' : '${status['model']}',
            ),
            infoRow('单笔敞口', '${config['maxOrderUsdt']} USDT'),
            infoRow('总敞口上限', '${config['maxExposureUsdt']} USDT'),
            infoRow('日内权益损失上限', '${config['maxDailyLossPct']}%'),
            infoRow('分析间隔', '${config['intervalSeconds']} 秒'),
            const SizedBox(height: 8),
            SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: busy ? null : () => act('/preview'),
                icon: const Icon(Icons.auto_awesome_outlined, size: 18),
                label: const Text('仅分析一次 · 不下单'),
              ),
            ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      if (status['okxConfigured'] != true || status['aiConfigured'] != true)
        const Notice('连接尚未就绪。请在“设置”中保存当前环境的 OKX 凭据与模型接口。'),
      const Text(
        '暂停会阻止后续自动指令，已提交订单仍可能成交，已有止盈止损保留。',
        style: TextStyle(color: muted, height: 1.6, fontSize: 12),
      ),
    ];
  }

  List<Widget> positions() {
    final snap = status['snapshot'] as Map<String, dynamic>?,
        rows = (status['snapshot'] as Map?)?['positions'] as List? ?? [];
    return [
      const Text(
        '当前仓位',
        style: TextStyle(fontSize: 26, fontWeight: FontWeight.w600),
      ),
      const SizedBox(height: 8),
      const Text('逐仓模式 · 支持单向或双向持仓', style: TextStyle(color: muted)),
      const SizedBox(height: 16),
      OutlinedButton.icon(
        onPressed: busy ? null : () => act('/sync', success: '账户已同步'),
        icon: const Icon(Icons.sync),
        label: const Text('刷新交易所仓位'),
      ),
      if (snap != null)
        Text(
          '快照时间 ${timeText('${snap['fetchedAt']}')}',
          style: const TextStyle(color: muted),
        ),
      const SizedBox(height: 16),
      if (rows.isEmpty)
        EmptyState(
          icon: Icons.layers_outlined,
          title: snap == null ? '等待账户同步' : '当前没有持仓',
          detail: snap == null ? '配置当前环境凭据后点击同步账户。' : '出现合适机会时，AI 会根据风险限制提出交易。',
        ),
      for (final raw in rows) positionCard(raw as Map<String, dynamic>),
    ];
  }

  Widget positionCard(Map<String, dynamic> p) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: Surface(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  '${p['instrument']}',
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 18,
                  ),
                ),
              ),
              BadgeLabel(
                (num.tryParse('${p['contracts']}') ?? 0) > 0 ? '做多' : '做空',
              ),
            ],
          ),
          const SizedBox(height: 20),
          metric('未实现盈亏 / USDT', money(p['unrealizedPnl'])),
          const SizedBox(height: 16),
          infoRow('合约张数', '${p['contracts']}'),
          infoRow('标记价格', money(p['markPrice'])),
          infoRow('名义敞口', '${money(p['notionalUsdt'])} USDT'),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: busy
                      ? null
                      : () async {
                          if (await confirm(
                            '$environmentLabel：减仓一半？',
                            '${p['instrument']} 将按当前交易所仓位提交只减仓市价单。',
                          )) {
                            await act(
                              '/positions/${p['instrument']}/reduce',
                              success: '减仓单已提交，请对账确认成交',
                            );
                          }
                        },
                  child: const Text('减仓 50%'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: OutlinedButton(
                  onPressed: busy
                      ? null
                      : () async {
                          if (await confirm(
                            '$environmentLabel：平掉该仓位？',
                            '${p['instrument']} 将提交只减仓市价单。',
                          )) {
                            await act(
                              '/positions/${p['instrument']}/close',
                              success: '平仓单已提交，请对账确认成交',
                            );
                          }
                        },
                  child: const Text('全部平仓'),
                ),
              ),
            ],
          ),
        ],
      ),
    ),
  );
  List<Widget> history() => [
    const Text(
      '交易与决策记录',
      style: TextStyle(fontSize: 26, fontWeight: FontWeight.w600),
    ),
    const SizedBox(height: 12),
    OutlinedButton.icon(
      onPressed: busy ? null : () => act('/reconcile', success: '已查询订单状态'),
      icon: const Icon(Icons.fact_check_outlined),
      label: const Text('与交易所对账'),
    ),
    const SizedBox(height: 18),
    const Text(
      '最近订单',
      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
    ),
    const SizedBox(height: 10),
    if (orders.isEmpty)
      const EmptyState(
        icon: Icons.receipt_long_outlined,
        title: '暂无订单',
        detail: '提交与成交状态会分别记录。',
      ),
    for (final o in orders)
      Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Surface(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${o['instrument']} · ${actionLabel('${o['action']}')}',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 6),
              Text(
                '${orderLabel('${o['state']}')} · ${timeText('${o['created_at']}')}',
                style: const TextStyle(color: mint),
              ),
              const SizedBox(height: 6),
              SelectableText(
                '${o['client_id']}',
                style: const TextStyle(fontSize: 11, color: muted),
              ),
              if (o['detail'] != null)
                Text(
                  '${o['detail']}',
                  style: const TextStyle(fontSize: 12, color: muted),
                ),
            ],
          ),
        ),
      ),
    const SizedBox(height: 18),
    const Text(
      '操作与分析',
      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
    ),
    const SizedBox(height: 10),
    for (final e in events)
      Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Surface(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${eventLabel('${e['kind']}')} · ${timeText('${e['created_at']}')}',
                style: const TextStyle(color: muted, fontSize: 12),
              ),
              const SizedBox(height: 8),
              Text('${e['summary']}'),
              NewsEvidence(payload: e['payload']),
            ],
          ),
        ),
      ),
  ];
  List<Widget> settings() => [
    SegmentedButton<String>(
      segments: const [
        ButtonSegment(value: 'DEMO', label: Text('模拟盘')),
        ButtonSegment(value: 'LIVE', label: Text('实盘')),
      ],
      selected: {widget.api.environment},
      onSelectionChanged: busy
          ? null
          : (values) => switchEnvironment(values.single),
    ),
    const SizedBox(height: 12),
    if (widget.api.environment == 'LIVE')
      const Notice('当前为实盘，交易使用真实资金。请配置实盘 API Key；切换环境不会自动开启交易。'),
    const Text(
      '运行设置',
      style: TextStyle(fontSize: 26, fontWeight: FontWeight.w600),
    ),
    const SizedBox(height: 16),
    Surface(
      child: Column(
        children: [
          infoRow('当前用户', widget.api.username),
          infoRow('服务器', widget.api.baseUrl),
          infoRow('交易环境', 'OKX $environmentLabel'),
          infoRow('OKX 凭据', status['okxConfigured'] == true ? '已配置' : '未配置'),
          infoRow('模型接口', status['aiConfigured'] == true ? '已配置' : '未配置'),
          infoRow('持仓模式', '逐仓 · 单向或双向'),
        ],
      ),
    ),
    const SizedBox(height: 16),
    if (connections != null)
      Surface(
        child: ConnectionEditor(
          key: ValueKey(widget.api.environment),
          initial: connections!,
          enabled: !busy && status['enabled'] != true,
          onSave: (value) => act(
            '/connections',
            method: 'PUT',
            body: value,
            success: '账户连接已保存',
          ),
        ),
      ),
    const SizedBox(height: 16),
    SettingsEditor(
      key: ValueKey(
        '${widget.api.environment}:${jsonEncode(status['settings'])}',
      ),
      initial: status['settings'] as Map<String, dynamic>,
      loadInstruments: () async =>
          (await widget.api.request('/instruments') as List).cast<String>(),
      enabled: !busy && status['enabled'] != true,
      onSave: (s) =>
          act('/settings', method: 'PUT', body: s, success: '风险参数已保存'),
    ),
    const SizedBox(height: 16),
    const Notice('修改参数或连接前请暂停自动交易。每个用户应使用独立的 OKX 账户。'),
    if (status['riskPolicy'] is Map<String, dynamic>) ...[
      const SizedBox(height: 16),
      RiskPolicyEditor(
        key: ValueKey(
          '${widget.api.environment}:${jsonEncode(status['riskPolicy'])}',
        ),
        initial: status['riskPolicy'] as Map<String, dynamic>,
        riskStatus: (status['riskStatus'] as Map<String, dynamic>?) ?? {},
        enabled: !busy && status['enabled'] != true,
        onSave: (value) => act(
          '/risk-policy',
          method: 'PUT',
          body: value,
          success: '硬性风险约束已保存',
        ),
        onReset: () => act(
          '/risk/reset',
          body: {'confirm': true},
          success: '已人工解除熔断，交易仍保持暂停',
        ),
      ),
    ],
  ];
}

class RiskPolicyEditor extends StatefulWidget {
  final Map<String, dynamic> initial, riskStatus;
  final bool enabled;
  final Future<void> Function(Map<String, dynamic>) onSave;
  final Future<void> Function() onReset;
  const RiskPolicyEditor({
    super.key,
    required this.initial,
    required this.riskStatus,
    required this.enabled,
    required this.onSave,
    required this.onReset,
  });
  @override
  State<RiskPolicyEditor> createState() => _RiskPolicyEditorState();
}

class _RiskPolicyEditorState extends State<RiskPolicyEditor> {
  final form = GlobalKey<FormState>();
  static const labels = {
    'maxTradeRiskPct': '单笔预估亏损上限 / 权益 %（最高 2）',
    'maxMarginPct': '保证金占用上限 / 权益 %（最高 80）',
    'maxDirectionalExposureUsdt': '同方向敞口上限 / USDT',
    'maxCorrelatedExposureUsdt': '相关币种总敞口上限 / USDT',
    'maxDrawdownPct': '峰值回撤熔断 / %（最高 20）',
    'maxConsecutiveLosses': '连续亏损熔断 / 次（1–10）',
    'maxOpensPerHour': '每小时开仓上限 / 次（1–20）',
    'cooldownSeconds': '开仓间隔 / 秒（至少 60）',
    'lossCooldownSeconds': '平仓后冷却 / 秒（不小于开仓间隔）',
    'orderTimeoutSeconds': '未成交撤单超时 / 秒（10–300）',
    'takerFeeBps': '单边手续费预算 / 基点',
    'maxSlippageBps': '最大预计滑点 / 基点',
    'maxSpreadBps': '最大买卖价差 / 基点',
  };
  static const integerKeys = {
    'maxConsecutiveLosses',
    'maxOpensPerHour',
    'cooldownSeconds',
    'lossCooldownSeconds',
    'orderTimeoutSeconds',
  };
  late final fields = {
    for (final key in labels.keys)
      key: TextEditingController(text: '${widget.initial[key]}'),
  };
  @override
  void dispose() {
    for (final field in fields.values) {
      field.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Surface(
    child: Form(
      key: form,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            '硬性风险约束',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 12),
          const Text(
            '由服务器强制执行。所有币种按同一相关组限制，不以多空相抵。1 基点 = 0.01%。费用为预算，须覆盖账户实际费率。',
          ),
          const SizedBox(height: 12),
          Text('连续亏损：${widget.riskStatus['consecutiveLosses'] ?? 0} 次'),
          if ((widget.riskStatus['haltReason'] ?? '').toString().isNotEmpty)
            Notice('已熔断：${widget.riskStatus['haltReason']}。仍可核对订单及减少已有风险。'),
          for (final entry in labels.entries)
            Padding(
              padding: const EdgeInsets.only(top: 14),
              child: TextFormField(
                controller: fields[entry.key],
                enabled: widget.enabled,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                ),
                decoration: InputDecoration(labelText: entry.value),
                validator: (value) {
                  final number = num.tryParse(value ?? '');
                  if (number == null || !number.isFinite || number <= 0) {
                    return '请输入有效正数';
                  }
                  if (integerKeys.contains(entry.key) &&
                      number != number.round()) {
                    return '请输入整数';
                  }
                  return null;
                },
              ),
            ),
          const SizedBox(height: 16),
          FilledButton(
            onPressed: widget.enabled
                ? () async {
                    if (form.currentState!.validate()) {
                      await widget.onSave({
                        for (final key in labels.keys)
                          key: num.parse(fields[key]!.text.trim()),
                      });
                    }
                  }
                : null,
            child: const Text('保存硬性约束'),
          ),
          if ((widget.riskStatus['haltReason'] ?? '').toString().isNotEmpty)
            TextButton(
              onPressed: widget.enabled
                  ? () async {
                      final confirmed = await showDialog<bool>(
                        context: context,
                        builder: (context) => AlertDialog(
                          title: const Text('人工复核解除熔断'),
                          content: const Text(
                            '请先核对亏损原因。仅空仓且无待确认订单时允许解除；当日亏损仍超限时不能解除。解除会重置回撤基准和连续亏损次数，保留冷却及亏损后仓位限制，不会自动开始交易。',
                          ),
                          actions: [
                            TextButton(
                              onPressed: () => Navigator.pop(context, false),
                              child: const Text('取消'),
                            ),
                            FilledButton(
                              onPressed: () => Navigator.pop(context, true),
                              child: const Text('已复核，解除熔断'),
                            ),
                          ],
                        ),
                      );
                      if (confirmed == true) await widget.onReset();
                    }
                  : null,
              child: const Text('人工复核解除熔断'),
            ),
        ],
      ),
    ),
  );
}

class SettingsEditor extends StatefulWidget {
  final Future<List<String>> Function() loadInstruments;
  final Map<String, dynamic> initial;
  final bool enabled;
  final Future<void> Function(Map<String, dynamic>) onSave;
  const SettingsEditor({
    super.key,
    required this.initial,
    required this.loadInstruments,
    required this.enabled,
    required this.onSave,
  });
  @override
  State<SettingsEditor> createState() => _SettingsEditorState();
}

class _SettingsEditorState extends State<SettingsEditor> {
  final form = GlobalKey<FormState>();
  late final Map<String, TextEditingController> fields;
  late Set<String> selected;
  static const labels = {
    'maxOrderUsdt': '单笔敞口上限 / USDT',
    'maxExposureUsdt': '总敞口上限 / USDT',
    'maxDailyLossPct': '日内权益损失上限 / %',
    'maxPositions': '最大持仓数量',
    'leverage': '杠杆',
    'intervalSeconds': '分析间隔 / 秒（至少 60）',
  };
  @override
  void initState() {
    super.initState();
    selected = (widget.initial['instruments'] as List).cast<String>().toSet();
    fields = {
      for (final key in labels.keys)
        key: TextEditingController(text: '${widget.initial[key]}'),
    };
  }

  @override
  void dispose() {
    for (final f in fields.values) {
      f.dispose();
    }
    super.dispose();
  }

  Future<void> save() async {
    if (!form.currentState!.validate()) return;
    await widget.onSave({
      for (final key in labels.keys) key: num.parse(fields[key]!.text.trim()),
      'instruments': selected.toList(),
    });
  }

  @override
  Widget build(BuildContext context) => Surface(
    child: Form(
      key: form,
      child: Column(
        children: [
          InstrumentPicker(
            initial: selected,
            enabled: widget.enabled,
            load: widget.loadInstruments,
            onChanged: (value) => selected = value,
          ),
          const SizedBox(height: 16),
          for (final item in labels.entries)
            Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: TextFormField(
                controller: fields[item.key],
                enabled: widget.enabled,
                keyboardType: const TextInputType.numberWithOptions(
                  decimal: true,
                ),
                decoration: InputDecoration(labelText: item.value),
                validator: (v) {
                  final n = num.tryParse(v ?? '');
                  if (n == null || !n.isFinite || n <= 0) {
                    return '请输入大于 0 的有效数字';
                  }
                  if ([
                        'maxPositions',
                        'leverage',
                        'intervalSeconds',
                      ].contains(item.key) &&
                      n != n.round()) {
                    return '请输入整数';
                  }
                  if (item.key == 'intervalSeconds' && n < 60) {
                    return '分析间隔至少 60 秒';
                  }
                  final order = num.tryParse(fields['maxOrderUsdt']!.text.trim());
                  final exposure = num.tryParse(
                    fields['maxExposureUsdt']!.text.trim(),
                  );
                  if (order != null && exposure != null && order > exposure) {
                    return '单笔敞口不能大于总敞口';
                  }
                  return null;
                },
              ),
            ),
          SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: widget.enabled ? save : null,
              child: const Text('保存风险参数'),
            ),
          ),
        ],
      ),
    ),
  );
}

class Surface extends StatelessWidget {
  final Widget child;
  const Surface({super.key, required this.child});
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(18),
    decoration: BoxDecoration(
      color: panel,
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: const Color(0xFF273538)),
    ),
    child: child,
  );
}

class BadgeLabel extends StatelessWidget {
  final String text;
  const BadgeLabel(this.text, {super.key});
  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
    decoration: BoxDecoration(
      color: const Color(0xFF294237),
      borderRadius: BorderRadius.circular(8),
    ),
    child: Text(
      text,
      style: const TextStyle(
        color: mint,
        fontSize: 12,
        fontWeight: FontWeight.w600,
      ),
    ),
  );
}

class Notice extends StatelessWidget {
  final String text;
  final bool error;
  const Notice(this.text, {super.key, this.error = false});
  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.only(bottom: 16),
    padding: const EdgeInsets.all(14),
    decoration: BoxDecoration(
      color: error ? const Color(0xFF41272A) : panel,
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      text,
      style: TextStyle(
        color: error ? const Color(0xFFFFB7B7) : muted,
        height: 1.5,
      ),
    ),
  );
}

class EmptyState extends StatelessWidget {
  final IconData icon;
  final String title, detail;
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    required this.detail,
  });
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 36),
    child: Column(
      children: [
        Icon(icon, size: 40, color: muted),
        const SizedBox(height: 16),
        Text(title, style: const TextStyle(fontSize: 18)),
        const SizedBox(height: 8),
        Text(
          detail,
          textAlign: TextAlign.center,
          style: const TextStyle(color: muted),
        ),
      ],
    ),
  );
}

Widget metric(String label, String value) => Column(
  crossAxisAlignment: CrossAxisAlignment.start,
  children: [
    Text(label, style: const TextStyle(color: muted, fontSize: 12)),
    const SizedBox(height: 7),
    Text(
      value,
      style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w600),
    ),
  ],
);
Widget infoRow(String label, String value) => Padding(
  padding: const EdgeInsets.symmetric(vertical: 8),
  child: Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Text(label, style: const TextStyle(color: muted)),
      const SizedBox(width: 14),
      Expanded(child: Text(value, textAlign: TextAlign.right)),
    ],
  ),
);
String money(dynamic value) => (num.tryParse('$value') ?? 0).toStringAsFixed(2);
String timeText(String value) {
  final t = DateTime.tryParse(value)?.toLocal();
  return t == null
      ? '—'
      : '${t.month}/${t.day} ${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}:${t.second.toString().padLeft(2, '0')}';
}

String actionLabel(String v) =>
    const {
      'HOLD': '观望',
      'OPEN_LONG': '开多',
      'OPEN_SHORT': '开空',
      'REDUCE': '减仓',
      'CLOSE': '平仓',
      'UPDATE_STOPS': '调整止盈止损',
    }[v] ??
    v;
String orderLabel(String v) =>
    const {
      'SUBMITTING': '正在提交',
      'UNKNOWN': '结果待确认 · 不会重试',
      'ACCEPTED': '已接受 · 待对账',
      'live': '等待成交',
      'partially_filled': '部分成交',
      'filled': '已成交',
      'canceled': '已撤销',
      'REJECTED': '已拒绝',
      'APPLIED': '修改已核对',
    }[v] ??
    v;
String eventLabel(String v) =>
    const {
      'NEWS_EVIDENCE': '资讯取样',
      'SETTINGS': '配置更新',
      'CONTROL': '运行控制',
      'ERROR': '异常暂停',
      'PREVIEW': '仅分析',
      'DECISION': 'AI 决策',
      'ORDER': '订单提交',
    }[v] ??
    v;
