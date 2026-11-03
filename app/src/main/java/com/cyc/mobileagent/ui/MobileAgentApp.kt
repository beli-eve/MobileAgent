package com.cyc.mobileagent.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cyc.mobileagent.app.AppContainer
import com.cyc.mobileagent.ui.theme.MobileAgentTheme
import com.cyc.mobileagent.wechat.ui.WechatFeatureScreen
import kotlinx.coroutines.launch

/**
 * App 级 Compose 协调器。
 *
 * 这里只管理顶层页面。
 * 微信联系人、校准和微信内部页面状态全部由微信 Feature 自己管理。
 */
@Composable
fun MobileAgentApp(
    container: AppContainer,
    resumeVersion: Int,
    openCalibrationInitially: Boolean,
    onOpenAccessibilitySettings: () -> Unit
) {
    val scope =
        rememberCoroutineScope()

    val runtime =
        container.runtime

    val runtimeState by
        runtime.state
            .collectAsState()

    var page by remember {
        mutableStateOf(
            if (openCalibrationInitially) {
                AppPage.WECHAT
            } else {
                AppPage.HOME
            }
        )
    }

    var command by remember {
        mutableStateOf("")
    }

    var openCalibrationOnNextEntry by remember {
        mutableStateOf(
            openCalibrationInitially
        )
    }

    var accessibilityEnabled by remember(
        resumeVersion
    ) {
        mutableStateOf(
            container
                .accessibilityController
                .isEnabled()
        )
    }

    var executionLogs by remember(
        runtimeState.logVersion
    ) {
        mutableStateOf(
            runtime.readLogs()
        )
    }

    MobileAgentTheme {
        Scaffold(
            modifier =
                Modifier.fillMaxSize()
        ) {
            innerPadding ->

            when (page) {
                AppPage.HOME -> {
                    HomeScreen(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(
                                    innerPadding
                                ),
                        command =
                            command,
                        onCommandChange = {
                            command =
                                it
                        },
                        status =
                            runtimeState.status,
                        accessibilityEnabled =
                            accessibilityEnabled,
                        isRunning =
                            runtimeState.isRunning,
                        canCancel =
                            runtimeState.canCancel,
                        onExecute = execute@{
                            val cleanCommand =
                                command.trim()

                            if (cleanCommand.isBlank()) {
                                return@execute
                            }

                            /*
                             * 点击“交给 Agent”后立即清空输入框。
                             * 后续执行成功、失败或超时都不会恢复旧文本。
                             */
                            command =
                                ""

                            scope.launch {
                                runtime.execute(
                                    cleanCommand
                                )
                            }
                        },
                        onCancelTask = {
                            runtime
                                .cancelCurrentTask()
                        },
                        onOpenAccessibilitySettings =
                            onOpenAccessibilitySettings,
                        onOpenWechatSettings = {
                            openCalibrationOnNextEntry =
                                false

                            page =
                                AppPage.WECHAT
                        },
                        onOpenExecutionLogs = {
                            executionLogs =
                                runtime.readLogs()

                            page =
                                AppPage.EXECUTION_LOGS
                        }
                    )
                }

                AppPage.WECHAT -> {
                    WechatFeatureScreen(
                        feature =
                            container.wechatFeature,
                        resumeVersion =
                            resumeVersion,
                        dataVersion =
                            runtimeState.dataVersion,
                        openCalibrationInitially =
                            openCalibrationOnNextEntry,
                        onBack = {
                            openCalibrationOnNextEntry =
                                false

                            page =
                                AppPage.HOME
                        }
                    )
                }

                AppPage.EXECUTION_LOGS -> {
                    ExecutionLogScreen(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(
                                    innerPadding
                                ),
                        logs =
                            executionLogs,
                        onBack = {
                            page =
                                AppPage.HOME
                        },
                        onClearLogs = {
                            runtime.clearLogs()

                            executionLogs =
                                emptyList()
                        }
                    )
                }
            }
        }
    }
}
