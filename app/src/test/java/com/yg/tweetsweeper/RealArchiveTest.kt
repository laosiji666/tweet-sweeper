package com.yg.tweetsweeper

import com.yg.tweetsweeper.data.ArchiveImporter
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 用真实的 X 数据包做回归（本机存在时才有意义）。
 * 主要防的是「流在读取前就被关掉」和「大包分片导致空结果」两类问题。
 */
class RealArchiveTest {

    private fun findArchive(): File? {
        val dir = File("/sdcard/Download/X")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }
    }

    @Test
    fun parsesRealArchiveThroughFreshStream() {
        val archive = findArchive()
        assumeTrue("本机没有 X 数据包，跳过", archive != null && archive!!.exists())

        // 模拟 ViewModel 的做法：在「打开流」的回调里现开现用，而不是提前开好
        val open: () -> java.io.InputStream? = { runCatching { archive!!.inputStream() }.getOrNull() }
        val parsed = open()!!.use { ArchiveImporter.parse(it) }

        assertTrue("following 解析为空", parsed.following.size > 100)
        assertTrue("follower 解析为空", parsed.follower.size > 100)
        assertTrue("没有识别出文件", parsed.files.isNotEmpty())
    }

    @Test
    fun zipStreamIsReusableIndependently() {
        val archive = findArchive()
        assumeTrue("本机没有 X 数据包，跳过", archive != null && archive!!.exists())
        // 连续两次解析同一文件，确认不依赖流的复用状态
        val first = archive!!.inputStream().use { ArchiveImporter.parse(it) }
        val second = archive!!.inputStream().use { ArchiveImporter.parse(it) }
        assertTrue(first.following.size == second.following.size)
        assertTrue(ZipInputStream(archive.inputStream()).use { it.nextEntry != null })
    }
}
