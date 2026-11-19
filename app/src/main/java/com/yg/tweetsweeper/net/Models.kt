package com.yg.tweetsweeper.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PublicMetrics(
    val followers: Int = 0,
    val following: Int = 0,
    @SerialName("tweet_count") val tweetCount: Int = 0,
    @SerialName("listed_count") val listedCount: Int = 0
)

@Serializable
data class XUser(
    val id: String = "",
    val username: String? = null,
    val name: String? = null,
    val protected: Boolean? = null,
    val verified: Boolean? = null,
    val description: String? = null,
    @SerialName("public_metrics") val metrics: PublicMetrics? = null,
    @SerialName("profile_image_url") val avatar: String? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    val handle: String get() = "@" + (username ?: id)
}

@Serializable
data class ApiError(
    val value: String? = null,
    val detail: String? = null,
    val title: String? = null,
    val type: String? = null,
    val parameter: String? = null,
    @SerialName("resource_type") val resourceType: String? = null,
    @SerialName("resource_id") val resourceId: String? = null
) {
    val suspended: Boolean
        get() {
            val d = (detail ?: "").lowercase()
            return d.contains("suspend") || d.contains("停用")
        }

    val notFound: Boolean
        get() {
            val d = (detail ?: "").lowercase() + (title ?: "").lowercase()
            return d.contains("not found") || d.contains("could not find") || d.contains("不存在")
        }
}

@Serializable
data class Meta(
    @SerialName("next_token") val nextToken: String? = null,
    @SerialName("result_count") val resultCount: Int = 0,
    @SerialName("previous_token") val previousToken: String? = null
)

@Serializable
data class UsersEnvelope(
    val data: List<XUser>? = null,
    val meta: Meta? = null,
    val errors: List<ApiError>? = null
)

@Serializable
data class UserEnvelope(
    val data: XUser? = null,
    val errors: List<ApiError>? = null
)

@Serializable
data class FollowResult(
    val following: Boolean? = null,
    @SerialName("pending_follow") val pendingFollow: Boolean? = null
)

@Serializable
data class FollowEnvelope(
    val data: FollowResult? = null,
    val errors: List<ApiError>? = null
)

@Serializable
data class LikeEnvelope(
    val data: LikeResult? = null,
    val errors: List<ApiError>? = null
)

@Serializable
data class LikeResult(val liked: Boolean? = null)

@Serializable
data class DeleteEnvelope(
    val data: DeleteResult? = null,
    val errors: List<ApiError>? = null
)

@Serializable
data class DeleteResult(val deleted: Boolean? = null)

@Serializable
data class ReferencedTweet(
    val type: String? = null,
    val id: String? = null
)

@Serializable
data class Tweet(
    val id: String = "",
    val text: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("referenced_tweets") val referenced: List<ReferencedTweet>? = null,
    @SerialName("author_id") val authorId: String? = null
) {
    val isRetweet: Boolean get() = referenced?.any { it.type == "retweeted" } == true
}

@Serializable
data class TweetsEnvelope(
    val data: List<Tweet>? = null,
    val meta: Meta? = null,
    val errors: List<ApiError>? = null
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)

class XApiException(val status: Int, val raw: String) : Exception(friendly(status, raw)) {

    companion object {
        fun friendly(status: Int, raw: String): String = when (status) {
            400 -> "请求参数被拒绝（400）：$raw"
            401 -> "授权失效（401），请重新登录"
            403 -> "权限不足（403）：可能有 scope 未授权，或该操作被账号设置限制"
            404 -> "对象不存在（404）"
            429 -> "触发官方接口限速（429），已自动排队等待"
            in 500..599 -> "推特服务端错误（$status），稍后重试"
            else -> "请求失败（$status）：$raw"
        }
    }
}
