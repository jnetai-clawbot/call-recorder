package com.ciphervault.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.preference.PreferenceManager

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CR_BootReceiver"
        const val ERROR_PREFIX = "CR-BR-"
        const val PREF_AUTO_START = "auto_start_app"
    }

    enum class ErrorCode(val code: String, val description: String) {
        BR001("BR001", "Boot auto-start failed"),
        BR999("BR999", "Unknown boot receiver error")
    }

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        val fullMsg = "[$ERROR_PREFIX${code.code}] ${code.description}: $message"
        Log.e(TAG, fullMsg, exception)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "Received broadcast: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON") {

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val autoStart = prefs.getBoolean(PREF_AUTO_START, true)

            if (!autoStart) {
                Log.d(TAG, "Auto-start disabled in settings")
                return
            }

            try {
                Log.d(TAG, "Auto-starting app after boot")
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            } catch (e: Exception) {
                reportError(ErrorCode.BR001, "Failed to launch app on boot", e)
            }
        }
    }
}
