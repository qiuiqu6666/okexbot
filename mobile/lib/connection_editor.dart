import 'package:flutter/material.dart';

class ConnectionEditor extends StatefulWidget {
  final Map<String, dynamic> initial;
  final bool enabled;
  final Future<bool> Function(Map<String, dynamic>) onSave;
  const ConnectionEditor({
    super.key,
    required this.initial,
    required this.enabled,
    required this.onSave,
  });
  @override
  State<ConnectionEditor> createState() => _ConnectionEditorState();
}

class _ConnectionEditorState extends State<ConnectionEditor> {
  late final Map<String, TextEditingController> fields;
  static const labels = {
    'okxKey': 'OKX API Key',
    'okxSecret': 'OKX Secret',
    'okxPassphrase': 'OKX Passphrase',
    'aiBaseUrl': '模型接口地址',
    'aiKey': '模型 API Key',
    'aiModel': '模型名称',
  };
  @override
  void initState() {
    super.initState();
    fields = {
      for (final key in labels.keys)
        key: TextEditingController(
          text: ['aiBaseUrl', 'aiModel'].contains(key)
              ? '${widget.initial[key] ?? ''}'
              : '',
        ),
    };
  }

  @override
  void dispose() {
    for (final c in fields.values) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> save() async {
    final success = await widget.onSave({
      for (final entry in fields.entries) entry.key: entry.value.text.trim(),
    });
    if (mounted && success) {
      for (final key in ['okxKey', 'okxSecret', 'okxPassphrase', 'aiKey']) {
        fields[key]!.clear();
      }
    }
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      const Text(
        '我的账户连接',
        style: TextStyle(fontSize: 19, fontWeight: FontWeight.w600),
      ),
      const SizedBox(height: 10),
      const Text(
        '密钥提交后由服务器加密保存，页面不回显。留空表示保留已保存的值。',
        style: TextStyle(color: Color(0xFF98A6AC), height: 1.5),
      ),
      const SizedBox(height: 16),
      for (final entry in labels.entries)
        Padding(
          padding: const EdgeInsets.only(bottom: 14),
          child: TextField(
            controller: fields[entry.key],
            enabled:
                widget.enabled && widget.initial['encryptionReady'] == true,
            obscureText: !['aiBaseUrl', 'aiModel'].contains(entry.key),
            autocorrect: false,
            enableSuggestions: false,
            decoration: InputDecoration(
              labelText: entry.value,
              helperText:
                  [
                    'okxKey',
                    'okxSecret',
                    'okxPassphrase',
                    'aiKey',
                  ].contains(entry.key)
                  ? (widget.initial[entry.key == 'aiKey'
                                ? 'aiConfigured'
                                : 'okxConfigured'] ==
                            true
                        ? '已保存，留空保留；填写可更新'
                        : '尚未配置')
                  : null,
            ),
          ),
        ),
      if (widget.initial['encryptionReady'] != true)
        const Padding(
          padding: EdgeInsets.only(bottom: 14),
          child: Text(
            '管理员需先配置服务端加密主密钥，才能保存连接。',
            style: TextStyle(color: Color(0xFFFFB7B7)),
          ),
        ),
      Text(
        '允许的模型地址：\n${(widget.initial['allowedAiBaseUrls'] as List? ?? []).join('\n')}',
        style: const TextStyle(color: Color(0xFF98A6AC), fontSize: 12),
      ),
      const SizedBox(height: 12),
      SizedBox(
        width: double.infinity,
        child: FilledButton(
          onPressed: widget.enabled && widget.initial['encryptionReady'] == true
              ? save
              : null,
          child: const Text('保存我的连接'),
        ),
      ),
    ],
  );
}
