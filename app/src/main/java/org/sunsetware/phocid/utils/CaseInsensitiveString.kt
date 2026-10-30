package org.sunsetware.phocid.utils

/**
 * Small Android-public replacement for the hidden ICU CaseInsensitiveString.
 *
 * Identity uses the project-wide ICU NFKC casefold in [casefolder]. The folded value is computed
 * once at construction; [equals] and [hashCode] only use it, so a mutable-looking pair always
 * agrees. [toString] still returns the original string.
 */
class CaseInsensitiveString(val string: String?) {
    private val folded: String? = string?.let { casefolder.normalize(it) }

    override fun equals(other: Any?): Boolean =
        other is CaseInsensitiveString && folded == other.folded

    override fun hashCode(): Int = folded?.hashCode() ?: 0

    override fun toString(): String = string ?: ""
}
