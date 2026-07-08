import 'package:flutter/material.dart';
import 'package:droiddesk/services/platform_bridge.dart';

/// Central state management for the entire DroidDesk app.
class AppState extends ChangeNotifier {
  // ── Setup State ──
  bool _isBootstrapped = false;
  bool _isRunning = false;
  String _installedDistro = '';
  String _selectedDistro = 'ubuntu';
  String _selectedDE = 'xfce4';
  int _setupStep = 0; // 0=welcome, 1=distro, 2=de, 3=download, 4=install, 5=done

  // ── Download/Install Progress ──
  double _downloadProgress = 0.0;
  String _downloadStatus = '';
  double _extractProgress = 0.0;
  String _extractStatus = '';
  bool _isDownloading = false;
  bool _isExtracting = false;
  final bool _isInstallingDE = false;

  // ── Device Info ──
  Map<String, dynamic> _deviceInfo = {};

  // ── Error State ──
  String? _errorMessage;

  // ── Getters ──
  bool get isBootstrapped => _isBootstrapped;
  bool get isRunning => _isRunning;
  String get installedDistro => _installedDistro;
  String get selectedDistro => _selectedDistro;
  String get selectedDE => _selectedDE;
  int get setupStep => _setupStep;
  double get downloadProgress => _downloadProgress;
  String get downloadStatus => _downloadStatus;
  double get extractProgress => _extractProgress;
  String get extractStatus => _extractStatus;
  bool get isDownloading => _isDownloading;
  bool get isExtracting => _isExtracting;
  bool get isInstallingDE => _isInstallingDE;
  Map<String, dynamic> get deviceInfo => _deviceInfo;
  String? get errorMessage => _errorMessage;

  bool get isSetupComplete => _isBootstrapped && _installedDistro.isNotEmpty;

  String get gpuType {
    final vendor = _deviceInfo['gpuVendor']?.toString() ?? '';
    if (vendor.contains('adreno')) return 'Adreno (Hardware Accelerated)';
    if (vendor.contains('mali')) return 'Mali (Software Fallback)';
    if (vendor.contains('powervr')) return 'PowerVR (Software Fallback)';
    return 'Unknown (Software Fallback)';
  }

  // ── Initialization ──

  Future<void> initialize() async {
    // Set up progress callbacks
    DroidDeskPlatform.onDownloadProgress = (progress, status) {
      _downloadProgress = progress;
      _downloadStatus = status;
      if (progress < 0) {
        _isDownloading = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        if (_isDownloading) {
          _isDownloading = false;
          runExtraction();
        }
      }
      notifyListeners();
    };

    DroidDeskPlatform.onExtractProgress = (progress, status) {
      _extractProgress = progress;
      _extractStatus = status;
      if (progress < 0) {
        _isExtracting = false;
        _errorMessage = status;
      } else if (progress >= 1.0) {
        if (_isExtracting) {
          _isExtracting = false;
          refreshStatus(); // Updates _isBootstrapped and completes setup
        }
      }
      notifyListeners();
    };

    await refreshStatus();
    await loadDeviceInfo();
  }

  Future<void> refreshStatus() async {
    try {
      final status = await DroidDeskPlatform.getRuntimeStatus();
      _isBootstrapped = status['isBootstrapped'] as bool? ?? false;
      _isRunning = status['isRunning'] as bool? ?? false;
      _installedDistro = status['distro'] as String? ?? '';
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to get runtime status: $e';
      notifyListeners();
    }
  }

  Future<void> loadDeviceInfo() async {
    try {
      _deviceInfo = await DroidDeskPlatform.getDeviceInfo();
      notifyListeners();
    } catch (e) {
      // Non-fatal — continue without device info
    }
  }

  // ── Setup Flow ──

  void setSelectedDistro(String distro) {
    _selectedDistro = distro;
    notifyListeners();
  }

  void setSelectedDE(String de) {
    _selectedDE = de;
    notifyListeners();
  }

  void setSetupStep(int step) {
    _setupStep = step;
    _errorMessage = null;
    notifyListeners();
  }

  Future<void> runSetup() async {
    try {
      _errorMessage = null;

      // Step 1: Bootstrap (extract proot binary)
      _setupStep = 3;
      notifyListeners();
      await DroidDeskPlatform.setupBootstrap();

      // Step 2: Download rootfs
      _isDownloading = true;
      _downloadProgress = 0.0;
      notifyListeners();
      await DroidDeskPlatform.downloadRootfs(_selectedDistro);

      // Download runs in background — callbacks update progress
      // Wait for download completion is handled by the callback

    } catch (e) {
      _errorMessage = 'Setup failed: $e';
      _isDownloading = false;
      notifyListeners();
    }
  }

  Future<void> runExtraction() async {
    try {
      _isExtracting = true;
      _extractProgress = 0.0;
      _errorMessage = null;
      notifyListeners();
      await DroidDeskPlatform.extractRootfs();
    } catch (e) {
      _errorMessage = 'Extraction failed: $e';
      _isExtracting = false;
      notifyListeners();
    }
  }

  // ── Session Control ──

  Future<void> startLinux() async {
    try {
      _errorMessage = null;
      await DroidDeskPlatform.startLinux(de: _selectedDE);
      _isRunning = true;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to start: $e';
      notifyListeners();
    }
  }

  Future<void> stopLinux() async {
    try {
      await DroidDeskPlatform.stopLinux();
      _isRunning = false;
      notifyListeners();
    } catch (e) {
      _errorMessage = 'Failed to stop: $e';
      notifyListeners();
    }
  }

  Future<String> executeCommand(String command) async {
    try {
      return await DroidDeskPlatform.executeCommand(command);
    } catch (e) {
      return 'Error: $e';
    }
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }
}
