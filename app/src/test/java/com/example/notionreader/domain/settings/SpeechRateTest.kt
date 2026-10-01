package com.example.notionreader.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechRateTest {

    @Test
    fun systemDefaultFormattingAndFactor() {
        val rate = SpeechRate.SystemDefault
        assertEquals("System default", rate.label)
        assertEquals(1.0f, rate.factor, 0.001f)
        assertEquals("SYSTEM_DEFAULT", rate.name)
    }

    @Test
    fun multiplierFormattingAndFactor() {
        val r1 = SpeechRate.Multiplier(1.0f)
        assertEquals("1.0×", r1.label)
        assertEquals(1.0f, r1.factor, 0.001f)

        val r125 = SpeechRate.Multiplier(1.25f)
        assertEquals("1.25×", r125.label)
        assertEquals(1.25f, r125.factor, 0.001f)

        val r075 = SpeechRate.Multiplier(0.75f)
        assertEquals("0.75×", r075.label)
        assertEquals(0.75f, r075.factor, 0.001f)

        val r15 = SpeechRate.Multiplier(1.5f)
        assertEquals("1.5×", r15.label)
        assertEquals(1.5f, r15.factor, 0.001f)
    }

    @Test
    fun parseLegacyEnumNames() {
        assertEquals(SpeechRate.SystemDefault, SpeechRate.parse("SYSTEM_DEFAULT"))
        assertEquals(SpeechRate.Multiplier(0.75f), SpeechRate.parse("RATE_0_75"))
        assertEquals(SpeechRate.Multiplier(1.0f), SpeechRate.parse("RATE_1_0"))
        assertEquals(SpeechRate.Multiplier(1.25f), SpeechRate.parse("RATE_1_25"))
        assertEquals(SpeechRate.Multiplier(1.5f), SpeechRate.parse("RATE_1_5"))
        assertEquals(SpeechRate.Multiplier(2.0f), SpeechRate.parse("RATE_2_0"))
    }

    @Test
    fun parseArbitraryMultiplierString() {
        assertEquals(SpeechRate.Multiplier(1.35f), SpeechRate.parse("RATE_1.35"))
        assertEquals(SpeechRate.Multiplier(0.85f), SpeechRate.parse("RATE_0.85"))
    }

    @Test
    fun serializeRoundTrip() {
        val original = SpeechRate.Multiplier(1.15f)
        val serialized = SpeechRate.serialize(original)
        val deserialized = SpeechRate.parse(serialized)
        assertEquals(original, deserialized)

        val defaultSerialized = SpeechRate.serialize(SpeechRate.SystemDefault)
        assertEquals(SpeechRate.SystemDefault, SpeechRate.parse(defaultSerialized))
    }

    @Test
    fun valuesAndPredefinedConstants() {
        val values = SpeechRate.values()
        assertTrue(values.contains(SpeechRate.SYSTEM_DEFAULT))
        assertTrue(values.contains(SpeechRate.RATE_1_25))
    }
}
