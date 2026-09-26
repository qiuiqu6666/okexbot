import 'dart:convert';
import 'package:flutter/material.dart';
import 'api.dart';

class AuthPage extends StatefulWidget {
  final Widget Function(PilotApi, Map<String, dynamic>) dashboardBuilder;
  final PilotApi Function(String)? createApi;
  const AuthPage({super.key, required this.dashboardBuilder, this.createApi});
  @override
  State<AuthPage> createState() => _AuthPageState();
}

class _AuthPageState extends State<AuthPage> {
  final form = GlobalKey<FormState>();
  final address = TextEditingController(
    text: const String.fromEnvironment(
      'PILOT_URL',
      defaultValue: 'http://47.76.193.239:8080',
    ),
  );
  final username = TextEditingController(),
      password = TextEditingController(),
      confirmation = TextEditingController();
  bool register = false, busy = false, showPassword = false;
  String? error;
  @override
  void dispose() {
    address.dispose();
    username.dispose();
    password.dispose();
    confirmation.dispose();
    super.dispose();
  }

  Future<void> submit() async {
    if (!form.currentState!.validate()) return;
    String url;
    try {
      url = normalizeServerAddress(address.text);
    } catch (e) {
      setState(() => error = '$e');
      return;
    }
    FocusManager.instance.primaryFocus?.unfocus();
    setState(() {
      busy = true;
      error = null;
    });
    final api = widget.createApi?.call(url) ?? PilotApi(url);
    try {
      await api.authenticate(
        username.text.trim(),
        password.text,
        register: register,
      );
      password.clear();
      confirmation.clear();
      final status = await api.request('/status') as Map<String, dynamic>;
      if (!mounted) {
        api.close();
        return;
      }
      final message = await Navigator.of(context).push<String>(
        MaterialPageRoute(builder: (_) => widget.dashboardBuilder(api, status)),
      );
      if (mounted) {
        setState(() {
          register = false;
          error = message;
        });
      }
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
            child: Form(
              key: form,
              child: AutofillGroup(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Icon(
                      Icons.explore_outlined,
                      size: 48,
                      color: Color(0xFF9EEBC5),
                    ),
                    const SizedBox(height: 22),
                    const Text(
                      '仓位领航',
                      style: TextStyle(
                        fontSize: 34,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      register ? '创建账号，开始管理你的模拟盘。' : '登录你的账户，掌握每一笔交易。',
                      style: const TextStyle(
                        color: Color(0xFF98A6AC),
                        fontSize: 15,
                      ),
                    ),
                    const SizedBox(height: 28),
                    Text(
                      register ? '注册账号' : '欢迎回来',
                      style: const TextStyle(
                        fontSize: 23,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                    const SizedBox(height: 18),
                    TextFormField(
                      key: const Key('username'),
                      controller: username,
                      enabled: !busy,
                      autocorrect: false,
                      autofillHints: const [AutofillHints.username],
                      textInputAction: TextInputAction.next,
                      decoration: const InputDecoration(
                        labelText: '用户名',
                        prefixIcon: Icon(Icons.person_outline),
                        helperText: '3–32 位字母、数字或下划线，不区分大小写',
                      ),
                      validator: (v) =>
                          RegExp(
                            r'^[A-Za-z0-9_]{3,32}$',
                          ).hasMatch(v?.trim() ?? '')
                          ? null
                          : '请输入 3–32 位有效用户名',
                    ),
                    const SizedBox(height: 16),
                    TextFormField(
                      key: const Key('password'),
                      controller: password,
                      enabled: !busy,
                      obscureText: !showPassword,
                      autocorrect: false,
                      enableSuggestions: false,
                      autofillHints: [
                        register
                            ? AutofillHints.newPassword
                            : AutofillHints.password,
                      ],
                      textInputAction: register
                          ? TextInputAction.next
                          : TextInputAction.done,
                      onFieldSubmitted: (_) {
                        if (!register && !busy) submit();
                      },
                      decoration: InputDecoration(
                        labelText: '密码',
                        prefixIcon: const Icon(Icons.lock_outline),
                        helperText: register ? '8–64 位字符' : null,
                        suffixIcon: IconButton(
                          tooltip: showPassword ? '隐藏密码' : '显示密码',
                          onPressed: () =>
                              setState(() => showPassword = !showPassword),
                          icon: Icon(
                            showPassword
                                ? Icons.visibility_off_outlined
                                : Icons.visibility_outlined,
                          ),
                        ),
                      ),
                      validator: (v) =>
                          v != null &&
                              v.length >= 8 &&
                              v.length <= 64 &&
                              utf8.encode(v).length <= 72
                          ? null
                          : '密码须为 8–64 位，最多 72 字节',
                    ),
                    if (register) ...[
                      const SizedBox(height: 16),
                      TextFormField(
                        key: const Key('confirmPassword'),
                        controller: confirmation,
                        enabled: !busy,
                        obscureText: true,
                        autocorrect: false,
                        enableSuggestions: false,
                        decoration: const InputDecoration(
                          labelText: '确认密码',
                          prefixIcon: Icon(Icons.lock_outline),
                        ),
                        validator: (v) => v == password.text ? null : '两次密码不一致',
                      ),
                    ],
                    const SizedBox(height: 20),
                    if (error != null)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 16),
                        child: Text(
                          error!,
                          style: const TextStyle(color: Color(0xFFFFB7B7)),
                        ),
                      ),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton(
                        key: const Key('authSubmit'),
                        onPressed: busy ? null : submit,
                        child: Padding(
                          padding: const EdgeInsets.all(14),
                          child: Text(
                            busy
                                ? '正在连接…'
                                : register
                                ? '注册并登录'
                                : '登录',
                          ),
                        ),
                      ),
                    ),
                    Center(
                      child: TextButton(
                        key: const Key('authToggle'),
                        onPressed: busy
                            ? null
                            : () {
                                form.currentState?.reset();
                                setState(() {
                                  register = !register;
                                  error = null;
                                  password.clear();
                                  confirmation.clear();
                                });
                              },
                        child: Text(register ? '已有账号？登录' : '没有账号？注册'),
                      ),
                    ),
                    const SizedBox(height: 8),
                    ExpansionTile(
                      tilePadding: EdgeInsets.zero,
                      childrenPadding: const EdgeInsets.only(bottom: 14),
                      title: const Text(
                        '服务器设置',
                        style: TextStyle(fontSize: 14),
                      ),
                      subtitle: Text(
                        address.text,
                        style: const TextStyle(
                          color: Color(0xFF98A6AC),
                          fontSize: 12,
                        ),
                      ),
                      children: [
                        TextField(
                          key: const Key('serverAddress'),
                          controller: address,
                          enabled: !busy,
                          autocorrect: false,
                          keyboardType: TextInputType.url,
                          onChanged: (_) => setState(() {}),
                          decoration: const InputDecoration(
                            labelText: '后端 IP:端口',
                            hintText: '47.76.193.239:8080',
                            helperText: '支持 HTTP / HTTPS；公网建议使用 HTTPS',
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 10),
                    const Text(
                      '登录后使用你自己的账户配置。退出登录不会自动平仓或停止服务器上的交易。',
                      style: TextStyle(
                        color: Color(0xFF98A6AC),
                        height: 1.6,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
}
