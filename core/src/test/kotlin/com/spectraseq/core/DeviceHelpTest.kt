package com.spectraseq.core

import com.spectraseq.core.model.DeviceHelp
import com.spectraseq.core.model.EffectType
import com.spectraseq.core.model.ModKind
import com.spectraseq.core.model.NodeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHelpTest {
    @Test
    fun everyNodeExplainsEachInputAndKnob() {
        for (k in NodeKind.entries) {
            val h = DeviceHelp.node(k)
            assertTrue("$k", h.summary.isNotBlank() && h.tip.isNotBlank())
            assertEquals("inputs of $k", k.inputs.size, h.inputs.size)
            assertEquals("params of $k", k.params.size, h.params.size)
        }
    }

    @Test
    fun everyEffectAndModulatorHasADescription() {
        for (t in EffectType.entries) assertTrue("$t", DeviceHelp.effect(t).isNotBlank())
        for (m in ModKind.entries) assertTrue("$m", DeviceHelp.modulator(m).isNotBlank())
    }
}
