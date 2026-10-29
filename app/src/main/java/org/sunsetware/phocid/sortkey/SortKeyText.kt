package org.sunsetware.phocid.sortkey

import java.text.Normalizer

sealed interface SortKeyValue {
    data class Value(val text: String) : SortKeyValue

    data object Clear : SortKeyValue

    data class Invalid(val message: String) : SortKeyValue
}

/** Generic text normalization. No script- or language-specific conversion. */
object SortKeyText {
    const val MAX_LENGTH = 200

    private val whitespace = Regex("[\\s\\p{Z}]+")

    fun parse(raw: String?): SortKeyValue {
        if (raw == null) return SortKeyValue.Clear
        if (raw.any { Character.isISOControl(it.code) }) {
            return SortKeyValue.Invalid("Sort key contains control characters")
        }
        val normalized =
            Normalizer.normalize(raw, Normalizer.Form.NFKC).replace(whitespace, " ").trim()
        if (normalized.isEmpty()) return SortKeyValue.Clear
        if (normalized.length > MAX_LENGTH) {
            return SortKeyValue.Invalid("Sort key is longer than $MAX_LENGTH characters")
        }
        return SortKeyValue.Value(normalized)
    }
}
