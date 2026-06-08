package com.ciphervault.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.preference.PreferenceManager

class CallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CR_CallReceiver"
        const val ERROR_PREFIX = "CR-CL-"
        const val PREF_AUTO_RECORD_CALLS = "auto_record_calls"
        const val PREF_INCLUDED_CONTACTS = "included_contacts"
        const val PREF_EXCLUDED_CONTACTS = "excluded_contacts"
    }

    enum class ErrorCode(val code: String, val description: String) {
        CL001("CL001", "Failed to start auto-record"),
        CL002("CL002", "Failed to stop auto-record"),
        CL003("CL003", "TelephonyManager unavailable"),
        CL999("CL999", "Unknown call receiver error")
    }

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        val fullMsg = "[$ERROR_PREFIX${code.code}] ${code.description}: $message"
        Log.e(TAG, fullMsg, exception)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val autoRecord = prefs.getBoolean(PREF_AUTO_RECORD_CALLS, false)

        if (!autoRecord) {
            logDebug("Auto-record calls disabled")
            return
        }

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        logDebug("Phone state: $state, number: $incomingNumber")

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val shouldRecord = shouldRecordCall(context, incomingNumber)
                logDebug("Off-hook: shouldRecord=$shouldRecord")
                if (shouldRecord && !AudioEngine.isRecording()) {
                    startAutoRecord(context)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (AudioEngine.isRecording()) {
                    stopAutoRecord(context)
                }
            }
            TelephonyManager.EXTRA_STATE_RINGING -> {
                logDebug("Ringing: $incomingNumber")
            }
        }
    }

    private fun shouldRecordCall(context: Context, number: String): Boolean {
        if (number.isBlank()) return true

        val contactPrefs = context.getSharedPreferences("contact_selections", Context.MODE_PRIVATE)
        val includedIds = contactPrefs.getStringSet(PREF_INCLUDED_CONTACTS, emptySet()) ?: emptySet()
        val excludedIds = contactPrefs.getStringSet(PREF_EXCLUDED_CONTACTS, emptySet()) ?: emptySet()

        if (excludedIds.isNotEmpty() && number in excludedIds) {
            logDebug("Number $number is excluded from recording")
            return false
        }

        if (includedIds.isNotEmpty() && number !in includedIds) {
            logDebug("Number $number is not in included list")
            return false
        }

        return true
    }

    private fun startAutoRecord(context: Context) {
        try {
            logDebug("Auto-starting recording on call")
            val intent = Intent(context, RecorderService::class.java).apply {
                action = RecorderService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            reportError(ErrorCode.CL001, "Failed to start auto-record service", e)
        }
    }

    private fun stopAutoRecord(context: Context) {
        try {
            logDebug("Auto-stopping recording on call end")
            val intent = Intent(context, RecorderService::class.java).apply {
                action = RecorderService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            reportError(ErrorCode.CL002, "Failed to stop auto-record service", e)
        }
    }
}
