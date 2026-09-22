package cc.lifefuck.minichat

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * 本地保存账号信息，用于 3 天内自动登录。
 * 同时支持保存一个普通账号和一个管理员子账号。
 */
object AuthStore {
    private const val NAME = "mini_chat_auth"

    // 主账号（普通用户）
    private const val KEY_ACCOUNT = "account"
    private const val KEY_PASSWORD = "password"
    private const val KEY_ROLE = "role"
    private const val KEY_TIME = "last_login_time"

    // 管理员子账号（在普通账号已登录后，可额外登录管理员）
    private const val KEY_ADMIN_ACCOUNT = "admin_account"
    private const val KEY_ADMIN_PASSWORD = "admin_password"
    private const val KEY_ADMIN_TIME = "admin_login_time"

    private const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000

    data class Saved(val account: String, val password: String, val role: String)
    data class AdminSaved(val account: String, val password: String)

    /** 保存当前登录的主账号。 */
    fun save(context: Context, account: String, password: String, role: String) {
        context.getSharedPreferences(NAME, MODE_PRIVATE).edit().apply {
            putString(KEY_ACCOUNT, account)
            putString(KEY_PASSWORD, password)
            putString(KEY_ROLE, role)
            putLong(KEY_TIME, System.currentTimeMillis())
            apply()
        }
        ApiClient.currentUsername = if (role == "user") account else ""
    }

    /** 获取 3 天内有效的主账号；过期则清空。 */
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

    /** 保存管理员子账号，同样 3 天有效。 */
    fun saveAdmin(context: Context, account: String, password: String) {
        context.getSharedPreferences(NAME, MODE_PRIVATE).edit().apply {
            putString(KEY_ADMIN_ACCOUNT, account)
            putString(KEY_ADMIN_PASSWORD, password)
            putLong(KEY_ADMIN_TIME, System.currentTimeMillis())
            apply()
        }
    }

    /** 获取 3 天内有效的管理员子账号；过期单独清空。 */
    fun getAdmin(context: Context): AdminSaved? {
        val sp = context.getSharedPreferences(NAME, MODE_PRIVATE)
        val account = sp.getString(KEY_ADMIN_ACCOUNT, null) ?: return null
        val password = sp.getString(KEY_ADMIN_PASSWORD, null) ?: return null
        val time = sp.getLong(KEY_ADMIN_TIME, 0)
        return if (System.currentTimeMillis() - time <= THREE_DAYS_MS) {
            AdminSaved(account, password)
        } else {
            clearAdmin(context)
            null
        }
    }

    /** 清除所有保存的账号信息。 */
    fun clear(context: Context) {
        context.getSharedPreferences(NAME, MODE_PRIVATE).edit().clear().apply()
        ApiClient.clearCookies()
    }

    /** 仅清除管理员子账号。 */
    fun clearAdmin(context: Context) {
        context.getSharedPreferences(NAME, MODE_PRIVATE).edit().apply {
            remove(KEY_ADMIN_ACCOUNT)
            remove(KEY_ADMIN_PASSWORD)
            remove(KEY_ADMIN_TIME)
            apply()
        }
    }

    fun hasSaved(context: Context): Boolean = get(context) != null
    fun hasAdminSaved(context: Context): Boolean = getAdmin(context) != null
}
