package cc.lifefuck.minichat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文字群聊页面：Miuix 风格消息气泡 + 底部输入框 + 全员禁言开关响应。
 * 顶部提供退出账号和管理员入口。
 * 禁止截图/录屏、发送消息即时显示 pending 状态。
 */
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 禁止截图和录屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        enableEdgeToEdge()
        setContent {
            MiuixTheme {
                ChatPage()
            }
        }
    }
}

data class Msg(
    val id: Int,
    val username: String,
    val content: String,
    val time: String,
    val pending: Boolean = false
)

@Composable
fun ChatPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf(listOf<Msg>()) }
    var lastId by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var isMuted by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var tempId by remember { mutableIntStateOf(-1) }

    // 管理员登录弹窗
    var showAdminLogin by remember { mutableStateOf(false) }
    var adminAccount by remember { mutableStateOf("") }
    var adminPassword by remember { mutableStateOf("") }
    var adminError by remember { mutableStateOf("") }
    var adminChecking by remember { mutableStateOf(false) }

    // 首次进入滚动到底
    var firstLoad by remember { mutableStateOf(true) }

    // 轮询消息
    LaunchedEffect(Unit) {
        while (true) {
            val (ok, json) = ApiClient.get("/api/messages?last_id=$lastId")
            if (ok) {
                val new = parseMessages(json)
                if (new.isNotEmpty()) {
                    messages = (messages + new).distinctBy { it.id }
                    lastId = messages.maxOf { it.id }
                    scope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
                isMuted = json.optBoolean("muted", false)
                if (firstLoad) {
                    firstLoad = false
                    if (messages.isNotEmpty()) {
                        scope.launch { listState.scrollToItem(messages.size - 1) }
                    }
                }
            }
            delay(1500)
        }
    }

    // 管理员登录弹窗
    if (showAdminLogin) {
        AlertDialog(
            onDismissRequest = {
                if (!adminChecking) showAdminLogin = false
            },
            title = { Text("管理员登录") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (adminError.isNotBlank()) {
                        Text(adminError, color = Color(0xFFE94634), fontSize = 13.sp)
                    }
                    TextField(
                        value = adminAccount,
                        onValueChange = { adminAccount = it },
                        label = "管理员账号",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    TextField(
                        value = adminPassword,
                        onValueChange = { adminPassword = it },
                        label = "管理员密码",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            adminChecking = true
                            adminError = ""
                            val (ok, json) = ApiClient.post(
                                "/api/admin-login",
                                mapOf("username" to adminAccount, "password" to adminPassword)
                            )
                            adminChecking = false
                            if (ok) {
                                AuthStore.saveAdmin(context, adminAccount, adminPassword)
                                showAdminLogin = false
                                adminAccount = ""
                                adminPassword = ""
                                context.startActivity(Intent(context, AdminActivity::class.java))
                            } else {
                                adminError = ApiClient.errorText(json)
                            }
                        }
                    }
                ) { Text("登录") }
            },
            dismissButton = {
                TextButton(onClick = { showAdminLogin = false }) { Text("取消") }
            }
        )
    }

    val hasAdmin = AuthStore.hasAdminSaved(context)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = "life的群组",
                subtitle = "当前用户：${ApiClient.currentUsername.ifBlank { "我" }}",
                actions = {
                    IconButton(
                        onClick = {
                            if (hasAdmin) {
                                context.startActivity(Intent(context, AdminActivity::class.java))
                            } else {
                                showAdminLogin = true
                            }
                        },
                        content = {
                            Text(
                                if (hasAdmin) "管理" else "管理员",
                                fontSize = 14.sp
                            )
                        }
                    )
                    IconButton(
                        onClick = {
                            AuthStore.clear(context)
                            context.startActivity(Intent(context, MainActivity::class.java))
                            (context as? Activity)?.finishAffinity()
                        },
                        content = {
                            Text("退出", fontSize = 14.sp)
                        }
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .padding(horizontal = 12.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }

            if (errorText.isNotBlank()) {
                Text(
                    text = errorText,
                    color = Color(0xFFE94634),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            if (!isMuted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        label = "说点什么…",
                        modifier = Modifier.weight(1f),
                        singleLine = false,
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (isSending || input.isBlank()) return@Button
                            val text = input.trim()
                            val now = java.text.SimpleDateFormat(
                                "HH:mm",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date())
                            // 立即本地显示 pending 消息
                            val pendingMsg = Msg(
                                id = tempId--,
                                username = ApiClient.currentUsername,
                                content = text,
                                time = "2026-01-01 $now",
                                pending = true
                            )
                            messages = messages + pendingMsg
                            input = ""
                            scope.launch {
                                listState.animateScrollToItem(messages.size - 1)
                            }
                            scope.launch {
                                isSending = true
                                errorText = ""
                                val (ok, json) = ApiClient.post(
                                    "/api/send",
                                    mapOf("content" to text)
                                )
                                if (!ok) {
                                    errorText = ApiClient.errorText(json)
                                }
                                // 移除本地 pending 消息，等轮询拉取正式消息
                                messages = messages.filter { it.id != pendingMsg.id }
                                isSending = false
                            }
                        },
                        modifier = Modifier.heightIn(min = 44.dp),
                        minHeight = 44.dp,
                        enabled = !isSending
                    ) {
                        Text(if (isSending) "发送中" else "发送")
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFFFF3E0))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "全员禁言中，暂时无法发言",
                        color = Color(0xFFFF6D00),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: Msg) {
    val isMe = msg.username == ApiClient.currentUsername
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        msg.pending -> Color(0xFFB0B0B0)
                        isMe -> MiuixTheme.colorScheme.primary
                        else -> MiuixTheme.colorScheme.surfaceContainerHigh
                    }
                )
                .padding(12.dp)
        ) {
            if (!isMe) {
                Text(
                    text = msg.username,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = msg.content,
                fontSize = 15.sp,
                color = if (isMe || msg.pending) Color.White
                else MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = msg.time.substring(11, 16),
                fontSize = 10.sp,
                color = if (isMe || msg.pending) Color.White.copy(alpha = 0.75f)
                else MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

private fun parseMessages(json: JSONObject): List<Msg> {
    val arr = json.optJSONArray("messages") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        Msg(
            id = obj.optInt("id", 0),
            username = obj.optString("username", "未知用户"),
            content = obj.optString("content", ""),
            time = obj.optString("created_at", "")
        )
    }
}
