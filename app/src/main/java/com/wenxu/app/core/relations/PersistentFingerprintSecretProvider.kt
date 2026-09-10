package com.wenxu.app.core.relations

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

interface FingerprintSecretStorage {
    fun readKeyVersion(): Int?
    fun readEncodedSecret(): String?
    fun write(keyVersion: Int, encodedSecret: String)
}

class PersistentFingerprintSecretProvider(
    private val storage: FingerprintSecretStorage,
    private val randomBytes: () -> ByteArray = {
        ByteArray(SECRET_BYTES).also(SecureRandom()::nextBytes)
    },
) : FingerprintSecretProvider {
    override fun get(): FingerprintSecret = synchronized(storage) {
        val storedVersion = runCatching(storage::readKeyVersion).getOrNull()
        val storedEncoded = runCatching(storage::readEncodedSecret).getOrNull()
        val storedSecret = storedEncoded?.let(::decodeHex)
        if (storedVersion != null && storedVersion in VALID_KEY_VERSIONS &&
            storedSecret?.size == SECRET_BYTES
        ) {
            return@synchronized FingerprintSecret(storedVersion, storedSecret)
        }

        val newVersion = if (storedVersion != null && storedVersion in VALID_KEY_VERSIONS) {
            if (storedVersion == VALID_KEY_VERSIONS.last) VALID_KEY_VERSIONS.first else storedVersion + 1
        } else {
            INITIAL_KEY_VERSION
        }
        val previousKeyId = storedSecret
            ?.takeIf { secret -> secret.size == SECRET_BYTES }
            ?.let { secret -> FingerprintSecret(INITIAL_KEY_VERSION, secret).keyId }
        val newSecret = generateDistinctSecret(previousKeyId)
        storage.write(newVersion, encodeHex(newSecret))
        FingerprintSecret(newVersion, newSecret)
    }

    private fun generateDistinctSecret(previousKeyId: Int?): ByteArray {
        repeat(MAX_GENERATION_ATTEMPTS) {
            val candidate = randomBytes()
            require(candidate.size == SECRET_BYTES) { "安装指纹密钥必须为256位" }
            if (previousKeyId == null || FingerprintSecret(INITIAL_KEY_VERSION, candidate).keyId != previousKeyId) {
                return candidate
            }
        }
        error("无法生成新的安装指纹密钥代次")
    }

    private fun encodeHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private fun decodeHex(value: String): ByteArray? {
        if (value.length != SECRET_BYTES * 2) return null
        return ByteArray(SECRET_BYTES) { index ->
            val high = value[index * 2].digitToIntOrNull(16) ?: return null
            val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
            ((high shl 4) or low).toByte()
        }
    }

    private companion object {
        const val SECRET_BYTES = 32
        const val INITIAL_KEY_VERSION = 1
        const val MAX_GENERATION_ATTEMPTS = 8
        val VALID_KEY_VERSIONS = 1..999_999
        const val HEX = "0123456789abcdef"
    }
}

class SharedPreferencesFingerprintSecretStorage(context: Context) : FingerprintSecretStorage {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun readKeyVersion(): Int? =
        if (preferences.contains(KEY_VERSION)) preferences.getInt(KEY_VERSION, 0) else null

    override fun readEncodedSecret(): String? = preferences.getString(KEY_SECRET, null)

    override fun write(keyVersion: Int, encodedSecret: String) {
        check(
            preferences.edit()
                .putInt(KEY_VERSION, keyVersion)
                .putString(KEY_SECRET, encodedSecret)
                .commit(),
        ) { "无法保存安装指纹密钥" }
    }

    private companion object {
        const val PREFERENCES_NAME = "document_fingerprint_secret"
        const val KEY_VERSION = "key_version"
        const val KEY_SECRET = "secret_hex"
    }
}
