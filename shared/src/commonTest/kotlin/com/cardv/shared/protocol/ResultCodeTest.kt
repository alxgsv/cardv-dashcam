package com.cardv.shared.protocol

import com.cardv.shared.model.Folder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultCodeTest {
    @Test
    fun knownCodes() {
        assertTrue(ResultCode.OK.isSuccess)
        assertFalse(ResultCode.REFUSED.isSuccess)
        assertTrue(ResultCode.REFUSED.isStateRefusal)
        assertEquals("REFUSED (709)", ResultCode.REFUSED.toString())
        assertEquals(ResultCode.UNKNOWN_ACTION, ResultCode.from(702))
        assertEquals(703, ResultCode.from(703).code)
    }

    @Test
    fun unknownCodeIsPreserved() {
        assertEquals(777, ResultCode.from(777).code)
        assertEquals("UNKNOWN", ResultCode.nameOf(777))
        assertFalse(ResultCode.from(777).isSuccess)
    }
}

class FolderTest {
    @Test
    fun folderNamesAreCaseExact() {
        assertEquals("DCIM", Folder.DCIM.property)
        assertEquals("SOS", Folder.SOS.property)
        assertEquals("LOCK", Folder.LOCK.property)
        assertEquals("Photo", Folder.PHOTO.property)
        assertEquals(Folder.DCIM, Folder.DEFAULT)
    }

    @Test
    fun wrongCaseIsNotAccepted() {
        assertNull(Folder.fromProperty("Lock"))
        assertNull(Folder.fromProperty("Sos"))
        assertNull(Folder.fromProperty("PHOTO"))
        assertEquals(Folder.LOCK, Folder.fromProperty("LOCK"))
        assertEquals(Folder.PHOTO, Folder.fromProperty("Photo"))
    }

    @Test
    fun directoriesMatchProtocol() {
        assertEquals("/SD/MOVIE", Folder.DCIM.directory)
        assertEquals("/SD/SOS", Folder.SOS.directory)
        assertEquals("/SD/LOCK", Folder.LOCK.directory)
        assertEquals("/SD/PHOTO", Folder.PHOTO.directory)
        assertTrue(Folder.LOCK.isVideo)
        assertFalse(Folder.PHOTO.isVideo)
    }
}
