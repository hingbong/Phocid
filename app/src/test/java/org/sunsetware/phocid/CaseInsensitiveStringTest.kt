package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.sunsetware.phocid.data.AlbumKey
import org.sunsetware.phocid.utils.CaseInsensitiveString

@RunWith(RobolectricTestRunner::class)
class CaseInsensitiveStringTest {
    private fun cas(value: String?) = CaseInsensitiveString(value)

    @Test
    fun sigmaAndFinalSigma_shareFoldedIdentity() {
        val sigma = cas("Σ")
        val finalSigma = cas("ς")
        assertThat(sigma).isEqualTo(finalSigma)
        assertThat(sigma.hashCode()).isEqualTo(finalSigma.hashCode())
        assertThat(cas("ΟΔΟΣ")).isEqualTo(cas("οδος"))
    }

    @Test
    fun turkishI_followsNfkcCasefold() {
        assertThat(cas("I")).isEqualTo(cas("i"))
        assertThat(cas("I").hashCode()).isEqualTo(cas("i").hashCode())
        // NFKC casefold keeps dotless ı distinct from i/I.
        assertThat(cas("ı")).isNotEqualTo(cas("i"))
        assertThat(cas("ı")).isNotEqualTo(cas("I"))
        // İ folds to i followed by a combining dot, so it is not equal to plain i.
        assertThat(cas("İ")).isEqualTo(cas("i\u0307"))
        assertThat(cas("İ").hashCode()).isEqualTo(cas("i\u0307").hashCode())
        assertThat(cas("İ")).isNotEqualTo(cas("i"))
    }

    @Test
    fun compatibilityAndCombiningForms_areEqual() {
        assertThat(cas("Ａ")).isEqualTo(cas("A"))
        assertThat(cas("Ａ")).isEqualTo(cas("a"))
        assertThat(cas("Ａ").hashCode()).isEqualTo(cas("a").hashCode())
        assertThat(cas("e\u0301")).isEqualTo(cas("é"))
        assertThat(cas("e\u0301")).isEqualTo(cas("É"))
        assertThat(cas("e\u0301").hashCode()).isEqualTo(cas("é").hashCode())
    }

    @Test
    fun nullAndEmpty_areDistinctAndStable() {
        assertThat(cas(null)).isEqualTo(cas(null))
        assertThat(cas(null).hashCode()).isEqualTo(cas(null).hashCode())
        assertThat(cas(null)).isNotEqualTo(cas(""))
        assertThat(cas("")).isEqualTo(cas(""))
        assertThat(cas("")).isNotEqualTo(cas(null))
        assertThat(cas("")).isNotEqualTo(cas(" "))
        assertThat(cas(null).toString()).isEmpty()
        assertThat(cas("AbC").toString()).isEqualTo("AbC")
    }

    @Test
    fun hashContainers_useFoldedIdentity() {
        val set = setOf(cas("Σ"), cas("ς"), cas("σ"))
        assertThat(set).hasSize(1)
        assertThat(set).contains(cas("ς"))

        val map = hashMapOf(cas("İ") to "dotted", cas("ı") to "dotless")
        assertThat(map[cas("i\u0307")]).isEqualTo("dotted")
        assertThat(map[cas("i")]).isNull()
        assertThat(map[cas("ı")]).isEqualTo("dotless")
    }

    @Test
    fun albumKey_usesFoldedFieldsForEqualityAndGrouping() {
        val first = AlbumKey("Album", listOf("Artist"))
        val second = AlbumKey("album", listOf("artist"))
        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
        assertThat(AlbumKey("Album", emptyList())).isEqualTo(AlbumKey("album", emptyList()))

        val grouped =
            listOf("Album" to "Artist", "ALBUM" to "ARTIST", "Other" to "Artist")
                .groupBy({ AlbumKey(it.first, listOf(it.second)) }, { it })
        assertThat(grouped).hasSize(2)
        assertThat(grouped[second]).hasSize(2)

        val index = mapOf(first to "value")
        assertThat(index[second]).isEqualTo("value")
    }
}
