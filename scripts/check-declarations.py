"""Fails when something the Play Console declarations describe changed without the declarations
being looked at again (hardening #16).

    python scripts/check-declarations.py           # CI: compare with store/play-declarations.md
    python scripts/check-declarations.py --update  # after re-checking the declarations, record the new state

Watched, because the Play declarations (location permission, foreground services, videos) and the
in-app prominent disclosure are written against them:
  permissions  every <uses-permission> in the shared manifest (what the Play build asks for)
  services     every <service> that declares a foregroundServiceType, with its special-use subtype
  disclosure   the LocationDisclosure dialog shown before the location prompt (ui/Permissions.kt)

The fingerprints live in store/play-declarations.md as `fingerprint <part>: <hash>` lines.
"""
import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOC = ROOT / "store/play-declarations.md"
MANIFEST = ROOT / "android/app/src/main/AndroidManifest.xml"
PERMISSIONS_KT = ROOT / "android/app/src/main/java/io/github/jeiel85/findmywifephone/ui/Permissions.kt"


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", s).strip()


def parts() -> dict[str, str]:
    manifest = MANIFEST.read_text(encoding="utf-8")
    manifest = re.sub(r"<!--.*?-->", "", manifest, flags=re.S)
    perms = sorted(norm(m) for m in re.findall(r"<uses-permission\b[^>]*>", manifest))
    services = []
    for m in re.finditer(r"<service\b[^>]*?(/?)>", manifest):
        block = m.group(0) if m.group(1) else manifest[m.start():manifest.index("</service>", m.end()) + len("</service>")]
        if "foregroundServiceType" in block:
            services.append(norm(block))
    services.sort()
    kt = PERMISSIONS_KT.read_text(encoding="utf-8")
    m = re.search(r"\nprivate fun LocationDisclosure\(.*?\n}\n", kt, flags=re.S)
    if not m:
        sys.exit(f"LocationDisclosure not found in {PERMISSIONS_KT.relative_to(ROOT)} — update this script")
    if not perms or not services:
        sys.exit(f"no permissions or foreground services found in {MANIFEST.relative_to(ROOT)} — update this script")
    return {"permissions": "\n".join(perms), "services": "\n".join(services), "disclosure": norm(m.group(0))}


def digest(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


LINE = re.compile(r"^fingerprint (\w+): (\w+)$", re.M)


def main() -> None:
    now = {k: digest(v) for k, v in parts().items()}
    doc = DOC.read_text(encoding="utf-8")
    if sys.argv[1:] == ["--update"]:
        block = "\n".join(f"fingerprint {k}: {v}" for k, v in now.items())
        doc = re.sub(r"```text\s*```", "", LINE.sub("", doc)).rstrip()
        doc += f"\n\n```text\n{block}\n```\n"
        DOC.write_text(doc, encoding="utf-8", newline="\n")
        print("recorded:", ", ".join(f"{k} {v}" for k, v in now.items()))
        return
    if sys.argv[1:]:
        sys.exit(__doc__)
    recorded = dict(LINE.findall(doc))
    changed = [k for k in now if recorded.get(k) != now[k]]
    if not changed:
        print("Play declarations in sync:", ", ".join(now))
        return
    print(f"FAIL: changed since the Play declarations were last checked: {', '.join(changed)}")
    print("""
Before merging, re-check against store/play-declarations.md and Play Console:
  - permissions: new or removed permissions may need a declaration (location, foreground service,
    full-screen intent, ...) or make one obsolete; the Data safety form may change too.
  - services: a foreground service type change needs the foreground-service declaration (and video).
  - disclosure: the declaration text and the background-location video must match what the dialog says;
    the privacy policy (docs/privacy/) must cover every use the dialog lists.
Then record the new state:  python scripts/check-declarations.py --update""")
    sys.exit(1)


if __name__ == "__main__":
    main()
