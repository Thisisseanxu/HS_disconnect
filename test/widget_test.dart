import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:hs_disconnect/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.thisisseanxu.hs_disconnect/control');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          if (call.method == 'getState') {
            return <String, dynamic>{
              'running': false,
              'blocking': false,
              'durationMs': 3000,
              'overlaySize': 64,
              'overlayGranted': false,
              'batteryOptimizationIgnored': false,
            };
          }
          return true;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows service controls', (tester) async {
    await tester.pumpWidget(const DisconnectApp());
    await tester.pump();

    expect(find.text('服务未启动'), findsOneWidget);
    expect(find.text('启动服务'), findsOneWidget);
  });
}
