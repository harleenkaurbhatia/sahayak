package com.sahayak.app

import android.content.Context

/** Simple local settings. The API key stays on this phone only. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sahayak", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    /** BCP-47 tag used for both speech recognition and text-to-speech. */
    var language: String
        get() = sp.getString("language", "hi-IN") ?: "hi-IN"
        set(v) = sp.edit().putString("language", v).apply()

    /** What the child calls this parent, used in reports ("Papa", "Mummy"). */
    var parentName: String
        get() = sp.getString("parent_name", "Papa") ?: "Papa"
        set(v) = sp.edit().putString("parent_name", v.trim()).apply()

    /** Child's phone number that receives an SMS report after each help session. Empty = off. */
    var childPhone: String
        get() = sp.getString("child_phone", "") ?: ""
        set(v) = sp.edit().putString("child_phone", v.trim()).apply()

    /** "bridge" = Claude Code on the child's computer, "api" = Claude API key on the phone. */
    var brain: String
        get() = sp.getString("brain", "api") ?: "api"
        set(v) = sp.edit().putString("brain", v).apply()

    var bridgeUrl: String
        get() = sp.getString("bridge_url", "http://127.0.0.1:8765") ?: "http://127.0.0.1:8765"
        set(v) = sp.edit().putString("bridge_url", v.trim().trimEnd('/')).apply()

    /** Free-text profile the agent uses for forms and bookings: name, address, phone, email, preferences. */
    var profile: String
        get() = sp.getString("profile", "") ?: ""
        set(v) = sp.edit().putString("profile", v.trim()).apply()
}
