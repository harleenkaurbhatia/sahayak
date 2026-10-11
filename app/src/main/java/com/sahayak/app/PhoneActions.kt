package com.sahayak.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The things the agent can physically do on the phone. Each returns a short result for Claude. */
class PhoneActions(private val service: AccessibilityService, private val screen: ScreenReader) {

    private fun node(id: Int) = screen.nodes[id] ?: throw IllegalArgumentException("No element [$id] on the current screen")

    /** All text inside an element (whole subtree), e.g. a list row or a "Pay ₹500" button with nested labels. */
    fun nodeLabel(id: Int): String {
        val parts = mutableListOf<CharSequence>()
        fun collect(n: AccessibilityNodeInfo, depth: Int) {
            if (parts.size > 20 || depth > 6) return
            n.text?.let(parts::add)
            n.contentDescription?.let(parts::add)
            for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, depth + 1) }
        }
        screen.nodes[id]?.let { collect(it, 0) }
        return parts.joinToString(" ").replace(Regex("\\s+"), " ").trim()
    }

    /** Id of the smallest listed element under a screen point, so coordinate taps get the same safety checks. */
    fun elementAt(x: Int, y: Int): Int? = screen.nodes.entries
        .map { (id, n) -> id to Rect().also { n.getBoundsInScreen(it) } }
        .filter { (_, r) -> r.contains(x, y) }
        .minByOrNull { (_, r) -> r.width().toLong() * r.height() }?.first

    suspend fun tap(id: Int): String {
        var n: AccessibilityNodeInfo? = node(id)
        while (n != null && !n.isClickable) n = n.parent
        if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "Tapped [$id]"
        val r = Rect().also { node(id).getBoundsInScreen(it) }
        return tapAt(r.centerX(), r.centerY())
    }

    suspend fun longPress(id: Int): String {
        val r = Rect().also { node(id).getBoundsInScreen(it) }
        return if (gesture(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), 800))
            "Long-pressed [$id]" else "Long press failed"
    }

    suspend fun tapAt(x: Int, y: Int): String =
        if (gesture(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 60)) "Tapped at ($x,$y)" else "Tap failed"

    fun typeText(id: Int, text: String): String {
        val n = node(id)
        if (n.isPassword) return "Refused: [$id] is a password/PIN field. Ask the person to type it themselves."
        if (!n.isEditable) return "Element [$id] is not a text field"
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed into [$id]" else "Typing failed"
    }

    suspend fun scroll(direction: String): String {
        val m = service.resources.displayMetrics
        val cx = m.widthPixels / 2f
        val cy = m.heightPixels / 2f
        val dy = m.heightPixels * 0.3f
        val dx = m.widthPixels * 0.35f
        // Finger moves opposite to the content direction: to see content further down, swipe up.
        val ok = when (direction) {
            "down" -> gesture(cx, cy + dy, cx, cy - dy, 350)
            "up" -> gesture(cx, cy - dy, cx, cy + dy, 350)
            "right" -> gesture(cx + dx, cy, cx - dx, cy, 300)
            "left" -> gesture(cx - dx, cy, cx + dx, cy, 300)
            else -> return "Unknown direction $direction"
        }
        return if (ok) "Scrolled $direction" else "Scroll failed"
    }

    fun press(key: String): String {
        val action = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return "Unknown key $key"
        }
        return if (service.performGlobalAction(action)) "Pressed $key" else "Press $key failed"
    }

    fun openApp(name: String): String {
        val pm = service.packageManager
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL
        )
        val wanted = name.lowercase().trim()
        val match = launchable.firstOrNull { it.loadLabel(pm).toString().lowercase() == wanted }
            ?: launchable.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(wanted) }
            ?: return "No app called \"$name\". Installed: " +
                launchable.joinToString(", ") { it.loadLabel(pm).toString() }.take(1500)
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return "Could not open $name"
        service.startActivity(intent)
        return "Opened ${match.loadLabel(pm)}"
    }

    private suspend fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
            val sent = service.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!sent && cont.isActive) cont.resume(false)
        }
}
