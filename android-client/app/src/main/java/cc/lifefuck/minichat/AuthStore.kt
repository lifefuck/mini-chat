package cc.lifefuck.minichat

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * 本地保存账号信息，用于 3 天内自动登录。
 */
object AuthStore {
    private const val NAME = "mini_chat_auth"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_PASSWORD = "password"
    private const val KEY_ROLE = "role"
    private const val KEY_TIME = "last_login_time"
    private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000

    fun save(context: Context, account: String, password: String, role: String) {
        context.getSharedPreferences(NAME, MODE_PRIVATE).edit().apply {
            putString(KEY_ACCOUNT, account)
            putString(KEY_PASSWORD, password)
            putString(KEY_ROLE, role)
            putLong(KEY_TIME, System.currentTimeMillis())
            apply()
        }
    }

    data class Saved(val account: String, val password: String, val role: String)

    fun get(context: Context): Saved? {
        val sp = context.getSharedPreferences(NAME, MODE_PRIVATE)
        val account = sp.getString(KEY_ACCOUNT, null) ?: return null
        val password = sp.getString(KEY_PASSWORD, null) ?: return null
        val role = sp.getString(KEY_ROLE, "") ?: ""
        val time = sp.getLong(KEY_TIME, 0)
        if (System.currentTimeMillis() - time > THREE_DAYS_MS) {
            clear(context)
            return null
        }
        return Saved(account, password, role)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(NAME, MODE_PRIVATE).edit().clear().apply()
        ApiClient.clearCookies()
    }

    fun hasSaved(context: Context): Boolean = get(context) != null
}
