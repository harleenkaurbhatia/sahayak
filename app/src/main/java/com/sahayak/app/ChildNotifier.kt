package com.sahayak.app

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log

/** Texts the child a short report after every help session, so they know what went wrong at home. */
class ChildNotifier(private val context: Context, private val prefs: Prefs) {

    fun report(problem: String, outcome: String) {
        val phone = prefs.childPhone
        if (phone.isBlank()) return
        if (context.checkSelfPermission(android.Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SEND_SMS not granted, skipping child report")
            return
        }
        val text = "Sahayak: ${prefs.parentName} got stuck on \"$problem\". $outcome"
        try {
            val sms = context.getSystemService(SmsManager::class.java)
            sms.sendMultipartTextMessage(phone, null, sms.divideMessage(text), null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Child report failed", e)
        }
    }

    private companion object { const val TAG = "ChildNotifier" }
}
