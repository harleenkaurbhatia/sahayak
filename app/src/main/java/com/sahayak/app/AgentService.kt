package com.sahayak.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Lives in the background once turned on in Accessibility settings. Shows a floating help button
 * on every screen; tapping it starts listening, and the agent takes over from there.
 */
class AgentService : AccessibilityService(), AgentHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var task: Job? = null
    private lateinit var prefs: Prefs
    private lateinit var voice: Voice
    private lateinit var screen: ScreenReader
    private lateinit var phone: PhoneActions
    private lateinit var notifier: ChildNotifier
    private var bubble: TextView? = null
    private var caption: TextView? = null

    /** Demo/testing only: spoken answers to use instead of the microphone (emulators have none). */
    private val scriptedAnswers = ArrayDeque<String>()

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        voice = Voice(this) { prefs.language }
        screen = ScreenReader(this)
        phone = PhoneActions(this, screen)
        notifier = ChildNotifier(this, prefs)
        showBubble()
        showCaption()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        scope.cancel()
        voice.shutdown()
        bubble?.let { getSystemService(WindowManager::class.java).removeView(it) }
        caption?.let { getSystemService(WindowManager::class.java).removeView(it) }
        super.onDestroy()
    }

    /** Bubble tapped: start a session, or stop the one that's running. */
    private fun onBubbleTap() {
        if (task?.isActive == true) {
            task?.cancel()
            voice.stopSpeaking()
            setBubble(State.IDLE)
            return
        }
        task = scope.launch {
            setBubble(State.LISTENING)
            val heard = voice.listen()
            if (heard.isNullOrBlank()) {
                setBubble(State.IDLE)
                say(t("मुझे सुनाई नहीं दिया, फिर से बटन दबाइए।", "I didn't catch that. Please press the button again."))
                return@launch
            }
            runTask(heard)
        }
    }

    /** Also used by the "type instead of speaking" box in MainActivity, handy for demos on an emulator. */
    fun startTask(request: String, answers: List<String> = emptyList()) {
        scriptedAnswers.clear()
        scriptedAnswers.addAll(answers)
        task?.cancel()
        task = scope.launch { runTask(request) }
    }

    private suspend fun runTask(request: String) {
        android.util.Log.i("SahayakAgent", "Task: $request")
        if (prefs.brain == "api" && prefs.apiKey.isBlank()) {
            say("Please open the Sahayak app and add the API key first.")
            return
        }
        setBubble(State.WORKING)
        showStatus("👤 \"$request\"")
        delay(1500)
        say(t("ठीक है, मैं देखता हूँ।", "Okay, let me take a look."))
        val tools = ToolRunner(phone, this)
        val agent: Brain = if (prefs.brain == "api") Agent(prefs.apiKey, languageName(), prefs.profile, screen, tools)
            else BridgeAgent(prefs.bridgeUrl, languageName(), prefs.profile, screen, tools)
        val outcome = try {
            agent.run(request)
        } finally {
            setBubble(State.IDLE)
        }
        showStatus("👤 \"$request\"")
        say(outcome.sayToParent)
        notifier.report(request, outcome.reportForChild)
        delay(4000)
        showStatus("")
    }

    override suspend fun speak(text: String) = say(text)

    override suspend fun askAndListen(question: String): String? {
        say(question)
        setBubble(State.LISTENING)
        val heard = scriptedAnswers.removeFirstOrNull()?.also { delay(1500) } ?: voice.listen()
        return heard.also { setBubble(State.WORKING); it?.let { a -> showStatus("👤 \"$a\"") } }
    }

    override fun showStatus(text: String) {
        caption?.text = text
        caption?.visibility = if (text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
    }

    private suspend fun say(text: String) {
        showStatus("🗣️ $text")
        voice.speak(text)
    }

    private fun languageName() = when (prefs.language) {
        "hi-IN" -> "Hindi"
        "pa-IN" -> "Punjabi"
        else -> "English"
    }

    private fun t(hindi: String, english: String) = if (prefs.language == "hi-IN") hindi else english

    // ---- floating button ----

    private enum class State(val label: String, val color: Int) {
        IDLE("🎤", Color.parseColor("#1E88E5")),
        LISTENING("👂", Color.parseColor("#43A047")),
        WORKING("⏳", Color.parseColor("#FB8C00")),
    }

    private fun setBubble(state: State) {
        bubble?.text = state.label
        (bubble?.background as? GradientDrawable)?.setColor(state.color)
    }

    private fun showBubble() {
        val wm = getSystemService(WindowManager::class.java)
        val size = (72 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - 16
            y = resources.displayMetrics.heightPixels / 2
        }
        val view = TextView(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            elevation = 12f
        }
        bubble = view
        setBubble(State.IDLE)

        // Drag to move, tap to talk.
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; downX = e.rawX; downY = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(view, params)
                }
                MotionEvent.ACTION_UP -> if (abs(e.rawX - downX) < 20 && abs(e.rawY - downY) < 20) onBubbleTap()
            }
            true
        }
        wm.addView(view, params)
    }

    /** Caption strip at the bottom of the screen. Not touchable, so taps pass through to the app. */
    private fun showCaption() {
        val wm = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM; y = (110 * resources.displayMetrics.density).toInt() }
        val pad = (14 * resources.displayMetrics.density).toInt()
        caption = TextView(this).apply {
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { setColor(Color.parseColor("#E6202124")); cornerRadius = pad.toFloat() }
            visibility = android.view.View.GONE
        }
        wm.addView(caption, params)
    }

    companion object {
        var instance: AgentService? = null
            private set
    }
}
