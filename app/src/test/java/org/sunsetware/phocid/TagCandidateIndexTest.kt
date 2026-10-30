package org.sunsetware.phocid

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.ui.views.statistics.tagCandidateIndex

class TagCandidateIndexTest {
    @Test
    fun uniqueCandidatesMapToTheirOwningTrack() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("a", "b"),
                    2L to listOf("c"),
                )
            )

        assertThat(index).containsExactlyEntriesOf(mapOf("a" to 1L, "b" to 1L, "c" to 2L))
    }

    @Test
    fun candidateClaimedByTwoTracksIsOmitted() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("shared", "a"),
                    2L to listOf("shared", "b"),
                )
            )

        assertThat(index).doesNotContainKey("shared")
        assertThat(index).containsEntry("a", 1L).containsEntry("b", 2L)
    }

    @Test
    fun ambiguityIsNotOverwrittenByALaterTrack() {
        val index =
            tagCandidateIndex(
                listOf(
                    1L to listOf("shared"),
                    2L to listOf("shared"),
                    3L to listOf("shared"),
                )
            )

        assertThat(index).doesNotContainKey("shared")
    }

    @Test
    fun repeatedCandidateFromTheSameTrackIsUnique() {
        assertThat(tagCandidateIndex(listOf(1L to listOf("a", "a"))))
            .containsExactlyEntriesOf(mapOf("a" to 1L))
    }
}
