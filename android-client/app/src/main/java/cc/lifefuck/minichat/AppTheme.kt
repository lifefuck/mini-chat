package cc.lifefuck.minichat

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 应用统一主题。
 * 使用 Miuix 内置的 Material You (Monet) 动态取色方案，
 * 跟随系统壁纸颜色与深色模式自动变化。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val controller = ThemeController(ColorSchemeMode.MonetSystem)
    MiuixTheme(controller = controller, content = content)
}
