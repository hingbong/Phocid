package org.sunsetware.phocid

import android.icu.lang.UCharacter
import java.util.Locale
import kotlin.streams.toList
import org.assertj.core.api.Assertions.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.utils.initialLetter

@RunWith(RobolectricTestRunner::class)
class InitialismTest {
    @Test
    fun testInitialLetter() {
        assertThat("".initialLetter(Locale.ROOT)).isEqualTo("#")
        assertThat(" ABC".initialLetter(Locale.ROOT)).isEqualTo("#")
        assertThat(".ABC".initialLetter(Locale.ROOT)).isEqualTo("#")
        assertThat("123".initialLetter(Locale.ROOT)).isEqualTo("#")
        assertThat("😄".initialLetter(Locale.ROOT)).isEqualTo("#")
        assertThat("ABC".initialLetter(Locale.ROOT)).isEqualTo("A")
        assertThat("abc".initialLetter(Locale.ROOT)).isEqualTo("A")
        assertThat("àbĆ".initialLetter(Locale.ROOT)).isEqualTo("À")

        // System ICU versions differ in available transliterators. The exact initial is
        // intentionally not asserted for Han; the fallback must still be non-empty.
        assertThat("汉字".initialLetter(Locale.CHINESE)).isNotEmpty()
        assertThat("汉字".initialLetter(Locale.SIMPLIFIED_CHINESE)).isNotEmpty()
        assertThat("漢字".initialLetter(Locale.CHINESE)).isNotEmpty()
        assertThat("\uD883\uDEDD".initialLetter(Locale.CHINESE)).isNotEmpty()
        assertThat("漢字".initialLetter(Locale.TRADITIONAL_CHINESE)).isEqualTo("漢")
        assertThat("漢字".initialLetter(Locale.JAPANESE)).isEqualTo("漢")

        val kana =
            "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわゐゑをん".codePoints().toList().joinToString(
                ""
            ) {
                UCharacter.toString(it).initialLetter(Locale.ROOT)
            }
        assertThat(kana).isNotEmpty()
        assertThat(kana).doesNotContain("#")
    }
}
