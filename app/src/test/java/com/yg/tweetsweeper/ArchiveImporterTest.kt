package com.yg.tweetsweeper

import com.yg.tweetsweeper.data.ArchiveImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveImporterTest {

    private val followingJs = """
        window.YTD.following.part0 = [ {
          "following" : {
            "accountId" : "111",
            "userLink" : "https://twitter.com/intent/user?user_id=111"
          }
        } , {
          "following" : {
            "accountId" : "222",
            "userLink" : "https://twitter.com/intent/user?user_id=222"
          }
        } ]
    """.trimIndent()

    private val followerJs = """
        window.YTD.follower.part0 = [ {
          "follower" : {
            "accountId" : "222",
            "userLink" : "https://twitter.com/intent/user?user_id=222"
          }
        } , {
          "follower" : {
            "accountId" : "333",
            "userLink" : "https://twitter.com/intent/user?user_id=333"
          }
        } ]
    """.trimIndent()

    @Test
    fun parsesSingleJsFile() {
        val p = ArchiveImporter.parse(followingJs.byteInputStream(), "data/following.js")
        assertEquals(listOf("111", "222"), p.following)
        assertTrue(p.follower.isEmpty())
    }

    @Test
    fun parsesFollowerJsByContentWhenNameIsBlank() {
        val p = ArchiveImporter.parse(followerJs.byteInputStream(), null)
        assertEquals(listOf("222", "333"), p.follower)
        assertTrue(p.following.isEmpty())
    }

    @Test
    fun parsesZipWithBothFiles() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.putNextEntry(ZipEntry("data/following.js"))
            zip.write(followingJs.toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data/follower.js"))
            zip.write(followerJs.toByteArray())
            zip.closeEntry()
        }
        val p = ArchiveImporter.parse(bos.toByteArray().inputStream(), "archive.zip")
        assertEquals(listOf("111", "222"), p.following)
        assertEquals(listOf("222", "333"), p.follower)
        assertEquals(2, p.files.size)
    }

    @Test
    fun dedupesIds() {
        val text = """[{"following":{"accountId":"9"}},{"following":{"accountId":"9"}}]"""
        assertEquals(listOf("9"), ArchiveImporter.extractIds(text))
    }
}
