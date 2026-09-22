package cc.lifefuck.minichat

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 应用统一主题。
 * 使用 Miuix 经典配色方案，不再跟随系统壁纸做 Monet 动态取色，
 * 而是使用固定的 Miuix 浅色/深色默认色板，视觉更稳定。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val controller = ThemeController(ColorSchemeMode.Light)
    MiuixTheme(controller = controller, content = content)
}
