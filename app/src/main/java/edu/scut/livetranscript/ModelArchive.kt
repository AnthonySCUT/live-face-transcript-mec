package edu.scut.livetranscript

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Installs an official Vosk ZIP into private storage, preserving the previous model on failure. */
object ModelArchive {
    fun install(input: InputStream, parent: File, kind: String) {
        require(kind == "english" || kind == "speaker")
        check(parent.isDirectory || parent.mkdirs())
        val stage = File(parent, ".import-${java.util.UUID.randomUUID()}")
        check(stage.mkdir())
        try {
            var total = 0L
            var count = 0
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count <= 10_000) { "模型文件数过多" }
                    val file = File(stage, entry.name).canonicalFile
                    require(file.path.startsWith(stage.canonicalPath + File.separator)) { "ZIP 路径不合法" }
                    if (entry.isDirectory) check(file.isDirectory || file.mkdirs()) else {
                        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                        require(!file.exists()) { "ZIP 包含重复文件" }
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= 300L * 1024 * 1024) { "解压模型超过 300 MiB" }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            fun isModel(dir: File): Boolean = if (kind == "english")
                File(dir, "am/final.mdl").isFile && File(dir, "conf/model.conf").isFile && File(dir, "graph").isDirectory
            else File(dir, "final.ext.raw").isFile && File(dir, "mean.vec").isFile && File(dir, "transform.mat").isFile
            val model = (listOf(stage) + stage.listFiles().orEmpty().filter { it.isDirectory }).singleOrNull(::isModel)
                ?: error("ZIP 中未找到匹配的 Vosk 模型")
            val target = File(parent, kind)
            val backup = File(parent, ".backup-${java.util.UUID.randomUUID()}")
            if (target.exists()) check(target.renameTo(backup)) { "无法备份旧模型" }
            if (!model.renameTo(target)) {
                if (backup.exists()) check(backup.renameTo(target)) { "恢复旧模型失败" }
                error("无法安装模型")
            }
            backup.deleteRecursively()
        } finally { stage.deleteRecursively() }
    }
}
