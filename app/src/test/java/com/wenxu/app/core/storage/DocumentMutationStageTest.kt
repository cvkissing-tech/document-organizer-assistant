package com.wenxu.app.core.storage

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Test

class DocumentMutationStageTest {
    @Test
    fun failureBeforeDocumentMutationIsExplicitlyUnchanged() {
        val cause = UnsupportedOperationException("cannot create trash directory")

        val result = runDocumentMutation<String> { throw cause }

        assertThat(result.exceptionOrNull()).isInstanceOf(DocumentOperationUnchangedException::class.java)
        assertThat(result.exceptionOrNull()?.cause).isSameInstanceAs(cause)
    }

    @Test
    fun failureAfterDocumentMutationStartsRemainsUnknown() {
        val cause = IllegalStateException("provider disconnected")

        val result = runDocumentMutation<String> { mutationStarted ->
            mutationStarted()
            throw cause
        }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(cause)
    }

    @Test
    fun cancellationPropagatesBeforeAndAfterMutationStarts() {
        listOf(false, true).forEach { startMutation ->
            val cancellation = CancellationException("cancelled")

            val error = runCatching {
                runDocumentMutation<String> { mutationStarted ->
                    if (startMutation) mutationStarted()
                    throw cancellation
                }
            }.exceptionOrNull()

            assertThat(error).isSameInstanceAs(cancellation)
        }
    }
}
