#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

check_only=false
if [[ "${1:-}" == "--check" ]]; then
    check_only=true
elif [[ $# -gt 0 ]]; then
    echo "usage: $0 [--check]" >&2
    exit 2
fi

mapfile -t files < <(find app/src/main -type f -name '*.kt' | sort)
if [[ ${#files[@]} -eq 0 ]]; then
    echo "No Kotlin sources found under app/src/main" >&2
    exit 1
fi

findings="$(
    grep -nHE \
        'MediaStore\.Audio\.Media\.DATA|Media\.DATA|Environment\.getExternalStorageDirectory|Environment\.getExternalStoragePublicDirectory|MediaStore\.Audio\.Media\.EXTERNAL_CONTENT_URI' \
        "${files[@]}" || true
)"

if [[ -n "$findings" ]]; then
    printf '%s\n' "$findings"
else
    echo "No deprecated storage API candidates found."
fi

if [[ "$check_only" == true ]]; then
    if grep -nHE 'Environment\.getExternalStorageDirectory|Environment\.getExternalStoragePublicDirectory' "${files[@]}"; then
        echo "Environment storage APIs must be replaced with StorageManager/StorageVolume." >&2
        exit 1
    fi
fi
