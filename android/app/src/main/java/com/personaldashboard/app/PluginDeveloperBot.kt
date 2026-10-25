package com.personaldashboard.app

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** One-time onboarding. Later user edits to this Bot and its skill are preserved. */
object PluginDeveloperBot {
    const val ID = "00000000-0000-4000-8000-000000000001"

    fun seed(context: Context, store: BotWorkspaceStore) {
        val marker = File(context.filesDir, "CONFIG/plugin-developer-seeded.json")
        if (marker.isFile) {
            updateFrameworkSkill(context)
            return
        }
        val registry = store.call(JSONObject().put("op", "list"))
        val bots = registry.getJSONArray("bots")
        if ((0 until bots.length()).none { bots.getJSONObject(it).getString("id") == ID }) {
            store.call(JSONObject().put("op", "create").put("id", ID).put("name", "插件 Bot"))
        }
        val settings = store.call(JSONObject().put("op", "settings").put("id", ID))
        if (settings.getString("instructions").isBlank())
            store.call(
                JSONObject()
                    .put("op", "saveSettings")
                    .put("id", ID)
                    .put(
                        "instructions",
                        "你是本App专用插件开发 Bot，继承共同人格，与主 Bot 保持人格一致。" +
                            "设计、编写、安装、测试、维护与回退独立插件。" +
                            "开始前读取 skills/app-plugin/SKILL.md 及技能引用的 host-contract.md。" +
                            "源码归 /PLUGINS/<id>，开发记忆与验收记录归自己的工作区。" +
                            "界面与业务逻辑归插件包，不使用宿主旧页面或旧路径兼容。" +
                            "按真实结果验收，不读取或输出凭据，不修改其他Bot数据。",
                    )
                    .put("memory", settings.getString("memory"))
                    .put("enabledTools", settings.getJSONArray("enabledTools")),
            )
        val skill = skillDirectory(context)
        for (name in listOf("SKILL.md", "host-contract.md", "page-api.md")) {
            val target = File(skill, name)
            if (!Files.exists(target.toPath(), NOFOLLOW_LINKS))
                writeReference(
                    target,
                    context.assets.open("plugin-developer/$name").bufferedReader().use {
                        it.readText()
                    },
                )
        }
        for (i in 0 until bots.length()) {
            val id = bots.getJSONObject(i).getString("id")
            store.call(
                JSONObject()
                    .put("op", "pin")
                    .put("id", id)
                    .put("pinned", id == "default" || id == ID),
            )
        }
        store.call(JSONObject().put("op", "pin").put("id", ID).put("pinned", true))
        marker.writeText("{\"version\":1}")
        updateFrameworkSkill(context)
    }

    /**
     * Framework references are host-owned; Bot instructions/memory and custom skill text remain
     * user-owned.
     */
    private fun updateFrameworkSkill(context: Context) {
        val skill = skillDirectory(context)
        for (name in
            listOf(
                "host-contract.md",
                "page-api.md",
                "file-api.md",
                "browser-api.md",
                "software-development.md",
                "visual-standard.md",
                "network-api.md",
                "preview-api.md",
                "service-api.md",
            )) {
            val text =
                context.assets.open("plugin-developer/$name").bufferedReader().use { it.readText() }
            val target = File(skill, name)
            check(!Files.isSymbolicLink(target.toPath())) { "插件开发参考不能是符号链接" }
            if (!target.isFile || target.readText() != text) writeReference(target, text)
        }
        val entry = File(skill, "SKILL.md")
        check(!Files.isSymbolicLink(entry.toPath())) { "插件开发技能不能是符号链接" }
        if (!entry.isFile)
            writeReference(
                entry,
                context.assets.open("plugin-developer/SKILL.md").bufferedReader().use {
                    it.readText()
                },
            )
        val text = entry.readText()
        val current = currentSkillEntry(text)
        if (current != text) writeReference(entry, current)
    }

    /** Only the bounded host-owned entry changes; the Bot owner's skill body stays intact. */
    internal fun currentSkillEntry(text: String): String {
        val withoutOldGuide = text.replace(LEGACY_GUIDE, "")
        val body =
            Regex("(?s)<!-- yugang-framework:start -->.*?<!-- yugang-framework:end -->\\n?")
                .replace(withoutOldGuide, "")
        val header = Regex("\\A---\\R.*?\\R---\\R", RegexOption.DOT_MATCHES_ALL).find(body)
        val split = header?.value?.length ?: 0
        return body.substring(0, split) + FRAMEWORK_GUIDE + body.substring(split)
    }

    private const val FRAMEWORK_GUIDE =
        "<!-- yugang-framework:start -->\n" +
            "当前框架入口（宿主维护）：先读取 [宿主契约](host-contract.md)，按其索引只读当前步骤需要的参考。" +
            "以下旧版流程与当前宿主契约冲突时以契约为准，不一次读完全部参考；用户当前明确要求优先。" +
            "个人补充保留在此技能正文中，不会因框架更新被覆盖。\n" +
            "<!-- yugang-framework:end -->\n"
    private const val LEGACY_GUIDE =
        "\n\n开始开发前，必须读取宿主维护的 [软件开发规范](software-development.md)、" +
            "[统一视觉规范](visual-standard.md)、[页面接口](page-api.md) 和 " +
            "[宿主契约](host-contract.md)、[联网接口](network-api.md) 和 " +
            "[独立验收通道](preview-api.md)、[模型与后台服务](service-api.md)。" +
            "这些框架参考随 App 更新；个人补充写在本技能或 Bot 记忆中。\n"

    private fun skillDirectory(context: Context): File {
        var directory = context.filesDir
        for (name in listOf("BOT", ID, "skills", "app-plugin")) {
            directory = File(directory, name)
            check(!Files.isSymbolicLink(directory.toPath())) { "插件开发技能目录不能是符号链接" }
            check(directory.isDirectory || directory.mkdir()) { "插件开发技能目录不可用" }
        }
        return directory
    }

    private fun writeReference(target: File, text: String) {
        check(!Files.isSymbolicLink(target.toPath())) { "插件开发参考不能是符号链接" }
        val temp =
            Files.createTempFile(requireNotNull(target.parentFile).toPath(), ".reference-", ".tmp")
        try {
            Files.write(temp, text.toByteArray(Charsets.UTF_8))
            Files.move(
                temp,
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
