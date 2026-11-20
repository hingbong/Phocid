package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.utils.SafeRegex

class SafeRegexTest {
    @Test
    fun acceptsOrdinaryRules() {
        assertThat(SafeRegex.isValid("^/Music/")).isTrue()
        assertThat(SafeRegex.isValid("""(?i).*\.mp3$""")).isTrue()
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
    fun acceptsLegacyLookaroundAndBackreferencesViaFallback() {
        // RE2/J cannot express backreferences or lookaround. These used to be reported invalid and
        // silently dropped at index time, which emptied libraries of users with legacy stored
        // rules.
        // They must now compile through the deadline-bounded java.util.regex fallback and match.
        assertThat(SafeRegex.isValid("""(a)\1""")).isTrue()
        assertThat(SafeRegex.isValid("""a(?=b)""")).isTrue()

        val backref = SafeRegex.compile("""(a)\1""")!!
        assertThat(backref.re2).isNull()
        assertThat(backref.matchesBounded("aa")).isTrue()
        assertThat(backref.matchesBounded("ab")).isFalse()

        val lookahead = SafeRegex.compile("""a(?=b)""")!!
        assertThat(lookahead.re2).isNull()
        assertThat(lookahead.matchesBounded("ab")).isTrue()
        assertThat(lookahead.matchesBounded("ac")).isFalse()
        assertThat(lookahead.matchesBounded("a")).isFalse()
    }

    @Test
    fun legacyLookaheadWhitelistRuleIsPreserved() {
        // Real stored configuration from a user whose library emptied after the RE2/J migration:
        // blacklist "^" blocks every path, and this whitelist rescues /Music/sync tracks except
        // Off Vocal / instrumental. The negative lookahead is essential and must keep working.
        val rule = """^(?=.*\/Music\/sync)(?!.*(Off Vocal|instrumental)).*$"""
        assertThat(SafeRegex.isValid(rule)).isTrue()
        val regex = SafeRegex.compile(rule)!!
        assertThat(regex.re2).isNull()

        assertThat(regex.matchesBounded("/storage/emulated/0/Music/sync/Artist/Song.flac")).isTrue()
        assertThat(
                regex.matchesBounded("/storage/emulated/0/Music/sync/Artist/Song (Off Vocal).flac"),
            )
            .isFalse()
        assertThat(
                regex.matchesBounded(
                    "/storage/emulated/0/Music/sync/Artist/Song instrumental.flac",
                ),
            )
            .isFalse()
        assertThat(
                regex.matchesBounded("/storage/emulated/0/Music/sync/Artist/song OFF VOCAL.flac"),
            )
            .isFalse()
        // Outside /Music/sync the positive lookahead fails, so the track is not rescued.
        assertThat(regex.matchesBounded("/storage/emulated/0/Music/other/Song.flac")).isFalse()
    }

    @Test
    fun legacyFallbackIsBoundedByDeadline() {
        // Lookaround forces the java.util.regex fallback; (a|aa)+$ bypasses the nested-quantifier
        // shape check yet backtracks catastrophically on a non-matching subject. The per-match
        // deadline must abort the match (reporting no match) instead of running away.
        val rule = """(?=(a|aa)+$)a"""
        assertThat(SafeRegex.isValid(rule)).isTrue()
        val regex = SafeRegex.compile(rule)!!
        assertThat(regex.re2).isNull()

        // Keep the non-matching tail inside the truncation cap so the subject is not cut to pure
        // a's (which would match quickly instead of backtracking).
        val subject = "a".repeat(SafeRegex.MAX_SUBJECT_LENGTH - 1) + "b"
        val startedAt = System.nanoTime()
        val matched = regex.matchesBounded(subject)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertThat(matched).isFalse()
        assertThat(elapsedMs).isLessThan(2_000)
    }

    @Test
    fun boundedMatchTruncatesLongSubjects() {
        val regex = SafeRegex.compile("needle")!!
        val longPath = "a".repeat(SafeRegex.MAX_SUBJECT_LENGTH) + "needle"
        // The match must not see beyond the cap.
        assertThat(regex.matchesBounded(longPath)).isFalse()
        assertThat(regex.matchesBounded("needle" + "a".repeat(50))).isTrue()
    }

    @Test
    fun adversarialPatternsBypassingTheShapeCheckStayLinear() {
        // Alternation overlap and parenthesised wrapping defeat the nested-quantifier shape
        // check. The RE2/J engine must still finish within a generous linear-time budget: a
        // backtracking matcher would not terminate for either pattern.
        val adversarial = listOf("(a|aa)+$", "^((a+))+$")
        val longSubject = "a".repeat(100_000)
        for (pattern in adversarial) {
            val regex = SafeRegex.compile(pattern)!!
            val startedAt = System.nanoTime()
            val matched = regex.re2!!.matcher(longSubject).find()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertThat(matched).isTrue()
            assertThat(elapsedMs).isLessThan(10_000)
        }
    }
}
