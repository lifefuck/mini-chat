package cc.lifefuck.minichat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文字群聊页面：Miuix 风格消息气泡 + 底部输入框 + 全员禁言开关响应。
 */
class ChatActivity : ComponentActivity() {
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
    val time: String
)

@Composable
fun ChatPage() {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf(listOf<Msg>()) }
    var lastId by remember { mutableStateOf(0) }
    var input by remember { mutableStateOf("") }
    var isMuted by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    // 轮询消息
    LaunchedEffect(Unit) {
        while (true) {
            val (ok, json) = ApiClient.get("/api/messages?last_id=$lastId")
            if (ok) {
                val new = parseMessages(json)
                if (new.isNotEmpty()) {
                    messages = messages + new
                    lastId = new.last().id
                    scope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
                isMuted = json.optBoolean("muted", false)
            }
            delay(1500)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "life的群组",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = "当前用户：${ApiClient.currentUsername.ifBlank { "我" }}",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        },
        bottomBar = {
            if (!isMuted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .imePadding(),
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
                            scope.launch {
                                isSending = true
                                errorText = ""
                                val (ok, json) = ApiClient.post(
                                    "/api/send",
                                    mapOf("content" to input.trim())
                                )
                                if (ok) {
                                    input = ""
                                } else {
                                    errorText = ApiClient.errorText(json)
                                }
                                isSending = false
                            }
                        },
                        modifier = Modifier.heightIn(min = 44.dp),
                        minHeight = 44.dp
                    ) {
                        Text("发送")
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
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
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
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
                    if (isMe) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh
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
                color = if (isMe) Color.White else MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = msg.time.substring(11, 16),
                fontSize = 10.sp,
                color = if (isMe) Color.White.copy(alpha = 0.75f)
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
