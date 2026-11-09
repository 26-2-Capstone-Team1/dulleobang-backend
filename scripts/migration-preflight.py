"""Read a stable copy of legacy SQLite and verify content-addressed files.

This is a preflight report, never an importer or a traffic cutover command.
Reports contain counts/hashes only; resource data and secrets stay out of reports.
"""
from argparse import ArgumentParser
from collections import Counter
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
import json
import shutil
import sqlite3


def digest(path):
    h = sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def inspect(source, scratch):
    source = Path(source).resolve(strict=True)
    database = source / "backend.sqlite3"
    originals = [database]
    if Path(str(database) + "-wal").exists():
        originals.append(Path(str(database) + "-wal"))
    before = {p.name: digest(p) for p in originals}
    target = Path(scratch) / "backend.sqlite3"
    for path in originals:
        shutil.copyfile(path, Path(scratch) / path.name)
    after = {p.name: digest(p) for p in originals}
    if before != after:
        raise RuntimeError("SOURCE_CHANGED_DURING_COPY: stop writes and repeat the preflight")
    if Path(str(database) + "-wal").exists() != (len(originals) == 2):
        raise RuntimeError("SOURCE_WAL_CHANGED_DURING_COPY")
    connection = sqlite3.connect(str(target))
    connection.row_factory = sqlite3.Row
    try:
        integrity = [r[0] for r in connection.execute("PRAGMA quick_check")]
        if integrity != ["ok"]:
            raise RuntimeError("SQLITE_INTEGRITY_FAILED")
        tables = [r[0] for r in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        allowed = {"resources", "credentials", "sessions", "refresh_tokens", "idempotency", "outbox", "queue", "rate_limits", "schema_migrations"}
        if set(tables) != allowed:
            raise RuntimeError("UNSUPPORTED_SOURCE_SCHEMA")
        counts = {name: connection.execute('SELECT COUNT(*) FROM "' + name + '"').fetchone()[0] for name in sorted(allowed)}
        resources = list(connection.execute("SELECT kind,id,owner,parent,data,deleted_at FROM resources"))
    finally:
        connection.close()
    known = {(row["kind"], row["id"]) for row in resources}
    kinds = Counter()
    deleted = Counter()
    issues = Counter()
    files = []
    active_jobs = 0
    for row in resources:
        kind = row["kind"]
        kinds[kind] += 1
        deleted[kind] += row["deleted_at"] is not None
        value = json.loads(row["data"], parse_constant=lambda value: (_ for _ in ()).throw(ValueError("NONFINITE_JSON")))
        if kind not in {"user", "audit", "cursor"} and ("user", row["owner"]) not in known:
            issues["missingOwner"] += 1
        if kind == "job" and value.get("status") in {"RUNNING", "QUEUED", "RETRY_WAIT"}:
            active_jobs += 1
        if kind == "file" and value.get("_stored"):
            files.append(value)
    blobs = {}
    objects = source / "objects"
    if objects.exists():
        for path in objects.iterdir():
            if path.is_symlink() or not path.is_file():
                issues["unexpectedObjectEntry"] += 1
                continue
            actual = digest(path)
            if actual != path.name or len(path.name) != 64:
                issues["objectHashMismatch"] += 1
            blobs[path.name] = path.stat().st_size
    for value in files:
        if value["sha256"] not in blobs:
            issues["missingStoredBlob"] += 1
        elif blobs[value["sha256"]] != value["byteSize"]:
            issues["storedBlobSizeMismatch"] += 1
    # Recheck every copied source file after the entire inspection, including WAL state.
    if before != {p.name: digest(p) for p in originals}:
        raise RuntimeError("SOURCE_CHANGED_DURING_PREFLIGHT")
    return {"passed": not issues, "sourceUnchanged": True, "sqliteIntegrity": "ok",
            "sourceDatabaseSha256": before[database.name], "tableCounts": counts,
            "resourceCounts": dict(kinds), "deletedResourceCounts": dict(deleted),
            "objectCount": len(blobs), "objectBytes": sum(blobs.values()),
            "unreferencedObjectCount": len(set(blobs) - {v["sha256"] for v in files}),
            "activeJobs": active_jobs, "readyForOfflineExport": not issues and active_jobs == 0,
            "issues": dict(issues), "scope": "read-only source snapshot preflight; no PostgreSQL import or cutover"}


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--source-data", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    source = args.source_data.resolve(strict=True)
    report = args.report.resolve()
    if report.is_relative_to(source):
        parser.error("The report must be outside the legacy data directory")
    scratch_root = Path(__file__).resolve().parents[1] / ".runtime" / "migration-preflight"
    scratch_root.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=scratch_root) as scratch:
        result = inspect(source, scratch)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": result["passed"], "resources": result["tableCounts"]["resources"], "activeJobs": result["activeJobs"], "objects": result["objectCount"]}))
    return 0 if result["readyForOfflineExport"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
