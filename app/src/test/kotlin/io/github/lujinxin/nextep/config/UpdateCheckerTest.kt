package io.github.lujinxin.nextep.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun workbenchTagsOrderAfterTheirBaseVersionAndAmongThemselves() {
        val base = UpdateChecker.versionParts("v1.3.0")!!
        val w9 = UpdateChecker.versionParts("v1.3.0-workbench.9")!!
        val w29 = UpdateChecker.versionParts("1.3.0-workbench.29")!!
        assertEquals(listOf(1L, 3L, 0L, 0L), base)
        assertTrue(UpdateChecker.compareVersions(w9, base) > 0)
        assertTrue(UpdateChecker.compareVersions(w29, w9) > 0)
        assertTrue(UpdateChecker.compareVersions(UpdateChecker.versionParts("v1.4.0")!!, w29) > 0)
        assertEquals(0, UpdateChecker.compareVersions(w29, UpdateChecker.versionParts("v1.3.0-workbench.29")!!))
        assertNull(UpdateChecker.versionParts("v1.3.0-corner.2"))
        assertNull(UpdateChecker.versionParts("latest"))
    }
}
