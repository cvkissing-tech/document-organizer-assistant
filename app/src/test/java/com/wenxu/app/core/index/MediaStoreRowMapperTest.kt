package com.wenxu.app.core.index

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.core.permission.StorageAccessState
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MediaStoreRowMapperTest {
    private val mapper = MediaStoreRowMapper()

    @Test
    fun rowMapperAcceptsOfficeAndPdfButRejectsImage() {
        assertThat(
            mapper.map(
                row = row(id = 7, name = "课程.pdf", mime = "application/pdf"),
                documentUri = "content://media/external/file/7",
                parentUri = "content://media/external/file",
            ),
        ).isNotNull()
        assertThat(
            mapper.map(
                row = row(
                    id = 8,
                    name = "汇报.pptx",
                    mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                ),
                documentUri = "content://media/external/file/8",
                parentUri = "content://media/external/file",
            ),
        ).isNotNull()
        assertThat(
            mapper.map(
                row = row(id = 9, name = "照片.jpg", mime = "image/jpeg"),
                documentUri = "content://media/external/file/9",
                parentUri = "content://media/external/file",
            ),
        ).isNull()
    }

    @Test
    fun unknownMimeFallsBackToSupportedExtension() {
        val mapped = mapper.map(
            row = row(id = 11, name = "课堂笔记.DOCX", mime = "application/octet-stream"),
            documentUri = "content://media/external/file/11",
            parentUri = "content://media/external/file",
        )

        assertThat(mapped).isNotNull()
        assertThat(mapped?.mimeType).isEqualTo("application/octet-stream")
        assertThat(mapped?.modifiedAt).isEqualTo(2_000_000L)
    }

    @Test
    fun pageAdvancesByEveryCheckedFileRatherThanOnlyAcceptedDocuments() = runTest {
        val source = fakeSource(
            rows = listOf(
                row(id = 7, name = "照片.jpg", mime = "image/jpeg", path = "Pictures/"),
                row(id = 8, name = "汇报.pptx", mime = "", path = "Download/QQ/"),
                row(id = 9, name = "视频.mp4", mime = "video/mp4", path = "Movies/"),
            ),
        )

        val page = source.readPage(afterId = 6L, limit = 2)

        assertThat(page.documents.map { it.displayName }).containsExactly("汇报.pptx")
        assertThat(page.documents.single().uri)
            .isEqualTo("content://media/external_primary/file/8")
        assertThat(page.nextCursor).isEqualTo(8L)
        assertThat(page.checkedFiles).isEqualTo(2)
        assertThat(page.directoryPaths).containsExactly("Pictures/", "Download/QQ/")
        assertThat(page.isComplete).isFalse()
    }

    @Test
    fun emptyResultIsCompleteAndDoesNotRepeatPreviousCursor() = runTest {
        val source = fakeSource(rows = emptyList())

        val page = source.readPage(afterId = 42L, limit = 200)

        assertThat(page.documents).isEmpty()
        assertThat(page.nextCursor).isNull()
        assertThat(page.checkedFiles).isEqualTo(0)
        assertThat(page.directoryPaths).isEmpty()
        assertThat(page.isComplete).isTrue()
    }

    @Test
    fun staleOrRepeatedRowsCannotMoveCursorBackwards() = runTest {
        val source = MediaStoreDocumentPageSource(
            accessState = { StorageAccessState.Granted },
            queryRows = { _, _ ->
                listOf(
                    row(id = 42, name = "旧行.pdf", mime = "application/pdf"),
                    row(id = 43, name = "新行.pdf", mime = "application/pdf"),
                    row(id = 43, name = "重复行.pdf", mime = "application/pdf"),
                )
            },
            pageAssembler = MediaStorePageAssembler(
                rowMapper = mapper,
                collectionUri = "content://media/external/file",
                documentUriForRow = { row -> "content://media/${row.volumeName}/file/${row.id}" },
            ),
        )

        val page = source.readPage(afterId = 42L, limit = 10)

        assertThat(page.documents.map { it.displayName }).containsExactly("新行.pdf")
        assertThat(page.nextCursor).isEqualTo(43L)
        assertThat(page.isComplete).isTrue()
    }

    @Test
    fun securityFailureIsReportedToCaller() = runTest {
        val deniedSource = fakeSource(
            rows = emptyList(),
            accessState = StorageAccessState.NeedsPermission,
        )

        val error = runCatching { deniedSource.readPage(afterId = null, limit = 200) }
            .exceptionOrNull()

        assertThat(error).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun providerSecurityFailureIsNotHidden() = runTest {
        val source = MediaStoreDocumentPageSource(
            accessState = { StorageAccessState.Granted },
            queryRows = { _, _ -> throw SecurityException("provider denied access") },
            pageAssembler = MediaStorePageAssembler(
                rowMapper = mapper,
                collectionUri = "content://media/external/file",
                documentUriForRow = { row -> "content://media/${row.volumeName}/file/${row.id}" },
            ),
        )

        val error = runCatching { source.readPage(afterId = null) }.exceptionOrNull()

        assertThat(error).isInstanceOf(SecurityException::class.java)
        assertThat(error).hasMessageThat().contains("provider denied access")
    }

    private fun fakeSource(
        rows: List<MediaStoreFileRow>,
        accessState: StorageAccessState = StorageAccessState.Granted,
    ) = MediaStoreDocumentPageSource(
        accessState = { accessState },
        queryRows = { afterId, queryLimit ->
            rows.filter { afterId == null || it.id > afterId }.take(queryLimit)
        },
        pageAssembler = MediaStorePageAssembler(
            rowMapper = mapper,
            collectionUri = "content://media/external/file",
            documentUriForRow = { row -> "content://media/${row.volumeName}/file/${row.id}" },
        ),
    )
}

private fun row(
    id: Long,
    name: String,
    mime: String?,
    path: String? = "Download/",
) = MediaStoreFileRow(
    id = id,
    volumeName = "external_primary",
    displayName = name,
    mimeType = mime,
    sizeBytes = 128,
    modifiedAtSeconds = 2_000,
    relativePath = path,
)
