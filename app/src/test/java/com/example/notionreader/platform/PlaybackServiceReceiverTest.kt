package com.example.notionreader.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaybackServiceReceiverTest {

    private fun mainSourceRoot(): File {
        val relative = "src/main/java/com/example/notionreader"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (cand in listOf(File(dir, relative), File(dir, "app/$relative"))) {
                if (cand.isDirectory) return cand.canonicalFile
            }
            dir = dir.parentFile
        }
        error("Could not find $relative")
    }

    @Test
    fun playbackServiceRegistersNoisyReceiverWithNotExportedFlag() {
        val serviceFile = File(mainSourceRoot(), "PlaybackService.kt")
        assertTrue("PlaybackService.kt must exist", serviceFile.exists())
        val content = serviceFile.readText()

        val cleanCode = content.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

        val rawRegisterReceiverRegex = Regex("""(?<!ContextCompat\.)registerReceiver\s*\(\s*noisyReceiver\s*,\s*noisyFilter\s*\)""")

        assertFalse(
            "PlaybackService.kt must not call raw registerReceiver without export flags. " +
                "Use ContextCompat.registerReceiver(this, noisyReceiver, noisyFilter, ContextCompat.RECEIVER_NOT_EXPORTED)",
            rawRegisterReceiverRegex.containsMatchIn(cleanCode)
        )

        assertTrue(
            "PlaybackService.kt must specify ContextCompat.RECEIVER_NOT_EXPORTED",
            cleanCode.contains("RECEIVER_NOT_EXPORTED") && cleanCode.contains("ContextCompat.registerReceiver")
        )
    }

    @Test
    fun allRuntimeReceiversAcrossAppSpecifyExportFlags() {
        val files = mainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val violations = mutableListOf<String>()

        val rawCallRegex = Regex("""(?<!ContextCompat\.)registerReceiver\s*\(\s*[^,]+,\s*[^,)]+\s*\)""")

        for (file in files) {
            val lines = file.readLines()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("//") || trimmed.startsWith("/*")) continue
                if (rawCallRegex.containsMatchIn(line) && !line.contains("RECEIVER_")) {
                    violations += "${file.name}:${index + 1}: $trimmed"
                }
            }
        }

        assertTrue(
            "Runtime broadcast receivers without explicit export flag:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
