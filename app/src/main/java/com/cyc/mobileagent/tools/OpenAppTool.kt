package com.cyc.mobileagent.tools

import android.content.Context

object OpenAppTool {

    fun open(
        context: Context,
        packageName: String
    ): Boolean {

        val intent =
            context.packageManager.getLaunchIntentForPackage(packageName)

        return if (intent != null) {
            context.startActivity(intent)
            true
        } else {
            false
        }
    }

    fun openWeChat(context: Context): Boolean {
        return open(
            context,
            "com.tencent.mm"
        )
    }

}