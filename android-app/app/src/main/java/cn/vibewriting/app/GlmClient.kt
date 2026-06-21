package cn.vibewriting.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class Completion(val text: String)

enum class ChatModelProvider(
    val preferenceValue: String,
    val displayName: String,
    val modelId: String,
    val chatUrl: String
) {
    ZHIPU(
        "zhipu",
        "智谱 GLM 高速",
        "glm-5.1-highspeed",
        "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    ),
    STEPFUN(
        "stepfun",
        "阶跃 Step 3.7 Flash",
        "step-3.7-flash",
        "https://api.stepfun.com/v1/chat/completions"
    );

    companion object {
        fun fromPreference(value: String?): ChatModelProvider {
            return values().firstOrNull { it.preferenceValue == value } ?: ZHIPU
        }
    }
}

class GlmClient(
    private val apiKey: String,
    private val chatModel: ChatModelProvider = ChatModelProvider.ZHIPU
) {
    private val speechUrl = "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"

    fun complete(
        title: String,
        content: String,
        references: String,
        styleConstraint: String
    ): Completion {
        val system = """
            你是中文文章编辑器里的续写助手。
            贴着作者现有语气续写，不解释，不重写全文。
            参考文件负责事实和表达方向，正文负责文风。
            用户填写的文风约束优先于正文样本。
            不用营销腔，不用总结腔，不用夸张句。
            不要使用引号，不要使用破折号。
            sentence 是光标后可以直接接上的下一句，必须短。
            如果光标前已经有逗号、句号、问号、感叹号或分号，sentence 不要再以标点开头。
            如果光标前一句语义已经结束但缺少标点，sentence 必须以合适的中文标点开头，通常是句号。
            如果光标前半句还没收住，sentence 可以以逗号开头继续承接。
            不要漏掉现有正文和续写之间必要的逗号、句号、问号或感叹号。
            paragraph 是光标后可以直接接上的一小段正文，可以为空。
            只返回 JSON，格式为 {"sentence":"","paragraph":""}。
        """.trimIndent()
        val user = """
            标题：
            ${title.ifBlank { "无标题文章" }}

            正文上下文：
            ${content.trim().takeLast(5000)}

            作者文风要求：
            ${styleConstraint.take(1200).ifBlank { "贴近当前正文的表达方式，段落可以短，句子要顺，允许有一点口语感。" }}

            当前正文文风样本：
            ${content.trim().takeLast(2400).ifBlank { "当前正文还没有可学习的文风样本。" }}

            参考文件：
            ${references.take(5000).ifBlank { "无" }}
        """.trimIndent()
        val body = chatBody(system, user, maxTokens = 220, temperature = 0.62, responseJson = true)
        val response = postJson(chatModel.chatUrl, body)
        val text = response.getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
        val completed = parseCompletionText(text)
        return Completion(cleanText(completed))
    }

    fun transcribe(file: File): String {
        require(file.exists() && file.length() > WAV_HEADER_BYTES) { "没有可转写的音频" }
        require(file.length() <= MAX_AUDIO_BYTES) { "音频文件不能超过 25 MB" }

        val boundary = "VibeWriting${UUID.randomUUID()}"
        val connection = open(speechUrl, connectTimeoutMs = 20_000, readTimeoutMs = 60_000).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            doOutput = true
        }
        connection.outputStream.buffered().use { output ->
            fun write(value: String) = output.write(value.toByteArray(Charsets.UTF_8))
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
            write("glm-asr-2512\r\n")
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"stream\"\r\n\r\n")
            write("false\r\n")
            write("--$boundary\r\n")
            write("Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n")
            write("Content-Type: audio/wav\r\n\r\n")
            file.inputStream().use { it.copyTo(output) }
            write("\r\n--$boundary--\r\n")
        }
        val response = readResponse(connection)
        return response.optString("text")
            .ifBlank { response.optJSONObject("data")?.optString("text").orEmpty() }
            .ifBlank { throw IllegalStateException("语音转写没有返回文本") }
            .trim()
    }

    fun polish(
        transcript: String,
        styleSample: String,
        styleConstraint: String
    ): String {
        val system = """
            你是中文口述稿编辑助手。
            把口语转写整理成可以直接接入文章的正文。
            删除口头禅、重复和无意义停顿，修正明显转写错误。
            保留原意、事实、观点和第一人称语气，不额外发挥。
            跟随给出的作者文风样本。
            用户填写的文风约束优先于作者文风样本。
            不使用引号，不使用破折号。
            只返回润色后的正文，不解释。
        """.trimIndent()
        val user = """
            作者文风样本：
            ${styleSample.takeLast(2400).ifBlank { "暂无样本，使用自然、克制的中文表达" }}

            用户文风约束：
            ${styleConstraint.take(2000).ifBlank { "未设置，跟随作者文风样本" }}

            语音转写：
            $transcript
        """.trimIndent()
        val response = postJson(chatModel.chatUrl, chatBody(system, user, 1200, 0.45))
        val text = response.getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")
        return cleanText(text)
    }

    private fun chatBody(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Double,
        responseJson: Boolean = false
    ): JSONObject {
        return JSONObject()
            .put("model", chatModel.modelId)
            .put("stream", false)
            .put("max_tokens", maxTokens)
            .put("temperature", temperature)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
            .also {
                when (chatModel) {
                    ChatModelProvider.ZHIPU -> {
                        it.put("thinking", JSONObject().put("type", "disabled"))
                        if (responseJson) {
                            it.put("response_format", JSONObject().put("type", "json_object"))
                        }
                    }
                    ChatModelProvider.STEPFUN -> {
                        it.put("reasoning_effort", "low")
                    }
                }
            }
    }

    private fun postJson(url: String, body: JSONObject): JSONObject {
        val connection = open(url, connectTimeoutMs = 10_000, readTimeoutMs = 20_000).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
        }
        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
        return readResponse(connection)
    }

    private fun open(
        url: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream: InputStream = if (status in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }
        val text = stream.bufferedReader().use { it.readText() }
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw IllegalStateException("服务返回了无法识别的内容")
        }
        if (status !in 200..299) {
            val message = json.optJSONObject("error")?.optString("message").orEmpty()
                .ifBlank { json.optString("message") }
                .ifBlank { "请求失败，状态码 $status" }
            throw IllegalStateException(message)
        }
        return json
    }

    private fun parseJsonObject(value: String): JSONObject {
        val text = value.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        return JSONObject(text)
    }

    private fun parseCompletionText(value: String): String {
        val text = value.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        runCatching {
            val json = JSONObject(text)
            return json.optString("sentence")
                .ifBlank { json.optString("text") }
                .ifBlank { json.optString("paragraph") }
                .ifBlank { text }
        }
        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            runCatching {
                val json = JSONObject(text.substring(jsonStart, jsonEnd + 1))
                return json.optString("sentence")
                    .ifBlank { json.optString("text") }
                    .ifBlank { json.optString("paragraph") }
                    .ifBlank { text }
            }
        }
        return text
    }

    private fun cleanText(value: String): String {
        return value
            .replace(Regex("[“”\"'「」『』]"), "")
            .replace(Regex("[—–]"), "，")
            .replace(Regex("-{2,}"), "，")
            .trim()
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44L
        const val MAX_AUDIO_BYTES = 25L * 1024L * 1024L
    }
}
