#!/usr/bin/env python3
"""Check that every relative link in the repository Markdown files resolves.

Used by CI and before publishing the repository, so a renamed docs/data file
never leaves a broken link on the GitHub landing page.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SKIP_DIRS = {".git", "build", ".gradle", ".kotlin", "__pycache__", "node_modules", ".idea"}
LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")


def markdown_files() -> list[Path]:
    files: list[Path] = []
    for path in ROOT.rglob("*.md"):
        rel = path.relative_to(ROOT)
        if any(part in SKIP_DIRS for part in rel.parts):
            continue
        files.append(path)
    return sorted(files)


def normalise_target(raw: str) -> str | None:
    target = raw.strip()
    if target.startswith("<") and target.endswith(">"):
        target = target[1:-1].strip()
    if not target or target.startswith(("http://", "https://", "mailto:", "#", "tel:")):
        return None
    if target.startswith(("C:", "D:", "E:")):
        return None
    # Templates intentionally reference files the student creates later
    # (e.g. data/raw/wifi/<session>/photos/P01.jpg), skip placeholders.
    if "<" in target or ">" in target or "{{" in target:
        return None
    # Drop anchors / query strings but keep URL-encoded spaces.
    target = target.split("#", 1)[0].split("?", 1)[0]
    if not target:
        return None
    return target.replace("%20", " ")


def main() -> int:
    broken: list[tuple[Path, str]] = []
    checked = 0
    for md in markdown_files():
        text = md.read_text(encoding="utf-8", errors="replace")
        # Ignore fenced code blocks: links there are usually examples.
        text = re.sub(r"```.*?```", "", text, flags=re.DOTALL)
        for match in LINK_RE.finditer(text):
            target = normalise_target(match.group(1))
            if target is None:
                continue
            checked += 1
            resolved = (md.parent / target).resolve()
            if not resolved.exists():
                broken.append((md.relative_to(ROOT), target))

    print(f"checked {checked} relative links in {len(markdown_files())} markdown files")
    if broken:
        print(f"\nBROKEN LINKS ({len(broken)}):")
        for md, target in broken:
            print(f"  {md}: {target}")
        return 1
    print("all relative links resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())