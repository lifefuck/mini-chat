package cc.lifefuck.minichat

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 管理员后台：审批/冻结/改名/重置密码/删除用户 + 全员禁言开关。
 * 顶部提供退出账号入口。
 */
class AdminActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AdminPage()
            }
        }
    }
}

data class UserItem(
    val id: Int,
    val qq: String,
    val username: String,
    val role: String,
    val status: String,
    val createdAt: String
)

@Composable
fun AdminPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var users by remember { mutableStateOf(listOf<UserItem>()) }
    var muted by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf("") }

    // 弹窗状态
    var showRename by remember { mutableStateOf<Int?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var showResetPwd by remember { mutableStateOf<Int?>(null) }
    var resetPwdValue by remember { mutableStateOf("") }

    // 拉取用户列表
    suspend fun reload() {
        isLoading = true
        val (ok, json) = ApiClient.get("/api/admin/users")
        if (ok) {
            users = parseUsers(json)
            muted = json.optBoolean("muted", false)
        } else {
            toast = ApiClient.errorText(json)
        }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        reload()
    }

    // 弹窗：改名
    if (showRename != null) {
        AlertDialog(
            onDismissRequest = { showRename = null },
            title = { Text("修改用户名") },
            text = {
                TextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    label = "新用户名",
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val uid = showRename ?: return@launch
                            val (ok, json) = ApiClient.post(
                                "/api/admin/change-username/$uid",
                                mapOf("new_username" to renameValue)
                            )
                            toast = if (ok) "用户名已修改" else ApiClient.errorText(json)
                            showRename = null
                            renameValue = ""
                            if (ok) reload()
                        }
                    },
                    text = "确认"
                )
            },
            dismissButton = {
                TextButton(onClick = { showRename = null }, text = "取消")
            }
        )
    }

    // 弹窗：重置密码
    if (showResetPwd != null) {
        AlertDialog(
            onDismissRequest = { showResetPwd = null },
            title = { Text("重置密码") },
            text = {
                Column {
                    Text("密码至少 6 位，新密码会直接生效。", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = resetPwdValue,
                        onValueChange = { resetPwdValue = it },
                        label = "新密码",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val uid = showResetPwd ?: return@launch
                            val (ok, json) = ApiClient.post(
                                "/api/admin/reset-password/$uid",
                                mapOf("new_password" to resetPwdValue)
                            )
                            toast = if (ok) "密码已重置" else ApiClient.errorText(json)
                            showResetPwd = null
                            resetPwdValue = ""
                            if (ok) reload()
                        }
                    },
                    text = "确认"
                )
            },
            dismissButton = {
                TextButton(onClick = { showResetPwd = null }, text = "取消")
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = "管理后台",
                subtitle = "管理员账号不能参与聊天",
                actions = {
                    IconButton(
                        onClick = {
                            AuthStore.clear(context)
                            context.startActivity(Intent(context, MainActivity::class.java))
                            (context as? android.app.Activity)?.finishAffinity()
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
                .padding(horizontal = 16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("全员禁言", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text("开启后普通用户无法发送消息", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    Switch(
                        checked = muted,
                        onCheckedChange = { checked ->
                            scope.launch {
                                val path = if (checked) "/api/admin/mute" else "/api/admin/unmute"
                                val (ok, json) = ApiClient.post(path, emptyMap())
                                if (ok) {
                                    muted = checked
                                } else {
                                    toast = ApiClient.errorText(json)
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(users.filter { it.role != "admin" }, key = { it.id }) { user ->
                    UserCard(
                        user = user,
                        onApprove = { scope.launch { ApiClient.post("/api/admin/approve/${user.id}", emptyMap()); reload() } },
                        onReject = { scope.launch { ApiClient.post("/api/admin/reject/${user.id}", emptyMap()); reload() } },
                        onDelete = { scope.launch { ApiClient.post("/api/admin/delete/${user.id}", emptyMap()); reload() } },
                        onRename = {
                            renameValue = user.username
                            showRename = user.id
                        },
                        onResetPwd = {
                            resetPwdValue = ""
                            showResetPwd = user.id
                        }
                    )
                }
            }

            if (toast.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toast,
                    color = if (toast.startsWith("已") || toast.startsWith("用户名") || toast.startsWith("密码"))
                        Color(0xFF2E7D32) else Color(0xFFE94634),
                    fontSize = 13.sp
                )
            }

            Button(
                onClick = { scope.launch { reload() } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                minHeight = 44.dp
            ) {
                Text(if (isLoading) "加载中…" else "刷新列表")
            }
        }
    }
}

@Composable
fun UserCard(
    user: UserItem,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onResetPwd: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (user.status == "approved") Color(0xFFE8F5E9) else Color(0xFFFFF3E0))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (user.status == "approved") "已通过" else "待审核",
                        fontSize = 11.sp,
                        color = if (user.status == "approved") Color(0xFF2E7D32) else Color(0xFFFF6D00)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("${user.username}（QQ ${user.qq}）", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text("注册时间：${user.createdAt}", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (user.status != "approved") {
                    CompactAction("通过", onClick = onApprove)
                } else {
                    CompactAction("冻结", onClick = onReject)
                }
                CompactAction("改名", onClick = onRename)
                CompactAction("重置密码", onClick = onResetPwd)
                CompactAction("删除", onClick = onDelete)
            }
        }
    }
}

@Composable
fun CompactAction(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 32.dp),
        text = text
    )
}

private fun parseUsers(json: JSONObject): List<UserItem> {
    val arr = json.optJSONArray("users") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        UserItem(
            id = obj.optInt("id", 0),
            qq = obj.optString("qq", ""),
            username = obj.optString("username", ""),
            role = obj.optString("role", "user"),
            status = obj.optString("status", "pending"),
            createdAt = obj.optString("created_at", "")
        )
    }
}
