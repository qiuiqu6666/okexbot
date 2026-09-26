import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

void main() => runApp(const PilotApp());
const mint = Color(0xFF9EEBC5),
    muted = Color(0xFF98A6AC),
    panel = Color(0xFF182326);

class PilotApp extends StatelessWidget {
  const PilotApp({super.key});
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
    home: const ConnectionPage(),
  );
}

class ApiError implements Exception {
  final String message;
  ApiError(this.message);
  @override
  String toString() => message;
}

class PilotApi {
  final String baseUrl, token;
  final http.Client client;
  PilotApi(this.baseUrl, this.token, {http.Client? client})
    : client = client ?? http.Client();
  Future<dynamic> request(
    String path, {
    String method = 'GET',
    Object? body,
  }) async {
    final req = http.Request(method, Uri.parse('$baseUrl/api$path'));
    req.headers.addAll({
      'Authorization': 'Bearer $token',
      'Content-Type': 'application/json',
    });
    if (body != null) req.body = jsonEncode(body);
    try {
      final response = await (() async => http.Response.fromStream(
        await client.send(req),
      ))().timeout(const Duration(seconds: 150));
      final dynamic data = jsonDecode(utf8.decode(response.bodyBytes));
      if (response.statusCode >= 400) {
        throw ApiError(data is Map ? '${data['message'] ?? '请求失败'}' : '请求失败');
      }
      return data;
    } on ApiError {
      rethrow;
    } catch (_) {
      throw ApiError('连接中断或响应无效。操作结果请刷新记录确认，不要重复提交。');
    }
  }

  void close() => client.close();
}

class ConnectionPage extends StatefulWidget {
  const ConnectionPage({super.key});
  @override
  State<ConnectionPage> createState() => _ConnectionPageState();
}

class _ConnectionPageState extends State<ConnectionPage> {
  final address = TextEditingController(
        text: const String.fromEnvironment('PILOT_URL'),
      ),
      token = TextEditingController();
  bool busy = false;
  String? error;
  @override
  void dispose() {
    address.dispose();
    token.dispose();
    super.dispose();
  }

  Future<void> connect() async {
    final url = address.text.trim().replaceAll(RegExp(r'/+$'), '');
    final uri = Uri.tryParse(url);
    if (uri == null ||
        !uri.hasAuthority ||
        uri.userInfo.isNotEmpty ||
        uri.hasQuery ||
        uri.hasFragment ||
        !(uri.scheme == 'https' || (kDebugMode && uri.scheme == 'http')) ||
        token.text.trim().length < 32) {
      setState(() => error = '请输入 HTTPS 服务地址和至少 32 字符的访问令牌；调试版支持本地 HTTP。');
      return;
    }
    setState(() {
      busy = true;
      error = null;
    });
    final api = PilotApi(url, token.text.trim());
    try {
      final status = await api.request('/status') as Map<String, dynamic>;
      if (!mounted) {
        api.close();
        return;
      }
      token.clear();
      await Navigator.of(context).push(
        MaterialPageRoute<void>(
          builder: (_) => Dashboard(api: api, initialStatus: status),
        ),
      );
    } catch (e) {
      api.close();
      if (mounted) setState(() => error = '$e');
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(28),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 460),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Icon(Icons.explore_outlined, size: 52, color: mint),
                const SizedBox(height: 28),
                const Text(
                  '仓位领航',
                  style: TextStyle(fontSize: 34, fontWeight: FontWeight.w700),
                ),
                const SizedBox(height: 10),
                const Text(
                  '让决策有依据，让风险有边界。',
                  style: TextStyle(color: muted, fontSize: 16),
                ),
                const SizedBox(height: 32),
                const BadgeLabel('OKX 模拟盘 · AI 仓位管理'),
                const SizedBox(height: 24),
                TextField(
                  controller: address,
                  autocorrect: false,
                  keyboardType: TextInputType.url,
                  decoration: const InputDecoration(
                    labelText: '服务地址',
                    hintText: 'https://your-server.example',
                  ),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: token,
                  obscureText: true,
                  autocorrect: false,
                  enableSuggestions: false,
                  decoration: const InputDecoration(
                    labelText: '访问令牌',
                    helperText: '令牌仅保存在本次会话中',
                  ),
                ),
                const SizedBox(height: 20),
                if (error != null) Notice(error!, error: true),
                SizedBox(
                  width: double.infinity,
                  child: FilledButton(
                    onPressed: busy ? null : connect,
                    child: Padding(
                      padding: const EdgeInsets.all(14),
                      child: Text(busy ? '正在连接…' : '连接控制台'),
                    ),
                  ),
                ),
                const SizedBox(height: 24),
                const Text(
                  '交易由你的服务器执行。OKX 和模型密钥只在服务器配置，手机不保存交易密钥。',
                  style: TextStyle(color: muted, height: 1.6),
                ),
              ],
            ),
          ),
        ),
      ),
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
  List<dynamic> events = [], orders = [];
  Timer? timer;
  int tab = 0;
  bool busy = false, refreshing = false, stopping = false;
  String? error;
  DateTime? syncedAt;
  @override
  void initState() {
    super.initState();
    status = widget.initialStatus;
    refresh();
    timer = Timer.periodic(const Duration(seconds: 10), (_) => refresh());
  }

  @override
  void dispose() {
    timer?.cancel();
    widget.api.close();
    super.dispose();
  }

  Future<void> refresh() async {
    if (refreshing || busy) return;
    refreshing = true;
    try {
      final results = await Future.wait([
        widget.api.request('/status'),
        widget.api.request('/events'),
        widget.api.request('/orders'),
      ]);
      if (mounted) {
        setState(() {
          status = results[0] as Map<String, dynamic>;
          events = results[1] as List;
          orders = results[2] as List;
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

  Future<void> act(
    String path, {
    Object? body,
    String method = 'POST',
    String success = '操作已完成',
  }) async {
    if (busy) return;
    setState(() {
      busy = true;
      error = null;
    });
    try {
      final result = await widget.api.request(path, method: method, body: body);
      if (!mounted) return;
      if (path == '/preview') {
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
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(success)));
      }
    } catch (e) {
      if (mounted) setState(() => error = '$e');
    } finally {
      if (mounted) {
        final savedError = error;
        setState(() => busy = false);
        await refresh();
        if (mounted && savedError != null) setState(() => error = savedError);
      }
    }
  }

  Future<void> pause() async {
    if (stopping) return;
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
        const BadgeLabel('模拟盘'),
        IconButton(
          tooltip: '断开控制台（不会暂停交易）',
          onPressed: () async {
            if (await confirm('断开控制台？', '断开手机不会停止服务器上的自动交易。需要停止时请先点击暂停。') &&
                context.mounted) {
              Navigator.pop(context);
            }
          },
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
        NavigationDestination(icon: Icon(Icons.tune), label: '设置'),
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
            const Text('USDT · OKX 模拟账户', style: TextStyle(color: muted)),
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
                              '开启模拟盘自动交易？',
                              'AI 将按当前风控参数自动开仓、减仓、平仓及调整保护单。关闭手机后服务器仍继续运行。',
                            )) {
                              await act('/start', success: '自动交易已开启');
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
        const Notice('连接尚未就绪。请在服务器配置 OKX 模拟盘凭据与模型接口，然后重启服务。'),
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
      const Text('单向持仓 · 逐仓模式', style: TextStyle(color: muted)),
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
          detail: snap == null ? '配置模拟盘后点击同步账户。' : '出现合适机会时，AI 会根据风险限制提出交易。',
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
                            '减仓一半？',
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
                            '平掉该仓位？',
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
            ],
          ),
        ),
      ),
  ];
  List<Widget> settings() => [
    const Text(
      '运行设置',
      style: TextStyle(fontSize: 26, fontWeight: FontWeight.w600),
    ),
    const SizedBox(height: 16),
    Surface(
      child: Column(
        children: [
          infoRow('交易环境', 'OKX 模拟盘'),
          infoRow('OKX 凭据', status['okxConfigured'] == true ? '已配置' : '未配置'),
          infoRow('模型接口', status['aiConfigured'] == true ? '已配置' : '未配置'),
          infoRow('持仓模式', '单向 · 逐仓'),
        ],
      ),
    ),
    const SizedBox(height: 16),
    SettingsEditor(
      key: ValueKey(jsonEncode(status['settings'])),
      initial: status['settings'] as Map<String, dynamic>,
      enabled: !busy && status['enabled'] != true,
      onSave: (s) =>
          act('/settings', method: 'PUT', body: s, success: '风险参数已保存'),
    ),
    const SizedBox(height: 16),
    const Notice('修改参数前请暂停自动交易。模型和 OKX 密钥在服务器环境变量中设置，不会通过此页面传回手机。'),
  ];
}

class SettingsEditor extends StatefulWidget {
  final Map<String, dynamic> initial;
  final bool enabled;
  final Future<void> Function(Map<String, dynamic>) onSave;
  const SettingsEditor({
    super.key,
    required this.initial,
    required this.enabled,
    required this.onSave,
  });
  @override
  State<SettingsEditor> createState() => _SettingsEditorState();
}

class _SettingsEditorState extends State<SettingsEditor> {
  final form = GlobalKey<FormState>();
  late final Map<String, TextEditingController> fields;
  static const labels = {
    'maxOrderUsdt': '单笔敞口上限 / USDT',
    'maxExposureUsdt': '总敞口上限 / USDT',
    'maxDailyLossPct': '日内权益损失上限 / %',
    'maxPositions': '最大持仓数量（1–5）',
    'leverage': '杠杆（1–3）',
    'intervalSeconds': '分析间隔 / 秒（至少 60）',
  };
  @override
  void initState() {
    super.initState();
    fields = {
      for (final key in labels.keys)
        key: TextEditingController(text: '${widget.initial[key]}'),
      'instruments': TextEditingController(
        text: (widget.initial['instruments'] as List).join(', '),
      ),
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
      'instruments': fields['instruments']!.text
          .toUpperCase()
          .split(RegExp(r'[,，\s]+'))
          .where((s) => s.isNotEmpty)
          .toList(),
    });
  }

  @override
  Widget build(BuildContext context) => Surface(
    child: Form(
      key: form,
      child: Column(
        children: [
          TextFormField(
            controller: fields['instruments'],
            enabled: widget.enabled,
            maxLines: 2,
            decoration: const InputDecoration(
              labelText: '合约白名单',
              helperText: '例：BTC-USDT-SWAP, ETH-USDT-SWAP',
            ),
            validator: (v) => v == null || v.trim().isEmpty ? '请填写合约' : null,
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
      'SETTINGS': '配置更新',
      'CONTROL': '运行控制',
      'ERROR': '异常暂停',
      'PREVIEW': '仅分析',
      'DECISION': 'AI 决策',
      'ORDER': '订单提交',
    }[v] ??
    v;
