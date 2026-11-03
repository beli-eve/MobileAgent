package com.cyc.mobileagent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.cyc.mobileagent.R
import kotlinx.coroutines.delay

enum class AppPage {
    HOME,
    WECHAT,
    EXECUTION_LOGS
}

@Composable
fun HomeScreen(
    modifier: Modifier,
    command: String,
    onCommandChange: (String) -> Unit,
    status: String,
    accessibilityEnabled: Boolean,
    isRunning: Boolean,
    canCancel: Boolean,
    onExecute: () -> Unit,
    onCancelTask: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenWechatSettings: () -> Unit,
    onOpenExecutionLogs: () -> Unit
) {
    val commandAreaBringIntoViewRequester =
        remember {
            BringIntoViewRequester()
        }

    var commandFieldFocused by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(
        commandFieldFocused
    ) {
        if (commandFieldFocused) {
            delay(220)
            commandAreaBringIntoViewRequester
                .bringIntoView()
        }
    }

    LazyColumn(
        modifier =
            modifier
                .imePadding()
                .padding(
                    horizontal = 20.dp,
                    vertical = 12.dp
                ),
        verticalArrangement =
            Arrangement.spacedBy(12.dp),
        contentPadding =
            PaddingValues(
                bottom = 28.dp
            )
    ) {
        item {
            Card(
                modifier =
                    Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = Color.White
                    )
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 18.dp,
                                vertical = 14.dp
                            ),
                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {
                    Image(
                        painter =
                            painterResource(
                                id = R.drawable.mobile_agent_cover
                            ),
                        contentDescription =
                            "MobileAgent 节点树封面",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                        contentScale =
                            ContentScale.Fit
                    )

                    Text(
                        text =
                            "MobileAgent",
                        style =
                            MaterialTheme.typography.headlineMedium,
                        color =
                            Color.Black
                    )

                    Spacer(
                        modifier =
                            Modifier.height(2.dp)
                    )

                    Text(
                        text =
                            "Agent Runtime",
                        style =
                            MaterialTheme.typography.bodyMedium,
                        color =
                            Color(0xFF666666)
                    )
                }
            }
        }

        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween,
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Text(
                    text =
                        "只说目标，应用选择交给 Agent",
                    modifier =
                        Modifier.weight(1f),
                    style =
                        MaterialTheme.typography.bodyMedium
                )

                TextButton(
                    onClick =
                        onOpenExecutionLogs,
                    enabled =
                        !isRunning
                ) {
                    Text(
                        "运行记录"
                    )
                }
            }
        }

        item {
            Card(
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier =
                        Modifier.padding(16.dp)
                ) {
                    Text(
                        "当前状态"
                    )

                    Spacer(
                        modifier =
                            Modifier.height(6.dp)
                    )

                    Text(
                        text =
                            status,
                        maxLines =
                            2
                    )
                }
            }
        }

        if (!accessibilityEnabled) {
            item {
                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier =
                            Modifier.padding(16.dp)
                    ) {
                        Text(
                            "无障碍服务"
                        )

                        Spacer(
                            modifier =
                                Modifier.height(6.dp)
                        )

                        Text(
                            "未开启，Agent 暂时无法操作手机"
                        )

                        Spacer(
                            modifier =
                                Modifier.height(10.dp)
                        )

                        OutlinedButton(
                            onClick =
                                onOpenAccessibilitySettings,
                            enabled =
                                !isRunning,
                            modifier =
                                Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "开启无障碍服务"
                            )
                        }
                    }
                }
            }
        }

        item {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .bringIntoViewRequester(
                            commandAreaBringIntoViewRequester
                        )
            ) {
                OutlinedTextField(
                    value =
                        command,
                    onValueChange =
                        onCommandChange,
                    label = {
                        Text(
                            "输入指令"
                        )
                    },
                    supportingText = {
                        Text(
                            "直接输入目标或指令"
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged {
                                commandFieldFocused =
                                    it.isFocused
                            }
                )

                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )

                Button(
                    onClick =
                        onExecute,
                    enabled =
                        !isRunning &&
                        command.isNotBlank(),
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isRunning) {
                            "正在处理..."
                        } else {
                            "交给 Agent"
                        }
                    )
                }
            }
        }

        if (
            isRunning &&
            canCancel
        ) {
            item {
                OutlinedButton(
                    onClick =
                        onCancelTask,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        "取消当前任务"
                    )
                }
            }
        }

        item {
            TextButton(
                onClick =
                    onOpenWechatSettings,
                enabled =
                    !isRunning,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "微信能力设置"
                )
            }
        }
    }
}


@Composable
fun ExecutionLogScreen(
    modifier: Modifier,
    logs: List<String>,
    onBack: () -> Unit,
    onClearLogs: () -> Unit
) {
    LazyColumn(
        modifier =
            modifier.padding(20.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp),
        contentPadding =
            PaddingValues(
                bottom =
                    28.dp
            )
    ) {
        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick =
                        onBack
                ) {
                    Text(
                        "返回"
                    )
                }

                Text(
                    "运行记录"
                )
            }
        }

        item {
            Text(
                "记录保存在 App 私有目录，不会显示在首页。"
            )
        }

        if (logs.isEmpty()) {
            item {
                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        text =
                            "暂无运行记录",
                        modifier =
                            Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(
                items =
                    logs
            ) {
                logLine ->

                Card(
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text(
                        text =
                            logLine,
                        modifier =
                            Modifier.padding(16.dp)
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick =
                    onClearLogs,
                enabled =
                    logs.isNotEmpty(),
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "清空运行记录"
                )
            }
        }
    }
}
