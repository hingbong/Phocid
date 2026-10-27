# Storage deprecations

This file tracks Android storage APIs that are deprecated but still present in the app.

Run the lightweight scanner from the repository root:

    ./scripts/scan-deprecated-storage.sh

The scanner only needs bash, find, and grep. For an API-level audit, run Android Lint in CI
(`./gradlew :app:lintDebug`) and download the `lint-report` artifact from the workflow run.

## Current inventory

| API | Location | Status |
| --- | --- | --- |
| MediaStore.DATA | LibraryIndex.kt, scanTracks | Kept behind @Suppress("DEPRECATION") |
| Environment.getExternalStorageDirectory | Replaced by StorageRoots.kt | Removed |
| Environment.getExternalStoragePublicDirectory | Replaced by StorageManager.primaryStorageVolume | Removed |
| MediaStore.Audio.Media.EXTERNAL_CONTENT_URI | Track.uri, ArtworkLoader.kt | Not deprecated; left unchanged |

EXTERNAL_CONTENT_URI is the same primary external volume as getContentUri(VOLUME_EXTERNAL), so
switching it would be cosmetic rather than a deprecation fix.

## Why MediaStore.DATA is still used

Jaudiotagger reads metadata through AudioFileIO.read(File), and ArtworkLoader uses
FileInputStream(File) plus AudioFileIO.read(File). Both need a real filesystem path.
MediaStore.DATA is deprecated, but it is still populated for media files and remains readable
with the current media permission on Android 15-17.

Replacing it with RELATIVE_PATH + DISPLAY_NAME is not a safe substitute: MediaStore does not
guarantee that the reconstructed path exists, and the result can differ on case-insensitive
filesystems, symlinks, and multi-volume devices.

## Statistics identity

Statistics already use a portable identity layer:

- UUID is the backup identity.
- AUDIO_HASH is the primary cross-device lookup key.
- TAG_KEY is the fallback for re-encoded files.
- REL_PATH is a legacy path-relative fallback.
- LOCAL_URI (content URI + size) is local-only; it is never written to backups.

The stats resolver can now compute AUDIO_HASH from a content URI and does not require
MediaStore.DATA. The library index still needs DATA for Jaudiotagger and ArtworkLoader, so the
next step is the full Track.path migration, not removing the column outright.

A restored backup contains only portable aliases. On a new device, TagKey/REL_PATH can bind
immediately; AUDIO_HASH binds on first play and then writes a new LOCAL_URI for that device.

## Deferred content URI migration

Removing MediaStore.DATA requires changing the Track.path identity across:

- Track, library indexing, folder views, and search
- PlaylistEntry and M3U import/export
- Stats UI/resolver path display hints (LOCAL_URI is local-only; backup identity is UUID-based)
- ArtworkLoader and Media3 media IDs

OpusMetadataIo already accepts an InputStream, so opus/ogg metadata can migrate first. The
blocking pieces are Jaudiotagger and the path-based identity. Do not start this migration as part
of a routine dependency or SDK bump.

## Submodule

OpusMetadataIo is a pure JVM library and does not use Android storage APIs. This cleanup does not
change the submodule. If a future content URI migration needs a stream/seek API there, point
.gitmodules at the fork with an HTTPS URL (GitHub Actions has no SSH key) and pin the commit.
