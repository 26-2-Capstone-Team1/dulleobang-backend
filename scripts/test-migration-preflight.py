"""Exercise snapshot integrity and file mismatch detection using disposable data."""
from pathlib import Path
from tempfile import TemporaryDirectory
import hashlib
import importlib.util
import json
import sqlite3
from contextlib import closing

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("preflight", ROOT / "scripts/migration-preflight.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
scratch_root = ROOT / ".runtime" / "migration-preflight-tests"
scratch_root.mkdir(parents=True, exist_ok=True)
with TemporaryDirectory(dir=scratch_root) as folder:
    base = Path(folder)
    source = base / "source"
    source.mkdir()
    (source / "objects").mkdir()
    with closing(sqlite3.connect(str(source / "backend.sqlite3"))) as connection:
        connection.executescript((ROOT / "legacy-baseline/app/migrations/001_initial.sql").read_text(encoding="utf-8"))
        connection.commit()
    first = base / "first"
    first.mkdir()
    result = module.inspect(source, first)
    assert result["passed"] and result["tableCounts"]["resources"] == 0
    original = module.digest(source / "backend.sqlite3")
    raw = b"immutable fixture"
    digest = hashlib.sha256(raw).hexdigest()
    (source / "objects" / digest).write_bytes(raw)
    second = base / "second"
    second.mkdir()
    assert module.inspect(source, second)["unreferencedObjectCount"] == 1
    assert module.digest(source / "backend.sqlite3") == original
    (source / "objects" / digest).write_bytes(b"corrupted")
    third = base / "third"
    third.mkdir()
    bad = module.inspect(source, third)
    assert not bad["passed"] and bad["issues"]["objectHashMismatch"] == 1
    assert module.digest(source / "backend.sqlite3") == original
print("3 migration preflight integrity checks passed; source unchanged")
