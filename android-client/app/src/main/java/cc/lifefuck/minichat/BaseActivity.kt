package cc.lifefuck.minichat

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Activity 基类：统一禁止截图、录屏，并在切换到后台/最近任务时覆盖白屏保护隐私。
 * Android 14+ 还会注册系统截图回调，检测到截图时立刻变白屏提示。
 */
abstract class BaseActivity : ComponentActivity() {

    private var privacyOverlay: View? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureWindow()
        registerScreenCaptureCallbackIfPossible()
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
     */
    private fun secureWindow() {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
     * Android 14+ 注册系统级截图回调。检测到截图时立刻覆盖白屏并提示。
     */
    private fun registerScreenCaptureCallbackIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        try {
            val callbackClass = Class.forName("android.app.Activity\$ScreenCaptureCallback")
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, _ ->
                if (method.name == "onScreenCaptured") {
                    runOnUiThread {
                        addPrivacyOverlay()
                        Toast.makeText(this, "该应用禁止截图", Toast.LENGTH_SHORT).show()
                        handler.postDelayed({ removePrivacyOverlay() }, 1500)
                    }
                }
                null
            }
            val executor = mainExecutor
            Activity::class.java
                .getMethod("registerScreenCaptureCallback", java.util.concurrent.Executor::class.java, callbackClass)
                .invoke(this, executor, callback)
        } catch (_: Exception) {
            // 部分 ROM 隐藏 API 不存在或厂商已屏蔽，忽略
        }
    }

    /**
     * 进入后台/最近任务/检测到截图时覆盖一层白屏，防止内容被抓取。
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
