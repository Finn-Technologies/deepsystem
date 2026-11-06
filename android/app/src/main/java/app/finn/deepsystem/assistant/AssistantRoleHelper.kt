package app.finn.deepsystem.assistant

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService

/**
 * Helper to inspect and request the Android Assistant Role (RoleManager.ROLE_ASSISTANT).
 */
object AssistantRoleHelper {

    fun isAssistantRoleHeld(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true &&
                    roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            isVoiceInteractionActive(context)
        }
    }

    fun isVoiceInteractionActive(context: Context): Boolean {
        val serviceComponent = ComponentName(context, FinnVoiceInteractionService::class.java)
        return VoiceInteractionService.isActiveService(context, serviceComponent)
    }

    fun createRequestRoleIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            } else {
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            }
        } else {
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        }
    }
}
