# expo-shizuku

Expo native module for silent APK installation via [Shizuku](https://shizuku.rikka.app/) on Android.

## Installation

```sh
npx expo install expo-shizuku
```

## Requirements

- Expo SDK 51+
- Android only
- [Shizuku](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) app installed and running on the device

## Usage

```typescript
import * as Shizuku from 'expo-shizuku';

// Check if Shizuku is available
const status = await Shizuku.checkPermission();
// { status: 'granted' | 'not_granted' | 'not_running', granted: boolean }

// Request permission (shows Shizuku permission dialog)
const granted = await Shizuku.requestPermission();

// Install APK silently (no user confirmation needed)
const result = await Shizuku.installApk('/path/to/app.apk');
// { success: boolean, code: number }

// Convenience: check if ready for silent install
const available = await Shizuku.isAvailable();
```

## How it works

Shizuku runs a privileged process via ADB or root. This module uses Shizuku's binder IPC to call Android's `IPackageInstaller` API directly, bypassing the standard install confirmation dialog.

## License

MIT
