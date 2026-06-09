import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const DisconnectApp());

class DisconnectApp extends StatelessWidget {
  const DisconnectApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '炉石拔线助手',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xff315f78),
          brightness: Brightness.dark,
        ),
        scaffoldBackgroundColor: const Color(0xff0c141b),
        useMaterial3: true,
      ),
      home: const ControlPage(),
    );
  }
}

class ControlPage extends StatefulWidget {
  const ControlPage({super.key});

  @override
  State<ControlPage> createState() => _ControlPageState();
}

class _ControlPageState extends State<ControlPage> {
  static const _channel = MethodChannel('com.example.hs_disconnect/control');
  Timer? _timer;
  bool _running = false;
  bool _blocking = false;
  bool _overlayGranted = false;
  bool _batteryIgnored = false;
  int _durationMs = 3000;
  double _size = 64;
  final _durationController = TextEditingController(text: '3000');
  final _durationFocus = FocusNode();

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    _durationController.dispose();
    _durationFocus.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final state = await _channel.invokeMapMethod<String, dynamic>('getState');
    if (!mounted || state == null) return;
    setState(() {
      _running = state['running'] as bool? ?? false;
      _blocking = state['blocking'] as bool? ?? false;
      _overlayGranted = state['overlayGranted'] as bool? ?? false;
      _batteryIgnored = state['batteryOptimizationIgnored'] as bool? ?? false;
      if (!_draggingDuration) {
        _durationMs = state['durationMs'] as int? ?? 3000;
        if (!_durationFocus.hasFocus) {
          _durationController.text = _durationMs.toString();
        }
      }
      if (!_draggingSize) {
        _size = (state['overlaySize'] as int? ?? 64).toDouble();
      }
    });
  }

  bool _draggingDuration = false;
  bool _draggingSize = false;

  Future<void> _toggleService() async {
    final ok = await _channel.invokeMethod<bool>(_running ? 'stop' : 'start');
    if (ok == false && mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请先允许 Android VPN 连接请求')));
    }
    await _refresh();
  }

  Future<void> _saveSettings() async {
    await _channel.invokeMethod('updateSettings', {
      'durationMs': _durationMs,
      'overlaySize': _size.round(),
    });
    await _refresh();
  }

  Future<void> _saveTypedDuration() async {
    final value = int.tryParse(_durationController.text.trim());
    if (value == null || value < 1) {
      _durationController.text = _durationMs.toString();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('至少断网 1 毫秒')));
      }
      return;
    }
    setState(() => _durationMs = value);
    await _saveSettings();
  }

  Future<void> _request(String method) async {
    await _channel.invokeMethod(method);
    await Future<void>.delayed(const Duration(milliseconds: 500));
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final statusColor = _blocking
        ? const Color(0xffff626d)
        : _running
        ? const Color(0xff78d6a4)
        : Colors.white54;
    return Scaffold(
      appBar: AppBar(
        title: const Text('炉石拔线助手'),
        backgroundColor: Colors.transparent,
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(20, 8, 20, 32),
        children: [
          Container(
            padding: const EdgeInsets.all(22),
            decoration: BoxDecoration(
              color: const Color(0xff13222c),
              borderRadius: BorderRadius.circular(24),
              border: Border.all(color: statusColor.withValues(alpha: .35)),
            ),
            child: Column(
              children: [
                Icon(
                  _blocking
                      ? Icons.signal_wifi_connected_no_internet_4
                      : Icons.shield_outlined,
                  size: 64,
                  color: statusColor,
                ),
                const SizedBox(height: 12),
                Text(
                  _blocking ? '正在连接' : (_running ? '悬浮重连按钮已就绪' : '服务未启动'),
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                Text(
                  _running
                      ? '已开启功能，点击悬浮按钮一键拔线'
                      : '点击按钮开启功能，请确保授予下方列出的权限，首次启动需请求 VPN 连接授权',
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white60),
                ),
                const SizedBox(height: 20),
                FilledButton.icon(
                  onPressed: _toggleService,
                  icon: Icon(
                    _running
                        ? Icons.stop_circle_outlined
                        : Icons.play_circle_outline,
                  ),
                  label: Text(_running ? '停止服务' : '启动服务'),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                    backgroundColor: _running ? const Color(0xff883f48) : null,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 26),
          Text('权限', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _PermissionTile(
            title: '悬浮窗',
            subtitle: _overlayGranted ? '已允许' : '需要允许，才能显示拔线按钮',
            granted: _overlayGranted,
            onTap: () => _request('requestOverlay'),
          ),
          _PermissionTile(
            title: '忽略电池优化',
            subtitle: _batteryIgnored ? '已允许' : '可减少后台服务被系统停止的概率',
            granted: _batteryIgnored,
            onTap: () => _request('requestBatteryOptimization'),
          ),
          const SizedBox(height: 24),
          Text('设置', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          _SettingCard(
            title: '拔线时长',
            value: '$_durationMs 毫秒',
            child: Column(
              children: [
                Slider(
                  min: 500,
                  max: 5000,
                  divisions: 9,
                  value: _durationMs.clamp(500, 5000).toDouble(),
                  label: '$_durationMs ms',
                  onChangeStart: (_) => _draggingDuration = true,
                  onChanged: (value) => setState(() {
                    _durationMs = value.round();
                    _durationController.text = _durationMs.toString();
                  }),
                  onChangeEnd: (_) {
                    _draggingDuration = false;
                    _saveSettings();
                  },
                ),
                TextField(
                  controller: _durationController,
                  focusNode: _durationFocus,
                  keyboardType: TextInputType.number,
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  decoration: const InputDecoration(
                    labelText: '手动输入',
                    suffixText: '毫秒',
                  ),
                  onSubmitted: (_) => _saveTypedDuration(),
                  onTapOutside: (_) {
                    _durationFocus.unfocus();
                    _saveTypedDuration();
                  },
                ),
              ],
            ),
          ),
          _SettingCard(
            title: '悬浮按钮大小',
            value: '${_size.round()} dp',
            child: Slider(
              min: 40,
              max: 120,
              divisions: 19,
              value: _size,
              onChangeStart: (_) => _draggingSize = true,
              onChanged: (value) => setState(() => _size = value),
              onChangeEnd: (_) {
                _draggingSize = false;
                _saveSettings();
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _PermissionTile extends StatelessWidget {
  const _PermissionTile({
    required this.title,
    required this.subtitle,
    required this.granted,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final bool granted;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        title: Text(title),
        subtitle: Text(subtitle),
        leading: Icon(
          granted ? Icons.check_circle : Icons.warning_amber_rounded,
        ),
        trailing: granted ? null : const Icon(Icons.chevron_right),
        onTap: granted ? null : onTap,
      ),
    );
  }
}

class _SettingCard extends StatelessWidget {
  const _SettingCard({
    required this.title,
    required this.value,
    required this.child,
  });

  final String title;
  final String value;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
        child: Column(
          children: [
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(title),
                Text(value, style: const TextStyle(color: Colors.white60)),
              ],
            ),
            child,
          ],
        ),
      ),
    );
  }
}
