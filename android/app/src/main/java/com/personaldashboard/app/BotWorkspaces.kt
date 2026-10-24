package com.personaldashboard.app

import android.content.Context
import java.io.File

/** One writer shared by Activity, the runtime's same-UID socket. */
object BotWorkspaces {
    private var store: BotWorkspaceStore? = null

    @Synchronized
    fun get(context: Context): BotWorkspaceStore =
        store
            ?: BotWorkspaceStore(
                    context.applicationContext.filesDir,
                    context.assets.open("bot-persona.md").bufferedReader().use { it.readText() },
                )
                .also {
                    PluginDeveloperBot.seed(context, it)
                    store = it
                }

    fun roots(context: Context): File = BotDirectoryLayout.prepare(context.filesDir)
}
