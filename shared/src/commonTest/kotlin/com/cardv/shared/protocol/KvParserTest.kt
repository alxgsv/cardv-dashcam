package com.cardv.shared.protocol

import com.cardv.shared.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KvParserTest {
    @Test
    fun parsesResultMessageAndValues() {
        val document = KvParser.parse(Fixtures.KV_CAMERA_DB)
        assertEquals(ResultCode.OK, document.result)
        assertEquals("OK", document.message)
        assertEquals("54+125", document.value("Camera.DBFileNum"))
        assertEquals("2022/7/20 21/12/49", document.value("Camera.TimeDate"))
        assertEquals(3, document.values.size)
    }

    @Test
    fun keepsNonZeroResultCode() {
        val document = KvParser.parse(Fixtures.KV_UNKNOWN_PROPERTY)
        assertEquals(ResultCode.UNKNOWN_PROPERTY, document.result)
        assertEquals("Unknown CGI-PARAM: Property", document.message)
        assertTrue(document.values.isEmpty())
    }

    @Test
    fun acceptsCrlfAndValuesContainingEquals() {
        val document = KvParser.parse("0\r\nOK\r\nKey=a=b\r\nEmpty=\r\n")
        assertEquals("a=b", document.value("Key"))
        assertEquals("", document.value("Empty"))
    }

    @Test
    fun rejectsBodiesThatAreNotCommandResponses() {
        assertFailsWith<MalformedResponseException> {
            KvParser.parse(Fixtures.DIR_DCIM)
        }
    }

    @Test
    fun resultCodeKeepsUnknownCodes() {
        val document = KvParser.parse("799\nSome new failure\n")
        assertEquals(799, document.result.code)
        assertEquals("UNKNOWN", ResultCode.nameOf(799))
        assertNull(document.value("Anything"))
    }
}
