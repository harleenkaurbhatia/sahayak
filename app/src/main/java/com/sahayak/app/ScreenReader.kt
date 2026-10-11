package com.sahayak.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * Turns what's on screen into something Claude can read: a numbered list of the visible
 * elements (from the accessibility tree) plus a small screenshot.
 */
class ScreenReader(private val service: AccessibilityService) {

    /** Element id -> node, from the most recent [describe] call. Ids are only valid until the next one. */
    val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()

    fun describe(): String {
        nodes.clear()
        val out = StringBuilder()
        val windows = service.windows.filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        val pkg = service.rootInActiveWindow?.packageName ?: "unknown"
        out.append("Foreground app: $pkg\n")
        for (window in windows) {
            val root = window.root ?: continue
            out.append("-- window: ${window.title ?: root.packageName} --\n")
            walk(root, out, depth = 0)
        }
        if (nodes.isEmpty()) out.append("(no readable elements - rely on the screenshot)\n")
        return out.toString()
    }

    private fun walk(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (nodes.size >= MAX_ELEMENTS || depth > 40 || !node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val interesting = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable ||
            text.isNotEmpty() || desc.isNotEmpty()
        if (interesting) {
            val id = nodes.size + 1
            nodes[id] = node
            val r = Rect().also { node.getBoundsInScreen(it) }
            val flags = buildList {
                if (node.isClickable || node.isLongClickable) add("click")
                if (node.isEditable) add(if (node.isPassword) "edit-password" else "edit")
                if (node.isScrollable) add("scroll")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                if (!node.isEnabled) add("disabled")
            }
            out.append("[$id] ${node.className?.toString()?.substringAfterLast('.') ?: "View"}")
            if (text.isNotEmpty()) out.append(" \"${text.take(80)}\"")
            if (desc.isNotEmpty() && desc != text) out.append(" desc=\"${desc.take(80)}\"")
            node.hintText?.toString()?.takeIf { it.isNotBlank() && it != text }?.let { out.append(" hint=\"${it.take(40)}\"") }
            if (flags.isNotEmpty()) out.append(" {${flags.joinToString(",")}}")
            out.append(" @(${r.centerX()},${r.centerY()})\n")
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, out, depth + 1) }
    }

    /** JPEG screenshot as base64, or null when the app blocks screenshots (many banking/UPI screens do). */
    suspend fun screenshotBase64(): String? = suspendCancellableCoroutine { cont ->
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    buffer.close()
                    if (hw == null) { cont.resume(null); return }
                    val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                    val scale = SHOT_WIDTH.toFloat() / soft.width
                    val small = Bitmap.createScaledBitmap(soft, SHOT_WIDTH, (soft.height * scale).toInt(), true)
                    val bytes = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 60, it) }
                    cont.resume(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
                }
                override fun onFailure(errorCode: Int) = cont.resume(null)
            })
    }

    private companion object {
        const val MAX_ELEMENTS = 250
        const val SHOT_WIDTH = 540
    }
}
