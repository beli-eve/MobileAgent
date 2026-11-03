package com.cyc.mobileagent.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.cyc.mobileagent.agent.AgentExecutionEvent
import com.cyc.mobileagent.agent.AgentPlanner
import com.cyc.mobileagent.agent.ExecutionLogStore
import com.cyc.mobileagent.chatgpt.ChatGPTTaskController
import com.cyc.mobileagent.gemini.GeminiTaskController
import com.cyc.mobileagent.deepseek.DeepSeekTaskController
import com.cyc.mobileagent.runtime.AccessibilityController
import com.cyc.mobileagent.runtime.AgentRuntime
import com.cyc.mobileagent.runtime.RuntimeEventContract
import com.cyc.mobileagent.skill.ChatGPTSkill
import com.cyc.mobileagent.skill.ChatSkill
import com.cyc.mobileagent.skill.GeminiSkill
import com.cyc.mobileagent.skill.DeepSeekSkill
import com.cyc.mobileagent.skill.SkillRegistry
import com.cyc.mobileagent.wechat.WechatFeature
import com.google.gson.Gson

/**
 * MobileAgent 的 Composition Root。
 *
 * 具体 Skill 在这里组装，而不是在 Activity 中组装。
 * MainActivity 只持有一个 AppContainer。
 */
class AppContainer(
    context: Context
) {

    private val appContext =
        context.applicationContext

    val accessibilityController =
        AccessibilityController(
            appContext
        )

    val wechatFeature =
        WechatFeature(
            appContext
        )

    private val chatGPTController =
        ChatGPTTaskController(
            appContext
        )

    private val geminiController =
        GeminiTaskController(
            appContext
        )

    private val deepSeekController =
        DeepSeekTaskController(
            appContext
        )

    private val skillRegistry =
        SkillRegistry(
            skills =
                listOf(
                    ChatSkill(),
                    wechatFeature.skill,
                    ChatGPTSkill(
                        controller =
                            chatGPTController
                    ),
                    GeminiSkill(
                        controller =
                            geminiController
                    ),
                    DeepSeekSkill(
                        controller =
                            deepSeekController
                    )
                )
        )

    val runtime =
        AgentRuntime(
            planner =
                AgentPlanner(),
            skillRegistry =
                skillRegistry,
            executionLogStore =
                ExecutionLogStore(
                    appContext
                ),
            localCommandHandlers =
                listOf(
                    wechatFeature
                        .localCommandHandler
                )
        )

    private val gson =
        Gson()

    private var receiverRegistered =
        false

    private val executionEventReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    RuntimeEventContract
                        .ACTION_EXECUTION_EVENT
                ) {
                    return
                }

                val eventJson =
                    intent.getStringExtra(
                        RuntimeEventContract
                            .EXTRA_EXECUTION_EVENT_JSON
                    )
                        .orEmpty()
                        .trim()

                if (eventJson.isBlank()) {
                    return
                }

                val event =
                    try {
                        gson.fromJson(
                            eventJson,
                            AgentExecutionEvent::class.java
                        )
                    } catch (_: Exception) {
                        return
                    }

                runtime
                    .handleExecutionEvent(
                        event
                    )
            }
        }

    fun start() {
        if (receiverRegistered) {
            return
        }

        ContextCompat.registerReceiver(
            appContext,
            executionEventReceiver,
            IntentFilter(
                RuntimeEventContract
                    .ACTION_EXECUTION_EVENT
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        receiverRegistered =
            true
    }

    fun shouldOpenCalibration(
        intent: Intent?
    ): Boolean {
        return wechatFeature
            .shouldOpenCalibration(
                intent
            )
    }

    fun close() {
        if (!receiverRegistered) {
            return
        }

        try {
            appContext.unregisterReceiver(
                executionEventReceiver
            )
        } catch (_: Exception) {
            // Receiver 可能已经被系统注销。
        }

        receiverRegistered =
            false
    }
}
