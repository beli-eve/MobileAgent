package com.cyc.mobileagent.wechat.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cyc.mobileagent.wechat.WechatFeature

/**
 * 微信能力自己的 Compose 入口。
 *
 * 联系人列表、联系人 CRUD、校准页面和微信子页面导航都封装在这里。
 * App 级 UI 只负责“进入 / 退出微信能力设置”，不再管理微信内部状态。
 */
@Composable
fun WechatFeatureScreen(
    feature: WechatFeature,
    resumeVersion: Int,
    dataVersion: Int,
    openCalibrationInitially: Boolean,
    onBack: () -> Unit
) {
    var page by remember {
        mutableStateOf(
            if (openCalibrationInitially) {
                WechatFeaturePage.CALIBRATION
            } else {
                WechatFeaturePage.ROOT
            }
        )
    }

    var contacts by remember {
        mutableStateOf(
            feature.getContacts()
        )
    }

    var profileMessage by remember {
        mutableStateOf("")
    }

    var calibrationContact by remember {
        mutableStateOf("")
    }

    var calibrationSnapshot by remember {
        mutableStateOf(
            feature.getCalibrationSnapshot()
        )
    }

    var calibrationMessage by remember {
        mutableStateOf(
            calibrationSnapshot.lastStatus
        )
    }

    fun refreshContacts() {
        contacts =
            feature.getContacts()
    }

    fun refreshCalibration() {
        calibrationSnapshot =
            feature.getCalibrationSnapshot()

        calibrationMessage =
            calibrationSnapshot.lastStatus
    }

    LaunchedEffect(
        resumeVersion,
        dataVersion
    ) {
        refreshContacts()
        refreshCalibration()
    }

    when (page) {
        WechatFeaturePage.ROOT -> {
            WechatSettingsScreen(
                modifier =
                    Modifier.fillMaxSize(),
                contactCount =
                    contacts.size,
                calibrationReady =
                    calibrationSnapshot.isComplete,
                onBack =
                    onBack,
                onOpenContacts = {
                    refreshContacts()
                    profileMessage =
                        ""
                    page =
                        WechatFeaturePage.CONTACTS
                },
                onOpenCalibration = {
                    refreshCalibration()
                    page =
                        WechatFeaturePage.CALIBRATION
                }
            )
        }

        WechatFeaturePage.CONTACTS -> {
            ContactManagerScreen(
                modifier =
                    Modifier.fillMaxSize(),
                contacts =
                    contacts,
                message =
                    profileMessage,
                onBack = {
                    page =
                        WechatFeaturePage.ROOT
                },
                onCreateContact = {
                    displayName,
                    alias ->

                    profileMessage =
                        try {
                            feature.createContact(
                                displayName =
                                    displayName,
                                alias =
                                    alias
                            )
                        } catch (
                            e: Exception
                        ) {
                            e.message
                                ?: "创建联系人失败"
                        }

                    refreshContacts()
                },
                onUpdateName = {
                    oldName,
                    newName ->

                    profileMessage =
                        try {
                            feature.updateContactName(
                                oldName =
                                    oldName,
                                newName =
                                    newName
                            )
                        } catch (
                            e: Exception
                        ) {
                            e.message
                                ?: "修改失败"
                        }

                    refreshContacts()
                },
                onAddAlias = {
                    displayName,
                    alias ->

                    profileMessage =
                        try {
                            feature.addAlias(
                                displayName =
                                    displayName,
                                alias =
                                    alias
                            )
                        } catch (
                            e: Exception
                        ) {
                            e.message
                                ?: "添加失败"
                        }

                    refreshContacts()
                },
                onRemoveAlias = {
                    alias ->

                    profileMessage =
                        feature.removeAlias(
                            alias
                        )

                    refreshContacts()
                },
                onDeleteContact = {
                    displayName ->

                    profileMessage =
                        feature.deleteContact(
                            displayName
                        )

                    refreshContacts()
                }
            )
        }

        WechatFeaturePage.CALIBRATION -> {
            CalibrationScreen(
                modifier =
                    Modifier.fillMaxSize(),
                snapshot =
                    calibrationSnapshot,
                calibrationContact =
                    calibrationContact,
                onCalibrationContactChange = {
                    calibrationContact =
                        it
                },
                message =
                    calibrationMessage,
                onBack = {
                    page =
                        WechatFeaturePage.ROOT
                },
                onStartCalibration = {
                    val resultMessage =
                        feature.startCalibration(
                            calibrationContact
                        )

                    calibrationSnapshot =
                        feature.getCalibrationSnapshot()

                    calibrationMessage =
                        resultMessage
                },
                onCancelCalibration = {
                    calibrationMessage =
                        feature.cancelCalibration()
                },
                onClearCalibration = {
                    calibrationSnapshot =
                        feature.clearCalibration()

                    calibrationMessage =
                        calibrationSnapshot.lastStatus
                }
            )
        }
    }
}

private enum class WechatFeaturePage {
    ROOT,
    CONTACTS,
    CALIBRATION
}
