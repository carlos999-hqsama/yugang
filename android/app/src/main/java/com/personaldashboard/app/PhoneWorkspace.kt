package com.personaldashboard.app

import android.content.Context
import java.io.File

object PhoneWorkspace {
    const val VIRTUAL_ROOT = "/BOT/default"

    fun prepare(context: Context): File =
        File(BotDirectoryLayout.prepare(context.filesDir), "default")
}
