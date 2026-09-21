package cc.lifefuck.minichat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 管理员后台 Activity：用户管理 + 全员禁言开关。
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var recyclerUsers: RecyclerView
    private lateinit var swMute: Switch
    private val users = mutableListOf<User>()
    private lateinit var adapter: UserAdapter
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        title = "管理后台"

        recyclerUsers = findViewById(R.id.recyclerUsers)
        swMute = findViewById(R.id.swMute)

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        adapter = UserAdapter()
        recyclerUsers.layoutManager = LinearLayoutManager(this)
        recyclerUsers.adapter = adapter

        swMute.setOnCheckedChangeListener { _, isChecked ->
            thread {
                val path = if (isChecked) "/api/admin/mute" else "/api/admin/unmute"
                ApiClient.postSync(path, emptyMap())
            }
        }

        loadUsers()
    }

    private fun loadUsers() {
        thread {
            val json = ApiClient.getSync("/api/admin/users") ?: JSONObject()
            val arr = json.optJSONArray("users")
            val muted = json.optBoolean("muted", false)
            runOnUiThread {
                swMute.isChecked = muted
                if (arr != null) {
                    users.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        users.add(
                            User(
                                id = obj.optInt("id"),
                                qq = obj.optString("qq"),
                                username = obj.optString("username"),
                                role = obj.optString("role"),
                                status = obj.optString("status")
                            )
                        )
                    }
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    data class User(
        val id: Int, val qq: String, val username: String,
        val role: String, val status: String
    )

    inner class UserAdapter : RecyclerView.Adapter<UserAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvInfo: TextView = itemView.findViewById(R.id.tvUserInfo)
            val tvStatus: TextView = itemView.findViewById(R.id.tvUserStatus)
            val btnToggle: Button = itemView.findViewById(R.id.btnToggle)
            val btnRename: Button = itemView.findViewById(R.id.btnRename)
            val btnResetPwd: Button = itemView.findViewById(R.id.btnResetPwd)
            val btnDelete: Button = itemView.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_user, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = users[position]
            holder.tvInfo.text = "QQ: ${user.qq}\n用户名: ${user.username}"
            holder.tvStatus.text = if (user.role == "admin") "管理员" else "状态: ${user.status}"

            if (user.role == "admin") {
                holder.btnToggle.visibility = View.GONE
                holder.btnRename.visibility = View.GONE
                holder.btnResetPwd.visibility = View.GONE
                holder.btnDelete.visibility = View.GONE
                return
            }

            holder.btnToggle.text = if (user.status == "approved") "冻结" else "通过"
            holder.btnToggle.setOnClickListener {
                val path = if (user.status == "approved") "/api/admin/reject/${user.id}" else "/api/admin/approve/${user.id}"
                postAndRefresh(path)
            }

            holder.btnRename.setOnClickListener {
                val edit = EditText(this@AdminActivity).apply { hint = "新用户名" }
                AlertDialog.Builder(this@AdminActivity)
                    .setTitle("修改用户名")
                    .setView(edit)
                    .setPositiveButton("确定") { _, _ ->
                        val name = edit.text.toString().trim()
                        postAndRefresh("/api/admin/change-username/${user.id}", mapOf("new_username" to name))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            holder.btnResetPwd.setOnClickListener {
                val edit = EditText(this@AdminActivity).apply {
                    hint = "新密码"
                    inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                AlertDialog.Builder(this@AdminActivity)
                    .setTitle("重置密码")
                    .setView(edit)
                    .setPositiveButton("确定") { _, _ ->
                        val pwd = edit.text.toString().trim()
                        postAndRefresh("/api/admin/reset-password/${user.id}", mapOf("new_password" to pwd))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            holder.btnDelete.setOnClickListener {
                AlertDialog.Builder(this@AdminActivity)
                    .setTitle("删除账号")
                    .setMessage("确定删除 ${user.username} 及其所有消息？")
                    .setPositiveButton("删除") { _, _ ->
                        postAndRefresh("/api/admin/delete/${user.id}")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }

        override fun getItemCount(): Int = users.size
    }

    private fun postAndRefresh(path: String, params: Map<String, String> = emptyMap()) {
        thread {
            val json = ApiClient.postSync(path, params)
            runOnUiThread {
                if (json?.optBoolean("ok", false) == true) {
                    loadUsers()
                } else {
                    toast(json?.optString("error", "操作失败") ?: "操作失败")
                }
            }
        }
    }
}
