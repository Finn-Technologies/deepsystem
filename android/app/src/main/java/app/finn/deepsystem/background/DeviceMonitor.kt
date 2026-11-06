package app.finn.deepsystem.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * Thermal level representing hardware thermal throttling status.
 */
enum class ThermalLevel(val severity: Int, val description: String) {
    NONE(0, "Normal"),
    LIGHT(1, "Light warming"),
    MODERATE(2, "Moderate heat"),
    SEVERE(3, "Severe throttling"),
    CRITICAL(4, "Critical temperature"),
    EMERGENCY(5, "Emergency"),
    SHUTDOWN(6, "Shutdown imminent");

    companion object {
        fun fromApiStatus(status: Int): ThermalLevel = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> NONE
            PowerManager.THERMAL_STATUS_LIGHT -> LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> SHUTDOWN
            else -> NONE
        }
    }
}

/**
 * Snapshot of device battery state.
 */
data class BatteryState(
    val levelPercent: Int = 100,
    val isCharging: Boolean = true,
    val isPowerSaveMode: Boolean = false
) {
    val isLowBattery: Boolean
        get() = levelPercent < 20 && !isCharging
}

/**
 * Monitors battery and thermal conditions to intelligently defer heavy jobs
 * and throttle inference threads during hardware stress.
 */
class DeviceMonitor private constructor(private val appContext: Context) {

    private val _thermalStatus = MutableStateFlow(ThermalLevel.NONE)
    val thermalStatus: StateFlow<ThermalLevel> = _thermalStatus.asStateFlow()

    private val _batteryState = MutableStateFlow(queryBatteryState())
    val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var batteryReceiver: BroadcastReceiver? = null

    init {
        registerThermalListener()
        registerBatteryReceiver()
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null) {
                    val current = powerManager.currentThermalStatus
                    _thermalStatus.value = ThermalLevel.fromApiStatus(current)

                    val listener = PowerManager.OnThermalStatusChangedListener { status ->
                        val level = ThermalLevel.fromApiStatus(status)
                        Log.i(TAG, "Thermal status changed: $level ($status)")
                        _thermalStatus.value = level
                    }
                    powerManager.addThermalStatusListener(Executors.newSingleThreadExecutor(), listener)
                    thermalListener = listener
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to register thermal listener: ${t.message}")
            }
        }
    }

    private fun registerBatteryReceiver() {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    _batteryState.value = queryBatteryState()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            appContext.registerReceiver(receiver, filter)
            batteryReceiver = receiver
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to register battery receiver: ${t.message}")
        }
    }

    fun queryBatteryState(): BatteryState {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = appContext.registerReceiver(null, ifilter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1

            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val percent = if (level >= 0 && scale > 0) ((level * 100) / scale) else 100

            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isPowerSave = powerManager?.isPowerSaveMode ?: false

            BatteryState(
                levelPercent = percent,
                isCharging = isCharging,
                isPowerSaveMode = isPowerSave
            )
        } catch (t: Throwable) {
            // Default safe state
            BatteryState(levelPercent = 100, isCharging = true, isPowerSaveMode = false)
        }
    }

    /**
     * Determines whether heavy agent jobs (e.g. background fine-tuning, large doc digest)
     * should be deferred to preserve battery and avoid draining an unplugged device.
     */
    fun shouldDeferHeavyTasks(allowOnBattery: Boolean = false): Boolean {
        val currentBattery = _batteryState.value
        if (allowOnBattery && !currentBattery.isPowerSaveMode && currentBattery.levelPercent > 10) {
            return false
        }
        return currentBattery.isLowBattery || currentBattery.isPowerSaveMode
    }

    /**
     * Determines whether inference thread counts should be throttled due to high heat.
     */
    fun isThermalThrottled(): Boolean {
        return _thermalStatus.value.severity >= ThermalLevel.SEVERE.severity
    }

    /**
     * Recommends CPU thread count for local inference based on current thermal state.
     */
    fun getRecommendedThreadCount(defaultThreads: Int = 4): Int {
        return when (_thermalStatus.value) {
            ThermalLevel.NONE, ThermalLevel.LIGHT -> defaultThreads
            ThermalLevel.MODERATE -> maxOf(1, defaultThreads - 1)
            ThermalLevel.SEVERE -> maxOf(1, defaultThreads / 2)
            ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY, ThermalLevel.SHUTDOWN -> 1
        }
    }

    companion object {
        private const val TAG = "DeviceMonitor"

        @Volatile
        private var instance: DeviceMonitor? = null

        fun getInstance(context: Context): DeviceMonitor {
            return instance ?: synchronized(this) {
                instance ?: DeviceMonitor(context.applicationContext).also { instance = it }
            }
        }

        // For unit testing
        fun setMockInstance(mock: DeviceMonitor?) {
            instance = mock
        }
    }
}
