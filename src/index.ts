import { requireNativeModule } from 'expo';

interface ShizukuPermissionResult {
  status: 'granted' | 'not_granted' | 'not_running';
  granted: boolean;
}

interface ShizukuInstallResult {
  success: boolean;
  code: number;
}

const ExpoShizuku = requireNativeModule<{
  checkPermission(): Promise<ShizukuPermissionResult>;
  requestPermission(): Promise<boolean>;
  installApk(apkPath: string): Promise<ShizukuInstallResult>;
}>('ExpoShizuku');

/** Check if Shizuku is running and if permission is granted. */
export async function checkPermission(): Promise<ShizukuPermissionResult> {
  return ExpoShizuku.checkPermission();
}

/** Request Shizuku permission. Returns true if granted. */
export async function requestPermission(): Promise<boolean> {
  return ExpoShizuku.requestPermission();
}

/** Install an APK silently via Shizuku's privileged PackageInstaller. */
export async function installApk(apkPath: string): Promise<ShizukuInstallResult> {
  return ExpoShizuku.installApk(apkPath);
}

/** Check if Shizuku is available (running + permission granted). */
export async function isAvailable(): Promise<boolean> {
  const result = await checkPermission();
  return result.granted;
}
