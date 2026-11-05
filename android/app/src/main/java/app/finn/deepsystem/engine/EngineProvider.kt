package app.finn.deepsystem.engine

import android.content.Context
import android.util.Log
import app.finn.deepsystem.BuildConfig

object EngineProvider {
    private const val TAG = "EngineProvider"
    private var overrideEngine: FinnEngine? = null
    private var currentEngine: FinnEngine? = null
    var useFakeEngine: Boolean = BuildConfig.USE_FAKE_ENGINE

    fun setTestEngine(engine: FinnEngine?) {
        overrideEngine = engine
    }

    @Synchronized
    fun getEngine(context: Context? = null): FinnEngine {
        overrideEngine?.let { return it }

        if (currentEngine != null) {
            return currentEngine!!
        }

        currentEngine = if (useFakeEngine || !NativeFinnBridge.isNativeLoaded()) {
            Log.i(TAG, "Providing FakeFinnEngine (useFake=$useFakeEngine, nativeLoaded=${NativeFinnBridge.isNativeLoaded()})")
            FakeFinnEngine()
        } else {
            Log.i(TAG, "Providing NativeFinnEngine")
            try {
                NativeFinnEngine(isFakeEngine = false)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to instantiate NativeFinnEngine, falling back to FakeFinnEngine", t)
                FakeFinnEngine()
            }
        }

        return currentEngine!!
    }

    @Synchronized
    fun switchEngine(forceFake: Boolean): FinnEngine {
        useFakeEngine = forceFake
        currentEngine?.close()
        currentEngine = null
        return getEngine()
    }
}
