package com.neirofm

import com.neirofm.data.PresenterScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PresenterScriptsTest {
    @Test fun podcastGetsPresenterIntroduction() {
        val result = PresenterScripts.introFor("PODCAST|2|История музыки")
        assertEquals("maria", result?.first)
        assertEquals(true, result?.second?.contains("История музыки"))
    }

    @Test fun advertisementGetsPresenterIntroduction() {
        val result = PresenterScripts.introFor("ADVERTISEMENT|3|Специальное предложение")
        assertEquals("alex", result?.first)
        assertEquals(true, result?.second?.contains("Специальное предложение"))
    }

    @Test fun musicDoesNotGetUnwantedIntroduction() {
        assertNull(PresenterScripts.introFor("MUSIC|0|Track"))
    }
}
