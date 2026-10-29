package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.sortkey.SortKeyText
import org.sunsetware.phocid.sortkey.SortKeyValue

class SortKeyTextTest {
    @Test
    fun parse_normalizesGenericText() {
        assertThat(SortKeyText.parse("  Ａ   b  ")).isEqualTo(SortKeyValue.Value("A b"))
    }

    @Test
    fun parse_nullOrBlank_clears() {
        assertThat(SortKeyText.parse(null)).isEqualTo(SortKeyValue.Clear)
        assertThat(SortKeyText.parse("   ")).isEqualTo(SortKeyValue.Clear)
    }

    @Test
    fun parse_controlCharacter_invalid() {
        assertThat(SortKeyText.parse("a\nb")).isInstanceOf(SortKeyValue.Invalid::class.java)
    }

    @Test
    fun parse_tooLong_invalid() {
        assertThat(SortKeyText.parse("a".repeat(SortKeyText.MAX_LENGTH + 1)))
            .isInstanceOf(SortKeyValue.Invalid::class.java)
    }

    @Test
    fun parse_maxLength_ok() {
        val value = "a".repeat(SortKeyText.MAX_LENGTH)
        assertThat(SortKeyText.parse(value)).isEqualTo(SortKeyValue.Value(value))
    }
}
