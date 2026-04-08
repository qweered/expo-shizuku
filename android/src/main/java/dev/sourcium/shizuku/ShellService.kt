package dev.sourcium.shizuku

import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * UserService implementation that runs in Shizuku's privileged process.
 * Executes shell commands with elevated (shell/root) privileges.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = process.waitFor()
        return "$exitCode\n$stdout\n$stderr"
    }

    override fun execWithStdin(command: String, fd: ParcelFileDescriptor): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        // Write the file descriptor content to process stdin
        val input = ParcelFileDescriptor.AutoCloseInputStream(fd)
        process.outputStream.use { out -> input.copyTo(out) }
        input.close()

        val stdout = process.inputStream.bufferedReader().use(BufferedReader::readText)
        val stderr = process.errorStream.bufferedReader().use(BufferedReader::readText)
        val exitCode = process.waitFor()
        return "$exitCode\n$stdout\n$stderr"
    }
}
