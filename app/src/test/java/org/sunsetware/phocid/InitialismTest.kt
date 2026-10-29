package org.sunsetware.phocid

import android.icu.lang.UCharacter
import android.icu.text.Transliterator
import java.util.Locale
import kotlin.streams.toList
import org.assertj.core.api.Assertions.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.utils.initialLetter

@RunWith(RobolectricTestRunner::class)
class InitialismTest {
    private fun available(id: String, sample: String): Boolean =
        runCatching { Transliterator.getInstance(id).transliterate(sample) }.isSuccess

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

        val hanAvailable = available("Han-Latin; NFD; [:Mark:] Remove; NFC; Lower", "汉")
        if (hanAvailable) {
            assertThat("汉字".initialLetter(Locale.CHINESE)).isEqualTo("h")
            assertThat("汉字".initialLetter(Locale.SIMPLIFIED_CHINESE)).isEqualTo("h")
            assertThat("漢字".initialLetter(Locale.CHINESE)).isEqualTo("h")
            assertThat("\uD883\uDEDD".initialLetter(Locale.CHINESE)).isEqualTo("b")
        } else {
            assertThat("汉字".initialLetter(Locale.CHINESE)).isEqualTo("汉")
            assertThat("汉字".initialLetter(Locale.SIMPLIFIED_CHINESE)).isEqualTo("汉")
            assertThat("漢字".initialLetter(Locale.CHINESE)).isEqualTo("漢")
            assertThat("\uD883\uDEDD".initialLetter(Locale.CHINESE)).isEqualTo("\uD883\uDEDD")
        }
        assertThat("漢字".initialLetter(Locale.TRADITIONAL_CHINESE)).isEqualTo("漢")
        assertThat("漢字".initialLetter(Locale.JAPANESE)).isEqualTo("漢")

        if (available("NFD; [:Mark:] Remove; NFC; Any-Latin; [:^Letter:] Remove; Lower", "あ")) {
            assertThat(
                    "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわゐゑをん"
                        .codePoints()
                        .toList()
                        .joinToString("") { UCharacter.toString(it).initialLetter(Locale.ROOT) }
                )
                .isEqualTo("あああああかかかかかさささささたたたたたなななななはははははまままままやややらららららわわわわん")
            assertThat(
                    "びぴヒビピﾋぁぃぅぇぉゔ".codePoints().toList().joinToString("") {
                        UCharacter.toString(it).initialLetter(Locale.ROOT)
                    }
                )
                .isEqualTo("ははははははああああああ")
        }
    }
}
