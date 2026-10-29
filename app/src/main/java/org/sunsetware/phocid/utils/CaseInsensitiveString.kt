package org.sunsetware.phocid.utils

import java.util.Locale

/** Small Android-public replacement for the hidden ICU CaseInsensitiveString. */
class CaseInsensitiveString(val string: String?) {
    override fun equals(other: Any?): Boolean =
        other is CaseInsensitiveString && string.equals(other.string, ignoreCase = true)

    override fun hashCode(): Int = string?.lowercase(Locale.ROOT)?.hashCode() ?: 0

    override fun toString(): String = string ?: ""
}
