package com.sahayak.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** One-time setup screen, usually filled in by the son/daughter. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }

        fun label(s: String, size: Float = 16f) = TextView(this).apply { text = s; textSize = size; setPadding(0, pad / 2, 0, pad / 4) }
        fun field(hint: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply { this.hint = hint; setText(value); inputType = type }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; setOnClickListener { onClick() } }

        col.addView(label("Sahayak", 28f))
        col.addView(label("Press the blue mic button on any screen, say what you need, and Sahayak does it for you."))

        val brains = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("api" to "Claude API key on this phone", "bridge" to "Claude Code on my computer (developer mode, phone connected by USB)").forEachIndexed { i, (tag, n) ->
            brains.addView(RadioButton(this).apply { id = 100 + i; text = n; this.tag = tag; isChecked = prefs.brain == tag })
        }
        val bridge = field("Bridge URL", prefs.bridgeUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val key = field("Claude API key (only for API mode)", prefs.apiKey, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val name = field("What you call them (Papa, Mummy)", prefs.parentName)
        val phone = field("Your phone number for SMS reports", prefs.childPhone, InputType.TYPE_CLASS_PHONE)
        val profile = field("About them, for forms & bookings: full name, home address, email, phone, " +
            "preferences (veg, window seat, Ola Mini, usual grocery list)", prefs.profile,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 4; gravity = android.view.Gravity.TOP }
        val langs = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        listOf("hi-IN" to "Hindi", "en-IN" to "English", "pa-IN" to "Punjabi").forEachIndexed { i, (tag, n) ->
            langs.addView(RadioButton(this).apply { id = i + 1; text = n; tag.also { this.tag = it }; isChecked = prefs.language == tag })
        }

        col.addView(label("1. Setup")); col.addView(brains); col.addView(bridge); col.addView(key); col.addView(name); col.addView(phone); col.addView(profile); col.addView(langs)
        col.addView(button("Save") {
            prefs.apiKey = key.text.toString()
            prefs.bridgeUrl = bridge.text.toString()
            brains.findViewById<RadioButton>(brains.checkedRadioButtonId)?.let { prefs.brain = it.tag as String }
            prefs.parentName = name.text.toString()
            prefs.childPhone = phone.text.toString()
            prefs.profile = profile.text.toString()
            langs.findViewById<RadioButton>(langs.checkedRadioButtonId)?.let { prefs.language = it.tag as String }
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })

        col.addView(label("2. Permissions"))
        col.addView(button("Allow microphone and SMS") {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.SEND_SMS), 1)
        })
        col.addView(button("Turn on Sahayak (Accessibility → Sahayak helper)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        col.addView(label("3. Try it without speaking"))
        val typed = field("e.g. WhatsApp pe Rahul ko hi bhejo", "")
        col.addView(typed)
        col.addView(button("Run") {
            val svc = AgentService.instance
            if (svc == null) Toast.makeText(this, "Turn on Sahayak in Accessibility first", Toast.LENGTH_LONG).show()
            else { svc.startTask(typed.text.toString()); moveTaskToBack(true) }
        })

        status = label("")
        col.addView(status)
        setContentView(ScrollView(this).apply { addView(col, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
    }

    /**
     * Demo/testing hook: adb shell am start -n com.sahayak.app/.MainActivity --es task "Open YouTube"
     * Optional --es answers "haan|nahi" supplies spoken replies on devices without a microphone.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        // Test hook is debug-only: in a release build any app could otherwise launch Sahayak with a task
        // and a pre-filled "haan", bypassing the spoken confirmation.
        if (BuildConfig.DEBUG) intent.getStringExtra("task")?.let { task ->
            intent.removeExtra("task")
            val answers = intent.getStringExtra("answers")?.split("|")?.map { it.trim() }.orEmpty()
            intent.removeExtra("answers")
            AgentService.instance?.let { it.startTask(task, answers); moveTaskToBack(true) }
        }
        super.onResume()
        status.text = if (AgentService.instance != null) "✅ Sahayak is on" else "⚠️ Sahayak is off. Turn it on in Accessibility."
    }
}
