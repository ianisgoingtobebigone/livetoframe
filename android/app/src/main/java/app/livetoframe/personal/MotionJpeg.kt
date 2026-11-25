package app.livetoframe.personal

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal class UnsupportedPhoto(message: String) : Exception(message)

internal data class StaticPlan(
    val videoStart: Long,
    val videoLength: Long,
    val stillEnd: Long,
    val xmpStart: Long,
    val xmpEnd: Long,
    val replacementXmp: ByteArray,
    val profile: String,
    val hasGainMap: Boolean,
    val mpfSizePatches: List<Pair<Long, ByteArray>>,
    val auxiliaryXmp: StaticXmpPatch? = null,
)

internal data class StaticXmpPatch(val start: Long, val end: Long, val bytes: ByteArray)

internal data class MotionVideo(val start: Long, val length: Long, val quickTime: Boolean)

internal object MotionJpeg {
    private val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()
    private val extendedHeader = "http://ns.adobe.com/xmp/extension/\u0000".toByteArray()
    private val mpfHeader = "MPF\u0000".toByteArray()
    private const val CAMERA = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM = "http://ns.google.com/photos/1.0/container/item/"
    private const val OPPO = "http://ns.oplus.com/photos/1.0/camera/"
    private const val HDR = "http://ns.adobe.com/hdr-gain-map/1.0/"
    private const val OPPO_GAIN_PADDING_SIZE = 16L
    private const val OPPO_SINGLE_IMAGE_SIZE_DELTA = 27L
    private val oppoEditingFields = setOf(
        "MotionPhotoEnable", "MotionPhotoSoundEnable", "MotionPhotoEditorFlag",
        "MotionPhotoVideoStart", "MotionPhotoVideoEnd",
    )
    private val oppoMotionFields = setOf(
        "MotionPhotoPrimaryPresentationTimestampUs", "MotionPhotoOwner", "OLivePhotoVersion", "VideoLength",
    )
    private const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val motionFields = setOf(
        "MotionPhoto", "MotionPhotoVersion", "MotionPhotoPresentationTimestampUs",
        "MicroVideo", "MicroVideoVersion", "MicroVideoOffset", "MicroVideoPresentationTimestampUs",
    )

    fun inspect(source: File): StaticPlan {
        val layout = readMotionLayout(source)
        val markers = layout.jpeg
        val xmp = checkNotNull(markers.xmp)
        val videoStart = layout.video.start
        val cameraProperties = layout.cameraProperties
        val oppoProperties = layout.oppoProperties
        val hdrProperties = layout.hdrProperties
        val hasGainMap = layout.gainEntry != null
        val oppo = oppoProperties.isNotEmpty()
        val hasOriginal = layout.originalEntry != null
        val plainOppo = oppo && !hasGainMap && !hasOriginal &&
            markers.mpf != null && layout.directory != null && layout.video.quickTime &&
            layout.video.length == source.length() - videoStart &&
            oppoProperties.propertyValue("MotionPhotoOwner") == "oplus"
        requireSupported(!oppo || (hasGainMap || hasOriginal || plainOppo) &&
            validOppoExportFields(oppoProperties, hasOriginal), "OPPO 实况照片结构尚未验证")
        val gainMap = if (hasGainMap) {
            requireSupported(hdrProperties.propertyValue("Version") == "1.0" && markers.mpf != null,
                "HDR 增益图标记或 MPF 索引不完整")
            validateGainMap(source, markers, videoStart, oppoMotionSource = oppo)
        } else {
            requireSupported(hdrProperties.isEmpty() && (hasOriginal || plainOppo || markers.mpf == null), "存在未核验的 HDR 或额外图像")
            null
        }
        val stillEnd = gainMap?.end ?: videoStart
        if (stillEnd != videoStart) {
            val entry = checkNotNull(layout.gainEntry)
            entry.getAttributeNodeNS(ITEM, "Length").value = (stillEnd - markers.end).toString()
        }
        val original = layout.originalEntry?.let {
            requireSupported(parsePositive(it.item("Length")) == videoStart - markers.end,
                "OPPO 内嵌原图长度不一致")
            validateEmbeddedOriginal(source, markers, videoStart, allowSizeCorrection = true)
        }
        val auxiliaryPatch = original?.jpeg?.xmp?.let { originalXmp ->
            val document = parseXmp(originalXmp)
            val auxiliaryCamera = document.properties(CAMERA)
            val auxiliaryOppo = document.properties(OPPO)
            val items = document.directories().singleOrNull()?.directoryItems().orEmpty()
            val motion = items.lastOrNull()
            val auxiliaryGain = items.getOrNull(1)
            requireSupported(items.size == 3 && items.all { it != null } &&
                items.first()?.item("Semantic") == "Primary" && items.first()?.item("Mime") == "image/jpeg" &&
                auxiliaryGain?.item("Semantic") == "GainMap" && auxiliaryGain.item("Mime") == "image/jpeg" &&
                auxiliaryGain.item("Length").toLongOrNull() == videoStart - original.jpeg.end &&
                motion?.item("Semantic") == "MotionPhoto" && motion.item("Mime") == "video/mp4" &&
                motion.item("Length").toLongOrNull() == layout.video.length &&
                auxiliaryOppo.propertyValue("VideoLength")?.toLongOrNull() == layout.video.length &&
                auxiliaryOppo.propertyValue("OLivePhotoVersion") == "2" &&
                auxiliaryCamera.propertyValue("MotionPhoto") == "1" &&
                hasKnownMotionFields(auxiliaryCamera) && validOppoExportFields(auxiliaryOppo, embeddedOriginal = true),
                "OPPO 内嵌原图的目录与外层不一致")
            stripMotion(auxiliaryCamera, auxiliaryOppo)
            checkNotNull(motion).parentNode.let { it.parentNode.removeChild(it) }
            StaticXmpPatch(originalXmp.start, originalXmp.end, serializeXmp(document, originalXmp.bytes.size))
        }

        stripMotion(cameraProperties, oppoProperties)
        if (hasGainMap || hasOriginal) layout.motionEntry?.let { it.parentNode.removeChild(it) }
        else layout.directory?.let { it.parentNode.removeChild(it) }
        val primarySizePatch = if (plainOppo && markers.mpf != null) {
            val index = readMpfIndex(markers, expectedCount = 1)
            val primary = index.records.single()
            // This variant's single MPF entry may undercount the JPEG by 27 bytes.
            requireSupported(primary.type == 0x030000L && primary.offset == markers.start &&
                primary.size in setOf(stillEnd, stillEnd - OPPO_SINGLE_IMAGE_SIZE_DELTA),
                "OPPO 单图 MPF 记录与 JPEG 边界不一致")
            index.sizePatch(primary, stillEnd)
        } else null
        val serialized = serializeXmp(layout.document,
            xmp.bytes.size.takeIf { hasGainMap || hasOriginal || primarySizePatch != null })
        val profile = when {
            hasOriginal -> "OPPO OLivePhoto v2 · 编辑封面与内嵌原图"
            plainOppo -> "OPPO OLivePhoto v2 · JPEG + QuickTime"
            oppo -> "OPPO OLivePhoto v2 · Ultra HDR JPEG"
            hasGainMap -> "Ultra HDR JPEG · Motion Photo 目录"
            layout.directory != null -> "JPEG · Motion Photo 目录"
            else -> "JPEG · MicroVideo 偏移"
        }
        return StaticPlan(videoStart, layout.video.length, stillEnd, xmp.start, xmp.end, serialized, profile,
            hasGainMap, gainMap?.sizePatches.orEmpty() + original?.sizePatches.orEmpty() + listOfNotNull(primarySizePatch),
            auxiliaryPatch)
    }

    fun previewVideo(source: File): MotionVideo = readMotionLayout(source).video

    private data class MotionLayout(
        val jpeg: Jpeg,
        val document: Document,
        val cameraProperties: List<Node>,
        val oppoProperties: List<Node>,
        val hdrProperties: List<Node>,
        val directory: Element?,
        val gainEntry: Element?,
        val originalEntry: Element?,
        val motionEntry: Element?,
        val video: MotionVideo,
    )

    private fun readMotionLayout(source: File): MotionLayout {
        val markers = readJpeg(source)
        requireSupported(markers.xmp != null, "缺少可识别的动态照片 XMP")
        val xmp = checkNotNull(markers.xmp)
        val document = parseXmp(xmp)
        val cameraProperties = document.properties(CAMERA)
        val oppoProperties = document.properties(OPPO)
        requireSupported(hasKnownMotionFields(cameraProperties), "XMP 含有未知的动态字段")
        val modern = cameraProperties.propertyValue("MotionPhoto")
        val legacy = cameraProperties.propertyValue("MicroVideo")
        requireSupported(modern in setOf(null, "0", "1") && legacy in setOf(null, "0", "1") &&
            (modern == "1" || legacy == "1") && (modern == null || legacy == null || modern == legacy),
            "动态标记缺失或不一致")

        val directories = document.directories()
        requireSupported(directories.size <= 1, "XMP 包含多个容器目录")
        val directory = directories.singleOrNull()
        val oldOffset = cameraProperties.propertyValue("MicroVideoOffset")?.let { parsePositive(it) }
        var gainEntry: Element? = null
        var originalEntry: Element? = null
        var motionEntry: Element? = null
        var quickTime = false
        val tailLength = if (directory != null) {
            val entries = directory.directoryItems()
            requireSupported(entries.size in 2..4 && entries.all { it != null }, "容器目录结构不受支持")
            val primary = checkNotNull(entries.first())
            val motion = checkNotNull(entries.last())
            motionEntry = motion.parentNode as Element
            quickTime = motion.item("Mime") == "video/quicktime"
            requireSupported(primary.item("Semantic") == "Primary" &&
                primary.item("Mime") == "image/jpeg" && primary.item("Length").zeroOrEmpty() &&
                primary.item("Padding").zeroOrEmpty() && motion.item("Semantic") == "MotionPhoto" &&
                (motion.item("Mime") == "video/mp4" || quickTime) && motion.item("Padding").zeroOrEmpty(),
                "主图或视频的目录记录不符合首验条件")
            if (entries.size == 3) {
                val gain = checkNotNull(entries[1])
                requireSupported(gain.item("Semantic") == "GainMap" && gain.item("Mime") == "image/jpeg" &&
                    gain.item("Padding").zeroOrEmpty(), "容器包含其他图像或附件")
                gainEntry = gain
            }
            if (entries.size == 4) {
                val placeholder = checkNotNull(entries[1])
                val original = checkNotNull(entries[2])
                requireSupported(oppoProperties.propertyValue("hdrVersion") == "1.0" &&
                    placeholder.item("Semantic") == "GainMap" && placeholder.item("Mime") == "image/png" &&
                    placeholder.item("Length") == "0" && placeholder.item("Padding").zeroOrEmpty() &&
                    original.item("Semantic") == "Original" && original.item("Mime") == "image/jpeg" &&
                    original.item("Padding").zeroOrEmpty(), "OPPO 内嵌原图的目录不受支持")
                originalEntry = original
            }
            parsePositive(motion.item("Length")).also {
                requireSupported(oldOffset == null || oldOffset == it, "视频长度与旧版偏移不一致")
            }
        } else {
            requireSupported(legacy == "1" && oldOffset != null, "缺少可信的视频边界")
            checkNotNull(oldOffset)
        }
        val attachmentLength = (gainEntry ?: originalEntry)?.let { parsePositive(it.item("Length")) } ?: 0L
        val videoStart = source.length() - tailLength
        requireSupported(videoStart >= markers.end && videoStart - markers.end == attachmentLength,
            "静态图与视频边界不一致")
        val oppo = oppoProperties.isNotEmpty()
        val videoLength = if (oppo) {
            requireSupported(oppoProperties.propertyValue("OLivePhotoVersion") == "2",
                "OPPO 私有动态照片版本尚未验证")
            parsePositive(oppoProperties.propertyValue("VideoLength").orEmpty()).also {
                requireSupported(it <= tailLength, "OPPO 主视频长度超出动态资源范围")
            }
        } else tailLength
        validateVideo(source, videoStart, videoLength, quickTime)
        return MotionLayout(markers, document, cameraProperties, oppoProperties, document.properties(HDR),
            directory, gainEntry, originalEntry, motionEntry, MotionVideo(videoStart, videoLength, quickTime))
    }

    private fun parseXmp(xmp: Xmp): Document {
        val xml = xmp.bytes.copyOfRange(xmpHeader.size, xmp.bytes.size)
        val rawText = xml.decodeToString(throwOnInvalidSequence = true)
        requireSupported(!rawText.contains("<!DOCTYPE", ignoreCase = true) &&
            !rawText.contains("<!ENTITY", ignoreCase = true), "XMP 包含不支持的 XML 声明")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External XMP entities are disabled") }
        }.parse(xml.inputStream())
    }

    private fun Document.elements(): List<Element> = getElementsByTagName("*").let { list ->
        List(list.length) { list.item(it) as Element }
    }

    private fun Document.properties(namespace: String): List<Node> = elements().flatMap { element ->
        element.attributes.asList().filter { it.namespaceURI == namespace } +
            listOf(element).filter { it.namespaceURI == namespace }
    }

    private fun Document.directories(): List<Element> =
        elements().filter { it.namespaceURI == CONTAINER && it.localName == "Directory" }

    private fun Element.directoryItems(): List<Element?> {
        val sequence = children().singleOrNull()?.takeIf { it.namespaceURI == RDF && it.localName == "Seq" }
        return sequence?.children()?.map { entry ->
            entry.takeIf { it.namespaceURI == RDF && it.localName == "li" }?.children()?.singleOrNull()?.takeIf {
                it.namespaceURI == CONTAINER && it.localName == "Item"
            }
        }.orEmpty()
    }

    private fun hasKnownMotionFields(camera: List<Node>): Boolean = camera.none {
        val key = it.localName.orEmpty()
        (key.startsWith("MotionPhoto") || key.startsWith("MicroVideo")) && key !in motionFields
    }

    private fun validOppoExportFields(properties: List<Node>, embeddedOriginal: Boolean): Boolean {
        if (properties.any {
            it.localName !in oppoMotionFields && it.localName !in oppoEditingFields &&
                !(embeddedOriginal && it.localName == "hdrVersion" && it.textContent.trim() == "1.0")
        }) return false
        if (properties.propertyValue("MotionPhotoEnable") !in setOf(null, "True") ||
            properties.propertyValue("MotionPhotoSoundEnable") !in setOf(null, "True", "False") ||
            properties.propertyValue("MotionPhotoEditorFlag") !in setOf(null, "2", "4", "6")) return false
        val start = properties.propertyValue("MotionPhotoVideoStart")
        val end = properties.propertyValue("MotionPhotoVideoEnd")
        if (start == null && end == null) return true
        return embeddedOriginal && start == "0" && (end?.toLongOrNull() ?: 0) > 0
    }

    private fun stripMotion(camera: List<Node>, oppo: List<Node>) {
        (camera.filter { it.localName in motionFields } +
            oppo.filter { it.localName in oppoMotionFields || it.localName in oppoEditingFields }).forEach { node ->
            if (node is org.w3c.dom.Attr) node.ownerElement.removeAttributeNode(node)
            else node.parentNode.removeChild(node)
        }
    }

    private fun serializeXmp(document: Document, fixedSize: Int?): ByteArray {
        val replacement = ByteArrayOutputStream().apply {
            write(xmpHeader)
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(this))
        }.toByteArray()
        requireSupported(replacement.size <= (fixedSize ?: 65533), "清理后的 XMP 超出原段大小")
        return if (fixedSize == null) replacement
        else ByteArray(fixedSize) { 0x20 }.also { replacement.copyInto(it) }
    }

    private fun List<Node>.propertyValue(name: String): String? {
        val matches = filter { it.localName == name }
        requireSupported(matches.size <= 1, "XMP 包含重复的动态字段")
        return matches.singleOrNull()?.textContent?.trim()
    }

    fun writeStill(source: File, plan: StaticPlan, target: File) {
        source.inputStream().buffered().use { input ->
            DataOutputStream(target.outputStream().buffered()).use { output ->
                var position = 0L
                for (patch in listOfNotNull(StaticXmpPatch(plan.xmpStart, plan.xmpEnd, plan.replacementXmp),
                    plan.auxiliaryXmp)) {
                    copyExactly(input, output, patch.start - position)
                    DataInputStream(input).skipNBytesCompat(patch.end - patch.start)
                    output.writeShort(0xffe1)
                    output.writeShort(patch.bytes.size + 2)
                    output.write(patch.bytes)
                    position = patch.end
                }
                copyExactly(input, output, plan.stillEnd - position)
            }
        }
        if (plan.mpfSizePatches.isNotEmpty()) {
            RandomAccessFile(target, "rw").use { file ->
                for ((offset, bytes) in plan.mpfSizePatches) {
                    file.seek(offset)
                    file.write(bytes)
                }
            }
        }
        val written = readJpeg(target)
        if (plan.hasGainMap) validateGainMap(target, written, target.length())
        else if (plan.auxiliaryXmp != null) validateEmbeddedOriginal(target, written, target.length())
        else {
            requireSupported(written.end == target.length(), "生成的副本含有额外数据")
            if (plan.mpfSizePatches.isNotEmpty()) {
                val primary = readMpfIndex(written, expectedCount = 1).records.single()
                requireSupported(primary.offset == 0L && primary.size == written.end,
                    "生成的副本单图 MPF 记录与 JPEG 边界不一致")
            }
        }
    }

    private data class Xmp(val start: Long, val end: Long, val bytes: ByteArray)
    private data class Mpf(val tiffStart: Long, val bytes: ByteArray)
    private data class Jpeg(val start: Long, val end: Long, val xmp: Xmp?, val mpf: Mpf?)
    private data class GainMap(val end: Long, val sizePatches: List<Pair<Long, ByteArray>>)

    private data class MpfEntry(val type: Long, val size: Long, val offset: Long, val sizeOffset: Long)
    private data class MpfIndex(val order: ByteOrder, val records: List<MpfEntry>) {
        fun sizePatch(record: MpfEntry, size: Long): Pair<Long, ByteArray> =
            record.sizeOffset to ByteBuffer.allocate(4).order(order).putInt(size.toInt()).array()
    }

    private fun readMpfIndex(jpeg: Jpeg, expectedCount: Int): MpfIndex {
        val mpf = jpeg.mpf ?: throw UnsupportedPhoto("缺少 MPF 索引")
        val bytes = ByteBuffer.wrap(mpf.bytes)
        requireSupported(bytes.capacity() >= 8, "MPF 索引太短")
        when (bytes.getShort(0).toInt()) {
            0x4949 -> bytes.order(ByteOrder.LITTLE_ENDIAN)
            0x4d4d -> bytes.order(ByteOrder.BIG_ENDIAN)
            else -> throw UnsupportedPhoto("MPF 字节序无效")
        }
        val ifd = bytes.getInt(4)
        requireSupported(bytes.getShort(2).toInt() == 42 && ifd in 8..(bytes.capacity() - 2),
            "MPF 索引偏移无效")
        val count = bytes.getShort(ifd).toInt() and 0xffff
        requireSupported(count <= (bytes.capacity() - ifd - 2) / 12, "MPF 字段超出范围")
        fun entry(tag: Int, type: Int, itemCount: Int): Int {
            val matches = (0 until count).map { ifd + 2 + it * 12 }.filter {
                bytes.getShort(it).toInt() and 0xffff == tag
            }
            val offset = matches.singleOrNull() ?: throw UnsupportedPhoto("缺少唯一 MPF 字段")
            requireSupported(bytes.getShort(offset + 2).toInt() == type && bytes.getInt(offset + 4) == itemCount,
                "MPF 字段类型无效")
            return bytes.getInt(offset + 8)
        }
        val entriesSize = expectedCount * 16
        requireSupported(entry(0xb001, 4, 1) == expectedCount, "MPF 图像数量不一致")
        val entries = entry(0xb002, 7, entriesSize)
        requireSupported(entries in 8..(bytes.capacity() - entriesSize), "MPF 图像记录超出范围")
        fun unsigned(offset: Int) = bytes.getInt(offset).toLong() and 0xffffffffL
        return MpfIndex(bytes.order(), List(expectedCount) { item ->
            val offset = entries + item * 16
            val relative = unsigned(offset + 8)
            MpfEntry(unsigned(offset), unsigned(offset + 4),
                if (relative == 0L) jpeg.start else mpf.tiffStart + relative, mpf.tiffStart + offset + 4)
        })
    }

    private data class EmbeddedOriginal(val jpeg: Jpeg, val sizePatches: List<Pair<Long, ByteArray>>)

    private fun validateEmbeddedOriginal(
        file: File, jpeg: Jpeg, end: Long, allowSizeCorrection: Boolean = false,
    ): EmbeddedOriginal {
        val index = readMpfIndex(jpeg, 3)
        val (primary, placeholder, originalRecord) = index.records
        val primarySize = jpeg.end - jpeg.start
        requireSupported(primary.type == 0x30000L && primary.offset == jpeg.start &&
            (primary.size == primarySize || allowSizeCorrection && primary.size in 1..primarySize) &&
            placeholder.type == 0L && placeholder.size == 0L && placeholder.offset == jpeg.end &&
            originalRecord.type == 0L && originalRecord.offset == jpeg.end && originalRecord.size == end - jpeg.end,
            "OPPO 内嵌原图的 MPF 索引不一致")
        val original = readJpeg(file, jpeg.end, end)
        val originalDocument = original.xmp?.let(::parseXmp)
        requireSupported(originalDocument?.properties(HDR)?.propertyValue("Version") == "1.0",
            "OPPO 内嵌原图缺少 HDR 信息")
        val gain = validateGainMap(file, original, end, oppoMotionSource = allowSizeCorrection)
        requireSupported(gain.end == end, "OPPO 内嵌增益图存在未验证的尾部填充")
        val patches = if (primary.size == primarySize) emptyList() else listOf(index.sizePatch(primary, primarySize))
        return EmbeddedOriginal(original, patches + gain.sizePatches)
    }

    private fun validateGainMap(
        file: File,
        jpeg: Jpeg,
        end: Long,
        oppoMotionSource: Boolean = false,
    ): GainMap {
        val index = readMpfIndex(jpeg, 2)
        val (primary, gain) = index.records
        val gainStart = gain.offset
        val primarySize = primary.size
        val actualPrimarySize = jpeg.end - jpeg.start
        requireSupported(primary.offset == jpeg.start, "MPF 主图偏移无效")
        requireSupported(gainStart == jpeg.end,
            "MPF 增益图起点不一致（索引 $gainStart，JPEG 边界 ${jpeg.end}）")
        val gainSize = gain.size
        requireSupported(gainSize == end - gainStart,
            "MPF 增益图长度不一致（索引 $gainSize，目录 ${end - gainStart}）")
        val map = readJpeg(file, gainStart, end)
        requireSupported(map.mpf == null && map.xmp?.bytes?.decodeToString()?.contains(HDR) == true,
            "增益图缺少有效的 HDR 元数据")
        val padding = end - map.end
        if (padding != 0L) {
            // Some OLivePhoto v2 files count 16 zero bytes before the MP4 as part of the gain map.
            requireSupported(oppoMotionSource && padding == OPPO_GAIN_PADDING_SIZE &&
                RandomAccessFile(file, "r").use { tail ->
                    tail.seek(map.end)
                    (1L..padding).all { tail.read() == 0 }
                }, "增益图 JPEG 结束位置与声明长度不一致")
        }
        // OLivePhoto v2 can misreport primary length. The primary EOI, MPF gain-map entry,
        // XMP directory and secondary JPEG must agree before normalizing the copy.
        requireSupported(primarySize == actualPrimarySize ||
            (oppoMotionSource && primarySize in 1..(end - jpeg.start)),
            "MPF 主图长度不一致（索引 $primarySize，JPEG 长度 $actualPrimarySize）")
        return GainMap(map.end, buildList {
            if (primarySize != actualPrimarySize) add(index.sizePatch(primary, actualPrimarySize))
            if (padding != 0L) add(index.sizePatch(gain, map.end - gainStart))
        })
    }

    private fun readJpeg(source: File, offset: Long = 0, limit: Long = source.length()): Jpeg {
        source.inputStream().buffered().use { stream ->
            DataInputStream(stream).skipNBytesCompat(offset)
            var position = offset
            fun byte(): Int = stream.read().also {
                requireSupported(it >= 0 && position < limit, "JPEG 数据提前结束")
                position++
            }
            requireSupported(byte() == 0xff && byte() == 0xd8, "首验仅支持 JPEG 原文件")
            var xmp: Xmp? = null
            var mpf: Mpf? = null
            var inScan = false
            var sawScan = false
            while (true) {
                if (inScan) {
                    while (byte() != 0xff) { /* Skip entropy-coded bytes. */ }
                } else {
                    requireSupported(byte() == 0xff, "JPEG 段标记无效")
                }
                var marker = byte()
                while (marker == 0xff) marker = byte()
                if (inScan && (marker == 0 || marker in 0xd0..0xd7)) continue
                if (marker == 0xd9) {
                    requireSupported(sawScan, "JPEG 缺少图像扫描数据")
                    return Jpeg(offset, position, xmp, mpf)
                }
                inScan = false
                requireSupported(marker in 0xc0..0xfe && marker !in 0xd0..0xd8, "JPEG 段结构不受支持")
                val start = position - 2
                val length = (byte() shl 8) + byte() - 2
                requireSupported(length >= 0 && length <= limit - position, "JPEG 段长度无效")
                val data = ByteArray(length)
                DataInputStream(stream).readFully(data)
                position += length
                when {
                    marker == 0xe2 && data.hasPrefix(mpfHeader) -> {
                        requireSupported(mpf == null, "JPEG 含有多个 MPF 段")
                        mpf = Mpf(start + 4 + mpfHeader.size, data.copyOfRange(mpfHeader.size, data.size))
                    }
                    marker == 0xe1 && data.hasPrefix(extendedHeader) ->
                        throw UnsupportedPhoto("扩展 XMP 尚待原片核验")
                    marker == 0xe1 && data.hasPrefix(xmpHeader) -> {
                        requireSupported(xmp == null, "JPEG 含有多个 XMP 段")
                        xmp = Xmp(start, position, data)
                    }
                    marker == 0xda -> {
                        inScan = true
                        sawScan = true
                    }
                }
            }
        }
    }

    private fun validateVideo(source: File, start: Long, length: Long, quickTime: Boolean) {
        RandomAccessFile(source, "r").use { file ->
            val end = start + length
            var offset = start
            val boxes = mutableSetOf<String>()
            while (offset < end) {
                requireSupported(end - offset >= 8, "MP4 尾部不完整")
                file.seek(offset)
                val shortSize = file.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(file::readFully).decodeToString()
                val headerLength = if (shortSize == 1L) 16L else 8L
                requireSupported(end - offset >= headerLength, "MP4 扩展长度不完整")
                val size = if (shortSize == 1L) file.readLong() else shortSize
                requireSupported(size >= headerLength && size <= end - offset,
                    "MP4 数据超出已声明范围")
                requireSupported(offset != start || (type == "ftyp" && size >= headerLength + 8), "缺少 MP4 文件类型")
                if (offset == start && quickTime) {
                    requireSupported(file.readInt() == 0x71742020, "QuickTime 视频类型与目录不一致")
                }
                if (size > headerLength) boxes.add(type)
                offset += size
            }
            requireSupported(boxes.containsAll(setOf("ftyp", "moov", "mdat")), "MP4 缺少必要数据")
        }
    }

    private fun Element.item(name: String): String = getAttributeNS(ITEM, name)

    private fun String.zeroOrEmpty(): Boolean = isEmpty() || this == "0"

    private fun parsePositive(value: String): Long =
        value.toLongOrNull()?.takeIf { it > 0 } ?: throw UnsupportedPhoto("视频长度无效")

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun org.w3c.dom.NamedNodeMap.asList(): List<Node> = List(length) { item(it) }

    private fun Element.children(): List<Element> =
        List(childNodes.length) { childNodes.item(it) }.filterIsInstance<Element>()

    private fun requireSupported(condition: Boolean, message: String) {
        if (!condition) throw UnsupportedPhoto(message)
    }

    private fun DataInputStream.skipNBytesCompat(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val read = skip(remaining)
            if (read == 0L) {
                readByte()
                remaining--
            } else remaining -= read
        }
    }

    private fun copyExactly(input: BufferedInputStream, output: DataOutputStream, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            requireSupported(read > 0, "原片在读取期间发生变化")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
}
