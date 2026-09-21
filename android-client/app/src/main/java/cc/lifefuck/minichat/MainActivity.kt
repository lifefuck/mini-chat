package cc.lifefuck.minichat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 入口 Activity：登录、注册、修改用户名、管理员登录四个功能卡片切换。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var contentLogin: View
    private lateinit var contentRegister: View
    private lateinit var contentChange: View
    private lateinit var contentAdmin: View

    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "life的群组"

        bindViews()
        setupTabs()
        setupActions()
    }

    private fun bindViews() {
        contentLogin = findViewById(R.id.contentLogin)
        contentRegister = findViewById(R.id.contentRegister)
        contentChange = findViewById(R.id.contentChange)
        contentAdmin = findViewById(R.id.contentAdmin)
        progress = findViewById(R.id.progress)
    }

    private fun setupTabs() {
        findViewById<Button>(R.id.tabLogin).setOnClickListener { showCard(0) }
        findViewById<Button>(R.id.tabRegister).setOnClickListener { showCard(1) }
        findViewById<Button>(R.id.tabChange).setOnClickListener { showCard(2) }
        findViewById<Button>(R.id.tabAdmin).setOnClickListener { showCard(3) }
    }

    private fun showCard(index: Int) {
        contentLogin.visibility = if (index == 0) View.VISIBLE else View.GONE
        contentRegister.visibility = if (index == 1) View.VISIBLE else View.GONE
        contentChange.visibility = if (index == 2) View.VISIBLE else View.GONE
        contentAdmin.visibility = if (index == 3) View.VISIBLE else View.GONE
    }

    private fun setupActions() {
        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val qq = valOf(R.id.etLoginQq)
            val pwd = valOf(R.id.etLoginPwd)
            if (qq.isBlank() || pwd.isBlank()) {
                toast("QQ 号和密码不能为空")
                return@setOnClickListener
            }
            doPost("/api/login", mapOf("qq" to qq, "password" to pwd)) { json ->
                when (json.optString("status", "")) {
                    "approved" -> {
                        ApiClient.setUsername(json.optString("username"))
                        startActivity(Intent(this, ChatActivity::class.java))
                        finish()
                    }
                    "pending" -> toast("账号正在等待管理员审核")
                    else -> toast(json.optString("error", "登录失败"))
                }
            }
        }

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            val qq = valOf(R.id.etRegQq)
            val name = valOf(R.id.etRegName)
            val pwd = valOf(R.id.etRegPwd)
            if (qq.isBlank() || name.isBlank() || pwd.length < 6) {
                toast("请填写完整，密码至少 6 位")
                return@setOnClickListener
            }
            doPost("/api/register", mapOf("qq" to qq, "username" to name, "password" to pwd)) { json ->
                if (json.optBoolean("ok", false)) {
                    toast("注册成功，等待管理员审核")
                } else {
                    toast(json.optString("error", "注册失败"))
                }
            }
        }

        findViewById<Button>(R.id.btnChangeName).setOnClickListener {
            val qq = valOf(R.id.etChangeQq)
            val pwd = valOf(R.id.etChangePwd)
            val newName = valOf(R.id.etChangeNewName)
            if (qq.isBlank() || pwd.isBlank() || newName.isBlank()) {
                toast("请填写完整")
                return@setOnClickListener
            }
            doPost("/api/change-username", mapOf("qq" to qq, "password" to pwd, "new_username" to newName)) { json ->
                if (json.optBoolean("ok", false)) {
                    toast("用户名已修改")
                } else {
                    toast(json.optString("error", "修改失败"))
                }
            }
        }

        findViewById<Button>(R.id.btnAdminLogin).setOnClickListener {
            val user = valOf(R.id.etAdminUser)
            val pwd = valOf(R.id.etAdminPwd)
            doPost("/api/admin-login", mapOf("username" to user, "password" to pwd)) { json ->
                if (json.optBoolean("ok", false)) {
                    startActivity(Intent(this, AdminActivity::class.java))
                    finish()
                } else {
                    toast(json.optString("error", "管理员登录失败"))
                }
            }
        }
    }

    private fun valOf(id: Int): String = findViewById<EditText>(id).text.toString().trim()

    private fun doPost(path: String, params: Map<String, String>, onResult: (JSONObject) -> Unit) {
        progress.visibility = View.VISIBLE
        thread {
            val json = ApiClient.postSync(path, params)
                ?: JSONObject().put("error", "网络错误或服务器无响应")
            runOnUiThread {
                progress.visibility = View.GONE
                onResult(json)
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
