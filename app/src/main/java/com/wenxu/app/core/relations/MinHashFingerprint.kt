package com.wenxu.app.core.relations

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.PriorityQueue
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class MinHashFingerprint(
    secretProvider: FingerprintSecretProvider,
    private val maximumSignatureSize: Int = DEFAULT_SIGNATURE_SIZE,
) {
    private val secret = secretProvider.get()
    val algorithmVersion: Int = ALGORITHM_FAMILY_PREFIX or secret.keyId

    init {
        require(maximumSignatureSize > 0)
    }

    fun create(text: String): LongArray {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return LongArray(0)
        val collector = BottomKCollector(maximumSignatureSize)
        val mac = newMac()
        val digestBuffer = ByteArray(mac.macLength)
        val codePoints = compactCodePoints(normalized)
        when {
            codePoints.isEmpty() -> Unit
            codePoints.size < SHINGLE_SIZE -> collector.offer(
                hashCodePointWindow(mac, digestBuffer, codePoints, 0, codePoints.size),
            )
            else -> {
                for (start in 0..codePoints.size - SHINGLE_SIZE) {
                    collector.offer(hashCodePointWindow(mac, digestBuffer, codePoints, start, SHINGLE_SIZE))
                }
            }
        }
        var previousWord: String? = null
        WORD_TOKEN.findAll(normalized).forEach { match ->
            val word = match.value
            collector.offer(hashUtf8(mac, digestBuffer, WORD_PREFIX, word))
            previousWord?.let { previous ->
                collector.offer(hashUtf8(mac, digestBuffer, WORD_PAIR_PREFIX, previous, word))
            }
            previousWord = word
        }
        return collector.toSortedArray()
    }

    fun similarity(first: LongArray, second: LongArray): Double {
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val left = first.distinct().sorted()
        val right = second.distinct().sorted()
        var leftIndex = 0
        var rightIndex = 0
        var matches = 0
        var selectedUnion = 0
        val limit = if (left.size < maximumSignatureSize && right.size < maximumSignatureSize) {
            Int.MAX_VALUE
        } else {
            maximumSignatureSize
        }
        while ((leftIndex < left.size || rightIndex < right.size) && selectedUnion < limit) {
            when {
                leftIndex >= left.size -> rightIndex += 1
                rightIndex >= right.size -> leftIndex += 1
                left[leftIndex] == right[rightIndex] -> {
                    matches += 1
                    leftIndex += 1
                    rightIndex += 1
                }
                left[leftIndex] < right[rightIndex] -> leftIndex += 1
                else -> rightIndex += 1
            }
            selectedUnion += 1
        }
        return if (selectedUnion == 0) 0.0 else matches.toDouble() / selectedUnion
    }

    fun normalize(text: String): String {
        val withoutIsolatedPageNumbers = text.lineSequence()
            .map(String::trim)
            .filterNot { line -> line.matches(ISOLATED_PAGE_NUMBER) }
            .joinToString(" ")
        return Normalizer.normalize(withoutIsolatedPageNumbers, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(CONTROL_CHARACTERS, " ")
            .replace(WHITESPACE, " ")
            .trim()
    }

    fun keyedToken(value: String): String {
        val normalized = normalize(value)
        if (normalized.isEmpty()) return ""
        return newMac().run {
            update(TOKEN_PREFIX)
            doFinal(normalized.encodeToByteArray())
        }.take(TOKEN_BYTES).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun compactCodePoints(value: String): IntArray {
        val buffer = IntArray(value.length)
        var count = 0
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            if (!Character.isWhitespace(codePoint)) buffer[count++] = codePoint
            index += Character.charCount(codePoint)
        }
        return buffer.copyOf(count)
    }

    private fun hashCodePointWindow(
        mac: Mac,
        digestBuffer: ByteArray,
        codePoints: IntArray,
        start: Int,
        count: Int,
    ): Long {
        mac.update(CODE_POINT_PREFIX)
        for (index in start until start + count) updateInt(mac, codePoints[index])
        return finishLong(mac, digestBuffer)
    }

    private fun hashUtf8(
        mac: Mac,
        digestBuffer: ByteArray,
        prefix: Byte,
        vararg values: String,
    ): Long {
        mac.update(prefix)
        values.forEachIndexed { index, value ->
            if (index > 0) mac.update(SEPARATOR)
            mac.update(value.encodeToByteArray())
        }
        return finishLong(mac, digestBuffer)
    }

    private fun updateInt(mac: Mac, value: Int) {
        mac.update((value ushr 24).toByte())
        mac.update((value ushr 16).toByte())
        mac.update((value ushr 8).toByte())
        mac.update(value.toByte())
    }

    private fun ByteArray.firstLong(): Long =
        ByteBuffer.wrap(this, 0, Long.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).long

    private fun finishLong(mac: Mac, digestBuffer: ByteArray): Long {
        mac.doFinal(digestBuffer, 0)
        return digestBuffer.firstLong()
    }

    private fun newMac(): Mac = Mac.getInstance(HMAC_ALGORITHM).apply {
        init(SecretKeySpec(secret.keyMaterial(), HMAC_ALGORITHM))
    }

    companion object {
        const val DEFAULT_SIGNATURE_SIZE = 128
        const val ALGORITHM_FAMILY_PREFIX = 0x4000_0000
        private const val SHINGLE_SIZE = 3
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val TOKEN_BYTES = 8
        private const val CODE_POINT_PREFIX: Byte = 1
        private const val WORD_PREFIX: Byte = 2
        private const val WORD_PAIR_PREFIX: Byte = 3
        private const val TOKEN_PREFIX: Byte = 4
        private const val SEPARATOR: Byte = 0
        private val ISOLATED_PAGE_NUMBER = Regex("^[-–—]?\\s*\\d{1,4}\\s*[-–—]?$")
        private val CONTROL_CHARACTERS = Regex("[\\p{Cc}&&[^\\r\\n\\t]]+")
        private val WHITESPACE = Regex("\\s+")
        private val WORD_TOKEN = Regex("[\\p{L}\\p{N}]+")
    }

    private class BottomKCollector(private val limit: Int) {
        private val maximumHeap = PriorityQueue<Long>(limit, compareByDescending { it })
        private val retained = HashSet<Long>(limit * 2)

        fun offer(value: Long) {
            if (value in retained) return
            if (maximumHeap.size < limit) {
                maximumHeap += value
                retained += value
                return
            }
            val currentMaximum = checkNotNull(maximumHeap.peek())
            if (value >= currentMaximum) return
            maximumHeap.remove()
            retained.remove(currentMaximum)
            maximumHeap += value
            retained += value
        }

        fun toSortedArray(): LongArray =
            LongArray(maximumHeap.size).also { result ->
                maximumHeap.forEachIndexed { index, value -> result[index] = value }
                result.sort()
            }
    }
}
