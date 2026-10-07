#!/usr/bin/env bash
# Downloads libretro cores for iOS, converts them to the right platform
# (simulator or device), re-signs them, and copies them into the app's
# Frameworks/ directory so they ship inside the .app bundle.
#
# This is the "developer signing" distribution path. Works for:
#   - Personal team signing (free Apple ID)
#   - Ad Hoc distribution (up to 100 devices per year)
#   - TestFlight (after Apple review)
#
# For the actual App Store, Apple requires cores to be statically linked
# (.a files built from source). That's a separate multi-day effort — see
# the "App Store path" section of SESSION_NOTES.md.
#
# Usage:
#   ./setup-cores.sh              # simulator (default — for local testing)
#   ./setup-cores.sh --device     # real device (for TestFlight/Ad Hoc)
#   ./setup-cores.sh --device ppsspp dolphin   # only specific cores
set -euo pipefail

# Script lives at the project root, so cd to its own directory.
cd "$(dirname "$0")"

MODE="--sim"
# Core stems are the CANONICAL names the app's routing requests
# (GameSystem.preferredCore + "_libretro" — see coreNameForRom), so
# beetle_psx_hw must appear here even though the buildbot publishes it
# under its legacy mednafen_psx_hw name (url_stem below maps it). azahar
# (3DS) and play (PS2) are routed by the shared table and join the
# default bundle set.
CORES_TO_BUNDLE=(
    mgba mesen snes9x genesis_plus_gx beetle_psx_hw pcsx_rearmed
    melonds mednafen_saturn nestopia gambatte sameboy fbneo picodrive
    mupen64plus_next azahar play
    # HW-render cores (require MoltenVK — run setup-moltenvk.sh first):
    ppsspp dolphin flycast
)

# The buildbot publishes beetle_psx_hw under its legacy mednafen_psx_hw
# name; the bundled filename keeps the canonical stem the app requests.
url_stem() {
    case "$1" in
        beetle_psx_hw) echo "mednafen_psx_hw" ;;
        *) echo "$1" ;;
    esac
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --sim|--simulator) MODE="--sim"; shift ;;
        --device)          MODE="--device"; shift ;;
        --help|-h)
            cat <<EOF
Usage: $0 [--sim|--device] [core_name ...]
  --sim      Convert to iossim platform via vtool (default — for Simulator)
  --device   Keep ios-arm64 platform (for real iPhone / TestFlight)
  core_name  Optional list of specific cores to bundle (default: all)
EOF
            exit 0 ;;
        --*) echo "Unknown option: $1"; exit 1 ;;
        *) CORES_TO_BUNDLE=("$@"); break ;;
    esac
done

# Output: iosApp/Frameworks/<name>_libretro.dylib
# These get embedded into the .app bundle via iosApp/project.yml.
DEST_DIR="iosApp/Frameworks"
mkdir -p "$DEST_DIR"

WORK_DIR="$(mktemp -d)"
trap "rm -rf '$WORK_DIR'" EXIT

BUILDBOT="https://buildbot.libretro.com/nightly/apple/ios-arm64/latest"

echo "Mode: $MODE"
echo "Cores: ${CORES_TO_BUNDLE[*]}"
echo "Output: $DEST_DIR/"
echo ""

for core in "${CORES_TO_BUNDLE[@]}"; do
    # Buildbot filenames are inconsistent — some cores use _ios suffix, some don't.
    # Try both. URL-name → final dylib name is normalized to <core>_libretro.dylib
    # (url_stem maps beetle_psx_hw to its published mednafen_psx_hw name).
    stem="$(url_stem "$core")"
    for url_name in "${stem}_libretro_ios" "${stem}_libretro"; do
        url="${BUILDBOT}/${url_name}.dylib.zip"
        zip_file="${WORK_DIR}/${core}.zip"
        if curl -fsSL --max-time 60 "$url" -o "$zip_file"; then
            break
        fi
    done

    if [ ! -s "$zip_file" ]; then
        echo "FAIL  $core — download failed"
        continue
    fi

    # Extract
    extract_dir="${WORK_DIR}/${core}"
    mkdir -p "$extract_dir"
    if ! (cd "$extract_dir" && unzip -q "../$(basename "$zip_file")"); then
        echo "FAIL  $core — unzip failed"
        continue
    fi

    dylib=$(find "$extract_dir" -name "*.dylib" | head -1)
    if [ -z "$dylib" ]; then
        echo "FAIL  $core — no dylib in archive"
        continue
    fi

    final_dylib="${DEST_DIR}/${core}_libretro.dylib"

    if [ "$MODE" = "--sim" ]; then
        # Convert platform ios → iossim so the simulator's loader accepts it.
        # macOS arm64 cores crash in the simulator (different TLS, syscalls).
        converted="${extract_dir}/${core}_sim.dylib"
        if ! vtool -set-build-version iossim 14.0 14.0 -replace -output "$converted" "$dylib" 2>/dev/null; then
            echo "FAIL  $core — vtool failed"
            continue
        fi
        dylib="$converted"
    fi

    # Re-sign ad-hoc — vtool invalidates the original signature, and
    # iOS refuses to dlopen unsigned dylibs even with code-signing disabled.
    if ! codesign -s - --force "$dylib" >/dev/null 2>&1; then
        echo "FAIL  $core — codesign failed"
        continue
    fi

    cp "$dylib" "$final_dylib"
    size=$(du -h "$final_dylib" | cut -f1)
    echo "OK    $core ($size)"
done

echo ""
echo "Bundled $(ls "$DEST_DIR"/*_libretro.dylib 2>/dev/null | wc -l | tr -d ' ') cores"
echo "Next: rebuild the iOS app — xcodegen picks up Frameworks/ automatically."
