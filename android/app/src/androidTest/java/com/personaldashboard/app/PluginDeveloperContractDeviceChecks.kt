package com.personaldashboard.app

import android.app.Instrumentation
import android.content.ContextWrapper
import android.os.Build
import android.os.Bundle
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/** Existing Bot upgrades must receive current guidance without replacing owner data. */
internal class PluginDeveloperContractDeviceChecks(private val runner: Instrumentation) {
    fun run() {
        check(Build.MODEL.contains("sdk") || Build.FINGERPRINT.contains("emulator")) { "仅允许专用模拟器" }
        val root = Files.createTempDirectory(runner.targetContext.cacheDir.toPath(), "bot-contract-").toFile()
        val isolated = object : ContextWrapper(runner.targetContext) { override fun getFilesDir() = root }
        val store = BotWorkspaceStore(root, "SYNTHETIC_PERSONA")
        val id = PluginDeveloperBot.ID
        try {
            PluginDeveloperBot.seed(isolated, store)
            val settings = store.call(JSONObject().put("op", "settings").put("id", id))
            store.call(JSONObject().put("op", "saveSettings").put("id", id).put("instructions", "SYNTHETIC_OWNER_INSTRUCTIONS").put("memory", "SYNTHETIC_OWNER_MEMORY").put("enabledTools", settings.getJSONArray("enabledTools")))
            store.call(JSONObject().put("op", "savePersona").put("text", "SYNTHETIC_OWNER_PERSONA"))
            val skill = File(root, "BOT/$id/skills/app-plugin")
            val entry = File(skill, "SKILL.md")
            val ownerText = "---\nname: app-plugin\ndescription: synthetic custom entry\n---\n# Custom owner skill\nSYNTHETIC_OWNER_NOTES\n"
            entry.writeText(ownerText)
            File(skill, "host-contract.md").writeText("STALE_FRAMEWORK")
            PluginDeveloperBot.seed(isolated, store)
            val upgraded = entry.readText()
            check(upgraded.startsWith(ownerText.substringBefore("# Custom"))) { "Framework preamble broke skill frontmatter" }
            check(upgraded.contains("当前框架入口") && upgraded.endsWith("# Custom owner skill\nSYNTHETIC_OWNER_NOTES\n"))
            check(File(skill, "host-contract.md").readText() == isolated.assets.open("plugin-developer/host-contract.md").bufferedReader().use { it.readText() })
            check(File(skill, "host-contract.md").readText().contains("按需参考"))
            PluginDeveloperBot.seed(isolated, store)
            check(entry.readText() == upgraded) { "Repeated startup duplicated framework guidance" }
            val saved = store.call(JSONObject().put("op", "settings").put("id", id))
            check(saved.getString("instructions") == "SYNTHETIC_OWNER_INSTRUCTIONS")
            check(saved.getString("memory") == "SYNTHETIC_OWNER_MEMORY")
            check(store.call(JSONObject().put("op", "persona")).getString("text") == "SYNTHETIC_OWNER_PERSONA")
            check(saved.getJSONArray("enabledTools").toString() == settings.getJSONArray("enabledTools").toString())
            runner.sendStatus(0, Bundle().apply { putString("stream", "PASS: Existing Bot gets current host contract and one managed entry; skill frontmatter, owner notes, instructions, memory, persona and tools remain intact\n") })
        } finally { root.deleteRecursively() }
    }
}
