"""Stage 6 gate: fail on NEW governance violations, never on the registered baseline."""
import re
import subprocess
import sys
from pathlib import Path

BASELINE = Path("docs/governance-baseline.md")
TEST = Path(".claude/skills/iso-compliance/scripts/test_docs_governance.py")


def failures(text: str) -> set[str]:
    # pytest's subTest short-summary lines read "SUBFAILED(...)" instead of "FAILED" (pytest 9.x).
    return {
        line.strip()
        for line in text.splitlines()
        if re.match(r"^(FAILED|SUBFAILED\(|.*::.* FAILED)", line.strip())
    }


def main() -> int:
    proc = subprocess.run(
        [sys.executable, "-m", "pytest", str(TEST), "-q"], capture_output=True, text=True
    )
    current = failures(proc.stdout + proc.stderr)
    registered = failures(BASELINE.read_text(encoding="utf-8"))
    new = current - registered
    if new:
        print("NEW governance violations (not in baseline):")
        for line in sorted(new):
            print(" ", line)
        return 1
    fixed = registered - current
    if fixed:
        print(f"{len(fixed)} baseline item(s) now pass — prune docs/governance-baseline.md.")
    print(f"governance OK: {len(current)} known, 0 new")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
