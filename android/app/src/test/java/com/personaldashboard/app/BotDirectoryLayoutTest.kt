package com.personaldashboard.app

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.nio.file.Files

class BotDirectoryLayoutTest {
    @Test fun movesWorkOnlyAndLeavesNoAliasesAcrossRepeatedStarts() {
        val root = Files.createTempDirectory("bot-layout").toFile()
        try {
            fun write(path: String, value: String) = File(root,path).apply { checkNotNull(parentFile).mkdirs(); writeText(value) }
            val id = "11111111-1111-4111-8111-111111111111"
            write("agent-workspace/documents/old.txt", "old default")
            write("bot-workspaces/$id/own.txt", "own")
            File(root,"bot-workspaces/default").mkdirs()
            write("bot-system/PERSONA.md", "common")
            write("documents/old.txt", "old document")
            write("home/project/old.txt", "old project")
            val runtime = write("home/.dsh/sessions/canonical", "must stay")
            val before = runtime.toPath().toRealPath()
            repeat(3) { BotDirectoryLayout.prepare(root) }
            assertEquals("old default", File(root,"BOT/default/documents/old.txt").readText())
            assertEquals("own", File(root,"BOT/$id/own.txt").readText())
            assertEquals("common", File(root,"CONFIG/PERSONA.md").readText())
            assertEquals("old document",File(root,"BOT/legacy-documents/old.txt").readText())
            assertEquals("old project",File(root,"BOT/legacy-projects/project/old.txt").readText())
            assertEquals(before,runtime.toPath().toRealPath())
            assertFalse(File(root,"BOT/legacy-projects/.dsh").exists())
            for (old in listOf("agent-workspace", "bot-workspaces", "bot-system", "documents", "home/project")) assertFalse(Files.exists(File(root,old).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertTrue(File(root,"PLUGINS").isDirectory)
        } finally { root.deleteRecursively() }
    }
    @Test fun removesOnlyMatchingOldAliasesAndPreservesTarget() {
        val root = Files.createTempDirectory("bot-layout").toFile()
        try {
            File(root,"BOT/default").mkdirs()
            File(root,"BOT/legacy-projects/project").mkdirs()
            File(root,"BOT/legacy-projects/project/old.txt").writeText("preserved")
            File(root,"home/.dsh").mkdirs()
            Files.createSymbolicLink(File(root,"agent-workspace").toPath(), File(root,"BOT/default").toPath())
            Files.createSymbolicLink(File(root,"home/project").toPath(), File(root,"BOT/legacy-projects/project").toPath())
            BotDirectoryLayout.prepare(root)
            assertFalse(Files.exists(File(root,"agent-workspace").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(File(root,"home/project").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("preserved",File(root,"BOT/legacy-projects/project/old.txt").readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun conflictsFailWithoutOverwritingEitherCopy() {
        val root = Files.createTempDirectory("bot-layout").toFile()
        try {
            for (p in listOf("agent-workspace", "BOT/default")) File(root,p).mkdirs()
            File(root,"agent-workspace/value").writeText("original")
            File(root,"BOT/default/value").writeText("target")
            try { BotDirectoryLayout.prepare(root); fail("expected conflict") } catch (_: IllegalStateException) {}
            assertEquals("original",File(root,"agent-workspace/value").readText())
            assertEquals("target",File(root,"BOT/default/value").readText())
        } finally { root.deleteRecursively() }
    }
}
