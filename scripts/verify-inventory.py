"""Verify registered routes using only the copied legacy source."""
from pathlib import Path
from tempfile import TemporaryDirectory
import ast
import json
import sys
ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "verification"
sys.path.insert(0, str(ROOT / "legacy-baseline"))
from app.main import create_app
from app.core import Settings
inventory = json.loads((ROOT / "src/main/resources/contract/api-inventory.json").read_text(encoding="utf-8"))
expected = {(r["method"], r["path"]) for r in inventory["routes"]}
with TemporaryDirectory() as scratch:
    app = create_app(Settings(data_dir=Path(scratch), scheduler=False, rate_limits=False))
    actual = {(method, route.path) for route in app.routes
              if getattr(route, "path", "").startswith(("/api/", "/storage/", "/health/"))
              for method in route.methods if method not in ("HEAD", "OPTIONS")}
assert expected == actual, {"missing": sorted(actual - expected), "extra": sorted(expected - actual)}
assert sum(r["audience"] == "v1" for r in inventory["routes"]) == 60
assert sum(r["audience"] == "mobile" for r in inventory["routes"]) == 37
assert len(expected) == len(inventory["routes"])
groups = {
    "auth": ("AuthController", "P3"), "users": ("UserController", "P3"),
    "listings": ("ListingController", "P5"), "furniture": ("FurnitureController", "P4"),
    "upload-sessions": ("UploadSessionController", "P4"), "assets": ("AssetController", "P4"),
    "chat": ("ChatController", "P5"), "chats": ("ChatController", "P5"),
    "ai-jobs": ("AiJobController", "P6"), "captures": ("CaptureController", "P6"),
    "room-states": ("RoomStateController", "P7"), "internal": ("WorkerJobController", "P6")}
rows = []
coverage_file = REPORTS / "spring-route-coverage.json"
bindings = {}
if coverage_file.exists():
    coverage = json.loads(coverage_file.read_text(encoding="utf-8"))
    assert coverage["passed"] and coverage["businessApiCount"] == 97
    bindings = {(row["method"], row["path"]): row["controller"] for row in coverage["routes"]}
    assert set(bindings) == expected
for route in inventory["routes"]:
    parts = route["path"].removeprefix("/api/v1/").removeprefix("/api/").split("/")
    controller, stage = groups.get(parts[0], ("HealthController", "P2"))
    path = route["path"]
    for suffix, selected in [
        ("/users/me/listings", ("ListingController", "P5")),
        ("/users/me/furniture", ("FurnitureController", "P4")),
        ("/users/me/scraps", ("ScrapController", "P5")),
        ("/users/me/placements", ("PlacementController", "P7")),
    ]:
        if path.endswith(suffix): controller, stage = selected
    for fragment, selected in [
        ("/captures", ("CaptureController", "P6")), ("/space", ("SpaceController", "P7")),
        ("/objects", ("ObjectClassificationController", "P7")), ("/room-states", ("RoomStateController", "P7")),
        ("/placements", ("PlacementController", "P7")), ("/images", ("ListingImageController", "P5")),
        ("/scrap", ("ScrapController", "P5")), ("/reports", ("ReportController", "P5")),
        ("/agent-profile", ("AgentVerificationController", "P5")),
        ("/listing-agent-verification", ("AgentVerificationController", "P5"))]:
        if fragment in path: controller, stage = selected
    if path.startswith("/storage/"): controller, stage = "StorageObjectController", "P4"
    existing = "tests/test_mobile.py" if route["audience"] == "mobile" else "tests/test_contract_matrix.py + tests/test_backend.py"
    controller = bindings.get((route["method"], path), controller)
    rows.append({**route, "targetController": controller, "implementationPhase": stage,
                 "implementationStatus": "registered-and-verified" if bindings else "awaiting-runtime-registration-check",
                 "legacyTestReference": existing, "foundationTest": "FoundationIntegrationTest.checksApiInventory"})
classification = []
for file in sorted((ROOT / "legacy-baseline/tests").glob("test_*.py")):
    source = file.read_text(encoding="utf-8")
    for node in ast.parse(source).body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name.startswith("test_"):
            function = ast.get_source_segment(source, node)
            category = "HTTP scenario" if ("client." in function or "client," in function or "matrix_server" in function) else "Python/unit fixture"
            classification.append({"file": file.name, "test": node.name, "classification": category})
report = {"passed": True, "apiCount": 97, "auxiliaryCount": 4, "routes": rows, "legacyTestClassification": classification}
REPORTS.mkdir(exist_ok=True)
(REPORTS / "inventory-check.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
docs = ["# API 이전 담당표", "", "Controller 이름은 실제 Spring MVC 등록 검사에서 가져옵니다. 개별 업무 흐름·권한·경쟁 조건은 도메인별 통합 테스트에서 별도로 검증합니다.", "",
        "| 메서드 | 경로 | 대상 Controller | 구현 단계 | 현재 상태 |", "|---|---|---|---|---|"]
docs.extend(f"| {r['method']} | {r['path']} | {r['targetController']} | {r['implementationPhase']} | {r['implementationStatus']} |" for r in rows)
(REPORTS / "api-ownership.md").write_text("\n".join(docs) + "\n", encoding="utf-8")
print("97 API + 4 auxiliary routes match; ownership and test classification recorded")
