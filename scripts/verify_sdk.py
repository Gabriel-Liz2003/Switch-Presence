#!/usr/bin/env python3
"""Validate a locally downloaded Discord Social SDK AAR without uploading its bytes."""
import sys
import zipfile
from pathlib import Path

REQUIRED = {
    "libs/discord_partner_sdk.jar",
    "prefab/modules/discord_partner_sdk/include/discordpp.h",
    "prefab/modules/discord_partner_sdk/include/cdiscord.h",
    "prefab/modules/discord_partner_sdk/libs/android.arm64-v8a/libdiscord_partner_sdk.so",
    "jni/arm64-v8a/libdiscord_partner_sdk.so",
    "prefab/prefab.json",
    "AndroidManifest.xml",
}


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: python3 scripts/verify_sdk.py /path/to/discord_partner_sdk.aar")
        return 2
    path = Path(sys.argv[1])
    if not path.is_file():
        print("ERROR: SDK .aar not found")
        return 2
    try:
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            missing = REQUIRED - names
            if missing:
                print("ERROR: AAR is missing required parts:")
                for name in sorted(missing):
                    print(f" - {name}")
                return 1
            with archive.open("prefab/modules/discord_partner_sdk/include/discordpp.h") as header:
                text = header.read().decode("utf-8", "replace")
                for required in ["UpdateRichPresence", "ClearRichPresence", "RunCallbacks",
                                 "SetApplicationId"]:
                    if required not in text:
                        print(f"ERROR: C++ SDK header missing {required}")
                        return 1
            bad = archive.testzip()
            if bad:
                print("ERROR: Corrupted archive member")
                return 1
            print("OK: valid Android SDK AAR structure and C++ presence API.")
            print("Supported native ABIs: " + ", ".join(
                arch for arch in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                if f"jni/{arch}/libdiscord_partner_sdk.so" in names))
            print("SDK bytes stay on your device; never commit this proprietary AAR.")
            return 0
    except (zipfile.BadZipFile, OSError) as exc:
        print(f"ERROR: Cannot inspect SDK: {type(exc).__name__}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
