import 'SidecarBridge.dart';

enum BridgeType { jni, sidecar }

class BridgeDispatcher {
  static final BridgeDispatcher _instance = BridgeDispatcher._internal();
  factory BridgeDispatcher() => _instance;
  BridgeDispatcher._internal();

  BridgeType get mode => BridgeType.sidecar;

  void setMode(BridgeType mode) {}

  Future<void> initialize(String bridgeJarPath) async {
    await SidecarBridge().initialize(bridgeJarPath);
  }

  Future<dynamic> invokeMethod(
    String method,
    Map<String, dynamic> args, {
    Duration timeout = const Duration(seconds: 60),
  }) async {
    return await SidecarBridge().invokeMethod(method, args, timeout: timeout);
  }

  Stream<dynamic> invokeStreamMethod(String method, Map<String, dynamic> args) {
    return SidecarBridge().invokeStreamMethod(method, args);
  }

  Future<bool> cancelRequest(String id) async {
    return SidecarBridge().cancelRequest(id);
  }

  void dispose() {
    SidecarBridge().dispose();
  }
}
