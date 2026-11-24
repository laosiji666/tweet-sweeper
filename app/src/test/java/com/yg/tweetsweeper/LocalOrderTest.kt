package com.yg.tweetsweeper

import com.yg.tweetsweeper.vm.LocalRow
import com.yg.tweetsweeper.vm.orderRows
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalOrderTest {

    @Test
    fun doneRowsAppearLastNewestFirst() {
        val rows = listOf(
            LocalRow("1", "不互关"),
            LocalRow("2", "不互关", done = true, doneAt = 100),
            LocalRow("3", "不互关", doneAt = 0),
            LocalRow("4", "不互关", done = true, doneAt = 300),
            LocalRow("5", "不互关", done = true, doneAt = 200),
            LocalRow("6", "不互关", kept = true)
        )
        val ordered = orderRows(rows)
        // 未处理的保持原顺序在前；已处理的按取关时间倒序在后
        assertEquals(listOf("1", "3", "6", "4", "5", "2"), ordered.map { it.id })
    }

    @Test
    fun emptyListIsSafe() {
        assertEquals(emptyList<String>(), orderRows(emptyList()).map { it.id })
    }
}
