import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'l10n/app_localizations.dart';
import 'package:shared_preferences/shared_preferences.dart';

const List<Locale> kSupportedLocales = <Locale>[
  Locale('zh', 'CN'),
  Locale('en', 'US'),
];

const String _kLocalePrefKey = 'app_locale';
const MethodChannel _kNativeChannel = MethodChannel(
  'com.example.hs_disconnect/control',
);

class LocaleNotifier extends ValueNotifier<Locale?> {
  LocaleNotifier._() : super(null);

  static final LocaleNotifier instance = LocaleNotifier._();

  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    value = _parse(prefs.getString(_kLocalePrefKey));
    await _pushToNative(value);
  }

  Future<void> set(Locale? locale) async {
    value = locale;
    final prefs = await SharedPreferences.getInstance();
    if (locale == null) {
      await prefs.remove(_kLocalePrefKey);
    } else {
      await prefs.setString(_kLocalePrefKey, _stringify(locale));
    }
    await _pushToNative(locale);
  }

  Future<void> _pushToNative(Locale? locale) async {
    final tag = locale == null ? '' : _stringify(locale);
    try {
      await _kNativeChannel.invokeMethod<bool>('setLocale', {'tag': tag});
    } catch (_) {}
  }

  static String _stringify(Locale locale) {
    final country = locale.countryCode;
    if (country == null || country.isEmpty) return locale.languageCode;
    return '${locale.languageCode}_$country';
  }

  static Locale? _parse(String? raw) {
    if (raw == null || raw.isEmpty) return null;
    final parts = raw.split('_');
    if (parts.isEmpty) return null;
    return Locale(parts[0], parts.length > 1 ? parts[1] : null);
  }
}

Locale _resolveLocale(List<Locale>? preferred, Iterable<Locale> supported) {
  if (preferred != null) {
    for (final want in preferred) {
      for (final s in supported) {
        if (s.languageCode == want.languageCode) return s;
      }
    }
  }
  return const Locale('en', 'US');
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await LocaleNotifier.instance.load();
  runApp(const DisconnectApp());
}

class DisconnectApp extends StatelessWidget {
  const DisconnectApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<Locale?>(
      valueListenable: LocaleNotifier.instance,
      builder: (context, locale, _) {
        return MaterialApp(
          debugShowCheckedModeBanner: false,
          onGenerateTitle: (ctx) => AppLocalizations.of(ctx).appTitle,
          locale: locale,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: kSupportedLocales,
          localeListResolutionCallback: _resolveLocale,
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
      },
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
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(AppLocalizations.of(context).errorNeedVpnPermission),
        ),
      );
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
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(AppLocalizations.of(context).errorMinDuration),
          ),
        );
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
    final l10n = AppLocalizations.of(context);
    final statusColor = _blocking
        ? const Color(0xffff626d)
        : _running
        ? const Color(0xff78d6a4)
        : Colors.white54;
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.appTitle),
        backgroundColor: Colors.transparent,
        actions: const [_LanguageMenuButton()],
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
                  _blocking
                      ? l10n.statusBlocking
                      : (_running ? l10n.statusReady : l10n.statusStopped),
                  style: Theme.of(context).textTheme.titleLarge,
                ),
                const SizedBox(height: 8),
                Text(
                  _running ? l10n.descriptionEnabled : l10n.descriptionDisabled,
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
                  label: Text(_running ? l10n.stopService : l10n.startService),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(52),
                    backgroundColor: _running ? const Color(0xff883f48) : null,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 26),
          Text(
            l10n.permissionsSection,
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          _PermissionTile(
            title: l10n.permissionOverlayTitle,
            subtitle: _overlayGranted
                ? l10n.permissionGranted
                : l10n.permissionOverlayHint,
            granted: _overlayGranted,
            onTap: () => _request('requestOverlay'),
          ),
          _PermissionTile(
            title: l10n.permissionBatteryTitle,
            subtitle: _batteryIgnored
                ? l10n.permissionGranted
                : l10n.permissionBatteryHint,
            granted: _batteryIgnored,
            onTap: () => _request('requestBatteryOptimization'),
          ),
          const SizedBox(height: 24),
          Text(
            l10n.settingsSection,
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: 8),
          _SettingCard(
            title: l10n.disconnectDuration,
            value: l10n.durationValue(_durationMs),
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
                  decoration: InputDecoration(
                    labelText: l10n.manualInput,
                    suffixText: l10n.millisSuffix,
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
            title: l10n.overlaySize,
            value: l10n.overlaySizeValue(_size.round()),
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

class _LanguageOption {
  const _LanguageOption(this.locale);
  final Locale? locale;
}

class _LanguageMenuButton extends StatelessWidget {
  const _LanguageMenuButton();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return ValueListenableBuilder<Locale?>(
      valueListenable: LocaleNotifier.instance,
      builder: (context, current, _) {
        return PopupMenuButton<_LanguageOption>(
          icon: const Icon(Icons.language),
          tooltip: l10n.languageMenuTooltip,
          onSelected: (opt) => LocaleNotifier.instance.set(opt.locale),
          itemBuilder: (context) => [
            _buildItem(const _LanguageOption(null), l10n.languageSystem, current),
            _buildItem(
              const _LanguageOption(Locale('zh', 'CN')),
              l10n.languageChinese,
              current,
            ),
            _buildItem(
              const _LanguageOption(Locale('en', 'US')),
              l10n.languageEnglish,
              current,
            ),
          ],
        );
      },
    );
  }

  PopupMenuItem<_LanguageOption> _buildItem(
    _LanguageOption opt,
    String label,
    Locale? current,
  ) {
    final selected = opt.locale == null
        ? current == null
        : current?.languageCode == opt.locale!.languageCode &&
              current?.countryCode == opt.locale!.countryCode;
    return PopupMenuItem<_LanguageOption>(
      value: opt,
      child: Row(
        children: [
          Icon(
            Icons.check,
            size: 18,
            color: selected ? null : Colors.transparent,
          ),
          const SizedBox(width: 12),
          Text(label),
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
