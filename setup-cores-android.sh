#!/usr/bin/env bash
# Downloads libretro cores for Android and bundles them into the APK via
# the standard jniLibs/ directory. Cores ship inside the APK at:
#     lib/<abi>/lib<coreName>.so
#
# Android auto-loads these via System.loadLibrary("coreName") — no vtool
# or codesign needed (unlike iOS). Both arm64-v8a and x86_64 are bundled
# so the same APK runs on modern devices and emulators.
#
# Usage:
#   ./setup-cores-android.sh             # default core set
#   ./setup-cores-android.sh mgba ppsspp # specific cores
set -euo pipefail

cd "$(dirname "$0")"

# Core stems are the CANONICAL names the app's routing requests
# (GameSystem.preferredCore + "_libretro" — MainActivity's
# bundledCorePath looks for lib<stem>_libretro.so), so beetle_psx_hw must
# appear here even though the buildbot publishes it under its legacy
# mednafen_psx_hw name (url_stem below maps it). A bundled APK built with
# the old mednafen_psx_hw stem could never launch PS1 without
# re-downloading cores.
CORES_TO_BUNDLE=(
    mgba mesen snes9x genesis_plus_gx beetle_psx_hw pcsx_rearmed
    melonds mednafen_saturn nestopia gambatte sameboy fbneo picodrive
    mupen64plus_next azahar play
)
# azahar is arm64-only on the buildbot: the x86_64 pass FAILs and is
# skipped below (an emulator-only x86_64 install simply has no 3DS core,
# same as before).

# The buildbot publishes beetle_psx_hw under its legacy mednafen_psx_hw
# name; the bundled filename keeps the canonical stem the app requests.
url_stem() {
    case "$1" in
        beetle_psx_hw) echo "mednafen_psx_hw" ;;
        *) echo "$1" ;;
    esac
}

if [[ $# -gt 0 ]]; then
    CORES_TO_BUNDLE=("$@")
fi

JNI_DIR="app-android/src/androidMain/jniLibs"
mkdir -p "$JNI_DIR/arm64-v8a" "$JNI_DIR/x86_64"

WORK_DIR="$(mktemp -d)"
trap "rm -rf '$WORK_DIR'" EXIT

# Android buildbot serves cores per-ABI as separate zips. NOTE the layout:
# android/latest/<abi> (the android/<abi>/latest path this script used for
# its whole life 404s — every bundled-APK core download silently failed
# while stale jniLibs kept the script looking healthy).
declare -A BUILDBOT_BASE=(
    [arm64-v8a]="https://buildbot.libretro.com/nightly/android/latest/arm64-v8a"
    [x86_64]="https://buildbot.libretro.com/nightly/android/latest/x86_64"
)

echo "Cores: ${CORES_TO_BUNDLE[*]}"
echo "Output: $JNI_DIR/"
echo ""

for core in "${CORES_TO_BUNDLE[@]}"; do
    for abi in arm64-v8a x86_64; do
        # Try both naming conventions (url_stem maps beetle_psx_hw to its
        # published mednafen_psx_hw name; azahar drops the _android infix).
        stem="$(url_stem "$core")"
        for url_name in "${stem}_libretro" "${stem}_libretro_android"; do
            url="${BUILDBOT_BASE[$abi]}/${url_name}.so.zip"
            zip_file="${WORK_DIR}/${core}_${abi}.zip"
            if curl -fsSL --max-time 60 "$url" -o "$zip_file"; then
                break
            fi
        done

        if [ ! -s "$zip_file" ]; then
            echo "FAIL  $core/$abi — download failed"
            continue
        fi

        extract_dir="${WORK_DIR}/${core}_${abi}"
        mkdir -p "$extract_dir"
        if ! (cd "$extract_dir" && unzip -q "../$(basename "$zip_file")"); then
            echo "FAIL  $core/$abi — unzip failed"
            continue
        fi

        so_file=$(find "$extract_dir" -name "*.so" | head -1)
        if [ -z "$so_file" ]; then
            echo "FAIL  $core/$abi — no .so in archive"
            continue
        fi

        # Android .so naming: lib<coreName>.so
        dest="${JNI_DIR}/${abi}/lib${core}_libretro.so"
        cp "$so_file" "$dest"
        echo "OK    $core/$abi ($(du -h "$dest" | cut -f1))"
    done
done

echo ""
echo "Bundled $(ls "$JNI_DIR"/*/*.so 2>/dev/null | wc -l | tr -d ' ') .so files"
echo "Next: rebuild the APK — Gradle picks up jniLibs/ automatically."
