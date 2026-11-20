package org.sunsetware.phocid

import android.content.pm.PackageManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.agent.AgentCallerGate

class AgentCallerGateTest {
    private val myUid = 10_000
    private val callerUid = 10_043

    /**
     * The PackageManager constant for differing signatures is a hidden API and not part of the
     * public SDK, so the value is kept as a literal. Any non-match result denies.
     */
    private val SIGNATURE_NOT_MATCH = -3

    @Test
    fun sameProcessCallerIsAlwaysTrusted() {
        // A local (same-process) caller needs no package lookup at all.
        assertThat(
                AgentCallerGate.isTrusted(
                    callingUid = myUid,
                    myUid = myUid,
                    packagesForUid = null,
                ) {
                    PackageManager.SIGNATURE_MATCH
                },
            )
            .isTrue()
    }

    @Test
    fun callerSignedWithOurCertificateIsTrusted() {
        assertThat(
                AgentCallerGate.isTrusted(
                    callingUid = callerUid,
                    myUid = myUid,
                    packagesForUid = arrayOf("org.sunsetware.phocid.bridge"),
                ) {
                    PackageManager.SIGNATURE_MATCH
                },
            )
            .isTrue()
    }

    @Test
    fun unresolvableCallerUidIsRejected() {
        assertThat(
                AgentCallerGate.isTrusted(
                    callingUid = callerUid,
                    myUid = myUid,
                    packagesForUid = null,
                ) {
                    PackageManager.SIGNATURE_MATCH
                },
            )
            .isFalse()
        assertThat(
                AgentCallerGate.isTrusted(
                    callingUid = callerUid,
                    myUid = myUid,
                    packagesForUid = emptyArray(),
                ) {
                    PackageManager.SIGNATURE_MATCH
                },
            )
            .isFalse()
    }

    @Test
    fun mismatchedSignatureIsRejected() {
        // The platform permission state is deliberately not consulted here: any non-match result
        // (not signed, neither signed, signatures differ) denies the caller.
        assertThat(
                AgentCallerGate.isTrusted(
                    callingUid = callerUid,
                    myUid = myUid,
                    packagesForUid = arrayOf("com.hostile.agent"),
                ) {
                    SIGNATURE_NOT_MATCH
                },
            )
            .isFalse()
    }

    @Test
    fun sharedUidRequiresEveryPackageToMatch() {
        // A uid shared by our bridge and a hostile package is rejected as a whole: allowing the
        // verified package would let the unverified suite-mate ride along on the same uid.
        assertThat(
                AgentCallerGate.isTrusted(
                    callingUid = callerUid,
                    myUid = myUid,
                    packagesForUid = arrayOf("org.sunsetware.phocid.bridge", "com.hostile.agent"),
                ) { callingPackage ->
                    if (callingPackage == "com.hostile.agent") SIGNATURE_NOT_MATCH
                    else PackageManager.SIGNATURE_MATCH
                },
            )
            .isFalse()
    }
}
