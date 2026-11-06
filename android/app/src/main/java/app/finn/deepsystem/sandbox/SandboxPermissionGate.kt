package app.finn.deepsystem.sandbox

import app.finn.deepsystem.background.NetworkLedger
import java.io.File

data class SandboxPermissions(
    val networkAllowed: Boolean = false,
    val fileWriteAllowed: Boolean = true,
    val processSpawnAllowed: Boolean = true,
    val timeoutMs: Long = 30_000L,
    val maxOutputBytes: Int = 64 * 1024,
    val allowedCommands: Set<String> = emptySet()
)

class SandboxPermissionGate(
    private val ledger: NetworkLedger? = null
) {
    fun checkProcessSpawn(perms: SandboxPermissions): Boolean = perms.processSpawnAllowed

    fun checkFileWrite(path: String, workspace: File, perms: SandboxPermissions): Boolean {
        if (!perms.fileWriteAllowed) return false
        val resolved = File(workspace, path).canonicalFile
        val base = workspace.canonicalFile
        return resolved.path.startsWith(base.path + File.separator) || resolved == base
    }

    fun checkCommand(command: String, perms: SandboxPermissions): Boolean {
        if (perms.allowedCommands.isEmpty()) return true
        val first = command.trim().split(Regex("\\s+")).firstOrNull()?.substringAfterLast('/') ?: return true
        return first in perms.allowedCommands
    }

    fun requestNetwork(url: String, purpose: String, perms: SandboxPermissions): Boolean {
        if (!perms.networkAllowed) return false
        ledger?.logStart(url, purpose)
        return true
    }

    fun logNetworkSuccess(id: String, bytes: Long) = ledger?.logSuccess(id, bytes)
    fun logNetworkFailure(id: String, error: String) = ledger?.logFailure(id, error)
}