package com.personaldashboard.app

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Move work files on the same filesystem; never copy session databases or credentials. */
object BotDirectoryLayout {
    @Synchronized
    fun prepare(files: File): File {
        directory(files)
        val root = File(files, "BOT")
        directory(root)
        relocate(File(files, "agent-workspace"), File(root, "default"))
        val oldBots = File(files, "bot-workspaces")
        if (!Files.isSymbolicLink(oldBots.toPath()) && oldBots.exists()) {
            directory(oldBots)
            oldBots.listFiles().orEmpty().forEach { entry ->
                if (entry.name == "default") {
                    check(
                        entry.isDirectory &&
                            !Files.isSymbolicLink(entry.toPath()) &&
                            entry.list().orEmpty().isEmpty(),
                    ) {
                        "旧默认挂载目录非空，原文件保留"
                    }
                    check(entry.delete())
                } else {
                    check(entry.name.matches(Regex("[0-9a-fA-F-]{36}"))) { "旧 Bot 目录包含未知文件，原文件保留" }
                    relocate(entry, File(root, entry.name), alias = false)
                }
            }
            check(oldBots.delete())
        }
        removeAlias(oldBots, root)
        relocate(File(files, "bot-system"), File(files, "CONFIG"))
        directory(File(files, "PLUGINS"))
        relocate(File(files, "documents"), File(root, "legacy-documents"))
        val home = File(files, "home")
        if (home.exists()) {
            directory(home)
            // Non-hidden entries were the old Agent's work. .dsh stays in place.
            val archive = File(root, "legacy-projects")
            val names =
                (home.listFiles().orEmpty().filter { !it.name.startsWith('.') }.map { it.name } +
                        archive.listFiles().orEmpty().map { it.name })
                    .toSet()
            if (names.isNotEmpty()) directory(archive)
            names.forEach { name -> relocate(File(home, name), File(archive, name)) }
        }
        File(root, "default/legacy-documents")
            .takeIf {
                it.isDirectory &&
                    !Files.isSymbolicLink(it.toPath()) &&
                    it.list().orEmpty().isEmpty()
            }
            ?.delete()
        return root
    }

    private fun directory(file: File) {
        check(!Files.isSymbolicLink(file.toPath())) { "Bot 目录不能是符号链接" }
        check(file.isDirectory || file.mkdirs()) { "无法创建 Bot 目录" }
    }

    private fun relocate(old: File, target: File, alias: Boolean = true) {
        val exists = Files.exists(old.toPath(), NOFOLLOW_LINKS)
        if (exists && Files.isSymbolicLink(old.toPath())) {
            check(old.canonicalFile == target.canonicalFile && target.exists()) { "旧目录链接不匹配，原文件保留" }
            Files.delete(old.toPath())
            return
        }
        check(!Files.isSymbolicLink(target.toPath())) { "目标不能是符号链接" }
        if (exists) {
            check(!Files.exists(target.toPath(), NOFOLLOW_LINKS)) { "归档目标已存在，原文件保留" }
            Files.move(old.toPath(), target.toPath())
        } else if (!target.exists()) directory(target)
        if (alias) removeAlias(old, target)
    }

    private fun removeAlias(old: File, target: File) {
        if (Files.isSymbolicLink(old.toPath())) {
            check(old.canonicalFile == target.canonicalFile) { "旧目录链接不匹配" }
            Files.delete(old.toPath())
        } else {
            check(!Files.exists(old.toPath(), NOFOLLOW_LINKS)) { "旧目录仍被占用" }
        }
    }
}
