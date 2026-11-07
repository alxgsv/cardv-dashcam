package com.cardv.shared.protocol

import com.cardv.shared.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DirXmlParserTest {
    @Test
    fun parsesProtocolFixture() {
        val listing = DirXmlParser.parse(Fixtures.DIR_DCIM)
        assertEquals("DCIM", listing.rootTag)
        assertEquals(2, listing.amount)
        assertEquals(2, listing.clips.size)

        val first = listing.clips.first()
        assertEquals("/SD/MOVIE/20220707_162655_002088.MP4", first.path)
        assertEquals("20220707_162655_002088.MP4", first.name)
        assertEquals("/SD/MOVIE", first.directory)
        assertEquals(489_291_776L, first.sizeBytes)
        assertEquals("MP4", first.format)
        assertEquals("1920x1080", first.resolution)
        assertEquals(30, first.fps)
        assertEquals(300.2, first.durationSeconds)
        assertEquals("RW", first.attribute)
        assertEquals(2022, first.timestamp?.year)
        assertEquals(7, first.timestamp?.monthNumber)
        assertEquals(7, first.timestamp?.dayOfMonth)
        assertEquals(16, first.timestamp?.hour)
        assertEquals(26, first.timestamp?.minute)
        assertEquals(55, first.timestamp?.second)
    }

    @Test
    fun parsesSosAndPhoto() {
        val sos = DirXmlParser.parse(Fixtures.DIR_SOS)
        assertEquals("SOS", sos.rootTag)
        assertEquals(1, sos.amount)
        assertEquals(49_152_000L, sos.clips.single().sizeBytes)

        val photo = DirXmlParser.parse(Fixtures.DIR_PHOTO)
        assertEquals("Photo", photo.rootTag)
        assertEquals("JPG", photo.clips.single().format)
        assertEquals(false, photo.clips.single().isVideo)
    }

    @Test
    fun parsesEmptyFolder() {
        val listing = DirXmlParser.parse(Fixtures.DIR_EMPTY)
        assertEquals(0, listing.amount)
        assertEquals(0, listing.clips.size)
    }

    @Test
    fun keepsRequestedRootTagForFallbackList() {
        // The camera returns the loop list for `Lock`, but echoes the requested root tag.
        val listing = DirXmlParser.parse(Fixtures.DIR_LOCK_FALLBACK)
        assertEquals("Lock", listing.rootTag)
        assertEquals("/SD/MOVIE/20220707_162655_002088.MP4", listing.clips.single().path)
    }

    @Test
    fun decodesEntitiesAndToleratesMissingFormat() {
        val listing = DirXmlParser.parse(
            """<DCIM><file><name>/SD/MOVIE/a&amp;b.MP4</name><size>1</size><time>2022-07-07 16:26:55</time></file><amount>1</amount></DCIM>""",
        )
        val clip = listing.clips.single()
        assertEquals("/SD/MOVIE/a&b.MP4", clip.path)
        assertNull(clip.format)
        assertNotNull(clip.timestamp)
    }

    @Test
    fun rejectsEmptyAndNonXmlBodies() {
        assertFailsWith<MalformedResponseException> { DirXmlParser.parse("") }
        assertFailsWith<MalformedResponseException> { DirXmlParser.parse("703\nUnknown") }
    }
}
