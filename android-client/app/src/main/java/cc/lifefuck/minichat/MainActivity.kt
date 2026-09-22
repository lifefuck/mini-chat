package cc.lifefuck.minichat

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.animation.Crossfade
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
 * 应用入口：登录 / 注册。
 *
 * 注册成功提示"成功注册，请联系管理员同意申请"；
 * 重复注册提示"该账号已注册"；
 * 登录时账号密码正确但管理员未同意提示"该账号申请等待同意"。
 * 修改昵称功能已移到聊天页左上角。
 */
class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainPage()
            }
        }
    }
}

private val tabs = listOf("登录", "注册")

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

    fun showToast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    // 自动登录成功后关闭登录页，避免手动登录再弹一次
    fun jumpAndFinish(json: JSONObject) {
        handleLoginResult(context, json) { errorText = it }
        (context as? android.app.Activity)?.finish()
    }

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
                jumpAndFinish(json)
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

            // 自动登录期间禁用 Tab 切换和输入框，防止手动登录冲突
            val controlsEnabled = !autoLogging && !isLoading

            TabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = {
                            if (!controlsEnabled) return@Tab
                            errorText = ""
                            selectedTab = index
                        },
                        text = { Text(title, fontSize = 15.sp) },
                        enabled = controlsEnabled
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Crossfade(targetState = selectedTab, label = "page") { tab ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when (tab) {
                            0 -> LoginForm(
                                account = loginAccount,
                                onAccount = { if (controlsEnabled) loginAccount = it },
                                pwd = loginPwd,
                                onPwd = { if (controlsEnabled) loginPwd = it },
                                enabled = controlsEnabled
                            )
                            1 -> RegisterForm(
                                qq = regQq,
                                onQq = { if (controlsEnabled) regQq = it },
                                name = regName,
                                onName = { if (controlsEnabled) regName = it },
                                pwd = regPwd,
                                onPwd = { if (controlsEnabled) regPwd = it },
                                pwd2 = regPwd2,
                                onPwd2 = { if (controlsEnabled) regPwd2 = it },
                                enabled = controlsEnabled
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            enabled = controlsEnabled,
                            onClick = {
                                if (!controlsEnabled) return@Button
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
                                                    showToast(ApiClient.errorText(json))
                                                }
                                            }
                                        }
                                        1 -> {
                                            val validationError = when {
                                                regQq.isBlank() || regName.isBlank() || regPwd.isBlank() -> "QQ 号、用户名、密码均不能为空"
                                                regPwd != regPwd2 -> "两次输入的密码不一致"
                                                regPwd.length < 6 -> "密码至少 6 位"
                                                else -> null
                                            }
                                            if (validationError != null) {
                                                errorText = validationError
                                                showToast(validationError)
                                            } else {
                                                val (ok, json) = ApiClient.post(
                                                    "/api/register",
                                                    mapOf("qq" to regQq, "username" to regName, "password" to regPwd)
                                                )
                                                if (ok) {
                                                    regQq = ""; regName = ""; regPwd = ""; regPwd2 = ""
                                                    errorText = "成功注册，请联系管理员同意申请"
                                                    showToast("成功注册，请联系管理员同意申请")
                                                } else {
                                                    val err = ApiClient.errorText(json)
                                                    errorText = err
                                                    showToast(err)
                                                }
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
                                    else -> "申请加入"
                                },
                                fontSize = 16.sp
                            )
                        }

                        if (errorText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            val isSuccess = errorText.startsWith("成功注册")
                            Text(
                                text = errorText,
                                color = if (isSuccess) Color(0xFF2E7D32) else if (errorText == "正在自动登录…") MiuixTheme.colorScheme.primary else Color(0xFFE94634),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 12.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "温馨提示",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "登录预计 5~10 秒，晚上属于高峰时段，消息发送较慢属于正常现象。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
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
fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    enabled: Boolean = true
) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = label,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        enabled = enabled
    )
}

@Composable
fun Title(text: String) {
    Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
fun LoginForm(
    account: String,
    onAccount: (String) -> Unit,
    pwd: String,
    onPwd: (String) -> Unit,
    enabled: Boolean = true
) {
    Title("账号登录")
    AuthField(account, onAccount, "QQ 号或管理员账号", enabled = enabled)
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(pwd, onPwd, "密码", isPassword = true, enabled = enabled)
}

@Composable
fun RegisterForm(
    qq: String, onQq: (String) -> Unit,
    name: String, onName: (String) -> Unit,
    pwd: String, onPwd: (String) -> Unit,
    pwd2: String, onPwd2: (String) -> Unit,
    enabled: Boolean = true
) {
    Title("注册账号")
    AuthField(qq, onQq, "QQ 号", enabled = enabled)
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(name, onName, "用户名", enabled = enabled)
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(pwd, onPwd, "密码", isPassword = true, enabled = enabled)
    Spacer(modifier = Modifier.height(10.dp))
    AuthField(pwd2, onPwd2, "确认密码", isPassword = true, enabled = enabled)
}
