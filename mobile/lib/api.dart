import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;

class ApiError implements Exception {
  final String message;
  final int? statusCode;
  ApiError(this.message, [this.statusCode]);
  @override
  String toString() => message;
}

String normalizeServerAddress(String input) {
  var text = input.trim().replaceAll(RegExp(r'/+$'), '');
  if (!text.contains('://')) text = 'http://$text';
  final uri = Uri.tryParse(text);
  if (uri == null ||
      !['http', 'https'].contains(uri.scheme) ||
      uri.host.isEmpty ||
      uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment ||
      (uri.path.isNotEmpty && uri.path != '/') ||
      uri.port < 1 ||
      uri.port > 65535) {
    throw ApiError('请输入有效的后端 IP:端口，例如 47.76.193.239:8080');
  }
  return text;
}

class PilotApi {
  final String baseUrl;
  String _token;
  String username = '';
  String environment = 'DEMO';
  final http.Client client;
  void Function()? onUnauthorized;
  PilotApi(this.baseUrl, [String token = '', http.Client? transport])
    : _token = token,
      client = transport ?? http.Client();

  Future<void> authenticate(
    String username,
    String password, {
    required bool register,
  }) async {
    final result = await request(
      register ? '/auth/register' : '/auth/login',
      method: 'POST',
      body: {'username': username, 'password': password},
    );
    if (result is! Map ||
        result['token'] is! String ||
        result['user'] is! Map ||
        !(result['token'] as String).contains(RegExp(r'^[A-Za-z0-9_-]{43}$'))) {
      throw ApiError('服务端尚未升级到用户名密码登录版本，或登录响应无效');
    }
    _token = result['token'] as String;
    this.username = '${result['user']['username']}';
  }

  Future<dynamic> request(
    String path, {
    String method = 'GET',
    Object? body,
  }) async {
    final req = http.Request(method, Uri.parse('$baseUrl/api$path'));
    req.followRedirects = false;
    req.headers['Content-Type'] = 'application/json';
    req.headers['X-Trading-Environment'] = environment;
    if (_token.isNotEmpty) req.headers['Authorization'] = 'Bearer $_token';
    if (body != null) req.body = jsonEncode(body);
    try {
      final response = await (() async => http.Response.fromStream(
        await client.send(req),
      ))().timeout(const Duration(seconds: 150));
      if (response.statusCode == 401 && _token.isNotEmpty) {
        _token = '';
        onUnauthorized?.call();
        throw ApiError('登录已过期，请重新登录', 401);
      }
      if (response.statusCode >= 300 && response.statusCode < 400) {
        throw ApiError('服务器返回重定向，请填写最终后端地址');
      }
      final dynamic data = jsonDecode(utf8.decode(response.bodyBytes));
      if (response.statusCode >= 400) {
        throw ApiError(
          data is Map ? '${data['message'] ?? '请求失败'}' : '请求失败',
          response.statusCode,
        );
      }
      return data;
    } on ApiError {
      rethrow;
    } catch (_) {
      throw ApiError('无法连接服务器或响应无效。请检查 IP、端口和网络；交易操作结果请刷新记录确认。');
    }
  }

  Future<void> logout() async {
    await request('/auth/logout', method: 'POST');
    _token = '';
  }

  void close() {
    _token = '';
    onUnauthorized = null;
    client.close();
  }
}
