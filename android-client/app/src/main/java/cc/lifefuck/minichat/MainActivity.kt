package cc.lifefuck.minichat

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用入口：登录 / 注册 / 修改用户名。
 * 统一登录入口兼容 QQ 号和管理员账号，自动跳转对应页面。
 * 使用 Miuix 组件与 Material3 TabRow（避免原版切换滑块卡顿/失效）。
 */
class MainActivity : ComponentActivity() {
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
                MainPage()
            }
        }
    }
}

private val tabs = listOf("登录", "注册", "改昵称")

@Composable
fun MainPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(0) }
    var errorText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var autoLogging by remember { mutableStateOf(false) }

    // 登录字段
    var loginAccount by remember { mutableStateOf("") }
    var loginPwd by remember { mutableStateOf("") }

    // 注册字段
    var regQq by remember { mutableStateOf("") }
    var regName by remember { mutableStateOf("") }
    var regPwd by remember { mutableStateOf("") }
    var regPwd2 by remember { mutableStateOf("") }

    // 改名字段
    var changeQq by remember { mutableStateOf("") }
    var changePwd by remember { mutableStateOf("") }
    var changeNew by remember { mutableStateOf("") }

    // 启动时检查 3 天内是否登录过，自动登录
    LaunchedEffect(Unit) {
        val saved = AuthStore.get(context)
        if (saved != null) {
            autoLogging = true
            loginAccount = saved.account
            loginPwd = saved.password
            val (ok, json) = ApiClient.post(
                "/api/unified-login",
                mapOf("account" to saved.account, "password" to saved.password)
            )
            autoLogging = false
            if (ok) {
                AuthStore.save(context, saved.account, saved.password, json.optString("role", ""))
                handleLoginResult(context, json) { errorText = it }
            } else {
                AuthStore.clear(context)
                errorText = ApiClient.errorText(json)
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp, bottom = 8.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "life的群组",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = "mini-chat 文字群聊",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (autoLogging) {
                Text(
                    "正在自动登录…",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            errorText = ""
                            selectedTab = index
                        },
                        text = { Text(title, fontSize = 15.sp) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Crossfade(targetState = selectedTab, label = "page") { tab ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when (tab) {
                            0 -> LoginForm(
                                account = loginAccount, onAccount = { loginAccount = it },
                                pwd = loginPwd, onPwd = { loginPwd = it }
                            )
                            1 -> RegisterForm(
                                qq = regQq, onQq = { regQq = it },
                                name = regName, onName = { regName = it },
                                pwd = regPwd, onPwd = { regPwd = it },
                                pwd2 = regPwd2, onPwd2 = { regPwd2 = it }
                            )
                            2 -> ChangeNameForm(
                                qq = changeQq, onQq = { changeQq = it },
                                pwd = changePwd, onPwd = { changePwd = it },
                                new = changeNew, onNew = { changeNew = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (isLoading) return@Button
                                scope.launch {
                                    isLoading = true
                                    errorText = ""
                                    when (tab) {
                                        0 -> {
                                            if (loginAccount.isBlank() || loginPwd.isBlank()) {
                                                errorText = "请填写账号和密码"
                                            } else {
                                                val (ok, json) = ApiClient.post(
                                                    "/api/unified-login",
                                                    mapOf("account" to loginAccount, "password" to loginPwd)
                                                )
                                                if (ok) {
                                                    AuthStore.save(context, loginAccount, loginPwd, json.optString("role", ""))
                                                    handleLoginResult(context, json) { errorText = it }
                                                    loginAccount = ""
                                                    loginPwd = ""
                                                } else {
                                                    errorText = ApiClient.errorText(json)
                                                }
                                            }
                                        }
                                        1 -> {
                                            errorText = when {
                                                regQq.isBlank() || regName.isBlank() || regPwd.isBlank() -> "QQ 号、用户名、密码均不能为空"
                                                regPwd != regPwd2 -> "两次输入的密码不一致"
                                                regPwd.length < 6 -> "密码至少 6 位"
                                                else -> {
                                                    val (ok, json) = ApiClient.post(
                                                        "/api/register",
                                                        mapOf("qq" to regQq, "username" to regName, "password" to regPwd)
                                                    )
                                                    if (ok) {
                                                        regQq = ""; regName = ""; regPwd = ""; regPwd2 = ""
                                                        "申请已提交，请等待管理员审核"
                                                    } else ApiClient.errorText(json)
                                                }
                                            }
                                        }
                                        2 -> {
                                            errorText = if (changeQq.isBlank() || changePwd.isBlank() || changeNew.isBlank()) {
                                                "请填写完整"
                                            } else {
                                                val (ok, json) = ApiClient.post(
                                                    "/api/change-username",
                                                    mapOf("qq" to changeQq, "password" to changePwd, "new_username" to changeNew)
                                                )
                                                if (ok) {
                                                    ApiClient.currentUsername = changeNew
                                                    changeQq = ""; changePwd = ""; changeNew = ""
                                                    "用户名已修改为 ${ApiClient.currentUsername}"
                                                } else ApiClient.errorText(json)
                                            }
                                        }
                                    }
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            minHeight = 44.dp
                        ) {
                            Text(
                                text = when (tab) {
                                    0 -> if (isLoading) "登录中…" else "登录"
                                    1 -> "申请加入"
                                    else -> "修改用户名"
                                },
                                fontSize = 16.sp
                            )
                        }

                        if (errorText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            val isSuccess = errorText.startsWith("申请已提交") || errorText.startsWith("用户名已修改")
                            Text(
                                text = errorText,
                                color = if (isSuccess) Color(0xFF2E7D32) else if (errorText == "正在自动登录…") MiuixTheme.colorScheme.primary else Color(0xFFE94634),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

private fun handleLoginResult(context: android.content.Context, json: JSONObject, onError: (String) -> Unit) {
    val role = json.optString("role", "")
    val status = json.optString("status", "")
    val username = json.optString("username", "")
    ApiClient.currentUsername = username
    when (role) {
        "admin" -> context.startActivity(Intent(context, AdminActivity::class.java))
        "user" -> when (status) {
            "approved" -> context.startActivity(Intent(context, ChatActivity::class.java))
            "pending" -> context.startActivity(Intent(context, PendingActivity::class.java))
            else -> onError("账号状态异常")
        }
        else -> onError("账号角色未知")
    }
}

@Composable
fun AuthField(value: String, onChange: (String) -> Unit, label: String, isPassword: Boolean = false) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true
    )
}

@Composable
fun Title(text: String) {
    Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
fun LoginForm(account: String, onAccount: (String) -> Unit, pwd: String, onPwd: (String) -> Unit) {
    Title("账号登录")
    AuthField(account, onAccount, "QQ 号或管理员账号")
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(pwd, onPwd, "密码", isPassword = true)
}

@Composable
fun RegisterForm(
    qq: String, onQq: (String) -> Unit,
    name: String, onName: (String) -> Unit,
    pwd: String, onPwd: (String) -> Unit,
    pwd2: String, onPwd2: (String) -> Unit
) {
    Title("注册账号")
    AuthField(qq, onQq, "QQ 号")
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(name, onName, "用户名")
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(pwd, onPwd, "密码", isPassword = true)
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(pwd2, onPwd2, "确认密码", isPassword = true)
}

@Composable
fun ChangeNameForm(
    qq: String, onQq: (String) -> Unit,
    pwd: String, onPwd: (String) -> Unit,
    new: String, onNew: (String) -> Unit
) {
    Title("修改用户名")
    AuthField(qq, onQq, "QQ 号")
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(pwd, onPwd, "密码", isPassword = true)
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(new, onNew, "新用户名")
}
