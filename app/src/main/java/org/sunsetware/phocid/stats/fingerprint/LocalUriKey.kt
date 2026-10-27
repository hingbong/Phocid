package org.sunsetware.phocid.stats.fingerprint

/**
 * Local-only lookup key for a MediaStore item.
 *
 * MediaStore ids are device-local and can be reused, so the size is part of the key. This key must
 * never be written to a backup or used as a cross-device identity.
 */
object LocalUriKey {
    fun of(uri: String, size: Long): String = "$uri|$size"
}
