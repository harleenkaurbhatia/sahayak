package com.sahayak.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Brain that asks Claude Code running on the child's computer (bridge/bridge.py), so no API key
 * lives on the phone. The phone reaches the computer through `adb reverse tcp:8765 tcp:8765`.
 */
class BridgeAgent(
    private val bridgeUrl: String,
    private val language: String,
    private val profile: String,
    private val screen: ScreenReader,
    private val tools: ToolRunner,
) : Brain {

    override suspend fun run(request: String): Outcome {
        var session: String? = null
        var results = JSONArray()

        repeat(MAX_STEPS) { step ->
            val body = JSONObject()
                .put("session", session ?: JSONObject.NULL)
                .put("system_prompt", systemPrompt(language, profile) + "\n\n" + TOOL_GUIDE)
                .put("request", if (step == 0) request else JSONObject.NULL)
                .put("results", results)
                .put("elements", screen.describe())
                .put("screenshot", screen.screenshotBase64() ?: JSONObject.NULL)

            Log.i(TAG, "Step $step: asking Claude Code")
            val reply = try {
                post(body)
            } catch (e: Exception) {
                Log.e(TAG, "Bridge error", e)
                return Outcome(sorry(language), "Sahayak couldn't reach Claude Code on your computer (${e.message}). Nothing was changed.")
            }
            session = reply.optString("session").ifBlank { null }
            val actions = reply.optJSONArray("actions") ?: JSONArray()
            if (actions.length() == 0) {
                return Outcome(sorry(language), "Claude Code returned no actions: ${reply.optString("error")}")
            }

            results = JSONArray()
            for (i in 0 until actions.length()) {
                val action = actions.getJSONObject(i)
                val name = action.getString("tool")
                if (name == "finish") {
                    return Outcome(action.optString("say_to_person"), action.optString("report_for_child"))
                }
                val result = tools.run(name, action)
                results.put("$name -> $result")
                // Anything that changes what's on screen or waits on the person ends this batch.
                if (name in BATCH_ENDERS || result.startsWith("Error") || result.startsWith("Blocked")) break
            }
            delay(SETTLE_MS)
        }
        return Outcome(sorry(language), "Sahayak tried $MAX_STEPS steps and could not finish. You may need to help over a call.")
    }

    private suspend fun post(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("$bridgeUrl/step").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (conn.responseCode >= 400) throw RuntimeException("HTTP ${conn.responseCode}: ${text.take(200)}")
        JSONObject(text)
    }

    private companion object {
        const val TAG = "SahayakAgent"
        const val MAX_STEPS = 60
        const val SETTLE_MS = 1200L
        val BATCH_ENDERS = setOf("tap", "tap_at", "long_press", "press", "open_app", "scroll", "ask_user")

        val TOOL_GUIDE = """
            Each turn, reply with {"actions": [...]}: the next one or two actions, each an object with "tool" plus
            its arguments. After an action that changes the screen you'll get the results and the new screen.
            Tools:
            - tap {element_id}            tap an element by [id] from the latest list
            - tap_at {x, y}               tap screen pixel coordinates (only if the element isn't listed)
            - long_press {element_id}
            - type_text {element_id, text}
            - scroll {direction}          up | down | left | right
            - press {key}                 back | home | recents | notifications
            - open_app {name}             e.g. WhatsApp, PhonePe, Files, YouTube
            - wait {seconds}
            - say {text}                  speak without waiting for a reply
            - ask_user {question}         speak a question and hear the answer
            - finish {say_to_person, report_for_child}
        """.trimIndent()
    }
}
