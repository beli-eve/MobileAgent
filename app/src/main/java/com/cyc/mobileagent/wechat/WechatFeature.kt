package com.cyc.mobileagent.wechat

import android.content.Context
import android.content.Intent
import com.cyc.mobileagent.agent.BindAliasStatus
import com.cyc.mobileagent.agent.CalibrationSnapshot
import com.cyc.mobileagent.agent.CalibrationStore
import com.cyc.mobileagent.agent.ContactProfile
import com.cyc.mobileagent.agent.MobileAgentProfileStore
import com.cyc.mobileagent.agent.UpdateContactNameStatus
import com.cyc.mobileagent.runtime.LocalCommandHandler
import com.cyc.mobileagent.runtime.LocalCommandResult
import com.cyc.mobileagent.skill.AgentSkill

/**
 * 微信能力的单一外部入口。
 *
 * 外层不再直接接触：
 * - ProfileStore
 * - CalibrationStore
 * - WechatTaskBuilder
 * - WechatTaskController
 * - 微信广播协议
 *
 * 联系人、校准和执行 Skill 都由这个 Feature 内部管理。
 */
class WechatFeature(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val profileStore =
        MobileAgentProfileStore(
            appContext
        )

    private val calibrationStore =
        CalibrationStore(
            appContext
        )

    private val taskController =
        WechatTaskController(
            appContext
        )

    val skill: AgentSkill =
        WechatSkill(
            taskBuilder =
                WechatTaskBuilder(
                    profileStore
                ),
            taskController =
                taskController
        )

    val localCommandHandler: LocalCommandHandler =
        WechatLocalCommandHandler(
            profileStore
        )

    fun shouldOpenCalibration(
        intent: Intent?
    ): Boolean {
        return intent
            ?.getBooleanExtra(
                WechatContract
                    .EXTRA_OPEN_CALIBRATION_PAGE,
                false
            )
            ?: false
    }

    fun getContacts():
            List<ContactProfile> {
        return profileStore
            .getWechatContacts()
    }

    fun createContact(
        displayName: String,
        alias: String
    ): String {
        val result =
            profileStore
                .bindWechatAlias(
                    alias =
                        alias,
                    displayName =
                        displayName
                )

        return formatBindResult(
            result.status,
            result.alias,
            result.displayName,
            result.conflictingDisplayName
        )
    }

    fun updateContactName(
        oldName: String,
        newName: String
    ): String {
        val result =
            profileStore
                .updateWechatContactDisplayName(
                    currentDisplayName =
                        oldName,
                    newDisplayName =
                        newName
                )

        return when (result.status) {
            UpdateContactNameStatus.UPDATED ->
                "已修改：${result.oldDisplayName} → ${result.newDisplayName}"

            UpdateContactNameStatus.UNCHANGED ->
                "名称没有变化"

            UpdateContactNameStatus.NOT_FOUND ->
                "没有找到联系人：${result.oldDisplayName}"

            UpdateContactNameStatus.NAME_CONFLICT ->
                "修改失败：目标名称已经被其他联系人使用"
        }
    }

    fun addAlias(
        displayName: String,
        alias: String
    ): String {
        val result =
            profileStore
                .bindWechatAlias(
                    alias =
                        alias,
                    displayName =
                        displayName
                )

        return formatBindResult(
            result.status,
            result.alias,
            result.displayName,
            result.conflictingDisplayName
        )
    }

    fun removeAlias(
        alias: String
    ): String {
        val removed =
            profileStore
                .removeWechatAlias(
                    alias
                )

        return if (removed) {
            "已删除别名"
        } else {
            "没有找到这个别名"
        }
    }

    fun deleteContact(
        displayName: String
    ): String {
        val removed =
            profileStore
                .deleteWechatContact(
                    displayName
                )

        return if (removed) {
            "已删除联系人"
        } else {
            "没有找到这个联系人"
        }
    }

    fun getCalibrationSnapshot():
            CalibrationSnapshot {
        return calibrationStore
            .getSnapshot()
    }

    fun startCalibration(
        contact: String
    ): String {
        val cleanContact =
            contact.trim()

        if (cleanContact.isBlank()) {
            return "请输入一个可以搜索到的校准联系人"
        }

        if (
            !taskController
                .isAccessibilityEnabled()
        ) {
            return "请先开启 MobileAgent 无障碍服务"
        }

        if (
            !taskController
                .openWeChat()
        ) {
            return "没有找到微信"
        }

        val message =
            "微信已打开，请按照屏幕浮层提示完成校准"

        calibrationStore
            .setLastStatus(
                message
            )

        taskController
            .startCalibration(
                cleanContact
            )

        return message
    }

    fun cancelCalibration(): String {
        taskController
            .cancelCalibration()

        return "已请求取消校准"
    }

    fun clearCalibration():
            CalibrationSnapshot {
        calibrationStore
            .clear()

        return calibrationStore
            .getSnapshot()
    }

    private fun formatBindResult(
        status: BindAliasStatus,
        alias: String,
        displayName: String,
        conflictingDisplayName: String?
    ): String {
        return when (status) {
            BindAliasStatus.CREATED_NEW_CONTACT ->
                "联系人档案已创建：$alias → $displayName"

            BindAliasStatus.ADDED_TO_EXISTING_CONTACT ->
                "联系人别名已添加：$alias → $displayName"

            BindAliasStatus.ALREADY_BOUND ->
                "这个联系人映射已经存在"

            BindAliasStatus.CONFLICT ->
                "别名冲突：当前已经绑定到 ${conflictingDisplayName.orEmpty()}"
        }
    }
}

/**
 * 明确的微信联系人绑定命令在本地处理，
 * 不让 LLM 决定是否发送消息。
 */
private class WechatLocalCommandHandler(
    private val profileStore: MobileAgentProfileStore
) : LocalCommandHandler {

    private val commandRouter =
        WechatContactCommandRouter()

    override fun tryHandle(
        command: String
    ): LocalCommandResult? {
        val binding =
            commandRouter
                .parseBinding(
                    command
                )
                ?: return null

        val result =
            profileStore
                .bindWechatAlias(
                    alias =
                        binding.alias,
                    displayName =
                        binding.wechatName
                )

        val status =
            when (result.status) {
                BindAliasStatus.CREATED_NEW_CONTACT ->
                    "联系人档案已创建"

                BindAliasStatus.ADDED_TO_EXISTING_CONTACT ->
                    "联系人别名已添加"

                BindAliasStatus.ALREADY_BOUND ->
                    "这个联系人映射已经存在"

                BindAliasStatus.CONFLICT ->
                    "联系人别名发生冲突"
            }

        val log =
            when (result.status) {
                BindAliasStatus.CREATED_NEW_CONTACT ->
                    "本地联系人档案已创建"

                BindAliasStatus.ADDED_TO_EXISTING_CONTACT ->
                    "本地联系人别名已添加"

                BindAliasStatus.ALREADY_BOUND ->
                    "本地联系人映射已存在"

                BindAliasStatus.CONFLICT ->
                    "本地联系人别名冲突"
            }

        return LocalCommandResult(
            statusMessage =
                status,
            logs =
                listOf(
                    log
                ),
            dataChanged =
                result.status ==
                    BindAliasStatus.CREATED_NEW_CONTACT ||
                    result.status ==
                    BindAliasStatus.ADDED_TO_EXISTING_CONTACT
        )
    }
}
