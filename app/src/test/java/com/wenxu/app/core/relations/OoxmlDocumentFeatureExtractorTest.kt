package com.wenxu.app.core.relations

import com.google.common.truth.Truth.assertThat
import com.wenxu.app.FakeDocumentGateway
import com.wenxu.app.core.database.entity.DocumentEntity
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OoxmlDocumentFeatureExtractorTest {
    @Test
    fun extractsVisibleWordTextAndStructure() = runTest {
        val bytes = zipOf(
            "[Content_Types].xml" to "<Types/>",
            "word/document.xml" to """
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>项目背景</w:t></w:r></w:p>
                    <w:p><w:r><w:t>这是可见的正文内容</w:t></w:r></w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格内容</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                  </w:body>
                </w:document>
            """.trimIndent(),
        )

        val result = extract("docx", bytes)

        assertThat(result).isInstanceOf(FingerprintExtraction.Success::class.java)
        val fingerprint = (result as FingerprintExtraction.Success).fingerprint
        assertThat(fingerprint.normalizedTextLength).isGreaterThan(0)
        assertThat(fingerprint.signature).isNotEmpty()
        assertThat(fingerprint.structure.sectionCount).isEqualTo(3)
        assertThat(fingerprint.structure.tableOrSheetCount).isEqualTo(1)
        assertThat(fingerprint.structure.headingTokens).isNotEmpty()
    }

    @Test
    fun extractsPowerPointSlidesAndImages() = runTest {
        val bytes = zipOf(
            "ppt/presentation.xml" to """
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:sldIdLst><p:sldId r:id="rId1"/><p:sldId r:id="rId2"/></p:sldIdLst>
                </p:presentation>
            """.trimIndent(),
            "ppt/_rels/presentation.xml.rels" to presentationRelationships(
                "rId1" to "slides/slide1.xml",
                "rId2" to "slides/slide2.xml",
            ),
            "ppt/slides/slide1.xml" to slideXml("项目汇报", includePicture = true),
            "ppt/slides/slide2.xml" to slideXml("总结", includePicture = false),
        )

        val result = extract("pptx", bytes) as FingerprintExtraction.Success

        assertThat(result.fingerprint.structure.sectionCount).isEqualTo(2)
        assertThat(result.fingerprint.structure.imageCount).isEqualTo(1)
        assertThat(result.fingerprint.structure.headingTokens).hasSize(2)
    }

    @Test
    fun extractsWorkbookSharedStringsAndSheetStructure() = runTest {
        val bytes = zipOf(
            "xl/workbook.xml" to """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheets xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheet name="成绩统计" sheetId="1" r:id="rId1"/>
                  </sheets>
                </workbook>
            """.trimIndent(),
            "xl/_rels/workbook.xml.rels" to workbookRelationships("rId1" to "worksheets/sheet1.xml"),
            "xl/sharedStrings.xml" to """
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <si><t>姓名</t></si><si><t>成绩</t></si>
                </sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                    <row r="2"><c r="A2" t="inlineStr"><is><t>张三</t></is></c><c r="B2"><f>SUM(B3:B4)</f><v>95</v></c></row>
                  </sheetData>
                </worksheet>
            """.trimIndent(),
        )

        val result = extract("xlsx", bytes) as FingerprintExtraction.Success

        assertThat(result.fingerprint.normalizedTextLength).isGreaterThan(0)
        assertThat(result.fingerprint.structure.sectionCount).isEqualTo(2)
        assertThat(result.fingerprint.structure.tableOrSheetCount).isEqualTo(1)
        assertThat(result.fingerprint.structure.headingTokens).isNotEmpty()
    }

    @Test
    fun ignoresTextOutsideWhitelistedEntries() = runTest {
        val visible = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>可见正文</w:t></w:r></w:p></w:body></w:document>"
        val first = extract("docx", zipOf("word/document.xml" to visible)) as FingerprintExtraction.Success
        val second = extract(
            "docx",
            zipOf(
                "word/document.xml" to visible,
                "docProps/core.xml" to "<secret>不应进入正文指纹</secret>",
            ),
        ) as FingerprintExtraction.Success

        assertThat(first.fingerprint.signature.asList())
            .containsExactlyElementsIn(second.fingerprint.signature.asList())
            .inOrder()
    }

    @Test
    fun limitsWhitelistedXmlEntriesToFourMiB() = runTest {
        val oversizedEntry = zipBytes(
            "word/document.xml" to ByteArray(4 * 1024 * 1024 + 1),
        )

        assertThat(extract("docx", oversizedEntry)).isEqualTo(FingerprintExtraction.ReadFailed)
    }

    @Test
    fun excelUsesWorkbookRelationshipsAndIgnoresHiddenAndOrphanSheets() = runTest {
        val bytes = zipOf(
            "xl/workbook.xml" to """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="可见" sheetId="1" r:id="rId1"/>
                    <sheet name="隐藏" sheetId="2" state="hidden" r:id="rId2"/>
                  </sheets>
                </workbook>
            """.trimIndent(),
            "xl/_rels/workbook.xml.rels" to workbookRelationships(
                "rId1" to "worksheets/sheet1.xml",
                "rId2" to "worksheets/sheet2.xml",
            ),
            "xl/worksheets/sheet1.xml" to worksheetXml("可见内容"),
            "xl/worksheets/sheet2.xml" to worksheetXml("隐藏内容"),
            "xl/worksheets/sheet3.xml" to worksheetXml("孤立内容"),
        )

        val result = extract("xlsx", bytes) as FingerprintExtraction.Success

        assertThat(result.fingerprint.structure.tableOrSheetCount).isEqualTo(1)
        assertThat(result.fingerprint.structure.sectionCount).isEqualTo(1)
    }

    @Test
    fun formulasAreNormalizedAndStoredOnlyAsKeyedTokens() = runTest {
        fun workbook(formula: String) = zipOf(
            "xl/workbook.xml" to """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="统计" sheetId="1" r:id="rId1"/></sheets>
                </workbook>
            """.trimIndent(),
            "xl/_rels/workbook.xml.rels" to workbookRelationships("rId1" to "worksheets/sheet1.xml"),
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="B1"><f>$formula</f><v>3</v></c></row></sheetData>
                </worksheet>
            """.trimIndent(),
        )
        val first = (extract("xlsx", workbook("SUM(A1:A2)")) as FingerprintExtraction.Success)
            .fingerprint.structure.headingTokens
        val normalized = (extract("xlsx", workbook(" sum ( A1 : A2 ) ")) as FingerprintExtraction.Success)
            .fingerprint.structure.headingTokens
        val otherSecret = MinHashFingerprint(
            FingerprintSecretProvider {
                FingerprintSecret(9, "different-install-secret".encodeToByteArray())
            },
        )
        val other = (extract("xlsx", workbook("SUM(A1:A2)"), otherSecret) as FingerprintExtraction.Success)
            .fingerprint.structure.headingTokens

        assertThat(first).containsExactlyElementsIn(normalized)
        assertThat(first).containsNoneIn(other)
        assertThat(first.joinToString()).doesNotContain("SUM")
    }

    @Test
    fun presentationRelationshipsDefineVisibleSlideOrder() = runTest {
        val bytes = zipOf(
            "ppt/presentation.xml" to """
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:sldIdLst><p:sldId r:id="rId2"/><p:sldId r:id="rId1"/><p:sldId r:id="rId3"/></p:sldIdLst>
                </p:presentation>
            """.trimIndent(),
            "ppt/_rels/presentation.xml.rels" to presentationRelationships(
                "rId1" to "slides/slide1.xml",
                "rId2" to "slides/slide2.xml",
                "rId3" to "slides/slide3.xml",
            ),
            "ppt/slides/slide1.xml" to slideXml("第一页", includePicture = false),
            "ppt/slides/slide2.xml" to slideXml("第二页", includePicture = false),
            "ppt/slides/slide3.xml" to slideXml("隐藏页", includePicture = false, show = false),
        )

        val result = extract("pptx", bytes) as FingerprintExtraction.Success

        assertThat(result.fingerprint.structure.sectionCount).isEqualTo(2)
        assertThat(result.fingerprint.structure.orderedSectionTokens).containsExactly(
            testMinHash().keyedToken("第二页"),
            testMinHash().keyedToken("第一页"),
        ).inOrder()
    }

    @Test
    fun missingVisibleSlideOrWorksheetMakesThePackageUnreadable() = runTest {
        val presentation = zipOf(
            "ppt/presentation.xml" to """
                <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <p:sldIdLst><p:sldId r:id="rId1"/><p:sldId r:id="rId2"/></p:sldIdLst>
                </p:presentation>
            """.trimIndent(),
            "ppt/_rels/presentation.xml.rels" to presentationRelationships(
                "rId1" to "slides/slide1.xml",
            ),
            "ppt/slides/slide1.xml" to slideXml("存在", includePicture = false),
        )
        val workbook = zipOf(
            "xl/workbook.xml" to """
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                    xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets><sheet name="表1" r:id="rId1"/><sheet name="表2" r:id="rId2"/></sheets>
                </workbook>
            """.trimIndent(),
            "xl/_rels/workbook.xml.rels" to workbookRelationships(
                "rId1" to "worksheets/sheet1.xml",
                "rId2" to "worksheets/sheet2.xml",
            ),
            "xl/worksheets/sheet1.xml" to worksheetXml("存在"),
        )

        assertThat(extract("pptx", presentation)).isEqualTo(FingerprintExtraction.ReadFailed)
        assertThat(extract("xlsx", workbook)).isEqualTo(FingerprintExtraction.ReadFailed)
    }

    @Test
    fun hiddenRowsAndColumnsDoNotContributeCellsOrFormulas() = runTest {
        val hiddenSheet = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <cols><col min="2" max="2" hidden="1"/></cols>
              <sheetData>
                <row r="1" hidden="1"><c r="A1" t="inlineStr"><is><t>隐藏行</t></is></c><c r="C1"><f>SECRET()</f><v>8</v></c></row>
                <row r="2"><c r="A2" t="inlineStr"><is><t>可见内容</t></is></c><c r="B2" t="inlineStr"><is><t>隐藏列</t></is></c></row>
              </sheetData>
            </worksheet>
        """.trimIndent()
        val expectedSheet = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData><row r="1" hidden="1"/><row r="2"><c r="A2" t="inlineStr"><is><t>可见内容</t></is></c></row></sheetData>
            </worksheet>
        """.trimIndent()

        val hidden = extract("xlsx", singleSheetWorkbook(hiddenSheet)) as FingerprintExtraction.Success
        val expected = extract("xlsx", singleSheetWorkbook(expectedSheet)) as FingerprintExtraction.Success

        assertThat(hidden.fingerprint.signature.asList())
            .containsExactlyElementsIn(expected.fingerprint.signature.asList()).inOrder()
        assertThat(hidden.fingerprint.structure.headingTokens)
            .containsExactlyElementsIn(expected.fingerprint.structure.headingTokens)
    }

    @Test
    fun historicalWordPropertyChangesDoNotAlterCurrentVisibilityOrHeadingStyle() = runTest {
        val historical = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p>
                <w:pPr><w:pPrChange><w:pPr><w:pStyle w:val="Heading1"/></w:pPr></w:pPrChange></w:pPr>
                <w:r><w:rPr><w:rPrChange><w:rPr><w:vanish/></w:rPr></w:rPrChange></w:rPr><w:t>当前正文</w:t></w:r>
              </w:p></w:body>
            </w:document>
        """.trimIndent()

        val result = extract("docx", zipOf("word/document.xml" to historical)) as FingerprintExtraction.Success

        assertThat(result.fingerprint.structure.headingTokens).isEmpty()
        assertThat(result.fingerprint.normalizedTextLength).isEqualTo("当前正文".length)
    }

    @Test
    fun largePresentationsAndFormulaSetsRemainBoundedAndPersistable() = runTest {
        val slideEntries = mutableListOf<Pair<String, String>>()
        val slideIds = StringBuilder()
        val slideRelationships = mutableListOf<Pair<String, String>>()
        repeat(513) { index ->
            val number = index + 1
            slideIds.append("<p:sldId r:id=\"rId$number\"/>")
            slideRelationships += "rId$number" to "slides/slide$number.xml"
            slideEntries += "ppt/slides/slide$number.xml" to slideXml("页面$number", false)
        }
        val presentationEntries = mutableListOf<Pair<String, String>>()
        presentationEntries += "ppt/presentation.xml" to """
            <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:sldIdLst>$slideIds</p:sldIdLst>
            </p:presentation>
        """.trimIndent()
        presentationEntries += "ppt/_rels/presentation.xml.rels" to
            presentationRelationships(*slideRelationships.toTypedArray())
        presentationEntries += slideEntries
        val presentation = extract("pptx", zipOf(*presentationEntries.toTypedArray())) as FingerprintExtraction.Success

        val formulaCells = (1..300).joinToString("") { column ->
            "<c r=\"A$column\"><f>SUM(A1:A$column)</f><v>$column</v></c>"
        }
        val formulaWorkbook = extract(
            "xlsx",
            singleSheetWorkbook(
                "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row r=\"1\">$formulaCells</row></sheetData></worksheet>",
            ),
        ) as FingerprintExtraction.Success

        assertThat(presentation.fingerprint.structure.sectionCount).isEqualTo(513)
        assertThat(presentation.fingerprint.structure.headingTokens).hasSize(256)
        assertThat(presentation.fingerprint.structure.orderedSectionTokens).hasSize(512)
        assertThat(formulaWorkbook.fingerprint.structure.headingTokens).hasSize(256)
        DocumentFingerprintStore.toEntity(
            presentation.fingerprint,
            com.wenxu.app.core.database.entity.FingerprintExtractStatus.SUCCESS,
            basisSize = 1,
            basisModifiedAt = 1,
            updatedAt = 1,
        )
        DocumentFingerprintStore.toEntity(
            formulaWorkbook.fingerprint,
            com.wenxu.app.core.database.entity.FingerprintExtractStatus.SUCCESS,
            basisSize = 1,
            basisModifiedAt = 1,
            updatedAt = 1,
        )
    }

    @Test
    fun mediaUsesSeparateRealisticEntryAndPackageBudgets() = runTest {
        val wordXml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>"
        val realisticMedia = zipGenerated(
            textEntries = arrayOf("word/document.xml" to wordXml),
            generatedEntries = arrayOf("word/media/image1.bin" to 12 * 1024 * 1024),
        )
        val oversizedMedia = zipGenerated(
            textEntries = arrayOf("word/document.xml" to wordXml),
            generatedEntries = arrayOf("word/media/image1.bin" to (32 * 1024 * 1024 + 1)),
        )
        val oversizedPackage = zipGenerated(
            textEntries = arrayOf("word/document.xml" to wordXml),
            generatedEntries = Array(5) { index -> "word/media/image$index.bin" to (26 * 1024 * 1024) },
        )

        assertThat(extract("docx", realisticMedia)).isInstanceOf(FingerprintExtraction.Success::class.java)
        assertThat(extract("docx", oversizedMedia)).isEqualTo(FingerprintExtraction.ReadFailed)
        assertThat(extract("docx", oversizedPackage)).isEqualTo(FingerprintExtraction.ReadFailed)
    }

    @Test
    fun ignoresWrongNamespacesDeletedAndHiddenWordText() = runTest {
        val guarded = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:f="urn:fake">
              <w:body><w:p>
                <w:r><w:t>可见正文</w:t></w:r>
                <f:t>伪造文字</f:t>
                <w:del><w:r><w:t>删除文字</w:t></w:r></w:del>
                <w:r><w:rPr><w:vanish/></w:rPr><w:t>隐藏文字</w:t></w:r>
              </w:p></w:body>
            </w:document>
        """.trimIndent()
        val visibleOnly = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>可见正文</w:t></w:r></w:p></w:body></w:document>"

        val first = extract("docx", zipOf("word/document.xml" to guarded)) as FingerprintExtraction.Success
        val second = extract("docx", zipOf("word/document.xml" to visibleOnly)) as FingerprintExtraction.Success

        assertThat(first.fingerprint.signature.asList())
            .containsExactlyElementsIn(second.fingerprint.signature.asList()).inOrder()
    }

    @Test
    fun legacyPdfEncryptedCorruptAndBlankFilesReturnExplicitStates() = runTest {
        assertThat(extract("doc", byteArrayOf(1))).isEqualTo(FingerprintExtraction.Unsupported)
        assertThat(extract("pdf", byteArrayOf(1))).isEqualTo(FingerprintExtraction.Unsupported)
        assertThat(extract("docx", OLE_MAGIC)).isEqualTo(FingerprintExtraction.Encrypted)
        assertThat(extract("docx", "not-a-zip".encodeToByteArray()))
            .isEqualTo(FingerprintExtraction.ReadFailed)
        assertThat(
            extract(
                "docx",
                zipOf(
                    "word/document.xml" to
                        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p/></w:body></w:document>",
                ),
            ),
        ).isEqualTo(FingerprintExtraction.Unsupported)
    }

    @Test
    fun rejectsDoctypeAndTextBeyondSafetyLimit() = runTest {
        val withDoctype = """
            <!DOCTYPE document [<!ENTITY xxe SYSTEM "file:///system/etc/hosts">]>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body>
            </w:document>
        """.trimIndent()
        val oversizedText = "文".repeat(500_001)
        val oversizedDocument =
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>" +
                oversizedText +
                "</w:t></w:r></w:p></w:body></w:document>"

        assertThat(extract("docx", zipOf("word/document.xml" to withDoctype)))
            .isEqualTo(FingerprintExtraction.ReadFailed)
        assertThat(extract("docx", zipOf("word/document.xml" to oversizedDocument)))
            .isEqualTo(FingerprintExtraction.ReadFailed)
    }

    @Test
    fun rejectsExternalDtdParameterEntityAndFakeWordNamespace() = runTest {
        val externalDtd = """
            <!DOCTYPE w:document SYSTEM "https://example.invalid/evil.dtd">
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>
        """.trimIndent()
        val parameterEntity = """
            <!DOCTYPE w:document [<!ENTITY % ext SYSTEM "https://example.invalid/evil.dtd"> %ext;]>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>
        """.trimIndent()
        val fakeNamespace = "<w:document xmlns:w=\"urn:fake\"><w:body><w:p><w:t>伪造正文</w:t></w:p></w:body></w:document>"

        assertThat(extract("docx", zipOf("word/document.xml" to externalDtd)))
            .isEqualTo(FingerprintExtraction.ReadFailed)
        assertThat(extract("docx", zipOf("word/document.xml" to parameterEntity)))
            .isEqualTo(FingerprintExtraction.ReadFailed)
        assertThat(extract("docx", zipOf("word/document.xml" to fakeNamespace)))
            .isEqualTo(FingerprintExtraction.Unsupported)
    }

    private suspend fun extract(
        extension: String,
        bytes: ByteArray,
        minHash: MinHashFingerprint = testMinHash(),
    ): FingerprintExtraction {
        val uri = "content://test/document.$extension"
        val gateway = FakeDocumentGateway().apply { put(uri, bytes) }
        return OoxmlDocumentFeatureExtractor(gateway, minHash)
            .extract(document(extension, uri, bytes.size.toLong()))
    }

    private fun document(extension: String, uri: String, size: Long) = DocumentEntity(
        id = 7,
        uri = uri,
        displayName = "document.$extension",
        normalizedName = "document",
        mimeType = "application/octet-stream",
        extension = extension,
        sizeBytes = size,
        modifiedAt = 2_000,
        sourceId = 1,
        parentUri = "content://test/tree",
        lastSeenScanId = "scan-1",
    )

    private fun slideXml(text: String, includePicture: Boolean, show: Boolean = true): String = """
        <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
               xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" show="${if (show) 1 else 0}">
          <p:cSld><p:spTree>
            <p:sp><p:txBody><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp>
            ${if (includePicture) "<p:pic/>" else ""}
          </p:spTree></p:cSld>
        </p:sld>
    """.trimIndent()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray =
        zipBytes(*entries.map { (name, text) -> name to text.encodeToByteArray() }.toTypedArray())

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun workbookRelationships(vararg relationships: Pair<String, String>): String =
        relationshipsXml(relationships, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet")

    private fun presentationRelationships(vararg relationships: Pair<String, String>): String =
        relationshipsXml(relationships, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide")

    private fun relationshipsXml(relationships: Array<out Pair<String, String>>, type: String): String =
        buildString {
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            relationships.forEach { (id, target) ->
                append("<Relationship Id=\"").append(id).append("\" Type=\"")
                    .append(type).append("\" Target=\"").append(target).append("\"/>")
            }
            append("</Relationships>")
        }

    private fun worksheetXml(text: String): String = """
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>$text</t></is></c></row></sheetData>
        </worksheet>
    """.trimIndent()

    private fun singleSheetWorkbook(sheetXml: String): ByteArray = zipOf(
        "xl/workbook.xml" to """
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets><sheet name="表1" r:id="rId1"/></sheets>
            </workbook>
        """.trimIndent(),
        "xl/_rels/workbook.xml.rels" to workbookRelationships("rId1" to "worksheets/sheet1.xml"),
        "xl/worksheets/sheet1.xml" to sheetXml,
    )

    private fun zipGenerated(
        textEntries: Array<Pair<String, String>>,
        generatedEntries: Array<Pair<String, Int>>,
    ): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            textEntries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.encodeToByteArray())
                zip.closeEntry()
            }
            val buffer = ByteArray(64 * 1024)
            generatedEntries.forEach { (name, size) ->
                zip.putNextEntry(ZipEntry(name))
                var remaining = size
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size)
                    zip.write(buffer, 0, count)
                    remaining -= count
                }
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private companion object {
        val OLE_MAGIC = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
    }
}
