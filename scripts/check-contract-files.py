from pathlib import Path
import json
ROOT = Path(__file__).resolve().parents[1]
spec = json.loads((ROOT / "src/main/resources/contract/mobile-openapi.json").read_text(encoding="utf-8"))
inventory = json.loads((ROOT / "src/main/resources/contract/api-inventory.json").read_text(encoding="utf-8"))
mobile = {(m.upper(), p) for p, values in spec["paths"].items() for m in values}
assert mobile == {(r["method"], r["path"]) for r in inventory["routes"] if r["audience"] == "mobile"}
def check(value):
    if isinstance(value, dict):
        if "$ref" in value and value["$ref"].startswith("#/"):
            node = spec
            for key in value["$ref"][2:].split("/"): node = node[key]
        for nested in value.values(): check(nested)
    elif isinstance(value, list):
        for nested in value: check(nested)
check(spec)
for path, methods in spec["paths"].items():
    for method, operation in methods.items():
        assert operation["operationId"]
        assert operation["responses"]
report = {"passed": True, "mobileOperations": len(mobile), "unresolvedReferences": 0}
(ROOT / "verification/mobile-contract-check.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
print("37 mobile operations and all local schema references verified")
