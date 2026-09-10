package com.wenxu.app.core.relations

import com.wenxu.app.core.database.entity.DocumentEntity
import com.wenxu.app.core.storage.DocumentGateway
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.helpers.DefaultHandler
import kotlin.coroutines.coroutineContext

private const val WORD_NAMESPACE = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val PRESENTATION_NAMESPACE = "http://schemas.openxmlformats.org/presentationml/2006/main"
private const val DRAWING_NAMESPACE = "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val SPREADSHEET_NAMESPACE = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val OFFICE_RELATIONSHIP_NAMESPACE =
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val PACKAGE_RELATIONSHIP_NAMESPACE =
    "http://schemas.openxmlformats.org/package/2006/relationships"
private const val MAX_HEADING_TOKENS = 256
private const val MAX_ORDERED_SECTION_TOKENS = 512

private fun MutableSet<String>.addBounded(token: String) {
    if (token.isNotEmpty() && size < MAX_HEADING_TOKENS) add(token)
}

class OoxmlDocumentFeatureExtractor(
    private val gateway: DocumentGateway,
    private val minHash: MinHashFingerprint,
) : DocumentFeatureExtractor {
    override val algorithmVersion: Int = minHash.algorithmVersion

    override suspend fun extract(document: DocumentEntity): FingerprintExtraction =
        withContext(Dispatchers.IO) {
            val kind = PackageKind.fromExtension(document.extension)
                ?: return@withContext FingerprintExtraction.Unsupported
            try {
                val entries = readPackage(document, kind)
                val extracted = when (kind) {
                    PackageKind.WORD -> extractWord(entries)
                    PackageKind.POWER_POINT -> extractPowerPoint(entries)
                    PackageKind.EXCEL -> extractExcel(entries)
                }
                val normalizedText = minHash.normalize(extracted.visibleText)
                if (normalizedText.isEmpty()) {
                    FingerprintExtraction.Unsupported
                } else {
                    val signature = minHash.create(normalizedText)
                    if (signature.isEmpty()) {
                        FingerprintExtraction.Unsupported
                    } else {
                        FingerprintExtraction.Success(
                            DocumentFingerprint(
                                documentId = document.id,
                                algorithmVersion = algorithmVersion,
                                signature = signature,
                                normalizedTextLength = normalizedText.length,
                                structure = extracted.structure,
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ZipException) {
                if (error.message.orEmpty().contains("encrypt", ignoreCase = true)) {
                    FingerprintExtraction.Encrypted
                } else {
                    FingerprintExtraction.ReadFailed
                }
            } catch (_: EncryptedPackageException) {
                FingerprintExtraction.Encrypted
            } catch (_: PackageLimitException) {
                FingerprintExtraction.ReadFailed
            } catch (_: IOException) {
                FingerprintExtraction.ReadFailed
            } catch (_: SecurityException) {
                FingerprintExtraction.ReadFailed
            } catch (_: Exception) {
                FingerprintExtraction.ReadFailed
            }
        }

    private suspend fun readPackage(
        document: DocumentEntity,
        kind: PackageKind,
    ): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        gateway.openInput(document.uri).use { raw ->
            BufferedInputStream(raw).use { buffered ->
                val header = readHeader(buffered)
                if (header.startsWith(OLE_COMPOUND_MAGIC)) throw EncryptedPackageException()
                if (!header.isZipHeader()) throw ZipException("Invalid OOXML package")

                ZipInputStream(buffered).use { zip ->
                    var entryCount = 0
                    var totalDecompressedBytes = 0L
                    var retainedBytes = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = zip.nextEntry ?: break
                        entryCount += 1
                        if (entryCount > MAX_ENTRY_COUNT) throw PackageLimitException()
                        val name = entry.name
                        val retain = !entry.isDirectory && kind.allows(name)
                        val entryLimit = if (retain) MAX_XML_ENTRY_BYTES else MAX_IGNORED_ENTRY_BYTES
                        if (entry.size > entryLimit) throw PackageLimitException()
                        if (retain) {
                            if (result.containsKey(name)) throw ZipException("Duplicate OOXML entry")
                        }
                        val entryRead = readEntryFully(zip, retain, totalDecompressedBytes, entryLimit)
                        totalDecompressedBytes = entryRead.totalDecompressedBytes
                        if (retain) {
                            val bytes = checkNotNull(entryRead.retained)
                            retainedBytes += bytes.size
                            if (retainedBytes > MAX_RETAINED_XML_BYTES) throw PackageLimitException()
                            result[name] = bytes
                        }
                        zip.closeEntry()
                    }
                }
            }
        }
        if (result.keys.none(kind::isRequiredContentEntry)) {
            throw ZipException("Missing OOXML content entry")
        }
        return result
    }

    private fun extractWord(entries: Map<String, ByteArray>): ExtractedDocument {
        val xml = entries[WORD_DOCUMENT] ?: throw ZipException("Missing Word document XML")
        val handler = parse(xml, WordHandler(minHash::keyedToken))
        return ExtractedDocument(
            visibleText = handler.visibleText.toString(),
            structure = StructureSignature(
                sectionCount = handler.paragraphCount,
                tableOrSheetCount = handler.tableCount,
                imageCount = handler.imageCount,
                headingTokens = handler.headingTokens,
            ),
        )
    }

    private fun extractPowerPoint(entries: Map<String, ByteArray>): ExtractedDocument {
        val presentationXml = entries[PRESENTATION_DOCUMENT]
            ?: throw ZipException("Missing presentation XML")
        val relationshipsXml = entries[PRESENTATION_RELATIONSHIPS]
            ?: throw ZipException("Missing presentation relationships")
        val presentation = parse(presentationXml, PresentationOrderHandler())
        val relationships = parse(
            relationshipsXml,
            RelationshipsHandler(RELATIONSHIP_TYPE_SLIDE),
        )
        val visibleText = StringBuilder()
        var imageCount = 0
        val headings = linkedSetOf<String>()
        val orderedSections = mutableListOf<String>()
        val usedRelationshipIds = hashSetOf<String>()
        val usedPaths = hashSetOf<String>()
        var visibleSlideCount = 0
        presentation.slides.forEach { slideReference ->
            if (!slideReference.visible) return@forEach
            if (!usedRelationshipIds.add(slideReference.relationshipId)) {
                throw ZipException("Duplicate visible slide relationship")
            }
            val target = relationships.resolve(slideReference.relationshipId)
                ?: throw ZipException("Missing visible slide relationship")
            val path = resolveRelatedPart("ppt", target)
                ?: throw ZipException("Invalid visible slide target")
            if (!usedPaths.add(path)) throw ZipException("Duplicate visible slide target")
            val xml = entries[path] ?: throw ZipException("Missing visible slide entry")
            val handler = parse(xml, PresentationHandler())
            if (!handler.visible) return@forEach
            visibleSlideCount += 1
            appendBounded(visibleText, handler.visibleText)
            imageCount += handler.imageCount
            handler.firstVisibleText?.let(minHash::keyedToken)?.let { headings.addBounded(it) }
            if (orderedSections.size < MAX_ORDERED_SECTION_TOKENS) {
                orderedSections += minHash.keyedToken(handler.visibleText.toString())
                    .ifEmpty { minHash.keyedToken("empty-slide") }
            }
        }
        return ExtractedDocument(
            visibleText = visibleText.toString(),
            structure = StructureSignature(
                sectionCount = visibleSlideCount,
                tableOrSheetCount = 0,
                imageCount = imageCount,
                headingTokens = headings,
                orderedSectionTokens = orderedSections,
            ),
        )
    }

    private fun extractExcel(entries: Map<String, ByteArray>): ExtractedDocument {
        val sharedStrings = entries[EXCEL_SHARED_STRINGS]
            ?.let { xml -> parse(xml, SharedStringsHandler()).values }
            .orEmpty()
        val workbook = entries[EXCEL_WORKBOOK]
            ?.let { xml -> parse(xml, WorkbookHandler()) }
            ?: throw ZipException("Missing workbook XML")
        val relationshipsXml = entries[EXCEL_WORKBOOK_RELATIONSHIPS]
            ?: throw ZipException("Missing workbook relationships")
        val relationships = parse(
            relationshipsXml,
            RelationshipsHandler(RELATIONSHIP_TYPE_WORKSHEET),
        )
        val visibleText = StringBuilder()
        val headings = linkedSetOf<String>()
        var rowCount = 0
        var imageCount = 0
        var sheetCount = 0
        val usedRelationshipIds = hashSetOf<String>()
        val usedPaths = hashSetOf<String>()
        workbook.sheets.forEach { sheet ->
            if (!sheet.visible) return@forEach
            if (!usedRelationshipIds.add(sheet.relationshipId)) {
                throw ZipException("Duplicate visible worksheet relationship")
            }
            val target = relationships.resolve(sheet.relationshipId)
                ?: throw ZipException("Missing visible worksheet relationship")
            val path = resolveRelatedPart("xl", target)
                ?: throw ZipException("Invalid visible worksheet target")
            if (!usedPaths.add(path)) throw ZipException("Duplicate visible worksheet target")
            val xml = entries[path] ?: throw ZipException("Missing visible worksheet entry")
            sheetCount += 1
            appendBounded(visibleText, sheet.name)
            headings.addBounded(minHash.keyedToken(sheet.name))
            val handler = parse(xml, WorksheetHandler(sharedStrings, minHash::keyedToken))
            appendBounded(visibleText, handler.visibleText)
            rowCount += handler.rowCount
            imageCount += handler.imageCount
            handler.headingTokens.forEach { token -> headings.addBounded(token) }
        }
        return ExtractedDocument(
            visibleText = visibleText.toString(),
            structure = StructureSignature(
                sectionCount = rowCount,
                tableOrSheetCount = sheetCount,
                imageCount = imageCount,
                headingTokens = headings,
            ),
        )
    }

    private fun resolveRelatedPart(baseDirectory: String, target: String): String? {
        if (target.isBlank() || '\\' in target) return null
        val rawSegments = if (target.startsWith('/')) {
            target.removePrefix("/").split('/')
        } else {
            (baseDirectory.split('/') + target.split('/'))
        }
        val normalized = ArrayDeque<String>()
        rawSegments.forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (normalized.isEmpty()) return null else normalized.removeLast()
                else -> normalized.addLast(segment)
            }
        }
        return normalized.joinToString("/").takeIf { path ->
            path.startsWith("$baseDirectory/") && !path.contains("../")
        }
    }

    private suspend fun readEntryFully(
        zip: ZipInputStream,
        retain: Boolean,
        totalBeforeEntry: Long,
        entryLimit: Long,
    ): EntryRead {
        val output = if (retain) ByteArrayOutputStream() else null
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryBytes = 0L
        var totalBytes = totalBeforeEntry
        while (true) {
            coroutineContext.ensureActive()
            val count = zip.read(buffer)
            if (count < 0) break
            entryBytes += count
            totalBytes += count
            if (entryBytes > entryLimit || totalBytes > MAX_TOTAL_DECOMPRESSED_BYTES) {
                throw PackageLimitException()
            }
            output?.write(buffer, 0, count)
        }
        return EntryRead(output?.toByteArray(), totalBytes)
    }

    private fun readHeader(input: BufferedInputStream): ByteArray {
        input.mark(HEADER_BYTES)
        val header = ByteArray(HEADER_BYTES)
        var count = 0
        while (count < header.size) {
            val read = input.read(header, count, header.size - count)
            if (read < 0) break
            count += read
        }
        input.reset()
        return header.copyOf(count)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun ByteArray.isZipHeader(): Boolean =
        size >= 4 && this[0] == 'P'.code.toByte() && this[1] == 'K'.code.toByte() &&
            ((this[2] == 3.toByte() && this[3] == 4.toByte()) ||
                (this[2] == 5.toByte() && this[3] == 6.toByte()) ||
                (this[2] == 7.toByte() && this[3] == 8.toByte()))

    private fun appendBounded(target: StringBuilder, value: CharSequence) {
        if (target.length + value.length + 1 > MAX_TEXT_CHARACTERS) {
            throw PackageLimitException()
        }
        target.append(value).append(' ')
    }

    private fun <T : DefaultHandler> parse(xml: ByteArray, handler: T): T {
        if (containsXmlDeclaration(xml, "<!DOCTYPE") || containsXmlDeclaration(xml, "<!ENTITY")) {
            throw SecurityException("OOXML中的DTD或实体声明已被拒绝")
        }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            try {
                isXIncludeAware = false
            } catch (_: UnsupportedOperationException) {
                // Declaration pre-scan and rejecting EntityResolver remain the fail-closed boundary.
            }
        }
        SECURE_XML_FEATURES.forEach { (feature, value) ->
            try {
                factory.setFeature(feature, value)
            } catch (_: SAXNotRecognizedException) {
                // Android parsers may not expose Xerces feature names. Declarations are rejected below.
            } catch (_: SAXNotSupportedException) {
                // Android parsers may not expose Xerces feature names. Declarations are rejected below.
            } catch (_: ParserConfigurationException) {
                // Pre-scan plus a rejecting EntityResolver provide the Android-compatible fail-closed path.
            }
        }
        val reader = factory.newSAXParser().xmlReader
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ ->
            throw SecurityException("外部XML实体已被拒绝")
        }
        reader.contentHandler = handler
        reader.parse(InputSource(ByteArrayInputStream(xml)))
        return handler
    }

    private fun containsXmlDeclaration(xml: ByteArray, declaration: String): Boolean {
        var matched = 0
        xml.forEach { rawByte ->
            if (rawByte == 0.toByte()) return@forEach
            val byte = rawByte.toInt() and 0xff
            val uppercase = if (byte in 'a'.code..'z'.code) byte - ASCII_CASE_OFFSET else byte
            matched = when {
                uppercase == declaration[matched].code -> matched + 1
                uppercase == declaration[0].code -> 1
                else -> 0
            }
            if (matched == declaration.length) return true
        }
        return false
    }

    private data class ExtractedDocument(
        val visibleText: String,
        val structure: StructureSignature,
    )

    private data class EntryRead(
        val retained: ByteArray?,
        val totalDecompressedBytes: Long,
    )

    private enum class PackageKind {
        WORD,
        POWER_POINT,
        EXCEL;

        fun allows(name: String): Boolean = when (this) {
            WORD -> name == WORD_DOCUMENT
            POWER_POINT -> name == PRESENTATION_DOCUMENT ||
                name == PRESENTATION_RELATIONSHIPS || PRESENTATION_SLIDE.matches(name)
            EXCEL -> name == EXCEL_WORKBOOK ||
                name == EXCEL_WORKBOOK_RELATIONSHIPS ||
                name == EXCEL_SHARED_STRINGS ||
                EXCEL_WORKSHEET.matches(name)
        }

        fun isRequiredContentEntry(name: String): Boolean = when (this) {
            WORD -> name == WORD_DOCUMENT
            POWER_POINT -> name == PRESENTATION_DOCUMENT
            EXCEL -> name == EXCEL_WORKBOOK
        }

        companion object {
            fun fromExtension(extension: String): PackageKind? = when (extension.lowercase(Locale.ROOT)) {
                "docx" -> WORD
                "pptx" -> POWER_POINT
                "xlsx" -> EXCEL
                else -> null
            }
        }
    }

    private class EncryptedPackageException : IOException()
    private class PackageLimitException : IOException()

    private companion object {
        const val HEADER_BYTES = 8
        const val ASCII_CASE_OFFSET = 32
        const val MAX_ENTRY_COUNT = 2_048
        const val MAX_XML_ENTRY_BYTES = 4L * 1024L * 1024L
        const val MAX_IGNORED_ENTRY_BYTES = 32L * 1024L * 1024L
        const val MAX_TOTAL_DECOMPRESSED_BYTES = 128L * 1024L * 1024L
        const val MAX_RETAINED_XML_BYTES = 24L * 1024L * 1024L
        const val MAX_TEXT_CHARACTERS = 500_000
        const val WORD_DOCUMENT = "word/document.xml"
        const val PRESENTATION_DOCUMENT = "ppt/presentation.xml"
        const val PRESENTATION_RELATIONSHIPS = "ppt/_rels/presentation.xml.rels"
        const val EXCEL_WORKBOOK = "xl/workbook.xml"
        const val EXCEL_WORKBOOK_RELATIONSHIPS = "xl/_rels/workbook.xml.rels"
        const val EXCEL_SHARED_STRINGS = "xl/sharedStrings.xml"
        const val RELATIONSHIP_TYPE_SLIDE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
        const val RELATIONSHIP_TYPE_WORKSHEET =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
        val PRESENTATION_SLIDE = Regex("^ppt/slides/slide\\d+\\.xml$")
        val EXCEL_WORKSHEET = Regex("^xl/worksheets/sheet\\d+\\.xml$")
        val OLE_COMPOUND_MAGIC = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )
        val SECURE_XML_FEATURES = listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )
    }
}

private abstract class VisibleTextHandler : DefaultHandler() {
    val visibleText = StringBuilder()

    protected fun appendVisible(value: String) {
        val normalized = value.trim()
        if (normalized.isEmpty()) return
        if (visibleText.length + normalized.length + 1 > MAX_VISIBLE_TEXT) {
            throw VisibleTextLimitException()
        }
        visibleText.append(normalized).append(' ')
    }

    protected fun isElement(
        uri: String?,
        localName: String,
        namespace: String,
        expectedLocalName: String,
    ): Boolean = uri == namespace && localName == expectedLocalName

    protected fun Attributes.value(namespace: String, name: String): String? =
        (0 until length)
            .firstOrNull { index ->
                getURI(index) == namespace && getLocalName(index) == name
            }
            ?.let(::getValue)

    protected fun Attributes.unqualifiedValue(name: String): String? =
        (0 until length)
            .firstOrNull { index -> getURI(index).isEmpty() && getLocalName(index) == name }
            ?.let(::getValue)

    private companion object {
        const val MAX_VISIBLE_TEXT = 500_000
    }
}

private class VisibleTextLimitException : RuntimeException()

private class WordHandler(
    private val tokenHasher: (String) -> String,
) : VisibleTextHandler() {
    var paragraphCount = 0
        private set
    var tableCount = 0
        private set
    var imageCount = 0
        private set
    val headingTokens = linkedSetOf<String>()
    private var captureText = false
    private val currentText = StringBuilder()
    private val currentParagraph = StringBuilder()
    private var headingParagraph = false
    private var deletionDepth = 0
    private var propertyChangeDepth = 0
    private var inRun = false
    private var runHidden = false

    override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
        if (uri != WORD_NAMESPACE) return
        when (localName) {
            "pPrChange", "rPrChange" -> propertyChangeDepth += 1
            "p" -> {
                paragraphCount += 1
                currentParagraph.clear()
                headingParagraph = false
            }
            "pStyle" -> {
                if (propertyChangeDepth > 0) return
                val style = attributes.value(WORD_NAMESPACE, "val").orEmpty().lowercase(Locale.ROOT)
                headingParagraph = style.startsWith("heading") ||
                    style.startsWith("title") || style.contains("标题")
            }
            "tbl" -> tableCount += 1
            "drawing", "pict" -> imageCount += 1
            "del", "moveFrom" -> deletionDepth += 1
            "r" -> {
                inRun = true
                runHidden = false
            }
            "vanish", "webHidden" -> if (inRun && propertyChangeDepth == 0) runHidden = true
            "t" -> {
                captureText = deletionDepth == 0 && !runHidden
                currentText.clear()
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (captureText) currentText.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String, qName: String) {
        if (uri != WORD_NAMESPACE) return
        when (localName) {
            "pPrChange", "rPrChange" ->
                propertyChangeDepth = (propertyChangeDepth - 1).coerceAtLeast(0)
            "t" -> {
                if (captureText) {
                    val text = currentText.toString()
                    appendVisible(text)
                    currentParagraph.append(text).append(' ')
                }
                captureText = false
            }
            "p" -> {
                if (headingParagraph && currentParagraph.isNotBlank()) {
                    headingTokens.addBounded(tokenHasher(currentParagraph.toString()))
                }
            }
            "del", "moveFrom" -> deletionDepth = (deletionDepth - 1).coerceAtLeast(0)
            "r" -> {
                inRun = false
                runHidden = false
            }
        }
    }
}

private class PresentationHandler : VisibleTextHandler() {
    var imageCount = 0
        private set
    var firstVisibleText: String? = null
        private set
    var visible: Boolean = true
        private set
    private var capturingText = false
    private val currentText = StringBuilder()

    override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
        when {
            isElement(uri, localName, PRESENTATION_NAMESPACE, "sld") -> {
                val show = attributes.unqualifiedValue("show")
                visible = show != "0" && !show.equals("false", ignoreCase = true)
            }
            isElement(uri, localName, PRESENTATION_NAMESPACE, "pic") -> imageCount += 1
            isElement(uri, localName, DRAWING_NAMESPACE, "t") -> {
                capturingText = true
                currentText.clear()
            }
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (capturingText) currentText.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String, qName: String) {
        if (isElement(uri, localName, DRAWING_NAMESPACE, "t")) {
            val text = currentText.toString().trim()
            appendVisible(text)
            if (firstVisibleText == null && text.isNotEmpty()) firstVisibleText = text
            capturingText = false
        }
    }
}

private data class OrderedPartReference(
    val relationshipId: String,
    val visible: Boolean,
)

private class PresentationOrderHandler : VisibleTextHandler() {
    val slides = mutableListOf<OrderedPartReference>()

    override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
        if (!isElement(uri, localName, PRESENTATION_NAMESPACE, "sldId")) return
        val id = attributes.value(OFFICE_RELATIONSHIP_NAMESPACE, "id").orEmpty()
        if (id.isEmpty()) return
        val show = attributes.unqualifiedValue("show")
        slides += OrderedPartReference(
            relationshipId = id,
            visible = show != "0" && !show.equals("false", ignoreCase = true),
        )
    }
}

private class RelationshipsHandler(
    private val expectedType: String,
) : VisibleTextHandler() {
    private val targets = linkedMapOf<String, String>()
    private val seenIds = hashSetOf<String>()
    private val invalidIds = hashSetOf<String>()

    fun resolve(id: String): String? = targets[id].takeUnless { id in invalidIds }

    override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
        if (!isElement(uri, localName, PACKAGE_RELATIONSHIP_NAMESPACE, "Relationship")) return
        val id = attributes.unqualifiedValue("Id").orEmpty()
        if (id.isEmpty()) return
        if (!seenIds.add(id)) {
            invalidIds += id
            targets.remove(id)
            return
        }
        if (attributes.unqualifiedValue("Type") != expectedType) return
        if (attributes.unqualifiedValue("TargetMode").equals("External", ignoreCase = true)) return
        val target = attributes.unqualifiedValue("Target").orEmpty()
        if (target.isNotEmpty()) targets[id] = target
    }
}

private class SharedStringsHandler : DefaultHandler() {
    val values = mutableListOf<String>()
    private var inItem = false
    private var inText = false
    private val currentItem = StringBuilder()

    override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
        if (uri != SPREADSHEET_NAMESPACE) return
        when (localName) {
            "si" -> {
                inItem = true
                currentItem.clear()
            }
            "t" -> if (inItem) inText = true
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (inText) currentItem.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String, qName: String) {
        if (uri != SPREADSHEET_NAMESPACE) return
        when (localName) {
            "t" -> inText = false
            "si" -> {
                values += currentItem.toString()
                inItem = false
            }
        }
    }
}

private data class WorkbookSheetReference(
    val name: String,
    val relationshipId: String,
    val visible: Boolean,
)

private class WorkbookHandler : VisibleTextHandler() {
    val sheets = mutableListOf<WorkbookSheetReference>()

    override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
        if (!isElement(uri, localName, SPREADSHEET_NAMESPACE, "sheet")) return
        val state = attributes.unqualifiedValue("state").orEmpty()
        val sheetName = attributes.unqualifiedValue("name").orEmpty().trim()
        val relationshipId = attributes.value(OFFICE_RELATIONSHIP_NAMESPACE, "id").orEmpty()
        if (sheetName.isEmpty() || relationshipId.isEmpty()) return
        sheets += WorkbookSheetReference(
            name = sheetName,
            relationshipId = relationshipId,
            visible = !state.equals("hidden", ignoreCase = true) &&
                !state.equals("veryHidden", ignoreCase = true),
        )
    }
}

private class WorksheetHandler(
    private val sharedStrings: List<String>,
    private val tokenHasher: (String) -> String,
) : VisibleTextHandler() {
    var rowCount = 0
        private set
    var imageCount = 0
        private set
    val headingTokens = linkedSetOf<String>()
    private var firstRowNumber: Int? = null
    private var currentRowNumber = 0
    private var currentCellType = ""
    private var currentCellReference = ""
    private var currentTag = ""
    private val tagText = StringBuilder()
    private val inlineText = StringBuilder()
    private var cellValue = ""
    private var hasFormula = false
    private var formulaText = ""
    private var currentRowHidden = false
    private var currentCellHidden = false
    private val hiddenColumns = mutableListOf<IntRange>()

    override fun startElement(uri: String?, localName: String, qName: String, attributes: Attributes) {
        if (uri != SPREADSHEET_NAMESPACE) return
        when (localName) {
            "col" -> {
                val hidden = attributes.unqualifiedValue("hidden").isTrueFlag()
                val min = attributes.unqualifiedValue("min")?.toIntOrNull()
                val max = attributes.unqualifiedValue("max")?.toIntOrNull()
                if (hidden && min != null && max != null && min in 1..max) hiddenColumns += min..max
            }
            "row" -> {
                rowCount += 1
                currentRowNumber = attributes.unqualifiedValue("r")?.toIntOrNull() ?: rowCount
                currentRowHidden = attributes.unqualifiedValue("hidden").isTrueFlag()
                if (!currentRowHidden && firstRowNumber == null) firstRowNumber = currentRowNumber
            }
            "c" -> {
                currentCellType = attributes.unqualifiedValue("t").orEmpty()
                currentCellReference = attributes.unqualifiedValue("r").orEmpty()
                inlineText.clear()
                cellValue = ""
                hasFormula = false
                formulaText = ""
                currentCellHidden = currentRowHidden ||
                    hiddenColumns.any { range -> cellColumnIndex(currentCellReference) in range }
            }
            "t", "v", "f" -> {
                currentTag = localName
                tagText.clear()
            }
            "drawing" -> imageCount += 1
        }
    }

    override fun characters(ch: CharArray, start: Int, length: Int) {
        if (currentTag.isNotEmpty()) tagText.append(ch, start, length)
    }

    override fun endElement(uri: String?, localName: String, qName: String) {
        if (uri != SPREADSHEET_NAMESPACE) return
        when (localName) {
            "t" -> {
                inlineText.append(tagText)
                currentTag = ""
            }
            "v" -> {
                cellValue = tagText.toString()
                currentTag = ""
            }
            "f" -> {
                hasFormula = true
                formulaText = tagText.toString()
                currentTag = ""
            }
            "c" -> finishCell()
        }
    }

    private fun finishCell() {
        if (currentCellHidden) return
        val visibleValue = when (currentCellType) {
            "s" -> cellValue.toIntOrNull()?.let(sharedStrings::getOrNull).orEmpty()
            "inlineStr", "str" -> inlineText.toString().ifBlank { cellValue }
            else -> cellValue
        }.trim()
        appendVisible(visibleValue)
        if (currentRowNumber == firstRowNumber && visibleValue.isNotEmpty()) {
            headingTokens.addBounded(tokenHasher(visibleValue))
        }
        if (hasFormula && currentCellReference.isNotEmpty()) {
            val normalizedFormula = formulaText.replace(Regex("\\s+"), "").uppercase(Locale.ROOT)
            headingTokens.addBounded(tokenHasher("formula:$currentCellReference:$normalizedFormula"))
        }
    }

    private fun String?.isTrueFlag(): Boolean = this == "1" || this.equals("true", ignoreCase = true)

    private fun cellColumnIndex(reference: String): Int {
        var result = 0
        for (character in reference) {
            if (!character.isLetter()) break
            result = result * 26 + (character.uppercaseChar().code - 'A'.code + 1)
        }
        return result
    }
}
