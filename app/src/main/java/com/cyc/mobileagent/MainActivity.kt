package com.cyc.mobileagent

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.cyc.mobileagent.app.AppContainer
import com.cyc.mobileagent.ui.MobileAgentApp

/**
 * Android Activity 只负责系统生命周期和系统设置入口。
 *
 * Agent Runtime、Skill 组装、微信能力、联系人、校准、
 * 日志和执行事件都不再放在这里。
 */
class MainActivity : ComponentActivity() {

    private lateinit var appContainer: AppContainer

    private val resumeVersionState =
        mutableStateOf(0)

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        appContainer =
            AppContainer(
                applicationContext
            )

        appContainer.start()
        enableEdgeToEdge()

        val openCalibrationInitially =
            appContainer
                .shouldOpenCalibration(
                    intent
                )

        setContent {
            MobileAgentApp(
                container =
                    appContainer,
                resumeVersion =
                    resumeVersionState.value,
                openCalibrationInitially =
                    openCalibrationInitially,
                onOpenAccessibilitySettings = {
                    startActivity(
                        Intent(
                            Settings
                                .ACTION_ACCESSIBILITY_SETTINGS
                        )
                    )
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()

        resumeVersionState.value +=
            1
    }

    override fun onDestroy() {
        if (
            this::appContainer
                .isInitialized
        ) {
            appContainer.close()
        }

        super.onDestroy()
    }
}
