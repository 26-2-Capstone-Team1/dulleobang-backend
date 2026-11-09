import copy
import hashlib
import json
import struct
import re
from io import BytesIO
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.core import Settings, bump, stamp, uid, worker_token
from app.main import create_app

covered_operations = set()
operation_statuses = {}
http_request_count = 0


@pytest.fixture(autouse=True)
def record_http_operations(monkeypatch):
    original = TestClient.request
    spec_ops = json.loads(
        (Path(__file__).parents[1] / "app/contract/operations.json").read_text(encoding="utf-8")
    )

    def tracked(self, method, url, *args, **kwargs):
        global http_request_count
        response = original(self, method, url, *args, **kwargs)
        http_request_count += 1
        for op in spec_ops:
            pattern = "^/api/v1" + re.sub(r"\{[^}]+\}", "[^/]+", op["path"]) + "$"
            if method.lower() == op["method"] and re.fullmatch(pattern, response.request.url.path):
                counts = operation_statuses.setdefault(op["oid"], {})
                status = str(response.status_code)
                counts[status] = counts.get(status, 0) + 1
                if 200 <= response.status_code < 300:
                    covered_operations.add(op["oid"])
        return response

    monkeypatch.setattr(TestClient, "request", tracked)


def pytest_sessionfinish(session, exitstatus):
    ops = json.loads((Path(__file__).parents[1] / "app/contract/operations.json").read_text(encoding="utf-8"))
    missing = sorted({op["oid"] for op in ops} - covered_operations)
    # Persist human-reviewable operation coverage, without request bodies or tokens.
    destination = Path(__file__).parents[1] / "verification.json"
    destination.write_text(
        json.dumps(
            {
                "testExitCode": int(exitstatus),
                "operationCount": len(ops),
                "successfulOperations": sorted(covered_operations),
                "missingSuccessfulOperations": missing,
                "httpRequestCount": http_request_count,
                "observedOperationStatuses": operation_statuses,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )


@pytest.fixture
def app(tmp_path):
    settings = Settings(data_dir=tmp_path, scheduler=False, rate_limits=False)
    return create_app(settings)


@pytest.fixture
def client(app):
    with TestClient(app) as client:
        yield client


@pytest.fixture
def actors(client, app):
    def account(email, listing_agent=False):
        r = client.post(
            "/api/v1/auth/signup",
            json={"email": email, "password": "StrongPassword!123", "displayName": "테스트 사용자"},
        )
        assert r.status_code == 201, r.text
        user = r.json()
        if listing_agent:
            with app.state.database.transaction() as s:
                user["roles"].append("LISTING_AGENT")
                s.put("user", user["userId"], bump(user), user["userId"])
        r = client.post("/api/v1/auth/login", json={"email": email, "password": "StrongPassword!123"})
        assert r.status_code == 200, r.text
        return {
            "user": r.json()["user"],
            "pair": r.json(),
            "headers": {"Authorization": "Bearer " + r.json()["accessToken"]},
        }

    listing_agent = account("listing_agent@example.com", True)
    tenant = account("tenant@example.com")
    stranger = account("stranger@example.com")
    worker = {"Authorization": "Bearer " + worker_token(app.state.settings, "test-worker")}
    return {"listing_agent": listing_agent, "tenant": tenant, "stranger": stranger, "worker": worker}


def create(client, path, body, actor, expected=201):
    r = client.post("/api/v1" + path, json=body, headers={**actor["headers"], "Idempotency-Key": uid()})
    assert r.status_code == expected, r.text
    return r


def example(name):
    spec = json.loads((Path(__file__).parents[1] / "app/contract/openapi.json").read_text(encoding="utf-8"))
    return copy.deepcopy(spec["components"]["schemas"][name]["examples"][0])


def listing(client, actors):
    body = example("ListingCreate")
    body["title"] = "검증용 매물"
    return create(client, "/listings", body, actors["listing_agent"]).json()


def image():
    out = BytesIO()
    Image.new("RGB", (640, 480), "white").save(out, "JPEG")
    return out.getvalue()


def info(raw, filename, mime):
    return {
        "clientFileId": uid(),
        "fileName": filename,
        "contentType": mime,
        "byteSize": len(raw),
        "sha256": hashlib.sha256(raw).hexdigest(),
    }


def put(client, grant, raw):
    r = client.put(grant["url"], content=raw, headers=grant["headers"])
    assert r.status_code == 200, r.text


def upload(client, actor, target_type, target_id, purpose, *, corrupt_manifest=False):
    photo = purpose == "LISTING_PHOTO"
    count = 1 if photo else (30 if purpose == "ROOM_CAPTURE" else 12)
    raw = image()
    files = [info(raw, f"image{i}.jpg", "image/jpeg") for i in range(count)]
    raws = [raw] * count
    if not photo:
        manifest = {
            "schemaVersion": "1.0",
            "platform": "ARCORE",
            "coordinateSystem": "RH_Y_UP_Z_BACK",
            "unit": "METER",
            "matrixLayout": "COLUMN_MAJOR",
            "captureStartedAt": stamp(),
            "frames": [
                {
                    "imageClientFileId": f["clientFileId"],
                    "timestampUs": i * 100000,
                    "widthPx": 640,
                    "heightPx": 480,
                    "intrinsics": {"fx": 500, "fy": 500, "cx": 320, "cy": 240},
                    "cameraToWorld": [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, i * 0.1, 0, 0, 1],
                    "trackingState": "NORMAL",
                }
                for i, f in enumerate(files)
            ],
        }
        if corrupt_manifest:
            manifest["frames"][1]["timestampUs"] = 0
        raw_manifest = json.dumps(manifest).encode()
        files.append(info(raw_manifest, "camera.json", "application/json"))
        raws.append(raw_manifest)
    response = create(
        client,
        "/upload-sessions",
        {"purpose": purpose, "targetType": target_type, "targetId": target_id, "files": files},
        actor,
    )
    data = response.json()
    for grant, raw in zip(data["uploads"], raws):
        put(client, grant, raw)
    verified = client.post(
        "/api/v1/upload-sessions/" + data["session"]["uploadSessionId"] + "/complete",
        headers=actor["headers"],
    )
    if corrupt_manifest:
        assert verified.status_code == 422, verified.text
    else:
        assert verified.status_code == 200, verified.text
    return data


def capture(client, actors, target_type, target_id, actor=None):
    actor = actor or actors["listing_agent" if target_type == "LISTING" else "tenant"]
    session = upload(
        client,
        actor,
        target_type,
        target_id,
        "ROOM_CAPTURE" if target_type == "LISTING" else "FURNITURE_CAPTURE",
    )
    resource = "/listings/" if target_type == "LISTING" else "/furniture/"
    accepted = create(
        client,
        resource + target_id + "/captures",
        {"uploadSessionId": session["session"]["uploadSessionId"]},
        actor,
        202,
    )
    return accepted.json()


def claim(client, actors, job_id):
    r = client.post(
        "/api/v1/internal/ai-jobs/" + job_id + "/claim",
        json={"workerId": "test-worker"},
        headers=actors["worker"],
    )
    assert r.status_code == 200, r.text
    return {**actors["worker"], "X-Job-Lease-Token": r.json()["leaseToken"]}, r.json()


def glb(names=("mesh",)):
    positions = struct.pack("<9f", -0.5, 0, -0.5, 0.5, 0, -0.5, 0, 1, 0.5)
    doc = {
        "asset": {"version": "2.0"},
        "scene": 0,
        "scenes": [{"nodes": list(range(len(names)))}],
        "nodes": [{"name": n, "mesh": 0} for n in names],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}}]}],
        "accessors": [
            {
                "bufferView": 0,
                "componentType": 5126,
                "count": 3,
                "type": "VEC3",
                "min": [-0.5, 0, -0.5],
                "max": [0.5, 1, 0.5],
            }
        ],
        "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": len(positions)}],
        "buffers": [{"byteLength": len(positions)}],
    }
    body = json.dumps(doc).encode()
    body += b" " * (-len(body) % 4)
    return (
        struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(body) + 8 + len(positions))
        + struct.pack("<II", len(body), 0x4E4F534A)
        + body
        + struct.pack("<II", len(positions), 0x004E4942)
        + positions
    )


def ply():
    names = "x y z f_dc_0 f_dc_1 f_dc_2 opacity scale_0 scale_1 scale_2 rot_0 rot_1 rot_2 rot_3".split()
    return (
        "ply\nformat binary_little_endian 1.0\ncomment dulleobang_gs_ply_v1\nelement vertex 1\n"
        + "".join("property float " + n + "\n" for n in names)
        + "end_header\n"
    ).encode() + struct.pack("<14f", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0)


def artifacts(client, job_id, headers, files):
    profile = {
        "SCENE": ("application/octet-stream", "DULLEOBANG_GS_PLY_V1"),
        "GEOMETRY": ("application/json", "ROOM_GEOMETRY_V1"),
        "MODEL": ("model/gltf-binary", "GLB_V2"),
        "COLLISION": ("model/gltf-binary", "GLB_V2"),
    }
    body = {
        "files": [
            {
                "clientFileId": uid(),
                "role": role,
                "contentType": profile[role][0],
                "format": profile[role][1],
                "byteSize": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
            for role, raw in files.items()
        ]
    }
    r = client.post("/api/v1/internal/ai-jobs/" + job_id + "/artifacts", json=body, headers=headers)
    assert r.status_code == 200, r.text
    assets = {}
    for desc, grant in zip(body["files"], r.json()["uploads"]):
        put(client, grant, files[desc["role"]])
        assets[desc["role"]] = {k: desc[k] for k in ("contentType", "byteSize", "sha256", "format")}
        assets[desc["role"]]["fileId"] = grant["fileId"]
    return assets


def complete_room(client, actors, job_id, bundle=None):
    headers, assignment = claim(client, actors, job_id)
    bundle = bundle or example("SpaceBundle")
    # Use a clockwise XZ polygon: counterclockwise as viewed from +Y.
    for room in bundle["geometry"]["rooms"]:
        p = room["floorPolygon"]
        area = (
            sum(p[i][0] * p[(i + 1) % len(p)][1] - p[(i + 1) % len(p)][0] * p[i][1] for i in range(len(p)))
            / 2
        )
        if area > 0:
            p.reverse()
    for obj in bundle["objects"]:
        obj["ownershipType"] = "UNKNOWN"
    names = (
        [o["collisionNode"] for o in bundle["objects"]]
        + [s["nodeName"] for s in bundle["geometry"]["structuralColliders"]]
        + [d["sweepNode"] for d in bundle["geometry"]["doors"] if d["sweepNode"]]
    )
    a = artifacts(
        client,
        job_id,
        headers,
        {"SCENE": ply(), "GEOMETRY": json.dumps(bundle["geometry"]).encode(), "COLLISION": glb(names)},
    )
    bundle.update(sceneAsset=a["SCENE"], geometryAsset=a["GEOMETRY"], collisionAsset=a["COLLISION"])
    body = {"type": "ROOM_RECONSTRUCTION", "bundle": bundle}
    r = client.post("/api/v1/internal/ai-jobs/" + job_id + "/complete", json=body, headers=headers)
    assert r.status_code == 200, r.text
    return bundle, headers, body


def publish(client, actors, listing_id):
    data = upload(client, actors["listing_agent"], "LISTING", listing_id, "LISTING_PHOTO")
    r = client.get("/api/v1/listings/" + listing_id, headers=actors["listing_agent"]["headers"])
    r = client.patch(
        "/api/v1/listings/" + listing_id,
        json={"photoFileIds": [data["session"]["files"][0]["fileId"]]},
        headers={**actors["listing_agent"]["headers"], "If-Match": r.headers["etag"]},
    )
    assert r.status_code == 200, r.text
    r = client.put(
        "/api/v1/listings/" + listing_id + "/publication",
        json={"status": "PUBLISHED"},
        headers={**actors["listing_agent"]["headers"], "If-Match": r.headers["etag"]},
    )
    assert r.status_code == 200, r.text
    return r.json()


def ready_furniture(client, actors):
    f = create(client, "/furniture", {"name": "책상", "category": "DESK"}, actors["tenant"]).json()
    accepted = capture(client, actors, "FURNITURE", f["furnitureId"])
    headers, assignment = claim(client, actors, accepted["jobId"])
    assets = artifacts(client, accepted["jobId"], headers, {"MODEL": glb(), "COLLISION": glb()})
    body = {
        "type": "FURNITURE_RECONSTRUCTION",
        "modelAsset": assets["MODEL"],
        "collisionAsset": assets["COLLISION"],
        "dimensions": {"width": 1, "height": 1, "depth": 1},
        "boundingBox": {"min": {"x": -0.5, "y": 0, "z": -0.5}, "max": {"x": 0.5, "y": 1, "z": 0.5}},
        "quality": {"scaleSource": "AR_TRACKING", "estimatedScaleErrorM": None, "warnings": []},
    }
    r = client.post("/api/v1/internal/ai-jobs/" + accepted["jobId"] + "/complete", json=body, headers=headers)
    assert r.status_code == 200, r.text
    return client.get("/api/v1/furniture/" + f["furnitureId"], headers=actors["tenant"]["headers"]).json()
