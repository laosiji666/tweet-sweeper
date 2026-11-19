package com.yg.tweetsweeper.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

interface TokenSource {
    fun accessToken(): String?
    suspend fun refreshToken(): Boolean
}

/**
 * X (Twitter) API v2 的最小封装。只做 GET / DELETE，串行 + 限速闸门。
 */
class XApi(private val tokens: TokenSource) {

    var log: (String) -> Unit = {}
    val gate = RateGate { log(it) }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private class Raw(val code: Int, val body: String)

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private suspend fun call(
        method: String,
        path: String,
        minIntervalMs: Long = 700
    ): Raw = withContext(Dispatchers.IO) {
        fun once(): Triple<Int, String, okhttp3.Headers> {
            val url = if (path.startsWith("http")) path else BASE + path
            val builder = Request.Builder().url(url).header("User-Agent", "TweetSweeper/1.0 (Android)")
            if (method == "GET") builder.get() else builder.delete()
            tokens.accessToken()?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                return Triple(resp.code, body, resp.headers)
            }
        }

        var out: Raw? = null
        var attempt = 0
        while (out == null) {
            gate.pass(minIntervalMs)
            val (code, body, headers) = once()
            gate.updateFromHeaders(headers)
            when {
                code == 429 && attempt < 3 -> {
                    val wait = gate.secondsUntilReset()
                    log("触发限速 429，等待 ${wait}s 后重试（第 ${attempt + 1} 次）")
                    delay(wait * 1000 + 800)
                    attempt++
                }
                code == 401 && attempt < 2 -> {
                    log("access token 过期，刷新中…")
                    if (!tokens.refreshToken()) throw XApiException(401, body)
                    attempt++
                }
                code !in 200..299 -> throw XApiException(code, body.take(300))
                else -> out = Raw(code, body)
            }
        }
        out
    }

    // ---------- 账号 ----------

    suspend fun me(): XUser {
        val r = call(
            "GET",
            "/2/users/me?user.fields=public_metrics,protected,verified,profile_image_url,description,created_at",
            400
        )
        return json.decodeFromString<UserEnvelope>(r.body).data
            ?: throw XApiException(r.code, r.body.take(300))
    }

    suspend fun userByUsername(username: String): XUser? {
        val r = call(
            "GET",
            "/2/users/by/username/${enc(username)}?user.fields=public_metrics,protected,verified,profile_image_url,description",
            400
        )
        val env = json.decodeFromString<UserEnvelope>(r.body)
        return env.data
    }

    suspend fun usersByIds(ids: List<String>): UsersEnvelope {
        require(ids.isNotEmpty()) { "ids 为空" }
        val chunk = ids.take(100).joinToString(",")
        val r = call(
            "GET",
            "/2/users?ids=$chunk&max_results=${ids.size.coerceAtMost(100)}" +
                "&user.fields=public_metrics,protected,verified,profile_image_url,description,created_at",
            400
        )
        return json.decodeFromString(r.body)
    }

    // ---------- 关系 ----------

    private fun userFields(): String =
        "user.fields=public_metrics,protected,verified,profile_image_url,description,created_at"

    suspend fun followingPage(userId: String, cursor: String?, max: Int = 1000): UsersEnvelope {
        val sb = StringBuilder("/2/users/$userId/following?max_results=$max&${userFields()}")
        if (cursor != null) sb.append("&pagination_token=").append(enc(cursor))
        val r = call("GET", sb.toString(), 900)
        return json.decodeFromString(r.body)
    }

    suspend fun followersPage(userId: String, cursor: String?, max: Int = 1000): UsersEnvelope {
        val sb = StringBuilder("/2/users/$userId/followers?max_results=$max&${userFields()}")
        if (cursor != null) sb.append("&pagination_token=").append(enc(cursor))
        val r = call("GET", sb.toString(), 900)
        return json.decodeFromString(r.body)
    }

    suspend fun unfollow(sourceId: String, targetId: String): Boolean {
        val r = call("DELETE", "/2/users/$sourceId/following/$targetId", 1200)
        val env = runCatching { json.decodeFromString<FollowEnvelope>(r.body) }.getOrNull()
        return env?.data?.following == false || r.code == 200
    }

    // ---------- 点赞 ----------

    suspend fun likesPage(userId: String, cursor: String?, max: Int = 100): TweetsEnvelope {
        val sb = StringBuilder(
            "/2/users/$userId/likes?max_results=$max&tweet.fields=created_at,referenced_tweets,text"
        )
        if (cursor != null) sb.append("&pagination_token=").append(enc(cursor))
        val r = call("GET", sb.toString(), 900)
        return json.decodeFromString(r.body)
    }

    suspend fun unlike(userId: String, tweetId: String): Boolean {
        val r = call("DELETE", "/2/users/$userId/likes/$tweetId", 1200)
        val env = runCatching { json.decodeFromString<LikeEnvelope>(r.body) }.getOrNull()
        return env?.data?.liked == false || r.code == 200
    }

    // ---------- 推文 ----------

    suspend fun tweetsPage(userId: String, cursor: String?, max: Int = 100): TweetsEnvelope {
        val sb = StringBuilder(
            "/2/users/$userId/tweets?max_results=$max&tweet.fields=created_at,referenced_tweets,text"
        )
        if (cursor != null) sb.append("&pagination_token=").append(enc(cursor))
        val r = call("GET", sb.toString(), 900)
        return json.decodeFromString(r.body)
    }

    suspend fun deleteTweet(tweetId: String): Boolean {
        val r = call("DELETE", "/2/tweets/$tweetId", 1200)
        val env = runCatching { json.decodeFromString<DeleteEnvelope>(r.body) }.getOrNull()
        return env?.data?.deleted == true || r.code == 200
    }

    fun decodeUsers(body: String): UsersEnvelope = json.decodeFromString(body)

    companion object {
        const val BASE = "https://api.x.com"
        const val MAX_PAGES = 60
    }
}
