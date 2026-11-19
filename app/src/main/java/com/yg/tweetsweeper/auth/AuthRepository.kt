package com.yg.tweetsweeper.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yg.tweetsweeper.net.TokenResponse
import com.yg.tweetsweeper.net.TokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AuthRepository(context: Context) : TokenSource {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext,
            "ts_secure_auth",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        appContext.getSharedPreferences("ts_plain_auth", Context.MODE_PRIVATE)
    }

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value.trim()).apply()

    override fun accessToken(): String? = prefs.getString(KEY_ACCESS, null)

    private var refreshTok: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    var expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES, 0L)
        private set(value) = prefs.edit().putLong(KEY_EXPIRES, value).apply()

    var scope: String
        get() = prefs.getString(KEY_SCOPE, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_SCOPE, value).apply()

    val isAuthorized: Boolean get() = !accessToken().isNullOrBlank()

    private var pendingVerifier: String?
        get() = prefs.getString(KEY_VERIFIER, null)
        set(value) = prefs.edit().putString(KEY_VERIFIER, value).apply()

    private var pendingState: String?
        get() = prefs.getString(KEY_STATE, null)
        set(value) = prefs.edit().putString(KEY_STATE, value).apply()

    fun startAuth(): Uri {
        val verifier = Pkce.randomString(64)
        val state = Pkce.randomString(24)
        pendingVerifier = verifier
        pendingState = state
        return Pkce.authorizeUri(clientId, state, Pkce.challenge(verifier))
    }

    suspend fun completeAuth(uri: Uri): Boolean {
        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            throw IllegalStateException("授权被拒绝：${uri.getQueryParameter("error_description") ?: error}")
        }
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code") ?: throw IllegalStateException("回调里没有 code")
        if (!pendingState.isNullOrBlank() && state != pendingState) {
            throw IllegalStateException("state 校验失败（可能不是本次授权回调），请重新发起")
        }
        val verifier = pendingVerifier ?: throw IllegalStateException("code_verifier 丢失，请重新发起授权")
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", Pkce.REDIRECT_URI)
            .add("code_verifier", verifier)
            .add("client_id", clientId)
            .build()
        saveToken(post(body))
        pendingVerifier = null
        pendingState = null
        return true
    }

    override suspend fun refreshToken(): Boolean = mutex.withLock {
        val rt = refreshTok ?: return@withLock false
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", rt)
            .add("client_id", clientId)
            .build()
        val result = runCatching { post(body) }
        result.isSuccess && runCatching {
            saveToken(result.getOrThrow())
            true
        }.getOrDefault(false)
    }

    private suspend fun post(body: FormBody): TokenResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(Pkce.TOKEN_URL)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", "TweetSweeper/1.0 (Android)")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val token = runCatching { json.decodeFromString<TokenResponse>(text) }.getOrNull()
                ?: throw IllegalStateException("令牌接口返回无法解析（${resp.code}）：${text.take(200)}")
            if (resp.code !in 200..299 || token.accessToken.isNullOrBlank()) {
                throw IllegalStateException(
                    "获取令牌失败（${resp.code}）：${token.errorDescription ?: token.error ?: text.take(200)}"
                )
            }
            token
        }
    }

    private fun saveToken(token: TokenResponse) {
        prefs.edit().apply {
            putString(KEY_ACCESS, token.accessToken)
            token.refreshToken?.let { putString(KEY_REFRESH, it) }
            token.scope?.let { putString(KEY_SCOPE, it) }
            val ttl = token.expiresIn ?: 7200L
            putLong(KEY_EXPIRES, System.currentTimeMillis() + ttl * 1000)
        }.apply()
    }

    fun signOut() {
        prefs.edit().apply {
            remove(KEY_ACCESS)
            remove(KEY_REFRESH)
            remove(KEY_EXPIRES)
            remove(KEY_SCOPE)
        }.apply()
    }

    private companion object {
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_SCOPE = "scope"
        const val KEY_VERIFIER = "pkce_verifier"
        const val KEY_STATE = "oauth_state"
    }
}
