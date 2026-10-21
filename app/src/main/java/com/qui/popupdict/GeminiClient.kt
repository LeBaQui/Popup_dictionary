package com.qui.popupdict

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ModelInfo(val id: String, val displayName: String)

object GeminiClient {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    // Lấy thông báo lỗi từ phản hồi của API
    private fun errorMessage(conn: HttpURLConnection, code: Int): String {
        val err = try {
            (conn.errorStream ?: conn.inputStream).bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
        return try {
            JSONObject(err).getJSONObject("error").getString("message")
        } catch (_: Exception) {
            "HTTP $code"
        }
    }

    // Liệt kê các model chat được (có generateContent), giống nút List trong gemini-chat-1.html.
    // Chạy trên thread nền.
    fun listModels(key: String): List<ModelInfo> {
        val result = ArrayList<ModelInfo>()
        var pageToken = ""
        do {
            var u = "$BASE/models?pageSize=1000"
            if (pageToken.isNotEmpty()) u += "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8")
            val conn = URL(u).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.setRequestProperty("x-goog-api-key", key)

                val code = conn.responseCode
                if (code !in 200..299) throw RuntimeException(errorMessage(conn, code))

                val data = JSONObject(conn.inputStream.bufferedReader().readText())
                val arr = data.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var ok = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j) == "generateContent") {
                            ok = true
                            break
                        }
                    }
                    if (!ok) continue
                    val id = m.optString("name").removePrefix("models/")
                    if (id.isNotEmpty()) result.add(ModelInfo(id, m.optString("displayName")))
                }
                pageToken = data.optString("nextPageToken", "")
            } finally {
                conn.disconnect()
            }
        } while (pageToken.isNotEmpty())
        return result
    }

    // Gọi streamGenerateContent (SSE) giống file gemini-chat-1.html. Chạy trên thread nền.
    fun stream(
        key: String,
        model: String,
        think: String,
        text: String,
        cancelled: () -> Boolean,
        onChunk: (String) -> Unit
    ) {
        val url = URL("$BASE/models/${URLEncoder.encode(model, "UTF-8")}:streamGenerateContent?alt=sse")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", key)

            val part = JSONObject().put("text", text)
            val content = JSONObject().put("role", "user").put("parts", JSONArray().put(part))
            val body = JSONObject().put("contents", JSONArray().put(content))
            if (think.isNotEmpty()) {
                body.put(
                    "generationConfig",
                    JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", think))
                )
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException(errorMessage(conn, code))

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            while (true) {
                if (cancelled()) break
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val js = line.substring(5).trim()
                if (js.isEmpty()) continue
                try {
                    val parts = JSONObject(js)
                        .optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.getJSONObject(i)
                        if (p.optBoolean("thought")) continue
                        val t = p.optString("text")
                        if (t.isNotEmpty()) onChunk(t)
                    }
                } catch (_: Exception) {
                    // bỏ qua chunk lỗi
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
