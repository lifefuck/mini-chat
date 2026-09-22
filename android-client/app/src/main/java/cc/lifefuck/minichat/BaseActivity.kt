package cc.lifefuck.minichat

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/**
 * Activity 基类：统一禁止截图、录屏，并在切换到后台/最近任务时覆盖白屏保护隐私。
 */
abstract class BaseActivity : ComponentActivity() {

    private var privacyOverlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
    }

    override fun onResume() {
        super.onResume()
        secureWindow()
        removePrivacyOverlay()
    }

    override fun onPause() {
        super.onPause()
        addPrivacyOverlay()
    }

    /**
     * 给当前窗口加 FLAG_SECURE，禁止系统截图与录屏。
     * Android 15+ 尝试关闭最近任务缩略图。
     */
    private fun secureWindow() {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                Activity::class.java
                    .getMethod("setRecentsScreenshotEnabled", Boolean::class.java)
                    .invoke(this, false)
            } catch (_: Exception) {
                // 隐藏 API 可能不存在，忽略
            }
        }
    }

    /**
     * 进入后台时覆盖一层白屏，防止最近任务、息屏显示、截图工具抓到真实内容。
     */
    private fun addPrivacyOverlay() {
        val root = window?.decorView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (privacyOverlay != null) return
        val overlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }
        privacyOverlay = overlay
        root.addView(overlay)
    }

    private fun removePrivacyOverlay() {
        val root = window?.decorView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        privacyOverlay?.let {
            root.removeView(it)
            privacyOverlay = null
        }
    }
}
