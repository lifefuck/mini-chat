package cc.lifefuck.minichat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 修改用户名页面：已登录用户可直接修改，无需再验证密码。
 */
class EditNameActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                val scope = rememberCoroutineScope()
                val context = androidx.compose.ui.platform.LocalContext.current
                var newName by remember { mutableStateOf(ApiClient.currentUsername) }
                var isLoading by remember { mutableStateOf(false) }

                Scaffold {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "修改昵称",
                            fontSize = 22.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                        Text(
                            text = "直接输入新昵称即可生效，无需验证密码。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        TextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = "新昵称",
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Done)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(
                            text = if (isLoading) "保存中…" else "保存",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val name = newName.trim()
                                if (name.isEmpty()) {
                                    Toast.makeText(context, "昵称不能为空", Toast.LENGTH_SHORT).show()
                                    return@TextButton
                                }
                                if (isLoading) return@TextButton
                                isLoading = true
                                scope.launch {
                                    val (ok, json) = ApiClient.post(
                                        "/api/change-username",
                                        mapOf("new_username" to name)
                                    )
                                    isLoading = false
                                    if (ok) {
                                        val u = json.optJSONObject("user")
                                        val savedName = u?.optString("username") ?: name
                                        ApiClient.currentUsername = savedName
                                        Toast.makeText(context, "昵称已修改", Toast.LENGTH_SHORT).show()
                                        setResult(RESULT_OK)
                                        finish()
                                    } else {
                                        Toast.makeText(context, ApiClient.errorText(json), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
