package com.neirofm

import com.neirofm.data.RadioScript
import com.neirofm.data.ScriptLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptLibraryClassificationTest {
    @Test fun allPackPrefixesAreRecognized() {
        assertEquals(RadioScript.Type.PODCAST, ScriptLibrary.classify("podcast_tishina.txt"))
        assertEquals(RadioScript.Type.ADVERTISEMENT, ScriptLibrary.classify("reklama_neirofm.txt"))
        assertEquals(RadioScript.Type.WEATHER, ScriptLibrary.classify("pogoda_general.txt"))
        assertEquals(RadioScript.Type.MORNING, ScriptLibrary.classify("utro_01.txt"))
        assertEquals(RadioScript.Type.NIGHT, ScriptLibrary.classify("noch_01.txt"))
        assertEquals(RadioScript.Type.GREETING, ScriptLibrary.classify("greeting_01.txt"))
        assertEquals(RadioScript.Type.TIME, ScriptLibrary.classify("time_00.txt"))
        assertEquals(RadioScript.Type.TIME, ScriptLibrary.classify("time_alt_4.txt"))
    }

    @Test fun classificationIgnoresDirectoryPrefixAndCase() {
        assertEquals(RadioScript.Type.PODCAST, ScriptLibrary.classify("nested/PODCAST_example.TXT"))
        assertEquals(RadioScript.Type.TIME, ScriptLibrary.classify("folder/time_23.txt"))
    }
}
