package com.mechrobotix.aprendels

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

/** Ejecutar en un teléfono/emulador: incluye decodificador Android, MediaPipe y caché real. */
class WordAutoReferenceTest {
    @Test fun cacheReplacementCorruptionAndCancellation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(context.noBackupFilesDir,"word_references_auto")
        val name="test_auto_cache"
        val other="test_auto_untouched"
        val cache=File(folder,"$name.json")
        val untouched=File(folder,"$other.json")
        val lesson=WordLesson(name,"Prueba",name,R.raw.s_hola,WordTolerance())
        fun clear() {listOf(name,other).forEach{n->listOf(".json",".json.bak",".json.new").forEach{File(folder,n+it).delete()}}}
        clear()
        try {
            var progress=0
            val first=WordReferenceStore.loadOrGenerate(context,lesson,onProgress={progress++})
            assertTrue(progress>0);assertTrue(first.keys.size>=4)
            val oldBytes=cache.readBytes()
            val cached=WordReferenceStore.loadOrGenerate(context,lesson,onProgress={fail("Se regeneró sin cambiar el video")})
            assertEquals(first.startMs,cached.startMs);assertArrayEquals(oldBytes,cache.readBytes())
            WordReferenceStore.loadOrGenerate(context,lesson.copy(videoName=other))
            val otherBytes=untouched.readBytes()
            val replaced=lesson.copy(video=R.raw.s_yo_bien) // Mismo nombre, contenido distinto.
            progress=0
            WordReferenceStore.loadOrGenerate(context,replaced,onProgress={progress++})
            assertTrue(progress>0);assertFalse(oldBytes.contentEquals(cache.readBytes()))
            assertArrayEquals(otherBytes,untouched.readBytes())
            val validBytes=cache.readBytes()
            try {
                var cancel=false
                WordReferenceStore.loadOrGenerate(context,lesson,cancelled={cancel},onProgress={cancel=true})
                fail("La generación no se canceló")
            }catch(_:CancellationException){}
            assertArrayEquals(validBytes,cache.readBytes())
            cache.writeText("JSON incompleto")
            progress=0
            WordReferenceStore.loadOrGenerate(context,replaced,onProgress={progress++})
            assertTrue(progress>0)
        }finally{clear()}
    }
}
