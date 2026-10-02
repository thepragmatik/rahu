#!/usr/bin/env python3
"""Check every relative link in the HTML doc set resolves to a real file.

The pages are hand-built, so a moved source file breaks a link silently: nothing
in the build reads these hrefs, and a browser simply renders a dead link. This
was added after eleven links broke from one cause -- the pages live in
docs/architecture-html/, so "../docs/adr/x.md" resolved to docs/docs/adr/x.md.
A doubled path segment is easy to write and invisible until someone clicks it.

Run: python3 check_links.py     (exit 1 on any missing target)
"""
import os
import re
import sys
import glob

HERE = os.path.dirname(os.path.abspath(__file__))
LINK = re.compile(r'(?:href|src)="([^"#?]+)"')


def main() -> int:
    missing = []
    checked = 0
    for page in sorted(glob.glob(os.path.join(HERE, "*.html"))):
        name = os.path.basename(page)
        body = open(page, encoding="utf-8").read()
        for target in LINK.findall(body):
            if target.startswith(("http://", "https://", "mailto:", "data:")):
                continue
            checked += 1
            resolved = os.path.normpath(os.path.join(HERE, target))
            if not os.path.exists(resolved):
                missing.append((name, target))

    if missing:
        print("BROKEN LINKS:")
        for name, target in missing:
            print(f"  {name:20} -> {target}")
        print(f"\n{len(missing)} broken of {checked} checked")
        return 1
    print(f"all {checked} relative links resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
