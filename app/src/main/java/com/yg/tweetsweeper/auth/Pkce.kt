package com.yg.tweetsweeper.auth

import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 Authorization Code + PKCE（公开客户端，不需要 client secret）。
 */
object Pkce {

    const val REDIRECT_URI = "tweetsweeper://callback"
    const val AUTHORIZE_URL = "https://x.com/i/oauth2/authorize"
    const val TOKEN_URL = "https://api.x.com/2/oauth2/token"

    /** 需要的权限：读推文/用户/关注/点赞，写关注（取关）与点赞，以及 offline.access 拿刷新令牌 */
    const val SCOPES = "tweet.read users.read follows.read follows.write like.read like.write offline.access"

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val random = SecureRandom()

    fun randomString(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }

    fun authorizeUri(clientId: String, state: String, challenge: String): Uri =
        Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
}
