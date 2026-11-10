package org.thortracker.storage

import org.junit.Assert.fail
import org.junit.Test
import java.io.FileNotFoundException

class PreviewDocumentsTest {
    @Test fun rejectsNamesThatCouldEscapeOrReplaceTheRoot() {
        for (name in listOf("", " ", ".", "..", "preview", "../song.thor", "/sdcard/ThorTunez/song.thor", "nested/song.thor", "..\\song.thor", "bad\u0000.thor")) {
            try {
                PreviewDocuments.validateName(name)
                fail("Accepted unsafe preview document name: $name")
            } catch (_: FileNotFoundException) { }
        }
    }

    @Test fun acceptsRealProjectExportAndImportedScaleNames() {
        for (name in listOf("My song.thor", "My song (2).wav", "19-tone.scl", "scales.thorscales", "controls.json"))
            PreviewDocuments.validateName(name)
    }
}
