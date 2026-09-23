package cc.lifefuck.minichat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.JavaNetCookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

/**
 * 后端 API 封装：处理 session cookie、POST/GET、JSON 解析、错误翻译。
 */
object ApiClient {
    // 默认服务器地址，用户要求硬编码，不手动输入
    // 默认服务器地址；应用启动时会被 MainActivity 读取的用户自定义地址覆盖
    const val DEFAULT_SERVER_URL = "https://mini-chat.wxlost.com"
    private var BASE_URL = DEFAULT_SERVER_URL

    /**
     * 动态切换 API 服务器地址。
     *
     * @param url 完整的 https:// 地址，末尾不带斜杠
     */
    fun setBaseUrl(url: String) {
        BASE_URL = url.trim().trimEnd('/')
    }

    /**
     * 获取当前 API 服务器地址。
     */
    fun getBaseUrl(): String = BASE_URL

    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // 内存缓存当前用户名与用户 ID，避免反复请求 /api/me
    @Volatile
    var currentUsername: String = ""
    @Volatile
    var currentUserId: Int = 0

    /**
     * 发送 POST 表单请求，返回解析后的 JSON。
     *
     * @param path 相对路径，如 "/api/login"
     * @param body 表单字段
     * @return Pair(isOk, JSONObject)，网络异常时也返回 false 和错误信息
     */
    suspend fun post(path: String, body: Map<String, String>): Pair<Boolean, JSONObject> {
        return withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder().apply {
                    body.forEach { (k, v) -> add(k, v) }
                }.build()
                val request = Request.Builder()
                    .url("$BASE_URL$path")
                    .post(form)
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext Pair(false, errJson(response.code, text))
                    }
                    return@withContext parseJson(text)
                }
            } catch (e: IOException) {
                return@withContext Pair(false, JSONObject().put("error", "无法连接服务器"))
            } catch (e: Exception) {
                return@withContext Pair(false, JSONObject().put("error", "请求异常：${e.message}"))
            }
        }
    }

    /**
     * 发送 GET 请求，返回 JSON。
     */
    suspend fun get(path: String): Pair<Boolean, JSONObject> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL$path")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext Pair(false, errJson(response.code, text))
                    }
                    return@withContext parseJson(text)
                }
            } catch (e: IOException) {
                Pair(false, JSONObject().put("error", "无法连接服务器"))
            } catch (e: Exception) {
                Pair(false, JSONObject().put("error", "请求异常：${e.message}"))
            }
        }
    }

    /**
     * 尝试把响应体解析为 JSON；失败时返回包含原文片段的错误对象。
     */
    private fun parseJson(text: String): Pair<Boolean, JSONObject> {
        return try {
            val json = JSONObject(text)
            val ok = json.optBoolean("ok", false)
            Pair(ok, json)
        } catch (e: Exception) {
            Pair(false, errJson(200, text))
        }
    }

    /**
     * 构造一个携带 HTTP 状态码和响应原文摘要的错误 JSON。
     */
    private fun errJson(code: Int, text: String): JSONObject {
        val snippet = text.trim().take(200).replace("\n", " ")
        val msg = when (code) {
            403 -> "请求被拦截（HTTP 403）"
            429 -> "请求过于频繁（HTTP 429）"
            500, 502, 503, 504 -> "服务器暂不可用（HTTP $code）"
            else -> "服务器返回错误（HTTP $code）"
        }
        val detail = if (snippet.isBlank()) msg else "$msg：$snippet"
        return JSONObject()
            .put("error", detail)
            .put("http_code", code)
    }

    /**
     * 判断最近一次错误是否为 Cloudflare/网关层面的拦截（403/429/5xx）。
     */
    fun isGatewayError(json: JSONObject?): Boolean {
        val code = json?.optInt("http_code", 0) ?: 0
        return code in listOf(403, 429, 500, 502, 503, 504)
    }

    /**
     * 上传本机 RSA 公钥到服务器，用于端到端加密。
     */
    suspend fun registerPublicKey(publicKey: String): Pair<Boolean, JSONObject> {
        return post("/api/register-public-key", mapOf("public_key" to publicKey))
    }

    /**
     * 获取所有已审核用户的公钥列表，用于发送加密群消息。
     */
    suspend fun fetchPublicKeys(): Pair<Boolean, JSONObject> {
        return get("/api/public-keys")
    }

    /**
     * 健康检查：探测服务器是否在线。
     *
     * @return Pair(是否在线, 最后一次错误描述)，在线时错误描述为空
     */
    suspend fun checkHealth(): Pair<Boolean, String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/api/health")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withContext Pair(false, "服务器返回错误（HTTP ${response.code}）")
                    }
                    return@withContext try {
                        val json = JSONObject(text)
                        if (json.optBoolean("ok", false)) {
                            Pair(true, "")
                        } else {
                            Pair(false, "服务器状态异常")
                        }
                    } catch (_: Exception) {
                        Pair(false, "服务器返回非 JSON 数据：${text.take(80)}")
                    }
                }
            } catch (e: IOException) {
                return@withContext Pair(false, "无法连接服务器：${e.message ?: "网络不可达"}")
            } catch (e: Exception) {
                return@withContext Pair(false, "检查服务器失败：${e.message}")
            }
        }
    }

    /**
     * 从 JSON 里取出后端错误文案；为空时返回默认提示。
     */
    fun errorText(json: JSONObject?): String {
        return json?.optString("error", "")?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"
    }

    /**
     * 清空 cookie，用于退出登录或切换账号。
     */
    fun clearCookies() {
        cookieManager.cookieStore.removeAll()
        currentUsername = ""
        currentUserId = 0
    }
}
