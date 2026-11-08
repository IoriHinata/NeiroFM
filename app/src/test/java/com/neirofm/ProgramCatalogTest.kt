package com.neirofm

import com.neirofm.data.ProgramCatalog
import com.neirofm.data.ScriptLibrary
import com.neirofm.data.RadioScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramCatalogTest {
 @Test fun selectsCorrectDayAndNightShows() {
  assertEquals("Neiro Утро", ProgramCatalog.current(8).name)
  assertEquals("Дневная волна", ProgramCatalog.current(13).name)
  assertEquals("Вечерний маршрут", ProgramCatalog.current(19).name)
  assertEquals("После полуночи", ProgramCatalog.current(2).name)
 }
 @Test fun generatedPodcastIsSubstantialAndHasPresenter() {
  val text=ProgramCatalog.createPodcast(ProgramCatalog.current(23))
  assertTrue(text.length > 700)
  assertTrue(text.contains("Макс"))
 }
 @Test fun recognizesImportedTextKindsFromFilename() {
  assertEquals(RadioScript.Type.PODCAST, ScriptLibrary.classify("podcast_space.txt"))
  assertEquals(RadioScript.Type.ADVERTISEMENT, ScriptLibrary.classify("реклама_лето.txt"))
  assertEquals(RadioScript.Type.WEATHER, ScriptLibrary.classify("pogoda_morning.txt"))
  assertEquals(RadioScript.Type.MORNING, ScriptLibrary.classify("utro_show.txt"))
 }
}
