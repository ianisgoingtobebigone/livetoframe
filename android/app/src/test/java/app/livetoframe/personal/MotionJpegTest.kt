package app.livetoframe.personal

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MotionJpegTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun preservesImageBytesAndNonMotionXmpForDirectoryPhoto() {
        val source = sample(directory = true)
        val plan = MotionJpeg.inspect(source)
        val target = folder.newFile()
        MotionJpeg.writeStill(source, plan, target)

        val expectedImage = jpeg()
        assertArrayEquals(expectedImage.copyOfRange(2, expectedImage.size), target.readBytes().takeLast(expectedImage.size - 2).toByteArray())
        val contents = target.readBytes().decodeToString()
        assertTrue(contents.contains("test caption"))
        assertFalse(contents.contains("MotionPhoto"))
        assertFalse(contents.contains("Container:Directory"))
        assertEquals(mp4().size.toLong(), plan.videoLength)
    }

    @Test fun acceptsLegacyMicroVideoAndNeverChangesSource() {
        val source = sample(directory = false)
        val before = source.readBytes()
        val target = folder.newFile()
        MotionJpeg.writeStill(source, MotionJpeg.inspect(source), target)
        assertArrayEquals(before, source.readBytes())
        assertFalse(target.readBytes().decodeToString().contains("MicroVideo"))
    }

    @Test fun rejectsDirectoryLengthMismatch() {
        val source = sample(directory = true, lengthAdjustment = -1)
        val error = assertThrows(UnsupportedPhoto::class.java) { MotionJpeg.inspect(source) }
        assertTrue(error.message.orEmpty().contains("边界"))
    }

    @Test fun rejectsAppendedBytesWithMalformedMp4Box() {
        val source = sample(directory = false, video = mp4().dropLast(1).toByteArray())
        val error = assertThrows(UnsupportedPhoto::class.java) { MotionJpeg.inspect(source) }
        assertTrue(error.message.orEmpty().contains("MP4"))
    }

    @Test fun rejectsGainMapAndLeavesSourceUntouched() {
        val source = sample(directory = true, extra = segment(0xe2, "MPF\u0000payload".toByteArray()))
        val before = source.readBytes()
        assertThrows(UnsupportedPhoto::class.java) { MotionJpeg.inspect(source) }
        assertArrayEquals(before, source.readBytes())
    }

    @Test fun rejectsOrdinaryJpegAndUnannouncedTail() {
        val source = folder.newFile().apply { writeBytes(jpeg() + mp4()) }
        assertThrows(UnsupportedPhoto::class.java) { MotionJpeg.inspect(source) }
    }

    @Test fun rejectsExtendedXmp() {
        val extra = segment(0xe1, "http://ns.adobe.com/xmp/extension/\u0000payload".toByteArray())
        assertThrows(UnsupportedPhoto::class.java) { MotionJpeg.inspect(sample(true, extra = extra)) }
    }

    @Test fun preservesOppoGainMapAndRepairsPrimaryMpfLength() {
        for (correction in listOf(728, 730, 910, -24)) {
            val byteOrder = if (correction > 0) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
            val source = oppoSample(primarySizeDelta = correction, byteOrder = byteOrder)
            val original = source.readBytes()
            val plan = MotionJpeg.inspect(source)
            val target = folder.newFile()
            MotionJpeg.writeStill(source, plan, target)

            assertTrue(plan.hasGainMap)
            assertEquals(mp4().size.toLong(), plan.videoLength)
            assertEquals(plan.videoStart, target.length())
            val copy = target.readBytes()
            assertTrue(copy.decodeToString().contains("GainMap"))
            assertFalse(copy.decodeToString().contains("OLivePhotoVersion"))
            val (offset, patch) = plan.mpfSizePatches.single()
            assertArrayEquals(patch, copy.copyOfRange(offset.toInt(), offset.toInt() + 4))
            assertArrayEquals(original.copyOfRange(offset.toInt() + 4, copy.size),
                copy.copyOfRange(offset.toInt() + 4, copy.size))
            assertArrayEquals(original, source.readBytes())
        }
    }

    @Test fun rejectsOppoWhenMpfAndDirectoryDisagree() {
        val offset = assertThrows(UnsupportedPhoto::class.java) {
            MotionJpeg.inspect(oppoSample(gainOffsetAdjustment = 1))
        }
        assertTrue(offset.message.orEmpty().contains("增益图起点"))
        val length = assertThrows(UnsupportedPhoto::class.java) {
            MotionJpeg.inspect(oppoSample(gainSizeAdjustment = 1))
        }
        assertTrue(length.message.orEmpty().contains("增益图长度"))
    }

    @Test fun rejectsOppoWhenTheGainMapHasNoValidJpegBoundary() {
        val error = assertThrows(UnsupportedPhoto::class.java) {
            MotionJpeg.inspect(oppoSample(damagedGainMap = true))
        }
        assertTrue(error.message.orEmpty().contains("JPEG"))
    }

    @Test fun trimsOppoGainMapPaddingAndRewritesBothLengthRecords() {
        val padding = ByteArray(16)
        val gain = gainMapJpeg()
        val source = oppoSample(primarySizeDelta = 0, gainPadding = padding)
        val original = source.readBytes()
        val plan = MotionJpeg.inspect(source)
        val target = folder.newFile()
        MotionJpeg.writeStill(source, plan, target)

        val copy = target.readBytes()
        assertEquals(plan.videoStart - padding.size, target.length())
        assertArrayEquals(gain, copy.takeLast(gain.size).toByteArray())
        assertTrue(copy.decodeToString().contains("""I:Length="${gain.size}""""))
        val (sizeOffset, _) = plan.mpfSizePatches.single()
        assertEquals(gain.size, ByteBuffer.wrap(copy).getInt(sizeOffset.toInt()))
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun rejectsOtherBytesAfterTheGainMapJpeg() {
        assertThrows(UnsupportedPhoto::class.java) {
            MotionJpeg.inspect(oppoSample(gainPadding = ByteArray(16) { 1 }))
        }
        assertThrows(UnsupportedPhoto::class.java) {
            MotionJpeg.inspect(oppoSample(gainPadding = ByteArray(15)))
        }
        assertThrows(UnsupportedPhoto::class.java) {
            MotionJpeg.inspect(oppoSample(oppo = false, primarySizeDelta = 0, gainPadding = ByteArray(16)))
        }
    }

    @Test fun previewsEditedOppoVideoWithoutMakingItEligibleForExport() {
        val source = oppoSample(
            gainPadding = ByteArray(16),
            extraOppoFields = """O:MotionPhotoEnable="True" O:MotionPhotoEditorFlag="3"""",
        )
        val original = source.readBytes()
        val video = MotionJpeg.previewVideo(source)
        assertEquals(mp4().size.toLong(), video.length)
        assertArrayEquals(mp4(), original.copyOfRange(video.start.toInt(), (video.start + video.length).toInt()))
        assertThrows(UnsupportedPhoto::class.java) { MotionJpeg.inspect(source) }
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun keepsStrictPrimaryMpfSizeForStandardHdr() {
        val error = assertThrows(UnsupportedPhoto::class.java) {
            MotionJpeg.inspect(oppoSample(oppo = false))
        }
        assertTrue(error.message.orEmpty().contains("主图长度"))
        val valid = MotionJpeg.inspect(oppoSample(oppo = false, primarySizeDelta = 0))
        assertTrue(valid.hasGainMap)
        assertTrue(valid.mpfSizePatches.isEmpty())
    }

    @Test fun exportsVerifiedOppoEditorFlagTwoWithItsGainMap() {
        val source = oppoSample(extraOppoFields = """O:MotionPhotoEnable="True" O:MotionPhotoEditorFlag="2"""")
        val plan = MotionJpeg.inspect(source)
        val output = folder.newFile()
        MotionJpeg.writeStill(source, plan, output)
        assertTrue(plan.hasGainMap)
        assertFalse(output.readBytes().decodeToString().contains("MotionPhotoEditorFlag"))
        assertArrayEquals(gainMapJpeg(), output.readBytes().takeLast(gainMapJpeg().size).toByteArray())
    }

    @Test fun exportsVerifiedOppoEditorFlagsFourAndSixWithTheirGainMaps() {
        for (flag in listOf(4, 6)) {
            val source = oppoSample(extraOppoFields = """O:MotionPhotoEnable="True" O:MotionPhotoEditorFlag="$flag"""")
            val plan = MotionJpeg.inspect(source)
            val output = folder.newFile()
            MotionJpeg.writeStill(source, plan, output)
            assertTrue(plan.hasGainMap)
            assertFalse(output.readBytes().decodeToString().contains("MotionPhotoEditorFlag"))
            assertArrayEquals(gainMapJpeg(), output.readBytes().takeLast(gainMapJpeg().size).toByteArray())
        }
    }

    @Test fun preservesEmbeddedOriginalAndRemovesBothMotionDirectories() {
        val source = embeddedOriginalSample()
        val plan = MotionJpeg.inspect(source)
        val output = folder.newFile()
        MotionJpeg.writeStill(source, plan, output)
        val metadata = output.readBytes().decodeToString()
        assertTrue(metadata.contains("""I:Semantic="Original""""))
        assertTrue(metadata.contains("""O:hdrVersion="1.0""""))
        assertFalse(metadata.contains("MotionPhoto"))
        assertFalse(metadata.contains("VideoLength"))
        assertEquals(plan.videoStart, output.length())
        assertArrayEquals(gainMapJpeg(), output.readBytes().takeLast(gainMapJpeg().size).toByteArray())
        assertThrows(UnsupportedPhoto::class.java) { MotionJpeg.inspect(embeddedOriginalSample(originalOffset = 1)) }
    }

    @Test fun previewsDeclaredQuickTimeOnlyWhenItsBrandMatches() {
        val video = mp4("qt  ")
        val source = sample(directory = true, video = video, videoMime = "video/quicktime")
        val range = MotionJpeg.previewVideo(source)
        assertTrue(range.quickTime)
        assertEquals(video.size.toLong(), range.length)
        assertThrows(UnsupportedPhoto::class.java) {
            MotionJpeg.previewVideo(sample(directory = true, videoMime = "video/quicktime"))
        }
    }

    private fun embeddedOriginalSample(originalOffset: Int = 0): File {
        val video = mp4()
        val inner = oppoSample(addTrailer = false).readBytes().dropLast(video.size).toByteArray()
        val main = jpeg()
        val xmp = xmp("""
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description xmlns:G="http://ns.google.com/photos/1.0/camera/"
               xmlns:O="http://ns.oplus.com/photos/1.0/camera/"
               xmlns:C="http://ns.google.com/photos/1.0/container/"
               xmlns:I="http://ns.google.com/photos/1.0/container/item/"
               G:MotionPhoto="1" O:OLivePhotoVersion="2" O:hdrVersion="1.0" O:VideoLength="${video.size}">
                <C:Directory><rdf:Seq>
                  <rdf:li><C:Item I:Semantic="Primary" I:Mime="image/jpeg"/></rdf:li>
                  <rdf:li><C:Item I:Semantic="GainMap" I:Mime="image/png" I:Length="0"/></rdf:li>
                  <rdf:li><C:Item I:Semantic="Original" I:Mime="image/jpeg" I:Length="${inner.size}"/></rdf:li>
                  <rdf:li><C:Item I:Semantic="MotionPhoto" I:Mime="video/mp4" I:Length="${video.size}"/></rdf:li>
                </rdf:Seq></C:Directory>
              </rdf:Description>
            </rdf:RDF>
            ${" ".repeat(256)}
        """.trimIndent())
        val tiffSize = 86
        val primaryLength = main.size + xmp.size + 8 + tiffSize
        val originalRelative = primaryLength - (2 + xmp.size + 8)
        val tiff = ByteBuffer.allocate(tiffSize).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(0x4d4d).putShort(42).putInt(8).putShort(2)
            putShort(0xb001.toShort()).putShort(4).putInt(1).putInt(3)
            putShort(0xb002.toShort()).putShort(7).putInt(48).putInt(38)
            putInt(0)
            putInt(0x30000).putInt(primaryLength - 10).putInt(0).putInt(0)
            putInt(0).putInt(0).putInt(originalRelative).putInt(0)
            putInt(0).putInt(inner.size).putInt(originalRelative + originalOffset).putInt(0)
        }.array()
        val mpf = segment(0xe2, "MPF\u0000".toByteArray() + tiff)
        return folder.newFile().apply {
            writeBytes(main.copyOfRange(0, 2) + xmp + mpf + main.copyOfRange(2, main.size) + inner + video)
        }
    }

    private fun oppoSample(
        gainOffsetAdjustment: Int = 0,
        primarySizeDelta: Int = 730,
        gainSizeAdjustment: Int = 0,
        damagedGainMap: Boolean = false,
        oppo: Boolean = true,
        byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
        gainPadding: ByteArray = byteArrayOf(),
        extraOppoFields: String = "",
        addTrailer: Boolean = true,
    ): File {
        val jpeg = jpeg()
        val gain = gainMapJpeg()
        if (damagedGainMap) gain[gain.lastIndex] = 0
        val gainSize = gain.size + gainPadding.size
        val video = mp4()
        val trailer = if (oppo && addTrailer) byteArrayOf(1, 2, 3) else byteArrayOf()
        val oppoFields = if (oppo) """O:OLivePhotoVersion="2" O:VideoLength="${video.size}"""" else ""
        val mainXmp = xmp("""
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description xmlns:G="http://ns.google.com/photos/1.0/camera/"
               xmlns:O="http://ns.oplus.com/photos/1.0/camera/"
               xmlns:h="http://ns.adobe.com/hdr-gain-map/1.0/"
               xmlns:C="http://ns.google.com/photos/1.0/container/"
               xmlns:I="http://ns.google.com/photos/1.0/container/item/"
               G:MotionPhoto="1" $oppoFields $extraOppoFields h:Version="1.0">
               <C:Directory><rdf:Seq>
                <rdf:li><C:Item I:Semantic="Primary" I:Mime="image/jpeg"/></rdf:li>
                <rdf:li><C:Item I:Semantic="GainMap" I:Mime="image/jpeg" I:Length="$gainSize"/></rdf:li>
                <rdf:li><C:Item I:Semantic="MotionPhoto" I:Mime="video/mp4" I:Length="${video.size + trailer.size}"/></rdf:li>
               </rdf:Seq></C:Directory>
              </rdf:Description>
            </rdf:RDF>
            ${" ".repeat(256)}
        """.trimIndent())
        val tiffSize = 70
        val mpfSize = 8 + tiffSize
        val imageSize = jpeg.size + mainXmp.size + mpfSize
        val tiffOffset = 2 + mainXmp.size + 8
        val tiff = ByteBuffer.allocate(tiffSize).order(byteOrder).apply {
            putShort(if (byteOrder == ByteOrder.BIG_ENDIAN) 0x4d4d else 0x4949)
            putShort(42)
            putInt(8)
            putShort(2)
            putShort(0xb001.toShort()).putShort(4).putInt(1).putInt(2)
            putShort(0xb002.toShort()).putShort(7).putInt(32).putInt(38)
            putInt(0)
            putInt(0).putInt(imageSize - primarySizeDelta).putInt(0).putInt(0)
            putInt(0).putInt(gainSize + gainSizeAdjustment)
                .putInt(imageSize - tiffOffset + gainOffsetAdjustment).putInt(0)
        }.array()
        val mpf = segment(0xe2, "MPF\u0000".toByteArray() + tiff)
        return folder.newFile().apply {
            writeBytes(jpeg.copyOfRange(0, 2) + mainXmp + mpf + jpeg.copyOfRange(2, jpeg.size) +
                gain + gainPadding + video + trailer)
        }
    }

    private fun gainMapJpeg(): ByteArray {
        val jpeg = jpeg()
        return jpeg.copyOfRange(0, 2) +
            xmp("""<extra xmlns:h="http://ns.adobe.com/hdr-gain-map/1.0/" h:Version="1.0"/>""") +
            jpeg.copyOfRange(2, jpeg.size)
    }

    private fun xmp(xml: String): ByteArray =
        segment(0xe1, ("http://ns.adobe.com/xap/1.0/\u0000" + xml).toByteArray())

    @Test fun galleryProbeRecognizesMotionWithoutReadingTheVideo() {
        for (directory in listOf(true, false)) {
            val photo = sample(directory)
            val probe = photo.inputStream().use { MotionHeader.probe(it, photo.length()) }
            assertEquals(mp4().size.toLong(), probe?.estimatedBytes)
        }
        val source = oppoSample()
        assertEquals(mp4().size.toLong() + 3, source.inputStream().use {
            MotionHeader.probe(it, source.length())
        }?.estimatedBytes)
        assertEquals(null, jpeg().inputStream().use { MotionHeader.probe(it, jpeg().size.toLong()) })
    }

    @Test fun oppoQuickTimeWithSingleImageMpfKeepsJpegBytesAndCorrectsSize() {
        for (delta in listOf(0, 27)) {
            val source = oppoQuickTimeSample(delta)
            val target = folder.newFile()
            val plan = MotionJpeg.inspect(source)
            MotionJpeg.writeStill(source, plan, target)
            val bytes = target.readBytes()
            assertEquals(plan.videoStart, target.length())
            val sizeOffset = plan.mpfSizePatches.single().first.toInt()
            assertEquals(bytes.size, ByteBuffer.wrap(bytes).getInt(sizeOffset))
            assertArrayEquals(source.readBytes().copyOfRange(sizeOffset + 4, bytes.size),
                bytes.copyOfRange(sizeOffset + 4, bytes.size))
            assertFalse(bytes.decodeToString().contains("OLivePhotoVersion"))
        }
        assertThrows(UnsupportedPhoto::class.java) { MotionJpeg.inspect(oppoQuickTimeSample(26)) }
        assertThrows(UnsupportedPhoto::class.java) { MotionJpeg.inspect(oppoQuickTimeSample(27, includeMpf = false)) }
    }

    private fun oppoQuickTimeSample(sizeDelta: Int, includeMpf: Boolean = true): File {
        val jpeg = jpeg()
        val video = mp4("qt  ")
        val metadata = xmp("""
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description xmlns:G="http://ns.google.com/photos/1.0/camera/"
                xmlns:O="http://ns.oplus.com/photos/1.0/camera/"
                xmlns:C="http://ns.google.com/photos/1.0/container/"
                xmlns:I="http://ns.google.com/photos/1.0/container/item/"
                G:MotionPhoto="1" O:MotionPhotoOwner="oplus" O:OLivePhotoVersion="2" O:VideoLength="${video.size}">
                <C:Directory><rdf:Seq>
                  <rdf:li><C:Item I:Semantic="Primary" I:Mime="image/jpeg"/></rdf:li>
                  <rdf:li><C:Item I:Semantic="MotionPhoto" I:Mime="video/quicktime" I:Length="${video.size}"/></rdf:li>
                </rdf:Seq></C:Directory>
              </rdf:Description>
            </rdf:RDF>
            ${" ".repeat(256)}
        """.trimIndent())
        val tiffSize = 54
        val imageSize = jpeg.size + metadata.size + 8 + tiffSize
        val tiff = ByteBuffer.allocate(tiffSize).apply {
            putShort(0x4d4d).putShort(42).putInt(8)
            putShort(2)
            putShort(0xb001.toShort()).putShort(4).putInt(1).putInt(1)
            putShort(0xb002.toShort()).putShort(7).putInt(16).putInt(38)
            putInt(0)
            putInt(0x030000).putInt(imageSize - sizeDelta).putInt(0).putInt(0)
        }.array()
        val mpf = if (includeMpf) segment(0xe2, "MPF\u0000".toByteArray() + tiff) else byteArrayOf()
        return folder.newFile().apply {
            writeBytes(jpeg.copyOfRange(0, 2) + metadata + mpf + jpeg.copyOfRange(2, jpeg.size) + video)
        }
    }

    private fun sample(
        directory: Boolean,
        lengthAdjustment: Int = 0,
        extra: ByteArray = byteArrayOf(),
        video: ByteArray = mp4(),
        videoMime: String = "video/mp4",
    ): File {
        val length = video.size + lengthAdjustment
        val flag = if (directory) """GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" """
        else """GCamera:MicroVideo="1" GCamera:MicroVideoOffset="$length" """
        val container = if (directory) """
            <Container:Directory><rdf:Seq>
              <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary"/></rdf:li>
              <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="$videoMime" Item:Semantic="MotionPhoto" Item:Length="$length"/></rdf:li>
            </rdf:Seq></Container:Directory>
        """.trimIndent() else ""
        val xml = """
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description $flag
               xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
               xmlns:Container="http://ns.google.com/photos/1.0/container/"
               xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
               xmlns:test="https://example.org/test/" test:caption="test caption">
               $container
              </rdf:Description>
            </rdf:RDF>
        """.trimIndent()
        val encoded = jpeg()
        val xmp = segment(0xe1, ("http://ns.adobe.com/xap/1.0/\u0000" + xml).toByteArray())
        return folder.newFile().apply {
            writeBytes(encoded.copyOfRange(0, 2) + xmp + extra + encoded.copyOfRange(2, encoded.size) + video)
        }
    }

    private fun jpeg(): ByteArray = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjI4LjEwMgD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABMAAEBAAAAAAAAAAAAAAAAAAAABwEBAQAAAAAAAAAAAAAAAAAABQcQAQAAAAAAAAAAAAAAAAAAAAARAQAAAAAAAAAAAAAAAAAAAAD/wAARCAAEAAQDASIAAhEAAxEA/9oADAMBAAIRAxEAPwCOAL+Kf//Z"
    )

    // Synthetic boxes exercise the structural parser. Android verifies the actual video track separately.
    private fun mp4(brand: String = "mp42"): ByteArray = ByteArrayOutputStream().apply {
        val output = DataOutputStream(this)
        listOf("ftyp" to (brand.toByteArray() + ByteArray(4)), "moov" to byteArrayOf(1), "mdat" to byteArrayOf(2)).forEach { (name, data) ->
            output.writeInt(8 + data.size)
            output.writeBytes(name)
            output.write(data)
        }
    }.toByteArray()

    private fun segment(marker: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        val output = DataOutputStream(this)
        output.writeShort(0xff00 or marker)
        output.writeShort(payload.size + 2)
        output.write(payload)
    }.toByteArray()
}
