"""Server-independent HTTP scenarios; no import from FastAPI."""
from pathlib import Path
from uuid import uuid4
import argparse
import json
import urllib.request
import urllib.error

def run(base_url, profile):
    results = []
    def request(method, path, expected, body=None, token=None, code=None):
        headers = {"Content-Type": "application/json", "Idempotency-Key": str(uuid4())}
        if token: headers["Authorization"] = "Bearer " + token
        raw = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(base_url.rstrip("/") + path, data=raw, headers=headers, method=method)
        try: response = urllib.request.urlopen(req, timeout=15)
        except urllib.error.HTTPError as exc: response = exc
        data = response.read()
        value = json.loads(data) if data else None
        ok = response.status == expected and (code is None or (value or {}).get("code") == code)
        results.append({"method": method, "path": path, "expectedStatus": expected, "actualStatus": response.status, "passed": ok})
        if not ok: raise AssertionError(f"{method} {path}: expected {expected}/{code}, got {response.status} {value}")
        return value
    request("GET", "/health/live", 200)
    request("GET", "/health/ready", 200)
    request("GET", "/api/listings", 401, code="UNAUTHORIZED")
    request("GET", "/api/v1/users/me", 401, code="UNAUTHORIZED")
    request("GET", "/api/unknown-route", 404)
    if profile == "foundation":
        request("POST", "/api/auth/signup", 422, {"email": "scope@example.com"}, code="VALIDATION_FAILED")
        return results
    email = f"contract-{uuid4().hex}@example.com"
    password = "ContractOnlyPassword!123"
    user = request("POST", "/api/auth/signup", 201, {"email": email, "password": password, "displayName": "contract"})
    pair = request("POST", "/api/auth/login", 200, {"email": email, "password": password})
    token = pair["accessToken"]
    assert user["userId"] == pair["user"]["userId"]
    request("GET", "/api/users/me", 200, token=token)
    furniture = request("POST", "/api/furniture", 201, {"name": "contract bed", "category": "bed"}, token)
    canonical = request("GET", "/api/v1/furniture/" + furniture["furnitureId"], 200, token=token)
    assert canonical["category"] == "BED"
    request("POST", "/api/furniture", 422, {"name": "bad", "category": "unknown"}, token)
    listing = request("POST", "/api/listings", 201, {
        "location": "서울 동작구 상도로 10", "dealType": "MONTHLY", "price": {"deposit": 500, "monthly": 31},
        "maintenanceFee": 5, "roomType": "ONE_ROOM", "area": 23.8, "availableFrom": "IMMEDIATE",
        "rentalOptions": ["aircon"], "latitude": 37.5, "longitude": 126.9
    }, token)
    value = request("GET", "/api/v1/listings/" + listing["listingId"], 200, token=token)
    assert value["price"]["deposit"] == 5_000_000
    pair = request("POST", "/api/auth/refresh", 200, {"refreshToken": pair["refreshToken"]})
    request("POST", "/api/auth/logout", 204, {"refreshToken": pair["refreshToken"]})
    request("GET", "/api/users/me", 401, token=pair["accessToken"], code="UNAUTHORIZED")
    return results

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--profile", choices=["legacy", "foundation"], default="legacy")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    report = {"profile": args.profile, "baseUrl": args.base_url, "passed": False}
    try:
        report["checks"] = run(args.base_url, args.profile)
        report["passed"] = True
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{len(report['checks'])} HTTP checks passed ({args.profile})")
