package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PersistentFingerprintSecretProviderTest {
    @Test
    fun secretAndVersionRemainStableAcrossProviderInstances() {
        val storage = InMemorySecretStorage()
        val first = PersistentFingerprintSecretProvider(storage) { ByteArray(32) { 0x21 } }.get()
        val second = PersistentFingerprintSecretProvider(storage) { error("不应重新生成") }.get()

        assertThat(second.keyVersion).isEqualTo(first.keyVersion)
        assertThat(second.keyMaterial()).isEqualTo(first.keyMaterial())
        assertThat(first.keyMaterial()).hasLength(32)
    }

    @Test
    fun corruptSecretIsRebuiltWithANewVersionToInvalidateCachedFingerprints() {
        val storage = InMemorySecretStorage(version = 5, encoded = "broken")

        val rebuilt = PersistentFingerprintSecretProvider(storage) { ByteArray(32) { 0x42 } }.get()

        assertThat(rebuilt.keyVersion).isEqualTo(6)
        assertThat(rebuilt.keyMaterial()).isEqualTo(ByteArray(32) { 0x42 })
        assertThat(PersistentFingerprintSecretProvider(storage) { error("不应重新生成") }.get().keyVersion)
            .isEqualTo(6)
    }

    @Test
    fun validSecretWithMissingVersionRebuildsToADifferentAlgorithmEpoch() {
        val storage = InMemorySecretStorage()
        val original = PersistentFingerprintSecretProvider(storage) { ByteArray(32) { 0x11 } }.get()
        val originalAlgorithm = MinHashFingerprint(FingerprintSecretProvider { original }).algorithmVersion
        storage.dropVersionButKeepSecret()

        val rebuilt = PersistentFingerprintSecretProvider(storage) { ByteArray(32) { 0x22 } }.get()
        val rebuiltAlgorithm = MinHashFingerprint(FingerprintSecretProvider { rebuilt }).algorithmVersion

        assertThat(rebuilt.keyMaterial()).isNotEqualTo(original.keyMaterial())
        assertThat(rebuiltAlgorithm).isNotEqualTo(originalAlgorithm)
    }
}

private class InMemorySecretStorage(
    private var version: Int? = null,
    private var encoded: String? = null,
) : FingerprintSecretStorage {
    override fun readKeyVersion(): Int? = version
    override fun readEncodedSecret(): String? = encoded
    override fun write(keyVersion: Int, encodedSecret: String) {
        version = keyVersion
        encoded = encodedSecret
    }

    fun dropVersionButKeepSecret() {
        version = null
    }
}
