package app.finn.deepsystem.storage

import android.content.Context
import android.content.SharedPreferences

class FinnPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var isReduceMotionForced: Boolean
        get() = prefs.getBoolean(KEY_REDUCE_MOTION, false)
        set(value) = prefs.edit().putBoolean(KEY_REDUCE_MOTION, value).apply()

    var isDarkModeForced: Boolean?
        get() = if (prefs.contains(KEY_DARK_MODE)) prefs.getBoolean(KEY_DARK_MODE, true) else null
        set(value) {
            if (value == null) prefs.edit().remove(KEY_DARK_MODE).apply()
            else prefs.edit().putBoolean(KEY_DARK_MODE, value).apply()
        }

    var micPermissionGranted: Boolean
        get() = prefs.getBoolean(KEY_MIC_PERMISSION, false)
        set(value) = prefs.edit().putBoolean(KEY_MIC_PERMISSION, value).apply()

    var cameraPermissionGranted: Boolean
        get() = prefs.getBoolean(KEY_CAM_PERMISSION, false)
        set(value) = prefs.edit().putBoolean(KEY_CAM_PERMISSION, value).apply()

    var notificationsPermissionGranted: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_PERMISSION, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_PERMISSION, value).apply()

    companion object {
        private const val PREFS_NAME = "finn_user_preferences"
        private const val KEY_ONBOARDING_COMPLETED = "key_onboarding_completed"
        private const val KEY_REDUCE_MOTION = "key_reduce_motion"
        private const val KEY_DARK_MODE = "key_dark_mode"
        private const val KEY_MIC_PERMISSION = "key_mic_permission"
        private const val KEY_CAM_PERMISSION = "key_cam_permission"
        private const val KEY_NOTIF_PERMISSION = "key_notif_permission"
    }
}
