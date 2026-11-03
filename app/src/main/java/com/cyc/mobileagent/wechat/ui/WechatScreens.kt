package com.cyc.mobileagent.wechat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyc.mobileagent.agent.CalibrationSnapshot
import com.cyc.mobileagent.agent.CalibrationTarget
import com.cyc.mobileagent.agent.ContactProfile

@Composable
fun WechatSettingsScreen(
    modifier: Modifier,
    contactCount: Int,
    calibrationReady: Boolean,
    onBack: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenCalibration: () -> Unit
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
                    "微信能力"
                )
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
                        "微信文字消息"
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Text(
                        "已启用。执行时由 Agent 自动判断是否调用微信，不需要你先选择应用。"
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick =
                    onOpenContacts,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "联系人档案 · $contactCount"
                )
            }
        }

        item {
            OutlinedButton(
                onClick =
                    onOpenCalibration,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    if (calibrationReady) {
                        "设备校准 · 已完成"
                    } else {
                        "设备校准 · 未完成"
                    }
                )
            }
        }
    }
}


@Composable
fun CalibrationScreen(
    modifier: Modifier,
    snapshot: CalibrationSnapshot,
    calibrationContact: String,
    onCalibrationContactChange: (String) -> Unit,
    message: String,
    onBack: () -> Unit,
    onStartCalibration: () -> Unit,
    onCancelCalibration: () -> Unit,
    onClearCalibration: () -> Unit
) {
    LazyColumn(
        modifier =
            modifier.padding(20.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp),
        contentPadding =
            PaddingValues(
                bottom = 28.dp
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
                    "设备校准"
                )
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
                        "不用输入坐标"
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Text(
                        "开始后会自动打开微信。你只需要按照屏幕浮层提示，依次点击 5 个真实控件。程序会自动计算并保存当前手机的相对坐标。"
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Text(
                        "最后一步只记录发送按钮位置，不会发送“校准测试”。"
                    )
                }
            }
        }

        item {
            Text(
                "状态：$message"
            )
        }

        item {
            OutlinedTextField(
                value =
                    calibrationContact,
                onValueChange =
                    onCalibrationContactChange,
                label = {
                    Text(
                        "校准联系人"
                    )
                },
                supportingText = {
                    Text(
                        "请输入一个可以在微信中搜索到的联系人"
                    )
                },
                modifier =
                    Modifier.fillMaxWidth()
            )
        }

        item {
            Button(
                onClick =
                    onStartCalibration,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    if (snapshot.isComplete) {
                        "重新开始引导式校准"
                    } else {
                        "开始引导式校准"
                    }
                )
            }
        }

        item {
            OutlinedButton(
                onClick =
                    onCancelCalibration,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "取消当前校准"
                )
            }
        }

        item {
            Text(
                "校准进度：${snapshot.completedCount}/${snapshot.totalCount}"
            )
        }

        items(
            items =
                CalibrationTarget.values()
                    .toList(),
            key = {
                it.storageKey
            }
        ) { target ->

            val point =
                snapshot.points[target]

            Card(
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {
                    Text(
                        target.displayName
                    )

                    Text(
                        if (
                            point?.calibrated ==
                            true
                        ) {
                            "已记录 ✓"
                        } else {
                            "使用默认值"
                        }
                    )
                }
            }
        }

        item {
            OutlinedButton(
                onClick =
                    onClearCalibration,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "清除本机校准数据"
                )
            }
        }
    }
}


@Composable
fun ContactManagerScreen(
    modifier: Modifier,
    contacts: List<ContactProfile>,
    message: String,
    onBack: () -> Unit,
    onCreateContact: (
        displayName: String,
        alias: String
    ) -> Unit,
    onUpdateName: (
        oldName: String,
        newName: String
    ) -> Unit,
    onAddAlias: (
        displayName: String,
        alias: String
    ) -> Unit,
    onRemoveAlias: (
        alias: String
    ) -> Unit,
    onDeleteContact: (
        displayName: String
    ) -> Unit
) {
    var createDisplayName by remember {
        mutableStateOf("")
    }

    var createAlias by remember {
        mutableStateOf("")
    }

    LazyColumn(
        modifier =
            modifier.padding(20.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp),
        contentPadding =
            PaddingValues(
                bottom = 24.dp
            )
    ) {
        item {
            Row(
                modifier =
                    Modifier.fillMaxWidth(),
                horizontalArrangement =
                    Arrangement.spacedBy(12.dp)
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
                    "联系人档案"
                )
            }
        }

        if (
            message.isNotBlank()
        ) {
            item {
                Text(
                    message
                )
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
                        "新建联系人档案"
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    OutlinedTextField(
                        value =
                            createDisplayName,
                        onValueChange = {
                            createDisplayName =
                                it
                        },
                        label = {
                            Text(
                                "真实微信名称"
                            )
                        },
                        supportingText = {
                            Text(
                                "填写微信中实际显示的联系人名称"
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    OutlinedTextField(
                        value =
                            createAlias,
                        onValueChange = {
                            createAlias =
                                it
                        },
                        label = {
                            Text(
                                "常用称呼 / 别名"
                            )
                        },
                        supportingText = {
                            Text(
                                "填写你平时使用的称呼或别名"
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Button(
                        onClick = {
                            val cleanDisplayName =
                                createDisplayName
                                    .trim()

                            val cleanAlias =
                                createAlias
                                    .trim()

                            if (
                                cleanDisplayName.isNotBlank() &&
                                cleanAlias.isNotBlank()
                            ) {
                                onCreateContact(
                                    cleanDisplayName,
                                    cleanAlias
                                )

                                createDisplayName =
                                    ""

                                createAlias =
                                    ""
                            }
                        },
                        enabled =
                            createDisplayName
                                .isNotBlank() &&
                            createAlias
                                .isNotBlank(),
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "创建联系人档案"
                        )
                    }
                }
            }
        }

        if (
            contacts.isEmpty()
        ) {
            item {
                Text(
                    "还没有联系人档案。可以在上面手动创建，也可以在首页使用明确的联系人绑定指令。"
                )
            }
        }

        items(
            items =
                contacts,
            key = {
                "${it.platform}:${it.displayName}"
            }
        ) { contact ->

            var editedName by remember(
                contact.displayName
            ) {
                mutableStateOf(
                    contact.displayName
                )
            }

            var newAlias by remember(
                contact.displayName
            ) {
                mutableStateOf("")
            }

            Card(
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier =
                        Modifier.padding(16.dp)
                ) {
                    Text(
                        "微信联系人"
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    OutlinedTextField(
                        value =
                            editedName,
                        onValueChange = {
                            editedName = it
                        },
                        label = {
                            Text(
                                "真实微信名称"
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Button(
                        onClick = {
                            onUpdateName(
                                contact.displayName,
                                editedName
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "保存微信名称"
                        )
                    }

                    Spacer(
                        modifier =
                            Modifier.height(16.dp)
                    )

                    Text(
                        "已有别名"
                    )

                    Spacer(
                        modifier =
                            Modifier.height(6.dp)
                    )

                    if (
                        contact.aliases.isEmpty()
                    ) {
                        Text(
                            "暂无别名"
                        )
                    } else {
                        contact.aliases.forEach {
                                alias ->

                            Row(
                                modifier =
                                    Modifier.fillMaxWidth(),
                                horizontalArrangement =
                                    Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text =
                                        alias
                                )

                                TextButton(
                                    onClick = {
                                        onRemoveAlias(
                                            alias
                                        )
                                    }
                                ) {
                                    Text(
                                        "删除"
                                    )
                                }
                            }
                        }
                    }

                    Spacer(
                        modifier =
                            Modifier.height(12.dp)
                    )

                    OutlinedTextField(
                        value =
                            newAlias,
                        onValueChange = {
                            newAlias = it
                        },
                        label = {
                            Text(
                                "新增别名"
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    )

                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )

                    Button(
                        onClick = {
                            if (
                                newAlias.isNotBlank()
                            ) {
                                onAddAlias(
                                    contact.displayName,
                                    newAlias
                                )

                                newAlias =
                                    ""
                            }
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "添加别名"
                        )
                    }

                    Spacer(
                        modifier =
                            Modifier.height(12.dp)
                    )

                    OutlinedButton(
                        onClick = {
                            onDeleteContact(
                                contact.displayName
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "删除整个联系人"
                        )
                    }
                }
            }
        }
    }
}
