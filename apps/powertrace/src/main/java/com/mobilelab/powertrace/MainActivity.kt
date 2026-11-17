package com.mobilelab.powertrace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mobilelab.core.ui.theme.LabTheme
import com.mobilelab.powertrace.data.FpsMeter
import com.mobilelab.powertrace.data.PowerTraceController
import com.mobilelab.powertrace.ui.TraceRoot

/**
 * 单 Activity + Compose 导航（5 个页面）。
 *
 * 采集在 [com.mobilelab.powertrace.service.SamplingService] 前台服务里运行，
 * Activity 只做展示与操作入口，销毁/旋转不影响采样。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PowerTraceController.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            LabTheme {
                TraceRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // fps 仅在 App 前台可测（Choreographer），前后台切换时启停。
        FpsMeter.attach()
    }

    override fun onPause() {
        FpsMeter.detach()
        super.onPause()
    }
}
