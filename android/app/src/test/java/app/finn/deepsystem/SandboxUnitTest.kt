package app.finn.deepsystem

import app.finn.deepsystem.sandbox.LinuxSandboxManager
import app.finn.deepsystem.sandbox.SandboxPermissionGate
import app.finn.deepsystem.sandbox.SandboxPermissions
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SandboxUnitTest {
    @Test fun permissionGateNetworkDeniedByDefault() {
        val gate = SandboxPermissionGate(null)
        val perms = SandboxPermissions(networkAllowed = false)
        assertFalse(gate.requestNetwork("https://example.com", "test", perms))
    }

    @Test fun permissionGateNetworkAllowed() {
        val gate = SandboxPermissionGate(null)
        val perms = SandboxPermissions(networkAllowed = true)
        assertTrue(gate.requestNetwork("https://example.com", "test", perms))
    }

    @Test fun permissionGateFileWriteScoped() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "ws").apply { mkdirs() }
        val gate = SandboxPermissionGate(null)
        val perms = SandboxPermissions(fileWriteAllowed = true)
        assertTrue(gate.checkFileWrite("a.txt", tmp, perms))
        assertFalse(gate.checkFileWrite("../escape.txt", tmp, perms))
        val denied = SandboxPermissions(fileWriteAllowed = false)
        assertFalse(gate.checkFileWrite("a.txt", tmp, denied))
    }

    @Test fun permissionGateCommandAllowlist() {
        val gate = SandboxPermissionGate(null)
        val perms = SandboxPermissions(allowedCommands = setOf("echo"))
        assertTrue(gate.checkCommand("echo hi", perms))
        assertFalse(gate.checkCommand("rm -rf /", perms))
    }

    @Test fun managerRunsEcho() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "sandboxws").apply { mkdirs() }
        val mgr = LinuxSandboxManager(null, tmp)
        assertFalse(mgr.isRootfsInstalled())
        val res = mgr.runCommand("echo hello", 5000L, false)
        assertEquals(0, res.exitCode)
        assertTrue(res.stdout.contains("hello"))
        assertFalse(res.usedProot)
    }

    @Test fun managerTimeout() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "sandboxws2").apply { mkdirs() }
        val mgr = LinuxSandboxManager(null, tmp)
        val res = mgr.runCommand("sleep 5", 300L, false)
        assertTrue(res.timedOut)
    }
}