package dev.sourcium.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExpoShizukuModule : Module() {

    private var pendingPermissionPromise: Promise? = null
    private val permissionRequestCode = 1001

    @Volatile
    private var shellService: IShellService? = null
    private val serviceLatch = CountDownLatch(1)

    private val permissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode == permissionRequestCode) {
                val promise = pendingPermissionPromise
                pendingPermissionPromise = null
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    promise?.resolve(true)
                } else {
                    promise?.resolve(false)
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            shellService = IShellService.Stub.asInterface(binder)
            serviceLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            shellService = null
        }
    }

    private val userServiceArgs by lazy {
        val context = appContext.reactContext ?: throw Exception("No context")
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("installer")
            .version(1)
    }

    override fun definition() = ModuleDefinition {
        Name("ExpoShizuku")

        OnCreate {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }

        OnDestroy {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            } catch (_: Exception) {}
        }

        AsyncFunction("checkPermission") {
            try {
                val isRunning = Shizuku.pingBinder()
                if (!isRunning) {
                    return@AsyncFunction mapOf(
                        "status" to "not_running",
                        "granted" to false
                    )
                }
                val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                return@AsyncFunction mapOf(
                    "status" to if (granted) "granted" else "not_granted",
                    "granted" to granted
                )
            } catch (e: Exception) {
                return@AsyncFunction mapOf(
                    "status" to "not_running",
                    "granted" to false
                )
            }
        }

        AsyncFunction("requestPermission") { promise: Promise ->
            try {
                if (!Shizuku.pingBinder()) {
                    promise.resolve(false)
                    return@AsyncFunction
                }
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    promise.resolve(true)
                    return@AsyncFunction
                }
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    promise.resolve(false)
                    return@AsyncFunction
                }
                pendingPermissionPromise = promise
                Shizuku.requestPermission(permissionRequestCode)
            } catch (e: Exception) {
                promise.resolve(false)
            }
        }

        AsyncFunction("installApk") { apkPath: String, promise: Promise ->
            try {
                if (!Shizuku.pingBinder()) {
                    throw CodedException("SHIZUKU_NOT_RUNNING", "Shizuku service is not running", null)
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw CodedException("SHIZUKU_NO_PERMISSION", "Shizuku permission not granted", null)
                }

                val result = installPackageViaShell(apkPath)
                promise.resolve(mapOf(
                    "success" to (result == 0),
                    "code" to result
                ))
            } catch (e: CodedException) {
                promise.reject(e)
            } catch (e: Exception) {
                promise.reject(
                    CodedException("INSTALL_FAILED", e.message ?: "Unknown error", e)
                )
            }
        }
    }

    /** Get or bind the ShellService running in Shizuku's privileged process. */
    private fun getShellService(): IShellService {
        shellService?.let { if (it.asBinder().pingBinder()) return it }

        // Bind the user service
        Shizuku.bindUserService(userServiceArgs, serviceConnection)

        // Wait for connection (up to 10 seconds)
        if (!serviceLatch.await(10, TimeUnit.SECONDS)) {
            throw Exception("Timed out waiting for Shizuku shell service")
        }

        return shellService ?: throw Exception("Shell service not connected")
    }

    /** Parse the result string from ShellService.exec(). Format: "exitCode\nstdout\nstderr" */
    private fun parseResult(result: String): ShellResult {
        val lines = result.split("\n", limit = 3)
        val exitCode = lines.getOrNull(0)?.toIntOrNull() ?: -1
        val stdout = lines.getOrNull(1) ?: ""
        val stderr = lines.getOrNull(2) ?: ""
        return ShellResult(exitCode, stdout.trim(), stderr.trim())
    }

    private fun installPackageViaShell(apkPath: String): Int {
        val context = appContext.reactContext
            ?: throw Exception("No React context available")

        // Resolve the file
        val file = when {
            apkPath.startsWith("content://") -> {
                val uri = Uri.parse(apkPath)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot read APK from content URI")
                val tempFile = File.createTempFile("shizuku_install", ".apk", context.cacheDir)
                tempFile.outputStream().use { out -> inputStream.copyTo(out) }
                inputStream.close()
                tempFile
            }
            apkPath.startsWith("file://") -> File(Uri.parse(apkPath).path!!)
            else -> File(apkPath)
        }

        if (!file.exists()) throw Exception("APK file not found: $apkPath")

        val service = getShellService()
        val fileSize = file.length()

        // Step 1: Create an install session
        val createResult = parseResult(service.exec("pm install-create --user current -S $fileSize"))
        if (createResult.exitCode != 0) {
            throw Exception("pm install-create failed: ${createResult.error.ifEmpty { createResult.output }}")
        }

        val sessionId = SESSION_ID_REGEX.find(createResult.output)?.value
            ?: throw Exception("Failed to parse session ID from: ${createResult.output}")

        try {
            // Step 2: Write the APK into the session via file descriptor
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val writeResult = parseResult(
                service.execWithStdin("pm install-write -S $fileSize $sessionId base.apk -", pfd)
            )
            if (writeResult.exitCode != 0) {
                throw Exception("pm install-write failed: ${writeResult.error.ifEmpty { writeResult.output }}")
            }

            // Step 3: Commit the session
            val commitResult = parseResult(service.exec("pm install-commit $sessionId"))
            if (commitResult.exitCode != 0) {
                throw Exception("pm install-commit failed: ${commitResult.error.ifEmpty { commitResult.output }}")
            }

            return 0
        } catch (e: Exception) {
            runCatching { service.exec("pm install-abandon $sessionId") }
            throw e
        }
    }

    companion object {
        private val SESSION_ID_REGEX = Regex("\\d+")
    }

    private data class ShellResult(val exitCode: Int, val output: String, val error: String)
}
