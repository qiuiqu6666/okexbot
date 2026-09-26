import 'package:flutter/material.dart';

class InstrumentPicker extends StatefulWidget {
  final Set<String> initial;
  final bool enabled;
  final Future<List<String>> Function() load;
  final ValueChanged<Set<String>> onChanged;
  const InstrumentPicker({
    super.key,
    required this.initial,
    required this.enabled,
    required this.load,
    required this.onChanged,
  });
  @override
  State<InstrumentPicker> createState() => _InstrumentPickerState();
}

class _InstrumentPickerState extends State<InstrumentPicker> {
  late Set<String> selected = {...widget.initial};
  bool loading = false;
  String? error;
  Future<void> choose(FormFieldState<Set<String>> field) async {
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final items = await widget.load();
      if (!mounted) return;
      if (items.isEmpty) throw Exception('交易所未返回币种，请重试');
      final result = await showDialog<Set<String>>(
        context: context,
        builder: (_) => _CoinDialog(items: items, initial: selected),
      );
      if (mounted && result != null) {
        setState(() => selected = result);
        field.didChange(selected);
        widget.onChanged({...selected});
      }
    } catch (_) {
      if (mounted) setState(() => error = '币种列表加载失败，请检查后端到 OKX 的网络后重试。已选币种保留。');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => FormField<Set<String>>(
    initialValue: selected,
    validator: (value) =>
        value == null || value.isEmpty ? '请至少选择 1 个交易币种' : null,
    builder: (field) => Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          '交易币种 · USDT 永续',
          style: TextStyle(fontSize: 18, fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 8),
        Text('已选 ${selected.length} 个；保存风险参数后生效'),
        Wrap(
          spacing: 8,
          children: [
            for (final id in selected)
              InputChip(
                label: Text(id.replaceAll('-USDT-SWAP', '')),
                onDeleted: widget.enabled && !loading
                    ? () {
                        setState(() => selected.remove(id));
                        field.didChange({...selected});
                        widget.onChanged({...selected});
                      }
                    : null,
              ),
          ],
        ),
        OutlinedButton.icon(
          onPressed: widget.enabled && !loading ? () => choose(field) : null,
          icon: const Icon(Icons.checklist),
          label: Text(loading ? '正在加载币种…' : '选择交易币种'),
        ),
        if (error != null)
          Text(error!, style: const TextStyle(color: Colors.orangeAccent)),
        if (field.hasError)
          Text(
            field.errorText!,
            style: const TextStyle(color: Colors.redAccent),
          ),
      ],
    ),
  );
}

class _CoinDialog extends StatefulWidget {
  final List<String> items;
  final Set<String> initial;
  const _CoinDialog({required this.items, required this.initial});
  @override
  State<_CoinDialog> createState() => _CoinDialogState();
}

class _CoinDialogState extends State<_CoinDialog> {
  late final Set<String> selected = {...widget.initial};
  String query = '';
  @override
  Widget build(BuildContext context) {
    final items = {
      ...selected,
      ...widget.items,
    }.where((id) => id.contains(query.toUpperCase())).toList()..sort();
    return AlertDialog(
      title: Text('选择交易币种（已选 ${selected.length}）'),
      content: SizedBox(
        width: 460,
        height: 420,
        child: Column(
          children: [
            TextField(
              autocorrect: false,
              decoration: const InputDecoration(labelText: '搜索币种，例如 BTC'),
              onChanged: (value) => setState(() => query = value.trim()),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: items.isEmpty
                  ? const Center(child: Text('没有匹配的币种'))
                  : ListView.builder(
                      itemCount: items.length,
                      itemBuilder: (context, index) {
                        final id = items[index],
                            checked = selected.contains(id),
                            available = widget.items.contains(id);
                        return CheckboxListTile(
                          value: checked,
                          title: Text(id.replaceAll('-USDT-SWAP', '')),
                          subtitle: Text(available ? id : '$id · 当前不可用，可取消选择'),
                          onChanged:
                              checked || available
                              ? (value) => setState(() {
                                  if (value == true) {
                                    selected.add(id);
                                  } else {
                                    selected.remove(id);
                                  }
                                })
                              : null,
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed:
              selected.isNotEmpty && selected.every(widget.items.contains)
              ? () => Navigator.pop(context, {...selected})
              : null,
          child: const Text('确定'),
        ),
      ],
    );
  }
}
