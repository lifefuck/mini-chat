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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * 群聊 Activity：RecyclerView 展示消息，轮询拉取新消息。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MessageAdapter
    private lateinit var input: EditText
    private lateinit var btnSend: Button
    private lateinit var tvMute: TextView
    private val messages = mutableListOf<Message>()
    private var lastId = 0
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            loadMessages()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        title = "life的群组"

        recyclerView = findViewById(R.id.recyclerMessages)
        input = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        tvMute = findViewById(R.id.tvMute)

        adapter = MessageAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnSend.setOnClickListener { sendMessage() }

        loadMessages()
        handler.postDelayed(pollRunnable, 1500)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(pollRunnable)
    }

    private fun loadMessages() {
        thread {
            val json = ApiClient.getSync("/api/messages", mapOf("last_id" to lastId.toString()))
            runOnUiThread {
                json?.let {
                    val muted = it.optBoolean("muted", false)
                    tvMute.visibility = if (muted) View.VISIBLE else View.GONE
                    input.isEnabled = !muted
                    btnSend.isEnabled = !muted

                    val arr = it.optJSONArray("messages")
                    appendMessages(arr)
                }
            }
        }
    }

    private fun appendMessages(arr: JSONArray?) {
        if (arr == null) return
        var added = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val msg = Message(
                id = obj.optInt("id"),
                username = obj.optString("username"),
                content = obj.optString("content"),
                time = obj.optString("created_at")
            )
            messages.add(msg)
            lastId = maxOf(lastId, msg.id)
            added = true
        }
        if (added) {
            adapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(messages.size - 1)
        }
    }

    private fun sendMessage() {
        val content = input.text.toString().trim()
        if (content.isEmpty()) return
        input.isEnabled = false
        btnSend.isEnabled = false
        thread {
            val json = ApiClient.postSync("/api/send", mapOf("content" to content))
            runOnUiThread {
                input.isEnabled = true
                btnSend.isEnabled = true
                if (json?.optBoolean("ok", false) == true) {
                    input.setText("")
                    loadMessages()
                } else {
                    toast(json?.optString("error", "发送失败") ?: "发送失败")
                }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    data class Message(val id: Int, val username: String, val content: String, val time: String)

    inner class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(R.id.tvMsgName)
            val tvTime: TextView = itemView.findViewById(R.id.tvMsgTime)
            val tvText: TextView = itemView.findViewById(R.id.tvMsgText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val layout = if (viewType == 1) R.layout.item_message_me else R.layout.item_message_other
            val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            return VH(view)
        }

        override fun getItemViewType(position: Int): Int {
            return if (messages[position].username == currentName()) 1 else 0
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg = messages[position]
            holder.tvName.text = msg.username
            holder.tvTime.text = msg.time
            holder.tvText.text = msg.content
        }

        override fun getItemCount(): Int = messages.size
    }

    private fun currentName(): String = ApiClient.getUsername()
}
