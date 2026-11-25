package com.simplexray.re.data.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileManagerTest {

    @Test
    fun testIsStandardGeoDatCaseInsensitive() {
        assertTrue(FileManager.isStandardGeoDat("geoip.dat"))
        assertTrue(FileManager.isStandardGeoDat("geosite.dat"))
        assertTrue(FileManager.isStandardGeoDat("Geoip.dat"))
        assertTrue(FileManager.isStandardGeoDat("GEOIP.DAT"))
        assertTrue(FileManager.isStandardGeoDat("GeoSite.Dat"))
    }

    @Test
    fun testIsStandardGeoDatRejectsThirdPartyNames() {
        assertFalse(FileManager.isStandardGeoDat("custom.dat"))
        assertFalse(FileManager.isStandardGeoDat("geoip2.dat"))
        assertFalse(FileManager.isStandardGeoDat("geosite-cn.dat"))
        assertFalse(FileManager.isStandardGeoDat(""))
    }

    @Test
    fun testSanitizeFileName() {
        org.junit.Assert.assertEquals("routing.lua", FileUriHelper.sanitizeFileName("routing.lua", "fallback"))
        org.junit.Assert.assertEquals("routing.lua", FileUriHelper.sanitizeFileName("/storage/emulated/0/Download/routing.lua", "fallback"))
        org.junit.Assert.assertEquals("routing.lua", FileUriHelper.sanitizeFileName("..\\routing.lua", "fallback"))
        org.junit.Assert.assertEquals("fallback", FileUriHelper.sanitizeFileName("..", "fallback"))
        org.junit.Assert.assertEquals("fallback", FileUriHelper.sanitizeFileName("", "fallback"))
    }

}
