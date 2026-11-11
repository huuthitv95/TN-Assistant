package vn.tn.assistant.auth

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One process-wide owner of refresh rotation, shared by the visible app and microphone service. */
class TnSessionStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tn-session-v1", Context.MODE_PRIVATE)
    @Volatile var accessToken: String? = null
        private set
    @Volatile var refreshToken: String? = null
        private set
    private var persistedCiphertext: String? = prefs.getString("refresh_ciphertext", null)
    @Volatile private var loginGeneration = 0L
    private class Refresh(val owner: Long, val token: String) {
        val done = CountDownLatch(1)
        var access: String? = null
        var error: Throwable? = null
    }
    private var pendingRefresh: Refresh? = null

    @Synchronized fun accept(response: JSONObject) {
        acceptTokens(response)
        loginGeneration++
        retireRefresh()
    }

    private fun acceptTokens(response: JSONObject) {
        val access = response.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IOException("Máy chủ không trả access token")
        val refresh = response.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: throw IOException("Máy chủ không trả refresh token")
        if (Build.VERSION.SDK_INT >= 23) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(refresh.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
            if (!prefs.edit().putString("refresh_ciphertext", encoded).commit()) throw IOException("Không lưu được phiên TN")
            persistedCiphertext = encoded
        }
        accessToken = access
        refreshToken = refresh
    }

    @Synchronized fun hasSession(): Boolean {
        syncPersistence()
        return !accessToken.isNullOrBlank() || prefs.contains("refresh_ciphertext")
    }

    fun restore(refresh: (String) -> JSONObject): String = rotate(null, refresh)
    fun refreshAfterUnauthorized(failedAccess: String, refresh: (String) -> JSONObject): String = rotate(failedAccess, refresh)

    /** One rotation per login; network I/O must never hold the UI/session lock. */
    private fun rotate(failedAccess: String?, refresh: (String) -> JSONObject): String {
        val flight: Refresh
        var leader = false
        synchronized(this) {
            syncPersistence()
            accessToken?.takeIf { failedAccess == null || it != failedAccess }?.let { return it }
            val token = refreshToken ?: decryptRefresh() ?: throw IOException("Phiên TN đã hết hạn")
            flight = pendingRefresh ?: Refresh(loginGeneration, token).also { pendingRefresh = it; leader = true }
        }
        if (leader) {
            try {
                val response = refresh(flight.token)
                synchronized(this) {
                    syncPersistence()
                    if (loginGeneration != flight.owner || pendingRefresh !== flight) throw IOException("Phiên TN đã thay đổi")
                    acceptTokens(response)
                    flight.access = accessToken
                }
            } catch (error: Throwable) {
                synchronized(this) { if (flight.error == null) flight.error = error }
            } finally {
                synchronized(this) { if (pendingRefresh === flight) pendingRefresh = null; flight.done.countDown() }
            }
        } else if (!flight.done.await(60, TimeUnit.SECONDS)) {
            throw IOException("Hết thời gian chờ làm mới phiên TN")
        }
        synchronized(this) {
            syncPersistence()
            if (loginGeneration != flight.owner) throw IOException("Phiên TN đã thay đổi")
            flight.error?.let { throw it }
            return flight.access ?: throw IOException("Không làm mới được phiên TN")
        }
    }

    @Synchronized fun clear() {
        loginGeneration++
        retireRefresh()
        accessToken = null; refreshToken = null
        prefs.edit().remove("refresh_ciphertext").commit()
        persistedCiphertext = null
    }

    private fun syncPersistence() {
        val current = prefs.getString("refresh_ciphertext", null)
        if (current != persistedCiphertext) {
            loginGeneration++
            retireRefresh()
            accessToken = null; refreshToken = null; persistedCiphertext = current
        }
    }

    private fun retireRefresh() {
        pendingRefresh?.let { it.error = IOException("Phiên TN đã thay đổi"); it.done.countDown() }
        pendingRefresh = null
    }

    /** Actions belong to a login, not to a short-lived access-token rotation. */
    @Synchronized fun actionGeneration(): Long { syncPersistence(); return loginGeneration }
    @Synchronized fun ownsActions(generation: Long): Boolean {
        syncPersistence()
        return generation == loginGeneration && (!accessToken.isNullOrBlank() || prefs.contains("refresh_ciphertext"))
    }

    private fun decryptRefresh(): String? {
        val encoded = prefs.getString("refresh_ciphertext", null) ?: return null
        if (Build.VERSION.SDK_INT < 23) return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (bytes.size < 29) throw IOException("Phiên TN không hợp lệ")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { clear(); null }
    }

    @androidx.annotation.RequiresApi(23)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "tn-assistant-refresh-v1"
        @Volatile private var instance: TnSessionStore? = null
        fun get(context: Context): TnSessionStore = instance ?: synchronized(this) {
            instance ?: TnSessionStore(context).also { instance = it }
        }
    }
}
