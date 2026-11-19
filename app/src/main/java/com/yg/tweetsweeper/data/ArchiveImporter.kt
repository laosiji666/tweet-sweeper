package com.yg.tweetsweeper.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
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
 */
object ArchiveImporter {

    data class Parsed(
        val following: List<String> = emptyList(),
        val follower: List<String> = emptyList(),
        val files: List<String> = emptyList()
    )

    private val ID_RE = Regex("\"accountId\"\\s*:\\s*\"(\\d+)\"")
    private const val MAX_BYTES = 48 * 1024 * 1024

    fun parse(input: InputStream, fileName: String? = null): Parsed {
        val buffered = input.buffered(64 * 1024)
        buffered.mark(4)
        val head = ByteArray(2)
        val n = buffered.read(head)
        buffered.reset()
        val isZip = n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        return if (isZip) parseZip(buffered) else parseJs(buffered, fileName ?: "")
    }

    /** 直接喂进来一段 JS / JSON 文本（调试或单文件导入用） */
    fun parseText(text: String, fileName: String = ""): Parsed = parseJs(text.byteInputStream(), fileName)

    private fun parseZip(input: InputStream): Parsed {
        var following: List<String> = emptyList()
        var follower: List<String> = emptyList()
        val files = ArrayList<String>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val base = entry.name.substringAfterLast('/').lowercase()
                if (!entry.isDirectory && (base == "following.js" || base == "follower.js")) {
                    val ids = extractIds(readLimited(zip))
                    if (base == "following.js") following = ids else follower = ids
                    files += entry.name
                }
                zip.closeEntry()
            }
        }
        return Parsed(following, follower, files)
    }

    private fun parseJs(input: InputStream, name: String): Parsed {
        val text = readLimited(input)
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
            if (total > MAX_BYTES) break
            out.write(buf, 0, read)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
