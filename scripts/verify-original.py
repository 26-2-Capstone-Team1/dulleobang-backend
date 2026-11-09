"""Read-only original-source comparison."""
from pathlib import Path
import hashlib
import json
import sys
ROOT = Path(__file__).resolve().parents[1]
original = ROOT.parent / "dulleobang-backend"
before = json.loads((ROOT / "verification/original-before.json").read_text(encoding="utf-8"))
ignored = {".git", ".venv", "data", "__pycache__", ".pytest_cache", ".ruff_cache"}
after = {p.relative_to(original).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
         for p in original.rglob("*") if p.is_file() and not any(s in ignored for s in p.relative_to(original).parts)}
report = {"passed": before == after, "filesChecked": len(before),
          "modified": sorted(k for k in before.keys() & after.keys() if before[k] != after[k]),
          "removed": sorted(before.keys() - after.keys()), "added": sorted(after.keys() - before.keys()),
          "excluded": sorted(ignored)}
(ROOT / "verification/original-after.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(report, ensure_ascii=True))
sys.exit(0 if report["passed"] else 1)
