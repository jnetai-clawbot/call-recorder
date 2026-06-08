package com.ciphervault.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.preference.PreferenceManager

class CallReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "CR_CallReceiver"
        const val ERROR_PREFIX = "CR-CL-"
        const val PREF_AUTO_RECORD_CALLS = "auto_record_calls"
        const val PREF_AUTO_RECORD_OUTGOING = "auto_record_outgoing"
        const val PREF_INCLUDED_CONTACTS = "included_contacts"
        const val PREF_EXCLUDED_CONTACTS = "excluded_contacts"
    }

    enum class ErrorCode(val code: String, val description: String) {
        CL001("CL001", "Failed to start auto-record"),
        CL002("CL002", "Failed to stop auto-record"),
        CL999("CL999", "Unknown call receiver error")
    }

    private var lastOffHookTime: Long = 0L
    private var lastIdleTime: Long = 0L

    private fun reportError(code: ErrorCode, message: String, exception: Exception? = null) {
        Log.e(TAG, "[${ERROR_PREFIX}${code.code}] ${code.description}: $message", exception)
    }

    private fun logDebug(message: String) {
        Log.d(TAG, message)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        logDebug("Phone state: $state, number: $incomingNumber")

        when (state) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                lastOffHookTime = System.currentTimeMillis()
                val isIncoming = incomingNumber.isNotBlank()
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)

                val autoRecIncoming = prefs.getBoolean(PREF_AUTO_RECORD_CALLS, false)
                val autoRecOutgoing = prefs.getBoolean(PREF_AUTO_RECORD_OUTGOING, false)

                if (isIncoming && !autoRecIncoming) {
                    logDebug("Incoming call auto-record disabled")
                    return
                }
                if (!isIncoming && !autoRecOutgoing) {
                    logDebug("Outgoing call auto-record disabled")
                    return
                }

                if (!AudioEngine.isRecording() && shouldRecordCall(context, incomingNumber)) {
                    logDebug("Starting auto-record for call")
                    startAutoRecord(context)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val now = System.currentTimeMillis()
                // Debounce: don't stop if we just went off-hook (state transitions can fire rapidly)
                if (now - lastOffHookTime < 3000) {
                    logDebug("Idle too soon after off-hook, ignoring")
                    return
                }
                lastIdleTime = now
                if (AudioEngine.isRecording()) {
                    logDebug("Call ended, stopping auto-record")
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
            logDebug("Number $number excluded")
            return false
        }

        if (includedIds.isNotEmpty() && number !in includedIds) {
            logDebug("Number $number not in included list")
            return false
        }

        return true
    }

    private fun startAutoRecord(context: Context) {
        try {
            val intent = Intent(context, RecorderService::class.java).apply {
                action = RecorderService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            reportError(ErrorCode.CL001, "Failed to start auto-record", e)
        }
    }

    private fun stopAutoRecord(context: Context) {
        try {
            val intent = Intent(context, RecorderService::class.java).apply {
                action = RecorderService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            reportError(ErrorCode.CL002, "Failed to stop auto-record", e)
        }
    }
}
