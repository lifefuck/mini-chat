package cc.lifefuck.minichat

import android.content.Context
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * 网络请求封装：管理服务器地址、Cookie(Session) 和统一 POST/GET。
 */
object ApiClient {

    // 默认服务器地址，指向 Cloudflare Tunnel 暴露的域名
    private const val DEFAULT_BASE = "https://mini-chat.wxlost.com"

    private val client = OkHttpClient.Builder()
        .cookieJar(SessionCookieJar())
        .build()

    private var baseUrl: String = DEFAULT_BASE
    private var currentUsername: String = ""

    fun setBaseUrl(url: String) {
        baseUrl = url.trim().removeSuffix("/")
    }

    fun getBaseUrl(): String = baseUrl

    fun setUsername(name: String) {
        currentUsername = name
    }

    fun getUsername(): String = currentUsername

    /**
     * 同步 POST application/x-www-form-urlencoded。
     * 返回 JSONObject 或 null（网络/解析失败）。
     */
    fun postSync(path: String, params: Map<String, String>): JSONObject? {
        val body = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder()
            .url("$baseUrl$path")
            .post(body)
            .build()
        return executeJson(req)
    }

    /**
     * 同步 GET，带查询参数。
     */
    fun getSync(path: String, params: Map<String, String> = emptyMap()): JSONObject? {
        val qs = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val url = if (qs.isBlank()) "$baseUrl$path" else "$baseUrl$path?$qs"
        val req = Request.Builder().url(url).get().build()
        return executeJson(req)
    }

    private fun executeJson(req: Request): JSONObject? {
        return try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) return null
                JSONObject(body)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 简单封装的内存 CookieJar，持久化交给系统不是必须的，进程存活即可。
     */
    private class SessionCookieJar : CookieJar {
        private val cookies = mutableMapOf<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this.cookies[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookies[url.host] ?: emptyList()
        }
    }
}
