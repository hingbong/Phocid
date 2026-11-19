package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.utils.SafeRegex
import org.sunsetware.phocid.utils.matchesBounded

class SafeRegexTest {
    @Test
    fun acceptsOrdinaryRules() {
        assertThat(SafeRegex.isValid("^/Music/")).isTrue()
        assertThat(SafeRegex.isValid("(?i).*\.mp3$")).isTrue()
        assertThat(SafeRegex.compile("^/Music/")).isNotNull()
    }

    @Test
    fun rejectsNestedQuantifiers() {
        // Classic catastrophic-backtracking shapes must be rejected at edit time.
        assertThat(SafeRegex.isValid("(a+)+$")).isFalse()
        assertThat(SafeRegex.isValid("(.*)*")).isFalse()
        assertThat(SafeRegex.isValid("(a*)*")).isFalse()
    }

    @Test
    fun rejectsOverlongPatternsAndInvalidSyntax() {
        assertThat(SafeRegex.isValid("a".repeat(SafeRegex.MAX_PATTERN_LENGTH + 1))).isFalse()
        assertThat(SafeRegex.isValid("(")).isFalse()
    }

    @Test
    fun boundedMatchTruncatesLongSubjects() {
        val regex = SafeRegex.compile("needle")!!
        val longPath = "a".repeat(SafeRegex.MAX_SUBJECT_LENGTH) + "needle"
        // The match must not see beyond the cap.
        assertThat(regex.matchesBounded(longPath)).isFalse()
        assertThat(regex.matchesBounded("needle" + "a".repeat(50))).isTrue()
    }
}
