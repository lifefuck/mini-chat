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
    private const val BASE_URL = "https://mini-chat.wxlost.com"

    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(JavaNetCookieJar(cookieManager))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // 内存缓存当前用户名，避免反复请求 /api/me
    @Volatile
    var currentUsername: String = ""

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
                    val text = response.body?.string() ?: "{}"
                    val json = JSONObject(text)
                    val ok = json.optBoolean("ok", false)
                    return@withContext Pair(ok, json)
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
                    val text = response.body?.string() ?: "{}"
                    Pair(true, JSONObject(text))
                }
            } catch (e: IOException) {
                Pair(false, JSONObject().put("error", "无法连接服务器"))
            } catch (e: Exception) {
                Pair(false, JSONObject().put("error", "请求异常：${e.message}"))
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
    }
}
