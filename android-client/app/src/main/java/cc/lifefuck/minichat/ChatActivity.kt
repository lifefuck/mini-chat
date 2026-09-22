package cc.lifefuck.minichat

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文字群聊页面。
 *
 * - 应用 Material You / Monet 动态取色
 * - 禁止截图录屏、最近任务白屏（继承 BaseActivity）
 * - 底部输入框随键盘上抬，键盘弹出时聊天记录自动滚到底部
 * - 顶部左侧显示当前用户头像（首字母/图片），点击头像可换头像；名字点击跳转修改昵称
 * - 每条消息气泡上显示发送者头像与名字
 * - 发送消息立即显示"发送中…"气泡，成功后立即插入后端返回的正式消息
 * - 管理员账号登录后也在此页面聊天，右上角可直接进入管理后台
 * - 顶部提供退出账号和管理员进入后台入口
 */
class ChatActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                ChatPage()
            }
        }
    }
}

/**
 * 消息数据类。
 *
 * @param id 消息唯一 ID，pending 消息为负数临时 ID
 * @param username 发送者昵称
 * @param content 消息正文
 * @param time 显示时间 HH:mm
 * @param pending 是否为本地"发送中"临时气泡
 * @param avatar 发送者头像 base64；空则使用首字母占位
 */
data class Msg(
    val id: Int,
    val username: String,
    val content: String,
    val time: String,
    val pending: Boolean = false,
    val avatar: String? = null
)

/** 格式化时间为 HH:mm 显示。 */
private fun formatTime(timeStr: String): String {
    if (timeStr.length >= 16) {
        return timeStr.substring(11, 16)
    }
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date())
}

/** 裁剪字符串为首字符：英文取第一个字母，中文取第一个汉字。 */
private fun avatarInitial(name: String): String {
    if (name.isBlank()) return "?"
    return name.trim().firstOrNull()?.toString() ?: "?"
}

/** 将头像 base64 字符串解码为 Compose ImageBitmap，失败返回 null。 */
private fun avatarBitmap(base64: String?) = base64?.let { data ->
    try {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Exception) { null }
}

/** 读取 URI 图片并压缩为 JPEG base64，限制最长边 256px、质量 60%。 */
private fun uriToBase64Avatar(context: android.content.Context, uri: Uri): String? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { ins ->
            val bm = BitmapFactory.decodeStream(ins) ?: return null
            val max = 256
            val w = bm.width
            val h = bm.height
            val ratio = if (w > h) w.toFloat() / max else h.toFloat() / max
            val bm2 = if (ratio > 1) {
                android.graphics.Bitmap.createScaledBitmap(
                    bm, (w / ratio).toInt(), (h / ratio).toInt(), true
                )
            } else bm
            val out = ByteArrayOutputStream()
            bm2.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    } catch (_: Exception) { null }
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

    // 服务器连接状态（前台每秒轮询）
    var isOnline by remember { mutableStateOf(true) }
    var connectionError by remember { mutableStateOf("") }
    var lastToastTime by remember { mutableStateOf(0L) }

    // 当前登录用户信息（昵称/头像/userId）
    var myUserId by remember { mutableIntStateOf(0) }
    var myUsername by remember { mutableStateOf(ApiClient.currentUsername) }
    var myAvatar by remember { mutableStateOf<String?>(null) }
    var myRole by remember { mutableStateOf("") }
    var myQq by remember { mutableStateOf("") }
    var keyRegisterError by remember { mutableStateOf("") }

    // 自己发送过的消息明文缓存，key 为消息 id。
    // 端到端加密中，发送者不会收到给自己的加密副本，
    // 因此解密自己发的消息需要从本地缓存读取原文。
    val ownPlainText = remember { mutableStateMapOf<Int, String>() }

    // 拉取当前用户信息
    LaunchedEffect(Unit) {
        // 优先读取登录页传入的附加信息，再请求服务器确认
        val activity = context as? Activity
        myRole = activity?.intent?.getStringExtra("role") ?: ""
        myQq = activity?.intent?.getStringExtra("qq") ?: ""
        val intentName = activity?.intent?.getStringExtra("username")
        if (!intentName.isNullOrBlank()) {
            myUsername = intentName
            ApiClient.currentUsername = intentName
        }

        val (ok, json) = ApiClient.get("/api/me")
        if (ok) {
            val user = json.optJSONObject("user")
            myUserId = user?.optInt("id") ?: 0
            val name = user?.optString("username") ?: ""
            if (name.isNotBlank()) {
                myUsername = name
                ApiClient.currentUsername = name
            }
            val qqServer = user?.optString("qq") ?: ""
            if (qqServer.isNotBlank()) myQq = qqServer
            val roleServer = user?.optString("role") ?: ""
            if (roleServer.isNotBlank()) myRole = roleServer
            myAvatar = user?.optString("avatar")?.takeIf { it.isNotBlank() }
            // 任何账号登录后都必须有 RSA 公钥才能收发端到端加密消息（包括管理员）
            val keyErr = ensurePublicKeyRegistered(context)
            if (keyErr != null) {
                keyRegisterError = keyErr
                Toast.makeText(context, "公钥未上传：$keyErr", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 图片选择器：选择单张图片作为头像
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val base64 = uriToBase64Avatar(context, uri)
            if (base64 == null) {
                Toast.makeText(context, "头像读取失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (ok, json) = ApiClient.post(
                "/api/update-avatar",
                mapOf("avatar" to base64)
            )
            if (ok) {
                myAvatar = base64
                // 刷新历史消息中所有自己消息的头像，让用户立即看到更新
                messages = messages.map {
                    if (it.username == myUsername) it.copy(avatar = base64) else it
                }
                Toast.makeText(context, "头像已更新", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, ApiClient.errorText(json), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 修改昵称结果回调
    val editNameLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val name = ApiClient.currentUsername
            if (name.isNotBlank()) myUsername = name
        }
    }

    /**
     * 解密服务器返回的消息 payload。
     * 兼容旧版明文消息（没有 payload 时使用 content 字段）。
     *
     * 对于自己发送的消息，端到端加密 payload 里通常不会包含自己的加密副本，
     * 因此优先从本地明文缓存 ownPlainText 读取；解密失败时尝试读取 content 字段。
     */
    fun decryptMessage(o: org.json.JSONObject): String {
        val id = o.optInt("id")
        val userId = o.optInt("user_id")
        // 发送者本地缓存命中：直接显示原文
        if (userId == myUserId && ownPlainText.containsKey(id)) {
            return ownPlainText[id] ?: o.optString("content")
        }
        val payload = o.optString("payload").takeIf { it.isNotBlank() }
        if (payload != null && myUserId != 0) {
            return CryptoManager.decryptPayload(payload, myUserId)
                ?: (ownPlainText[id] ?: o.optString("content").ifBlank { "[无法解密此消息]" })
        }
        return o.optString("content")
    }

    // 加载历史消息
    suspend fun loadMessages(): Boolean {
        val (_, json) = ApiClient.get("/api/messages?last_id=0")
        val arr = json.optJSONArray("messages") ?: return false
        val loaded = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Msg(
                id = o.optInt("id"),
                username = o.optString("username"),
                content = decryptMessage(o),
                time = formatTime(o.optString("created_at", "")),
                avatar = o.optString("avatar").takeIf { it.isNotBlank() }
            )
        }
        messages = loaded
        lastId = loaded.maxOfOrNull { it.id } ?: 0
        muted = json.optBoolean("muted", false)
        return true
    }

    // 前台每秒检测服务器状态
    LaunchedEffect(Unit) {
        while (true) {
            val (online, err) = ApiClient.checkHealth()
            isOnline = online
            connectionError = if (online) "" else err
            if (!online) {
                val now = System.currentTimeMillis()
                if (now - lastToastTime > 3000) {
                    Toast.makeText(context, "未连接到服务器", Toast.LENGTH_SHORT).show()
                    lastToastTime = now
                }
            }
            delay(1000)
        }
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
                    content = decryptMessage(o),
                    time = formatTime(o.optString("created_at", "")),
                    avatar = o.optString("avatar").takeIf { it.isNotBlank() }
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
                subtitle = "晚上属于高峰时段，老外全起床了，服务器卡很正常",
                navigationIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                editNameLauncher.launch(
                                    Intent(context, EditNameActivity::class.java)
                                )
                            }
                        )
                    ) {
                        // 头像点击换头像
                        AvatarView(
                            name = myUsername,
                            avatar = myAvatar,
                            size = 34.dp,
                            onClick = {
                                avatarPicker.launch("image/*")
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        // 名字点击改昵称
                        Text(
                            text = myUsername.ifBlank { "我" },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // 管理员入口
                    // 管理员入口：当前已是管理员时直接进后台，
                    // 否则弹出账号切换提示，确认后带着当前账号跳回登录页方便切换
                    IconButton(
                        onClick = {
                            if (myRole == "admin") {
                                context.startActivity(Intent(context, AdminActivity::class.java))
                            } else {
                                Toast.makeText(context, "当前不是管理员账号", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.widthIn(min = 48.dp)
                    ) {
                        Text(
                            text = if (myRole == "admin") "后台" else "管理",
                            fontSize = 13.sp,
                            maxLines = 1,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    // 切换账号：保留当前账号并跳转登录页
                    IconButton(
                        onClick = {
                            val intent = Intent(context, MainActivity::class.java)
                            intent.putExtra("pre_fill_account", myQq)
                            context.startActivity(intent)
                            (context as? Activity)?.finishAffinity()
                        },
                        modifier = Modifier.widthIn(min = 48.dp)
                    ) {
                        Text(
                            text = "切换",
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
                            (context as? Activity)?.finishAffinity()
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

                    // 发送前强制检查服务器状态
                    if (!isOnline) {
                        errorTip = connectionError.ifBlank { "未连接到服务器" }
                        return@InputBottomBar
                    }

                    isSending = true
                    errorTip = ""
                    scope.launch {
                        val tempId = -(System.currentTimeMillis() % 100000).toInt()
                        val now = formatTime("")
                        val tempMsg = Msg(
                            id = tempId,
                            username = myUsername,
                            content = text,
                            time = now,
                            pending = true,
                            avatar = myAvatar
                        )
                        messages = messages + tempMsg
                        input = ""

                        // 端到端加密：获取所有接收者公钥后加密
                        val payload = run {
                            val (keysOk, keysJson) = ApiClient.fetchPublicKeys()
                            if (!keysOk) {
                                errorTip = ApiClient.errorText(keysJson)
                                isSending = false
                                messages = messages.filter { it.id != tempId }
                                input = text
                                return@launch
                            }
                            val keysArr = keysJson.optJSONArray("keys") ?: org.json.JSONArray()
                            val recipients = mutableMapOf<Int, String>()
                            for (i in 0 until keysArr.length()) {
                                val item = keysArr.getJSONObject(i)
                                val uid = item.optInt("id")
                                val pub = item.optString("public_key").takeIf { it.isNotBlank() }
                                if (uid != 0 && pub != null) recipients[uid] = pub
                            }
                            if (recipients.isEmpty()) {
                                errorTip = "群成员公钥为空，无法加密发送"
                                isSending = false
                                messages = messages.filter { it.id != tempId }
                                input = text
                                return@launch
                            }
                            CryptoManager.encryptPayload(text, recipients)
                                ?: run {
                                    errorTip = "消息加密失败"
                                    isSending = false
                                    messages = messages.filter { it.id != tempId }
                                    input = text
                                    return@launch
                                }
                        }

                        val (ok, json) = ApiClient.post(
                            "/api/send",
                            mapOf("payload" to payload)
                        )
                        messages = messages.filter { it.id != tempId }
                        if (ok) {
                            val obj = json.optJSONObject("message")
                            if (obj != null) {
                                val msgId = obj.optInt("id")
                                val sentContent = text
                                // 保存自己发送的明文，确保当前会话内解密失败仍能显示原文
                                if (msgId > 0) {
                                    ownPlainText[msgId] = sentContent
                                }
                                val newMsg = Msg(
                                    id = msgId,
                                    username = obj.optString("username"),
                                    content = sentContent,
                                    time = formatTime(obj.optString("created_at", "")),
                                    avatar = obj.optString("avatar").takeIf { it.isNotBlank() }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 未连接服务器提示横幅
            if (!isOnline && connectionError.isNotBlank()) {
                ConnectionBanner(error = connectionError)
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageItem(msg)
                }
            }
        }
    }

}

/**
 * 圆形头像组件。
 *
 * @param name 昵称，无头像时显示首字符
 * @param avatar 头像 base64 字符串，为空则显示首字符
 * @param size 头像尺寸
 * @param onClick 点击回调
 */
@Composable
fun AvatarView(
    name: String,
    avatar: String?,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val bitmap = remember(avatar) { avatarBitmap(avatar) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primary)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "头像",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = avatarInitial(name),
                color = MiuixTheme.colorScheme.onPrimary,
                fontSize = (size.value * 0.45f).sp,
                fontWeight = FontWeight.Bold
            )
        }
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
                delay(1500)
                onErrorShown()
            }
        }
    }
}

/**
 * 未连接服务器提示横幅。
 *
 * 点击可将完整报错文案复制到剪贴板。
 */
@Composable
fun ConnectionBanner(error: String) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable {
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("服务器错误", error))
                Toast.makeText(context, "已复制报错：$error", Toast.LENGTH_LONG).show()
            },
        cornerRadius = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFF2F0))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⚠ 未连接到服务器",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE94634),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "点击复制报错",
                fontSize = 12.sp,
                color = Color(0xFFE94634).copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun MessageItem(msg: Msg) {
    val isMe = msg.username == ApiClient.currentUsername
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        if (!isMe) {
            AvatarView(
                name = msg.username,
                avatar = msg.avatar,
                size = 38.dp,
                onClick = {}
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 260.dp),
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
        ) {
            // 发送者名字
            Text(
                text = msg.username,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (msg.pending) MiuixTheme.colorScheme.primary else Color(0xFF3482FF),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))

            // 气泡
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (msg.pending) Color(0xFFEAF2FF)
                        else if (isMe) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.surfaceContainerHigh
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
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

        if (isMe) {
            Spacer(modifier = Modifier.width(8.dp))
            AvatarView(
                name = msg.username,
                avatar = msg.avatar,
                size = 38.dp,
                onClick = {}
            )
        }
    }
}
