package com.cyc.mobileagent.wechat

/**
 * 微信联系人映射的确定性本地解析器。
 *
 * 只匹配明确包含微信语义的联系人绑定命令。
 * 普通事实陈述不会在这里被截获。
 */
class WechatContactCommandRouter {

    data class BindingCommand(
        val alias: String,
        val wechatName: String
    )

    fun parseBinding(
        rawCommand: String
    ): BindingCommand? {
        val command =
            rawCommand
                .trim()
                .trimEnd(
                    '。',
                    '.'
                )

        if (command.isBlank()) {
            return null
        }

        val match =
            REMEMBER_PATTERN
                .matchEntire(
                    command
                )
                ?: EXPLICIT_PATTERN
                    .matchEntire(
                        command
                    )
                ?: BIND_PATTERN
                    .matchEntire(
                        command
                    )
                ?: return null

        val alias =
            cleanValue(
                match.groupValues[1]
            )

        val wechatName =
            cleanValue(
                match.groupValues[2]
            )

        if (
            alias.isBlank() ||
            wechatName.isBlank() ||
            alias == wechatName
        ) {
            return null
        }

        return BindingCommand(
            alias =
                alias,
            wechatName =
                wechatName
        )
    }

    private fun cleanValue(
        value: String
    ): String {
        return value
            .trim()
            .trim(
                '，',
                ',',
                '：',
                ':',
                '“',
                '”',
                '"',
                '\'',
                ' '
            )
    }

    companion object {

        private val REMEMBER_PATTERN =
            Regex(
                pattern =
                    """^记住[，,：:\s]*(.+?)\s*(?:是|就是|对应(?:的是)?|对应到)\s*(?:微信联系人(?:名称)?|微信(?:里|里的|中|中的|上|上的)?(?:的)?)(?:叫|是|为)?\s*(.+)$""",
                option =
                    RegexOption.IGNORE_CASE
            )

        private val EXPLICIT_PATTERN =
            Regex(
                pattern =
                    """^(.+?)\s*(?:是|就是|对应(?:的是)?)\s*(?:微信联系人(?:名称)?|微信(?:里|里的|中|中的|上|上的)?(?:的)?)(?:叫|是|为)?\s*(.+)$""",
                option =
                    RegexOption.IGNORE_CASE
            )

        private val BIND_PATTERN =
            Regex(
                pattern =
                    """^(?:把\s*)?(.+?)\s*(?:绑定到|绑定为|设为|设置为|对应到)\s*(?:微信联系人(?:名称)?|微信(?:里|里的|中|中的|上|上的)?(?:的)?)\s*(.+)$""",
                option =
                    RegexOption.IGNORE_CASE
            )
    }
}
