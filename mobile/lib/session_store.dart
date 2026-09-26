import 'package:shared_preferences/shared_preferences.dart';

class SavedSession {
  final String baseUrl, token, username, environment;
  const SavedSession(this.baseUrl, this.token, this.username, this.environment);
}

class SessionStore {
  static const urlKey = 'pilot.baseUrl';
  static const tokenKey = 'pilot.token';
  static const userKey = 'pilot.username';
  static const envKey = 'pilot.environment';

  Future<SavedSession?> read() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final url = prefs.getString(urlKey) ?? '';
      final token = prefs.getString(tokenKey) ?? '';
      if (url.isEmpty || token.isEmpty) return null;
      final environment = prefs.getString(envKey) == 'LIVE' ? 'LIVE' : 'DEMO';
      return SavedSession(url, token, prefs.getString(userKey) ?? '', environment);
    } catch (_) {
      return null;
    }
  }

  Future<void> save(SavedSession session) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(urlKey, session.baseUrl);
      await prefs.setString(tokenKey, session.token);
      await prefs.setString(userKey, session.username);
      await prefs.setString(envKey, session.environment);
    } catch (_) {}
  }

  Future<void> clear() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(tokenKey);
      await prefs.remove(userKey);
    } catch (_) {}
  }
}
