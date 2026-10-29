package org.sunsetware.phocid

import android.icu.lang.UCharacter
import android.icu.text.Collator
import android.icu.text.Normalizer2
import android.icu.text.RuleBasedCollator
import android.icu.text.Transliterator
import java.util.Locale
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AndroidIcuSmokeTest {
    @Test
    fun collatorAndNormalizer_available() {
        val collator = Collator.getInstance(Locale.ENGLISH)
        collator.strength = Collator.PRIMARY
        (collator as RuleBasedCollator).numericCollation = true
        assertThat(collator.compare("a", "A")).isEqualTo(0)
        assertThat(Normalizer2.getNFCInstance().normalize("e\u0301")).isEqualTo("é")
        assertThat(UCharacter.toString(0x41)).isEqualTo("A")
    }

    @Test
    fun transliterators_doNotCrashWhenUnavailable() {
        runCatching { Transliterator.getInstance("Any-Latin") }
        runCatching { Transliterator.getInstance("Han-Latin") }
    }
}
