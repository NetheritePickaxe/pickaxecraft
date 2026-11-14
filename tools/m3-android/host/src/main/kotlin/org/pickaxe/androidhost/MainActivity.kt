package org.pickaxe.androidhost

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 最小宿主：一个 Activity + 一块只读日志视图。
 *
 * 界面不是重点，报告文件才是：`adb pull` 取回 `report.txt` 就是证据。
 * 屏幕常亮，避免跑 20 轮开关会话时设备先睡过去。
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var runner: HostRunner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        logView = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(24, 24, 24, 24)
        }
        setContentView(
            ScrollView(this).apply {
                addView(
                    logView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
        logView.gravity = Gravity.START or Gravity.TOP

        // 保留实例：旋转屏幕时 Activity 会重建，但客户端与会话不能跟着重建。
        @Suppress("DEPRECATION")
        runner = (lastNonConfigurationInstance as? HostRunner) ?: HostRunner()
        runner.start(applicationContext, intent) { line ->
            runOnUiThread { logView.append(line + "\n") }
        }
    }

    @Deprecated("由框架调用；保留 runner 以跨配置变更复用同一个客户端")
    override fun onRetainNonConfigurationInstance(): Any = runner
}
