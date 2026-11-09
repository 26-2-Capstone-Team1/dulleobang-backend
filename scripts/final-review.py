"""Aggregate current evidence without claiming external validation."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[1]
build_log_path = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "verification/sam3d-full-test.log"
build_bytes = build_log_path.read_bytes()
build_log = build_bytes.decode("utf-16" if build_bytes.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")
assert "BUILD SUCCESSFUL" in build_log and "BUILD FAILED" not in build_log, "The final Java build must pass before aggregating evidence"
for script in ["verify-original.py", "check-contract-files.py", "verify-inventory.py"]:
    subprocess.run([sys.executable, str(ROOT / "scripts" / script)], check=True)
read = lambda name: json.loads((ROOT / "verification" / name).read_text(encoding="utf-8"))
tests = []
for file in sorted((ROOT / "verification/junit").glob("TEST-*.xml")):
    suite = ET.parse(file).getroot()
    tests.append({"suite": suite.attrib["name"], **{key: int(suite.attrib.get(key, 0)) for key in ["tests", "failures", "errors", "skipped"]}})
required = {"AuthIntegrationTest", "UploadFurnitureIntegrationTest", "ProductIntegrationTest", "CaptureJobIntegrationTest", "SpaceResultIntegrationTest", "PlacementIntegrationTest", "RatePolicyIntegrationTest", "AssetRetentionIntegrationTest", "AdminOperationsIntegrationTest", "ProductionGeometryTest", "FoundationIntegrationTest", "CompatibilityTest", "ArchitectureTest", "OAuthProviderTest", "FailureHandlingTest"}
assert required <= {test["suite"].rsplit(".", 1)[-1] for test in tests}
assert "MethodStructureTest" in {test["suite"].rsplit(".", 1)[-1] for test in tests}
assert "Sam3dIntegrationTest" in {test["suite"].rsplit(".", 1)[-1] for test in tests}
assert "UploadIngestIntegrationTest" in {test["suite"].rsplit(".", 1)[-1] for test in tests}
assert all(test["failures"] == test["errors"] == test["skipped"] == 0 for test in tests)
original = read("original-after.json")
routes = read("spring-route-coverage.json")
assert original["passed"] and routes["passed"] and len(routes["routes"]) == 101
extensions = json.loads((ROOT / "src/main/resources/contract/extensions/sam3d.json").read_text(encoding="utf-8"))["routes"]
assert {(r["method"],r["path"]) for r in routes["extensionRoutes"]} == {(r["method"],r["path"]) for r in extensions}
http = {name: read(name) for name in ["spring-product-http-contracts.json", "foundation-http-contracts.json", "python-worker-http-contracts.json"]}
assert all(report["passed"] for report in http.values())
frozen = read("original-before.json")
for folder in ["app", "tests"]:
    for file in (ROOT / "legacy-baseline" / folder).rglob("*.py"):
        relative = file.relative_to(ROOT / "legacy-baseline").as_posix()
        assert hashlib.sha256(file.read_bytes()).hexdigest() == frozen[relative], relative
java = list((ROOT / "src/main/java").rglob("*.java"))
preflight = read("migration-preflight.json")
assert preflight["passed"] and preflight["sourceUnchanged"]
report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "scope": "P0-P7 plus completed SAM3D furniture import; P8 preflight only",
    "passed": True, "javaTestsPassed": sum(test["tests"] for test in tests), "javaSuites": tests,
    "mainJavaFiles": len(java), "businessApiCount": 97, "auxiliaryRoutes": 4, "sam3dExtensionRoutes": 2,
    "controllerRegistration": routes["evidence"], "original": original,
    "httpChecks": {name: len(value["checks"]) for name, value in http.items()},
    "database": "real native PostgreSQL 17.11; Flyway V1-V7; actual TCP HTTP",
    "migrationPreflight": preflight,
    "reviewedFixes": ["deleted target job access and maintenance continuation", "owner/target lock order and concurrent ZIP quota", "null-body JCS idempotency and retry", "PostgreSQL artifact lease timestamp cast", "mobile chat read receipts", "fresh signed URLs on furniture and idempotent photos/chat", "query checks with mobile aliases", "auth/user/URL/AI/poll/worker rate policies", "shared blob retention and concurrent upload/cleanup locks", "expired upload and orphan staging cleanup", "media and multipart errors", "result and capture responsibility separation"],
    "structureReview": "docs/structure-review.md; ArchitectureTest and MethodStructureTest enforce layer boundaries and concise methods",
    "serverAvailability": "SAM3D loopback port unavailable; live GPU endpoint still needed",
    "notValidated": ["actual server deployment and traffic cutover", "nonempty SQLite-to-PostgreSQL full migration/importer", "iPhone", "live Kakao/Google OAuth", "external AI engine", "Docker image runtime", "remote GitHub CI"],
    "p8Complete": False,
    "limitations": "Legacy database: zero business rows, zero objects. Preflight is not an importer. Real server absence was confirmed by the user. Original data and traffic were not changed.",
}
(ROOT / "verification/final-review.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({"passed": True, "javaTests": report["javaTestsPassed"], "javaFiles": len(java), "api": 97, "originalFiles": original["filesChecked"], "p8Complete": False}, ensure_ascii=False))
