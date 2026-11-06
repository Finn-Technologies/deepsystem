package app.finn.deepsystem.sandbox

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

data class SandboxRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val usedProot: Boolean = false
)

class LinuxSandboxManager(
    private val context: Context?,
    private val workspaceDir: File
) {
    private val tag = "LinuxSandboxManager"

    init {
        workspaceDir.mkdirs()
        File(workspaceDir, "tmp").mkdirs()
    }

    fun rootfsDir(): File? {
        val candidates = listOf(
            File(context?.filesDir, "rootfs"),
            File(context?.filesDir, "proot/rootfs"),
            File("/data/local/tmp/finn-rootfs")
        )
        return candidates.firstOrNull { it.isDirectory && File(it, "bin/sh").exists() }
    }

    fun isRootfsInstalled(): Boolean = rootfsDir() != null

    fun runCommand(
        command: String,
        timeoutMs: Long = 30_000L,
        allowNetwork: Boolean = false,
        maxOutputBytes: Int = 64 * 1024
    ): SandboxRunResult {
        val rootfs = rootfsDir()
        val useProot = rootfs != null && isProotAvailable()
        val processBuilder: ProcessBuilder = if (useProot) {
            ProcessBuilder(
                prootBinary(), "-r", rootfs!!.absolutePath,
                "-b", "${workspaceDir.absolutePath}:/workspace",
                "-w", "/workspace",
                "sh", "-c", command
            )
        } else {
            ProcessBuilder("sh", "-c", command)
        }
        // clean environment
        processBuilder.environment().clear()
        processBuilder.environment()["PATH"] = "/usr/bin:/bin:/usr/local/bin"
        processBuilder.environment()["HOME"] = workspaceDir.absolutePath
        processBuilder.environment()["TMPDIR"] = File(workspaceDir, "tmp").absolutePath
        if (!useProot) processBuilder.directory(workspaceDir)

        val process = processBuilder.start()
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                if (stdout.length < maxOutputBytes) stdout.append(line).append('\n')
            }
        }
        val stderrThread = Thread {
            process.errorStream.bufferedReader().forEachLine { line ->
                if (stderr.length < maxOutputBytes) stderr.append(line).append('\n')
            }
        }
        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        var timedOut = false
        if (!finished) {
            timedOut = true
            process.destroyForcibly()
        }
        stdoutThread.join(1000)
        stderrThread.join(1000)

        val truncated = stdout.length >= maxOutputBytes || stderr.length >= maxOutputBytes
        val exitCode = if (timedOut) 124 else try { process.exitValue() } catch (_: Exception) { -1 }
        Log.i(tag, "cmd='$command' exit=$exitCode timedOut=$timedOut proot=$useProot")
        return SandboxRunResult(exitCode, stdout.toString(), stderr.toString(), timedOut, truncated, useProot)
    }

    private fun isProotAvailable(): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v proot")).waitFor() == 0
        } catch (_: Exception) { false }
    }

    private fun prootBinary(): String = "proot"
}