"""Add the d8 output (classes.dex, classes2.dex, ...) to a linked APK.

aapt2 produces the manifest/resources part of the APK; base classes cannot be
added by aapt2, so they are appended here. Entry compression is preserved for
existing entries (resources.arsc must stay stored), and zipalign fixes alignment
afterwards.

Root-level classpath resources of the runtime jars are copied too: the Android
class loader resolves `Class.getResourceAsStream` and `ServiceLoader` against the
APK zip, so a resource such as `yggdrasil_session_pubkey.der` or
`META-INF/services/org.cloudburstmc.math.vector.VectorProvider` only exists for a
dexed class if it sits at the same path in the APK.

Usage: python package_apk.py <base.apk> <out.apk> <dex dir> [jar ...]
"""

import os
import sys
import zipfile


def root_resources(jar_paths):
    """Yield (name, bytes) for the classpath resources a dexed class may load.

    Root files (such as `yggdrasil_session_pubkey.der`) and `META-INF/services`
    providers are included: ServiceLoader reads the latter through the class
    loader, and without it `org.cloudburstmc.math.vector.Vectors` fails on ART
    with "Could not initialize vector provider".
    """
    seen = set()
    for jar_path in jar_paths:
        with zipfile.ZipFile(jar_path) as jar:
            for info in jar.infolist():
                name = info.filename
                if info.is_dir() or name.endswith(".class"):
                    continue
                services = name.startswith("META-INF/services/")
                if not services and ("/" in name or name.upper().startswith("META-INF")):
                    continue
                if name in seen:
                    continue
                seen.add(name)
                yield name, jar.read(info.filename)


def main() -> int:
    if len(sys.argv) < 4:
        print(__doc__, file=sys.stderr)
        return 2
    base, out, dex_dir = sys.argv[1], sys.argv[2], sys.argv[3]
    jars = sys.argv[4:]

    dex_names = sorted(
        name for name in os.listdir(dex_dir) if name.endswith(".dex")
    )
    if not dex_names:
        print(f"no dex files in {dex_dir}", file=sys.stderr)
        return 1

    packed = []
    with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, "w") as dst:
        existing = set()
        for info in src.infolist():
            dst.writestr(info, src.read(info.filename))
            existing.add(info.filename)
        for name in dex_names:
            dst.write(os.path.join(dex_dir, name), name, compress_type=zipfile.ZIP_DEFLATED)
        for name, data in root_resources(jars):
            if name in existing:
                print(f"skip resource {name}: already in the linked APK", file=sys.stderr)
                continue
            dst.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
            packed.append(name)

    print(f"packed {out} with {len(dex_names)} dex file(s): {', '.join(dex_names)}")
    if packed:
        print(f"classpath resources: {', '.join(packed)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
