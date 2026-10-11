package com.sahayak.app

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Brain that calls the Claude API directly with an API key.
 * The see -> think -> act loop. Each turn Claude gets the current screen (element list + screenshot),
 * calls one or more phone tools, and we send back the results plus the new screen, until it calls finish.
 */
class Agent(
    apiKey: String,
    private val language: String,
    private val profile: String,
    private val screen: ScreenReader,
    private val tools: ToolRunner,
) : Brain {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build()

    override suspend fun run(request: String): Outcome {
        val messages = mutableListOf<MessageParam>()
        messages += userTurn(listOf(text("The person said: \"$request\"")) + observe())

        repeat(MAX_STEPS) { step ->
            Log.i(TAG, "Step $step: asking Claude")
            val response = try {
                withContext(Dispatchers.IO) { client.messages().create(params(messages)) }
            } catch (e: AnthropicServiceException) {
                Log.e(TAG, "API error", e)
                return Outcome(sorry(language), "Sahayak could not reach the AI (${e.statusCode()}). Nothing was changed.")
            }
            messages += response.toParam()

            if (response.stopReason().orElse(null) == StopReason.REFUSAL) {
                return Outcome(sorry(language), "The AI declined this request, so nothing was done.")
            }
            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (toolUses.isEmpty()) {
                val said = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" ")
                return Outcome(said.ifBlank { sorry(language) }, "Session ended without a clear result. Last reply: $said")
            }

            val results = mutableListOf<ContentBlockParam>()
            for (use in toolUses) {
                val input = JSONObject(jsonMapper().writeValueAsString(use._input()))
                if (use.name() == "finish") {
                    return Outcome(input.optString("say_to_person"), input.optString("report_for_child"))
                }
                val result = tools.run(use.name(), input)
                Log.d(TAG, "${use.name()} $input -> $result")
                results += ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder().toolUseId(use.id()).content(result).build()
                )
            }
            delay(SETTLE_MS) // let the UI react before looking again
            messages += userTurn(results + observe())
        }
        return Outcome(sorry(language), "Sahayak tried $MAX_STEPS steps and could not finish. You may need to help over a call.")
    }

    private suspend fun observe(): List<ContentBlockParam> {
        val elements = screen.describe()
        val shot = screen.screenshotBase64()
        Log.d(TAG, "Observed ${screen.nodes.size} elements, screenshot=${shot?.length ?: "blocked"}\n$elements")
        val blocks = mutableListOf(
            text("Current screen elements:\n$elements" + if (shot == null) "\n(Screenshot blocked by this app.)" else "")
        )
        if (shot != null) {
            blocks += ContentBlockParam.ofImage(
                ImageBlockParam.builder().source(
                    Base64ImageSource.builder().data(shot).mediaType(Base64ImageSource.MediaType.IMAGE_JPEG).build()
                ).build()
            )
        }
        return blocks
    }

    private fun params(messages: List<MessageParam>) = MessageCreateParams.builder()
        .model("claude-opus-5-5")
        .maxTokens(16000L)
        .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build()) // fast replies matter more than depth here
        .system(systemPrompt(language, profile))
        .tools(TOOLS.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
        .messages(messages)
        // Route refused requests to a fallback model instead of just stopping.
        .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
        .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        .build()



    private fun text(s: String) = ContentBlockParam.ofText(TextBlockParam.builder().text(s).build())

    private fun userTurn(blocks: List<ContentBlockParam>) =
        MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build()

    companion object {
        private const val TAG = "SahayakAgent"
        private const val MAX_STEPS = 60
        private const val SETTLE_MS = 1200L


        private fun tool(name: String, description: String, props: Map<String, Map<String, Any>> = emptyMap()) =
            Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().apply {
                            props.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                        }.build())
                        .required(props.keys.toList())
                        .build()
                )
                .build()

        private val INT = mapOf("type" to "integer")
        private fun str(desc: String) = mapOf("type" to "string", "description" to desc)

        private val TOOLS = listOf(
            tool("tap", "Tap an on-screen element by its [id] from the latest element list.", mapOf("element_id" to INT)),
            tool("tap_at", "Tap at screen pixel coordinates. Use only when the element isn't in the list.", mapOf("x" to INT, "y" to INT)),
            tool("long_press", "Long-press an element by [id].", mapOf("element_id" to INT)),
            tool("type_text", "Replace the text in an editable field.", mapOf("element_id" to INT, "text" to str("Text to type"))),
            tool("scroll", "Scroll the screen to reveal more content.",
                mapOf("direction" to mapOf("type" to "string", "enum" to listOf("up", "down", "left", "right")))),
            tool("press", "Press a system button.",
                mapOf("key" to mapOf("type" to "string", "enum" to listOf("back", "home", "recents", "notifications")))),
            tool("open_app", "Open an installed app by its name, e.g. WhatsApp, PhonePe, Files.", mapOf("name" to str("App name"))),
            tool("wait", "Wait for something to load.", mapOf("seconds" to INT)),
            tool("say", "Say something out loud to the person without waiting for a reply.", mapOf("text" to str("What to say"))),
            tool("ask_user", "Ask the person a question out loud and hear their spoken answer.", mapOf("question" to str("The question"))),
            tool("finish", "End the session.", mapOf(
                "say_to_person" to str("Final words to the person, in their language"),
                "report_for_child" to str("1-2 line English note for their child"),
            )),
        )
    }
}
