package com.androidspect.server.routes

import android.content.Context
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI Assistant — multi-provider chat with autonomous file access via tool use.
 *
 * Tools exposed to the AI (all providers via a unified agentic loop):
 *   list_directory(path) → directory listing
 *   read_file(path)      → file content (capped at 200 KB)
 *
 * Endpoints:
 *   GET    /api/ai/providers          → list providers (keys masked)
 *   PUT    /api/ai/providers/{id}     → save/update provider
 *   DELETE /api/ai/providers/{id}     → delete provider
 *   POST   /api/ai/chat              → chat with agentic file-access loop
 *   GET    /api/ai/files?pkg=        → file browser for manual attachment
 *   GET    /api/ai/files/content?path= → read a specific file
 */
fun Routing.aiChatRoutes(context: Context) {

    val configFile = File(context.filesDir, "ai_providers.json")
    val json       = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun loadProviders(): List<AiProvider> = runCatching {
        if (!configFile.exists()) return@runCatching emptyList<AiProvider>()
        json.decodeFromString(ListSerializer(AiProvider.serializer()),
            configFile.readText())
    }.getOrDefault(emptyList())

    fun saveProviders(list: List<AiProvider>) = configFile.writeText(
        json.encodeToString(ListSerializer(AiProvider.serializer()), list))

    route("/api/ai") {

        // ── Providers ─────────────────────────────────────────────────────────

        get("/providers") {
            call.respond(loadProviders().map { p ->
                p.copy(apiKey = if (p.apiKey.length > 8)
                    p.apiKey.take(4) + "…" + p.apiKey.takeLast(4) else "****")
            })
        }

        put("/providers/{id}") {
            val id  = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest)
            val req = call.receive<AiProvider>()

            // Validate required fields.
            val errors = mutableListOf<String>()
            if (req.name.isBlank())  errors.add("name is required")
            if (req.model.isBlank()) errors.add("model is required")
            if (req.type != "custom" && req.apiKey.isBlank())
                errors.add("API key is required for ${req.type}")
            if (req.type == "custom" && req.baseUrl.isNullOrBlank())
                errors.add("base URL is required for custom providers")
            if (errors.isNotEmpty())
                return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to errors.joinToString(", ")))

            val providers = loadProviders().toMutableList()
            val idx = providers.indexOfFirst { it.id == id }
            val provider = req.copy(id = id)
            if (idx >= 0) providers[idx] = provider else providers.add(provider)
            saveProviders(providers)
            call.respond(mapOf("ok" to "saved"))
        }

        delete("/providers/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
            saveProviders(loadProviders().filter { it.id != id })
            call.respond(mapOf("ok" to "deleted"))
        }

        // ── Chat with agentic loop ─────────────────────────────────────────────

        post("/chat") {
            val req = call.receive<AiChatRequest>()

            if (req.providerId.isBlank())
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "No provider selected."))

            val provider = loadProviders().find { it.id == req.providerId }
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Provider '${req.providerId}' not found. " +
                          "Go to ⚙ Manage providers and save it first."))

            if (provider.model.isBlank())
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Model is not set for provider '${provider.name}'. Edit the provider to add a model."))
            if (provider.type != "custom" && provider.apiKey.isBlank())
                return@post call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "API key is missing for provider '${provider.name}'."))

            val result = withContext(Dispatchers.IO) {
                runCatching { runAgenticLoop(provider, req, context) }
                    .getOrElse { e -> AiChatResponse(
                        content     = "",
                        toolCalls   = emptyList(),
                        error       = "${e::class.java.simpleName}: ${e.message?.take(400)}"
                    )}
            }
            call.respond(result)
        }

        // ── File browser (for manual attachment) ──────────────────────────────

        get("/files") {
            val pkg = call.request.queryParameters["pkg"] ?: ""
            call.respond(buildFileTree(context, pkg))
        }

        get("/files/content") {
            val path = call.request.queryParameters["path"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "path required"))
            val content = safeReadFile(path, context)
                ?: return@get call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Access denied or not found: $path"))
            call.respond(AiFileContent(path = path, name = File(path).name,
                size = File(path).length(), content = content))
        }
    }
}

// ── Agentic loop ───────────────────────────────────────────────────────────────

private const val MAX_TOOL_ROUNDS = 8

/**
 * Each provider has a different native format for tool calls and results.
 * We keep a provider-specific history (ProviderMessage) that tracks the
 * actual tool_use/tool_result blocks needed by each API, and a parallel
 * simple AiMessage list for the display history.
 *
 * To avoid complexity, we implement a unified strategy:
 *   - We run the real API call per-provider
 *   - For tool results we inject them as a user message in a format the model
 *     understands for each provider type
 * The key fix over the previous version: we never add a fake "assistant" message
 * before the tool result — that was causing the loop confusion.
 */
private fun runAgenticLoop(
    provider: AiProvider,
    req: AiChatRequest,
    context: Context
): AiChatResponse {
    val toolResults  = mutableListOf<AiToolCall>()
    // Start with the user's message history unchanged.
    val messages     = req.messages.toMutableList()

    repeat(MAX_TOOL_ROUNDS) {
        val resp = dispatchToProvider(provider, req.copy(messages = messages))

        // Error from provider — return immediately.
        if (resp.error != null) {
            return AiChatResponse(content = resp.content, toolCalls = toolResults, error = resp.error)
        }

        // No tool call — final answer.
        if (resp.toolCall == null) {
            return AiChatResponse(content = resp.content, toolCalls = toolResults)
        }

        // Execute the tool.
        val tc     = resp.toolCall
        val result = executeTool(tc.name, tc.arguments, context)
        toolResults.add(AiToolCall(name = tc.name, arguments = tc.arguments, result = result))

        // Inject the tool result back into the conversation.
        // Strategy: append a single user message with the result so the next
        // call sees it as context. We do NOT add a fake assistant message —
        // that was causing the loop.
        val toolMsg = "Tool ${tc.name}(path=\"${tc.arguments["path"] ?: ""}\") returned:\n" +
                      "```\n${result.take(8000)}\n```\n" +
                      "Continue your task using this information. Do not call the same tool with the same path again."
        messages.add(AiMessage("user", toolMsg))
    }

    // Rounds exhausted — get final answer with accumulated context.
    val finalResp = dispatchToProvider(provider, req.copy(messages = messages))
    return AiChatResponse(
        content   = finalResp.content.ifBlank { "Maximum tool call rounds reached. Check tool results above." },
        toolCalls = toolResults,
        error     = finalResp.error
    )
}
private val TOOL_DEFS = listOf(AiToolDef(
        name        = "list_directory",
        description = "List files and directories at a given path inside the AndroidSpect data directory. " +
                      "Use this to explore the app's internal storage before reading specific files.",
        parameters  = mapOf(
            "type"       to "object",
            "properties" to mapOf("path" to mapOf("type" to "string",
                "description" to "Absolute path to list (e.g. /data/data/com.androidspect/files/)")),
            "required"   to listOf("path")
        )
    ),
    AiToolDef(
        name        = "read_file",
        description = "Read the content of a file from AndroidSpect's internal storage. " +
                      "Returns up to 200 KB. Use list_directory first to find file paths.",
        parameters  = mapOf(
            "type"       to "object",
            "properties" to mapOf("path" to mapOf("type" to "string",
                "description" to "Absolute path to the file to read")),
            "required"   to listOf("path")
        )
    )
)


private fun executeTool(name: String, args: Map<String, String>, context: Context): String {
    val path = args["path"] ?: return "Error: path argument missing"
    return when (name) {
        "list_directory" -> {
            val content = safeListDir(path, context)
                ?: return "Access denied: $path is outside the allowed directories."
            content
        }
        "read_file" -> {
            safeReadFile(path, context)
                ?: "Access denied or file not found: $path"
        }
        else -> "Unknown tool: $name"
    }
}

// ── Provider dispatch ──────────────────────────────────────────────────────────

private data class ProviderResponse(
    val content: String = "",
    val toolCall: ToolCallRequest? = null,
    val error: String? = null
)
private data class ToolCallRequest(val name: String, val arguments: Map<String, String>)

private fun dispatchToProvider(provider: AiProvider, req: AiChatRequest): ProviderResponse {
    return when (provider.type) {
        "anthropic" -> sendAnthropic(provider, req)
        "openai"    -> sendOpenAI(provider, req, "https://api.openai.com/v1/chat/completions")
        "gemini"    -> sendGemini(provider, req)
        "custom"    -> sendOpenAI(provider, req, provider.baseUrl
            ?: return ProviderResponse(error = "Custom provider has no base URL"))
        else        -> ProviderResponse(error = "Unknown provider type: ${provider.type}")
    }
}

// ── Anthropic ──────────────────────────────────────────────────────────────────

private fun sendAnthropic(provider: AiProvider, req: AiChatRequest): ProviderResponse {
    val body = buildJsonObject {
        put("model", provider.model)
        put("max_tokens", req.maxTokens ?: 4096)
        put("system", buildSystemPrompt(req))
        putJsonArray("messages") {
            req.messages.forEach { m -> add(buildJsonObject {
                put("role", m.role); put("content", m.content) }) }
        }
        putJsonArray("tools") {
            TOOL_DEFS.forEach { t -> add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                putJsonObject("input_schema") {
                    val props = t.parameters["properties"] as? Map<*, *> ?: emptyMap<Any,Any>()
                    val req2  = t.parameters["required"] as? List<*> ?: emptyList<Any>()
                    put("type", "object")
                    putJsonObject("properties") {
                        props.forEach { (k, v) ->
                            val vm = v as? Map<*, *> ?: return@forEach
                            putJsonObject(k.toString()) {
                                put("type", vm["type"]?.toString() ?: "string")
                                put("description", vm["description"]?.toString() ?: "")
                            }
                        }
                    }
                    putJsonArray("required") { req2.forEach { add(JsonPrimitive(it.toString())) } }
                }
            })}
        }
    }

    val conn = openConn("https://api.anthropic.com/v1/messages", provider.apiKey).also {
        it.setRequestProperty("x-api-key", provider.apiKey)
        it.setRequestProperty("anthropic-version", "2023-06-01")
    }
    return execute(conn, body.toString()) { respBody ->
        val j   = Json.parseToJsonElement(respBody).jsonObject
        val err = j["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        if (err != null) return@execute ProviderResponse(error = "Anthropic: $err")

        val stopReason = j["stop_reason"]?.jsonPrimitive?.content
        if (stopReason == "tool_use") {
            val toolBlock = j["content"]?.jsonArray?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use" }?.jsonObject
            val name  = toolBlock?.get("name")?.jsonPrimitive?.content ?: return@execute ProviderResponse(error = "Tool call malformed")
            val input = toolBlock["input"]?.jsonObject ?: return@execute ProviderResponse(error = "Tool input missing")
            val args  = input.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
            return@execute ProviderResponse(toolCall = ToolCallRequest(name, args))
        }
        val text = j["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        ProviderResponse(content = text)
    }
}

// ── OpenAI / compatible ────────────────────────────────────────────────────────

private fun sendOpenAI(provider: AiProvider, req: AiChatRequest, endpoint: String): ProviderResponse {
    val body = buildJsonObject {
        put("model", provider.model)
        put("max_tokens", req.maxTokens ?: 4096)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", buildSystemPrompt(req)) })
            req.messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
        }
        putJsonArray("tools") {
            TOOL_DEFS.forEach { t -> add(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name)
                    put("description", t.description)
                    putJsonObject("parameters") {
                        val props = t.parameters["properties"] as? Map<*, *> ?: emptyMap<Any,Any>()
                        val req2  = t.parameters["required"] as? List<*> ?: emptyList<Any>()
                        put("type", "object")
                        putJsonObject("properties") {
                            props.forEach { (k, v) ->
                                val vm = v as? Map<*,*> ?: return@forEach
                                putJsonObject(k.toString()) {
                                    put("type", vm["type"]?.toString() ?: "string")
                                    put("description", vm["description"]?.toString() ?: "")
                                }
                            }
                        }
                        putJsonArray("required") { req2.forEach { add(JsonPrimitive(it.toString())) } }
                    }
                }
            })}
        }
        put("tool_choice", "auto")
    }

    val conn = openConn(endpoint, provider.apiKey)
    if (provider.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
    return execute(conn, body.toString()) { respBody ->
        val j   = Json.parseToJsonElement(respBody).jsonObject
        val err = j["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        if (err != null) return@execute ProviderResponse(error = "OpenAI: $err")

        val choice  = j["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val finish  = choice?.get("finish_reason")?.jsonPrimitive?.content
        val message = choice?.get("message")?.jsonObject

        if (finish == "tool_calls") {
            val tc   = message?.get("tool_calls")?.jsonArray?.firstOrNull()?.jsonObject ?: return@execute ProviderResponse(error = "Tool call missing")
            val name = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content ?: return@execute ProviderResponse(error = "Tool name missing")
            val argsStr = tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.content ?: "{}"
            val args = runCatching { Json.parseToJsonElement(argsStr).jsonObject.entries.associate { (k,v) -> k to v.jsonPrimitive.content } }.getOrDefault(emptyMap())
            return@execute ProviderResponse(toolCall = ToolCallRequest(name, args))
        }
        val text = message?.get("content")?.jsonPrimitive?.content ?: ""
        ProviderResponse(content = text)
    }
}

// ── Gemini ─────────────────────────────────────────────────────────────────────

private fun sendGemini(provider: AiProvider, req: AiChatRequest): ProviderResponse {
    val model    = provider.model
    val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${provider.apiKey}"

    val body = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", buildSystemPrompt(req)) }) }
            })
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { add(buildJsonObject { put("text", "Understood.") }) }
            })
            req.messages.forEach { m ->
                add(buildJsonObject {
                    put("role", if (m.role == "assistant") "model" else "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", m.content) }) }
                })
            }
        }
        putJsonArray("tools") {
            add(buildJsonObject {
                putJsonArray("functionDeclarations") {
                    TOOL_DEFS.forEach { t -> add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        putJsonObject("parameters") {
                            val props = t.parameters["properties"] as? Map<*,*> ?: emptyMap<Any,Any>()
                            put("type", "OBJECT")
                            putJsonObject("properties") {
                                props.forEach { (k, v) ->
                                    val vm = v as? Map<*,*> ?: return@forEach
                                    putJsonObject(k.toString()) {
                                        put("type", (vm["type"]?.toString() ?: "string").uppercase())
                                        put("description", vm["description"]?.toString() ?: "")
                                    }
                                }
                            }
                        }
                    })}
                }
            })
        }
        putJsonObject("generationConfig") { put("maxOutputTokens", req.maxTokens ?: 4096) }
    }

    val conn = openConn(endpoint, "")
    return execute(conn, body.toString()) { respBody ->
        val j   = Json.parseToJsonElement(respBody).jsonObject
        val err = j["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        if (err != null) return@execute ProviderResponse(error = "Gemini: $err")

        val candidate = j["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val parts     = candidate?.get("content")?.jsonObject?.get("parts")?.jsonArray

        // Check for function call.
        val fnCall = parts?.firstOrNull { it.jsonObject.containsKey("functionCall") }?.jsonObject?.get("functionCall")?.jsonObject
        if (fnCall != null) {
            val name = fnCall["name"]?.jsonPrimitive?.content ?: return@execute ProviderResponse(error = "Tool name missing")
            val args = fnCall["args"]?.jsonObject?.entries?.associate { (k,v) -> k to v.jsonPrimitive.content } ?: emptyMap()
            return@execute ProviderResponse(toolCall = ToolCallRequest(name, args))
        }
        val text = parts?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content ?: ""
        ProviderResponse(content = text)
    }
}

// ── HTTP + file helpers ────────────────────────────────────────────────────────

private fun openConn(url: String, @Suppress("UNUSED_PARAMETER") key: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).also {
        it.requestMethod = "POST"
        it.setRequestProperty("Content-Type", "application/json")
        it.connectTimeout = 30_000; it.readTimeout = 120_000
        it.doOutput = true
    }

private fun <T> execute(conn: HttpURLConnection, body: String, parse: (String) -> T): T {
    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
    val code   = conn.responseCode
    val stream = if (code < 400) conn.inputStream else conn.errorStream
    return parse(stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
}

private fun allowedRoots(context: Context) = listOf(
    File(context.cacheDir,  "decompiler"),
    File(context.filesDir,  "snapshots"),
    File(context.filesDir,  "notes"),
    File(context.filesDir,  "captures"),
    File(context.filesDir,  "frida_custom_scripts"),
    File(context.filesDir,  "frida_script_cache"),
)

private fun isAllowed(path: String, context: Context): Boolean {
    val resolved = File(path).canonicalFile
    return allowedRoots(context).any { resolved.absolutePath.startsWith(it.canonicalFile.absolutePath) }
}

private fun safeListDir(path: String, context: Context): String? {
    if (!isAllowed(path, context)) return null
    val dir = File(path)
    if (!dir.exists() || !dir.isDirectory) return "Not a directory: $path"
    val entries = dir.listFiles() ?: return "(empty)"
    return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        .joinToString("\n") { f ->
            if (f.isDirectory) "[DIR]  ${f.name}/"
            else               "[FILE] ${f.name}  (${f.length()} bytes)"
        }
        .ifBlank { "(empty directory)" }
}

private fun safeReadFile(path: String, context: Context): String? {
    if (!isAllowed(path, context)) return null
    val file = File(path)
    if (!file.exists() || !file.isFile) return null
    val maxBytes = 200_000L
    return if (file.length() > maxBytes)
        file.inputStream().use { it.readNBytes(maxBytes.toInt()) }.toString(Charsets.UTF_8) +
        "\n\n…[truncated — ${file.length()} bytes total, showing first $maxBytes]"
    else file.readText()
}

private fun buildSystemPrompt(req: AiChatRequest) = buildString {
    appendLine("You are an expert Android security researcher assisting with a mobile app pentest using AndroidSpect.")
    if (req.packageName.isNotBlank()) appendLine("Current target package: ${req.packageName}")
    appendLine()
    appendLine("## Tools available")
    appendLine("  • list_directory(path) — list files in a directory")
    appendLine("  • read_file(path) — read file content (max 200 KB)")
    appendLine()
    appendLine("## File navigation rules — follow strictly")
    appendLine("1. NEVER call the same tool with the same path twice.")
    appendLine("2. Navigate step by step: list → find target → read. Do not list the same directory again after getting results.")
    appendLine("3. For a class like com.BugBazaar.ExternalAuthLogin, the smali file is at:")
    appendLine("   .../classes/com/BugBazaar/ExternalAuthLogin.smali  (dots become slashes, add .smali)")
    appendLine("4. Once you find and read a file, stop calling tools and give your analysis.")
    appendLine("5. Maximum tool calls per response: 8. Be efficient.")
    appendLine()
    appendLine("## Root paths")
    appendLine("  /data/data/com.androidspect/cache/decompiler/    — decompiled Smali/Java (jobId subdirs)")
    appendLine("  /data/data/com.androidspect/files/snapshots/     — app snapshots (JSON)")
    appendLine("  /data/data/com.androidspect/files/notes/         — pentest notes")
    appendLine("  /data/data/com.androidspect/files/captures/      — screen captures")
    appendLine()
    appendLine("## Decompiler directory structure")
    appendLine("  cache/decompiler/<pkg>/<jobId>/classes/com/example/SomeClass.smali")
    appendLine("  List cache/decompiler/<pkg>/ first to find the jobId, then navigate to the class.")
    appendLine()
    appendLine("Be concise, technical, actionable. Format with markdown.")
    if (req.systemContext.isNotBlank()) { appendLine(); append(req.systemContext) }
}

private fun buildFileTree(context: Context, pkg: String): List<AiFileEntry> =
    allowedRoots(context).flatMap { root ->
        if (!root.exists()) return@flatMap emptyList()
        root.walkTopDown()
            .filter { it.isFile && it.length() > 0 }
            .filter { pkg.isEmpty() || it.absolutePath.contains(pkg) }
            .take(300)
            .map { f -> AiFileEntry(f.absolutePath, f.name, root.name, f.length(), f.extension) }
            .toList()
    }.sortedWith(compareBy({ it.category }, { it.name }))

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable data class AiProvider(
    val id: String, val name: String, val type: String,
    val apiKey: String = "", val model: String = "",
    val baseUrl: String? = null
)

@Serializable data class AiMessage(val role: String, val content: String)

@Serializable data class AiChatRequest(
    val providerId: String, val messages: List<AiMessage>,
    val packageName: String = "", val systemContext: String = "",
    val maxTokens: Int? = null
)

@Serializable data class AiToolCall(val name: String, val arguments: Map<String, String>, val result: String)
data class AiToolDef(val name: String, val description: String, val parameters: Map<String, Any>)

@Serializable data class AiUsage(val inputTokens: Int, val outputTokens: Int)

@Serializable data class AiChatResponse(
    val content: String, val toolCalls: List<AiToolCall> = emptyList(),
    val error: String? = null
)

@Serializable data class AiFileEntry(
    val path: String, val name: String, val category: String,
    val size: Long, val ext: String
)

@Serializable data class AiFileContent(
    val path: String, val name: String, val size: Long, val content: String
)
