package edu.scut.livetranscript

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class ModelArchiveTest {
    private fun archive(vararg names: String): ByteArray = ByteArrayOutputStream().also { buffer ->
        ZipOutputStream(buffer).use { zip -> names.forEach { name ->
            zip.putNextEntry(ZipEntry(name)); zip.write(byteArrayOf(1)); zip.closeEntry()
        } }
    }.toByteArray()

    @Test fun installsWrappedModelAndPreservesOldModelAfterInvalidReplacement() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            ModelArchive.install(archive("vosk/am/final.mdl", "vosk/conf/model.conf", "vosk/graph/words.txt").inputStream(), root, "english")
            assertTrue(root.resolve("english/am/final.mdl").isFile)
            try { ModelArchive.install(archive("wrong.txt").inputStream(), root, "english"); fail() }
            catch (_: IllegalStateException) { }
            assertTrue(root.resolve("english/am/final.mdl").isFile)
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsPathTraversal() {
        val root = Files.createTempDirectory("models").toFile()
        try {
            try { ModelArchive.install(archive("../escape.txt").inputStream(), root, "speaker"); fail() }
            catch (_: IllegalArgumentException) { }
            assertFalse(root.resolve("escape.txt").exists())
        } finally { root.deleteRecursively() }
    }
}
