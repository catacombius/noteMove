package com.spectraseq.core

import com.spectraseq.core.export.ProjectJson
import com.spectraseq.core.model.SetTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatesTest {
    @Test
    fun everyTemplateMakesALoadableSet() {
        for (t in SetTemplate.entries) {
            val p = t.create("T ${t.label}")
            assertTrue(t.label, p.tracks.isNotEmpty())
            assertEquals(t.label, p.tracks.size, p.tracks.map { it.id }.toSet().size)
            assertEquals(p, ProjectJson.decode(ProjectJson.encode(p)))
        }
    }
}
