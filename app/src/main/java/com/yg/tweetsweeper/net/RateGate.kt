package com.yg.tweetsweeper.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Headers

/**
 * 统一限速闸门：
 *  - 解析 X 返回的 x-rate-limit-* 头
 *  - 剩余为 0 时阻塞到窗口重置
 *  - 保证两次请求之间有最小间隔，避免被判定为滥用
 */
class RateGate(private val log: (String) -> Unit = {}) {

    @Volatile private var remaining: Int = -1
    @Volatile private var resetAt: Long = 0L
    @Volatile var limit: Int = -1
        private set

    private var lastCallAt: Long = 0L
    private val mutex = Mutex()

    fun updateFromHeaders(headers: Headers) {
        headers.get("x-rate-limit-limit")?.toIntOrNull()?.let { limit = it }
        headers.get("x-rate-limit-remaining")?.toIntOrNull()?.let { remaining = it }
        headers.get("x-rate-limit-reset")?.toLongOrNull()?.let { resetAt = it * 1000L }
    }

    fun secondsUntilReset(): Long {
        val wait = resetAt - System.currentTimeMillis()
        return if (wait <= 0) 5 else wait / 1000 + 2
    }

    fun remainingLabel(): String = when {
        remaining < 0 || limit < 0 -> "配额未知"
        else -> "窗口剩余 $remaining/$limit"
    }

    suspend fun pass(minIntervalMs: Long) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (remaining == 0 && resetAt > now) {
                val wait = resetAt - now + 2000
                log("接口配额用尽，等待 ${wait / 1000}s 到窗口重置…")
                delay(wait)
                remaining = -1
            }
            val gap = System.currentTimeMillis() - lastCallAt
            if (gap < minIntervalMs) delay(minIntervalMs - gap)
            lastCallAt = System.currentTimeMillis()
        }
    }
}
