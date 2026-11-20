package com.yg.tweetsweeper.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * 解析 X 官方数据导出（Your X data）里的关系文件。
 *
 * following.js / follower.js 形如：
 *   window.YTD.following.part0 = [ { "following" : {
 *       "accountId" : "12345678",
 *       "userLink" : "https://twitter.com/intent/user?user_id=12345678" } } ]
 *
 * 只依赖 accountId，因此不需要任何 API 调用。
 *
 * 注意：X 的包有 6000+ 条目，用 [ZipInputStream] 顺序读会因为中央目录/数据描述符
 * 错位只读到第一个条目（实测 85 MB 的真实包只返回 1 个 entry），所以这里改成
 * 落临时文件后用 [ZipFile] 按中央目录读。
 */
object ArchiveImporter {

    data class Parsed(
        val following: List<String> = emptyList(),
        val follower: List<String> = emptyList(),
        val files: List<String> = emptyList()
    )

    private val ID_RE = Regex("\"accountId\"\\s*:\\s*\"(\\d+)\"")
    private const val MAX_ENTRY_BYTES = 48 * 1024 * 1024

    /** 从任意输入流解析：先落临时文件（ZipFile 需要真实路径），解析完删除。 */
    fun parse(input: InputStream, fileName: String? = null): Parsed {
        val tmp = File.createTempFile("ts-archive-", ".zip")
        return try {
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst, 64 * 1024) } }
            parse(tmp, fileName)
        } finally {
            tmp.delete()
        }
    }

    fun parse(file: File, fileName: String? = null): Parsed =
        if (isZip(file)) parseZip(file) else parseJs(file.readText(), fileName ?: file.name)

    /** 直接喂一段 JS / JSON 文本（调试或单文件导入用） */
    fun parseText(text: String, fileName: String = ""): Parsed = parseJs(text, fileName)

    private fun isZip(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private fun parseZip(file: File): Parsed {
        val following = ArrayList<String>()
        val follower = ArrayList<String>()
        val files = ArrayList<String>()

        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/').lowercase()
                if (!base.endsWith(".js")) continue
                val isFollowing = base.startsWith("following")
                val isFollower = base.startsWith("follower")
                if (!isFollowing && !isFollower) continue
                if (entry.size > MAX_ENTRY_BYTES) continue

                val text = zip.getInputStream(entry).use { readLimited(it) }
                val ids = extractIds(text)
                if (isFollowing) following += ids else follower += ids
                files += entry.name
            }
        }
        return Parsed(following.distinct(), follower.distinct(), files)
    }

    private fun parseJs(text: String, name: String): Parsed {
        val ids = extractIds(text)
        val lower = name.lowercase()
        val isFollower = when {
            lower.contains("following") -> false
            lower.contains("follower") -> true
            text.contains("\"follower\"") -> true
            else -> false
        }
        return if (isFollower) {
            Parsed(follower = ids, files = listOf(name.ifBlank { "follower.js" }))
        } else {
            Parsed(following = ids, files = listOf(name.ifBlank { "following.js" }))
        }
    }

    fun extractIds(text: String): List<String> =
        ID_RE.findAll(text).map { it.groupValues[1] }.distinct().toList()

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            total += read
            if (total > MAX_ENTRY_BYTES) break
            out.write(buf, 0, read)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 兼容老接口：保留供测试或外部使用 */
    @Suppress("unused")
    private fun streamEntries(file: File): List<String> = buildList {
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                add(e.name)
                zip.closeEntry()
            }
        }
    }
}
