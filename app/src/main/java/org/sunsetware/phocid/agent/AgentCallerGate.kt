package org.sunsetware.phocid.agent

import android.content.pm.PackageManager

/**
 * Decides whether a binder caller may use the agent query service.
 *
 * The platform's signature-level permission can be subverted by install order: a hostile package
 * that is installed first and declares the same permission name at a normal protection level wins
 * the definition race, the main app's later signature-level declaration is then ignored, and the
 * hostile package holds the permission automatically. The service therefore verifies callers
 * itself, independently of the platform permission state: every package sharing the caller's uid
 * must be signed with our own signing certificate.
 */
internal object AgentCallerGate {
    /**
     * @param callingUid binder identity of the caller ([android.os.Binder.getCallingUid])
     * @param myUid our own process uid; a same-process caller is always trusted
     * @param packagesForUid packages sharing [callingUid] as reported by PackageManager, or null
     *   when the uid cannot be resolved to any package
     * @param checkSignatures maps a caller package name to the
     *   [PackageManager.checkSignatures] result against our own package
     */
    fun isTrusted(
        callingUid: Int,
        myUid: Int,
        packagesForUid: Array<String>?,
        checkSignatures: (String) -> Int,
    ): Boolean {
        if (callingUid == myUid) return true
        val packages = packagesForUid ?: return false
        if (packages.isEmpty()) return false
        // A uid can be shared by several packages: every one of them must be signed by us.
        // Trusting any single matching package would let an unverified suite-mate ride along.
        return packages.all { checkSignatures(it) == PackageManager.SIGNATURE_MATCH }
    }
}
