package cc.lifefuck.minichat

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文字群聊页面。
 * - 禁止截图录屏、最近任务白屏（继承 BaseActivity）
 * - 底部输入框随键盘上抬，键盘弹出时聊天记录自动滚到底部
 * - 发送消息立即显示"发送中…"气泡，成功后立即插入后端返回的正式消息
 * - 顶部提供退出账号和管理员登录/进入后台入口
 */
class ChatActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

/** 格式化时间为 HH:mm 显示。 */
private fun formatTime(timeStr: String): String {
    if (timeStr.length >= 16) {
        return timeStr.substring(11, 16)
    }
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date())
}

@Composable
fun ChatPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    var messages by remember { mutableStateOf(listOf<Msg>()) }
    var input by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var errorTip by remember { mutableStateOf("") }
    var lastId by remember { mutableStateOf(0) }

    // 管理员入口状态
    var showAdminLogin by remember { mutableStateOf(false) }
    var adminAccount by remember { mutableStateOf("") }
    var adminPwd by remember { mutableStateOf("") }
    var adminLogging by remember { mutableStateOf(false) }
    var adminLoginError by remember { mutableStateOf("") }
    val hasAdminSaved = remember { AuthStore.getAdmin(context) != null }
    var needsAdminRefresh by remember { mutableIntStateOf(0) }
    val hasAdmin = remember(needsAdminRefresh) { AuthStore.getAdmin(context) != null }

    // 加载历史消息
    suspend fun loadMessages(): Boolean {
        val (_, json) = ApiClient.get("/api/messages?last_id=0")
        val arr = json.optJSONArray("messages") ?: return false
        val loaded = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Msg(
                id = o.optInt("id"),
                username = o.optString("username"),
                content = o.optString("content"),
                time = formatTime(o.optString("created_at", ""))
            )
        }
        messages = loaded
        lastId = loaded.maxOfOrNull { it.id } ?: 0
        muted = json.optBoolean("muted", false)
        return true
    }

    // 轮询新消息
    LaunchedEffect(Unit) {
        loadMessages()
        while (true) {
            delay(1500)
            val (_, json) = ApiClient.get("/api/messages?last_id=$lastId")
            val arr = json.optJSONArray("messages") ?: continue
            val new = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Msg(
                    id = o.optInt("id"),
                    username = o.optString("username"),
                    content = o.optString("content"),
                    time = formatTime(o.optString("created_at", ""))
                )
            }
            if (new.isNotEmpty()) {
                messages = messages + new
                lastId = new.maxOf { it.id }
            }
            muted = json.optBoolean("muted", false)
        }
    }

    // 消息列表变化后滚到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // 键盘弹出时继续滚到底部，确保聊天记录可见
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = "life的群组",
                subtitle = "",
                actions = {
                    // 管理员入口（普通账号登录后仍可直接进后台）
                    IconButton(
                        onClick = {
                            val saved = AuthStore.getAdmin(context)
                            if (saved != null) {
                                scope.launch {
                                    adminLogging = true
                                    val (ok, _) = ApiClient.post(
                                        "/api/admin-login",
                                        mapOf("username" to saved.account, "password" to saved.password)
                                    )
                                    adminLogging = false
                                    if (ok) {
                                        context.startActivity(Intent(context, AdminActivity::class.java))
                                    } else {
                                        showAdminLogin = true
                                        adminLoginError = "管理员凭证已过期，请重新登录"
                                    }
                                }
                            } else {
                                adminAccount = ""
                                adminPwd = ""
                                showAdminLogin = true
                                adminLoginError = ""
                            }
                        },
                        modifier = Modifier.widthIn(min = 48.dp)
                    ) {
                        Text(
                            text = if (hasAdmin) "后台" else "管理",
                            fontSize = 13.sp,
                            maxLines = 1,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    // 退出账号
                    IconButton(
                        onClick = {
                            AuthStore.clear(context)
                            context.startActivity(Intent(context, MainActivity::class.java))
                            (context as? android.app.Activity)?.finishAffinity()
                        },
                        modifier = Modifier.widthIn(min = 48.dp)
                    ) {
                        Text(
                            text = "退出",
                            fontSize = 13.sp,
                            maxLines = 1,
                            color = Color(0xFFE94634)
                        )
                    }
                }
            )
        },
        bottomBar = {
            InputBottomBar(
                input = input,
                onInputChange = { input = it },
                muted = muted,
                isSending = isSending,
                errorTip = errorTip,
                onErrorShown = { errorTip = "" },
                onSend = { text ->
                    if (text.isEmpty() || isSending) return@InputBottomBar
                    isSending = true
                    errorTip = ""
                    scope.launch {
                        val tempId = -(System.currentTimeMillis() % 100000).toInt()
                        val now = formatTime("")
                        val tempMsg = Msg(
                            id = tempId,
                            username = ApiClient.currentUsername,
                            content = text,
                            time = now,
                            pending = true
                        )
                        messages = messages + tempMsg
                        input = ""
                        val (ok, json) = ApiClient.post("/api/send", mapOf("content" to text))
                        messages = messages.filter { it.id != tempId }
                        if (ok) {
                            val obj = json.optJSONObject("message")
                            if (obj != null) {
                                val newMsg = Msg(
                                    id = obj.optInt("id"),
                                    username = obj.optString("username"),
                                    content = obj.optString("content"),
                                    time = formatTime(obj.optString("created_at", ""))
                                )
                                if (newMsg.id > lastId) {
                                    messages = messages + newMsg
                                    lastId = newMsg.id
                                }
                            }
                        } else {
                            errorTip = ApiClient.errorText(json)
                            input = text
                        }
                        isSending = false
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .consumeWindowInsets(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg)
            }
        }
    }

    // 管理员登录弹窗
    if (showAdminLogin) {
        AlertDialog(
            onDismissRequest = { if (!adminLogging) showAdminLogin = false },
            title = { Text("登录管理员") },
            text = {
                Column {
                    TextField(
                        value = adminAccount,
                        onValueChange = { adminAccount = it },
                        label = "管理员账号",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = adminPwd,
                        onValueChange = { adminPwd = it },
                        label = "密码",
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    if (adminLoginError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = adminLoginError,
                            color = Color(0xFFE94634),
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (adminAccount.isBlank() || adminPwd.isBlank() || adminLogging) return@Button
                        scope.launch {
                            adminLogging = true
                            adminLoginError = ""
                            val (ok, json) = ApiClient.post(
                                "/api/admin-login",
                                mapOf("username" to adminAccount, "password" to adminPwd)
                            )
                            adminLogging = false
                            if (ok) {
                                AuthStore.saveAdmin(context, adminAccount, adminPwd)
                                needsAdminRefresh += 1
                                showAdminLogin = false
                                context.startActivity(Intent(context, AdminActivity::class.java))
                            } else {
                                adminLoginError = ApiClient.errorText(json)
                            }
                        }
                    },
                    minHeight = 40.dp
                ) {
                    Text(if (adminLogging) "登录中…" else "登录并进入后台")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { if (!adminLogging) showAdminLogin = false }
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun InputBottomBar(
    input: String,
    onInputChange: (String) -> Unit,
    muted: Boolean,
    isSending: Boolean,
    errorTip: String,
    onErrorShown: () -> Unit,
    onSend: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (muted) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "当前全员禁言中",
                        fontSize = 14.sp,
                        color = Color(0xFFE94634)
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = input,
                        onValueChange = onInputChange,
                        label = "输入消息…",
                        modifier = Modifier.weight(1f).padding(end = 10.dp),
                        singleLine = false,
                        maxLines = 4
                    )
                    Button(
                        onClick = { onSend(input.trim()) },
                        modifier = Modifier.heightIn(min = 44.dp),
                        minHeight = 44.dp
                    ) {
                        Text("发送")
                    }
                }
            }
        }

        if (errorTip.isNotBlank()) {
            Text(
                text = errorTip,
                color = Color(0xFFE94634),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LaunchedEffect(errorTip) {
                onErrorShown()
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
                    if (msg.pending) Color(0xFFEAF2FF)
                    else if (isMe) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.surfaceContainerHigh
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (!isMe) {
                Text(
                    text = msg.username,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (msg.pending) MiuixTheme.colorScheme.primary else Color(0xFF3482FF)
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = msg.content + if (msg.pending) "（发送中…）" else "",
                fontSize = 15.sp,
                color = if (isMe && !msg.pending) Color.White else MiuixTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )
            if (msg.time.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = msg.time,
                    fontSize = 10.sp,
                    color = if (isMe && !msg.pending) Color.White.copy(alpha = 0.75f)
                    else MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}
