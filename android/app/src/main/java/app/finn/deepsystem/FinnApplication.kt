package app.finn.deepsystem

import android.app.Application
import android.util.Log

class FinnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "FinnApplication initialized")
    }

    companion object {
        private const val TAG = "FinnApplication"
    }
}
