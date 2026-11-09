import copy
import json
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.core import uid
from app.geometry import validate_glb, validate_ply
from app.core import Problem
from conftest import (
    artifacts,
    capture,
    claim,
    complete_room,
    create,
    example,
    glb,
    image,
    info,
    listing,
    ply,
    publish,
    put,
    ready_furniture,
    upload,
)


def test_contract_all_operations_registered(app):
    expected = {(x["method"].upper(), "/api/v1" + x["path"]) for x in app.state.contract.ops}
    actual = {(m, r.path) for r in app.routes for m in getattr(r, "methods", [])}
    assert len(expected) == 60
    assert expected <= actual
    from app.service import Service

    assert all(callable(getattr(Service, x["oid"], None)) for x in app.state.contract.ops)


def test_auth_refresh_logout_and_offline_roles(client, actors, app):
    user = actors["tenant"]
    bad = client.post(
        "/api/v1/auth/signup",
        json={
            "email": "admin@example.com",
            "displayName": "관리자",
            "password": "StrongPassword!123",
            "roles": ["LISTING_AGENT"],
        },
    )
    assert bad.status_code == 422
    assert (
        client.post(
            "/api/v1/listings",
            json=example("ListingCreate"),
            headers={**user["headers"], "Idempotency-Key": uid()},
        ).status_code
        == 201
    )
    raw = user["pair"]["refreshToken"]
    headers = {"Idempotency-Key": uid()}
    first = client.post("/api/v1/auth/refresh", json={"refreshToken": raw}, headers=headers)
    assert first.status_code == 200, first.text
    repeated = client.post("/api/v1/auth/refresh", json={"refreshToken": raw}, headers=headers)
    assert first.json() == repeated.json()
    with app.state.database.connect() as conn:
        persisted = conn.execute(
            "SELECT response FROM idempotency WHERE actor IN (SELECT id FROM sessions WHERE user_id=?)",
            (user["user"]["userId"],),
        ).fetchone()[0]
        assert first.json()["refreshToken"] not in persisted
    reused = client.post(
        "/api/v1/auth/refresh", json={"refreshToken": raw}, headers={"Idempotency-Key": uid()}
    )
    assert reused.status_code == 401 and reused.json()["code"] == "TOKEN_REUSED"
    assert client.get("/api/v1/users/me", headers=user["headers"]).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refreshToken": raw}, headers=headers).status_code == 401
    listing_agent = actors["listing_agent"]
    assert (
        client.post(
            "/api/v1/auth/logout", json={"refreshToken": listing_agent["pair"]["refreshToken"]}
        ).status_code
        == 204
    )
    assert client.get("/api/v1/users/me", headers=listing_agent["headers"]).status_code == 401


def test_http_validation_errors_and_etags(client, actors):
    h = actors["tenant"]["headers"]
    r = client.get("/api/v1/users/me", headers=h)
    assert r.status_code == 200
    assert client.patch("/api/v1/users/me", json={"displayName": "new"}, headers=h).status_code == 428
    assert (
        client.patch(
            "/api/v1/users/me", json={"displayName": "new"}, headers={**h, "If-Match": '"999"'}
        ).status_code
        == 412
    )
    r2 = client.patch(
        "/api/v1/users/me", json={"displayName": "  새 이름  "}, headers={**h, "If-Match": r.headers["etag"]}
    )
    assert r2.status_code == 200 and r2.json()["displayName"] == "새 이름"
    assert r2.headers["etag"] != r.headers["etag"]
    for query in ("unknownLocation=서울", "limit=0", "hasSpace=yes", "limit=1&limit=2", "minArea=NaN"):
        r = client.get("/api/v1/listings?" + query, headers=h)
        assert r.status_code == 400 and r.headers["content-type"].startswith("application/problem+json"), (
            r.text
        )
    assert client.get("/api/v1/listings?minDeposit=10&maxDeposit=1", headers=h).status_code == 422
    assert (
        client.patch(
            "/api/v1/users/me",
            content='{"displayName":"a","displayName":"b"}',
            headers={**h, "Content-Type": "application/json"},
        ).status_code
        == 400
    )
    assert (
        client.patch(
            "/api/v1/users/me", content="{}", headers={**h, "Content-Type": "text/plain"}
        ).status_code
        == 415
    )
    r = client.get("/api/v1/users/me")
    assert r.status_code == 401 and r.headers["www-authenticate"] == "Bearer"


def test_idempotency_ownership_and_delete_replay(client, actors):
    h = {**actors["listing_agent"]["headers"], "Idempotency-Key": uid()}
    body = example("ListingCreate")
    a = client.post("/api/v1/listings", json=body, headers=h)
    b = client.post("/api/v1/listings", json=dict(reversed(list(body.items()))), headers=h)
    assert a.status_code == b.status_code == 201 and a.json() == b.json()
    assert a.headers["location"] == b.headers["location"]
    body["title"] = "다른 값"
    assert client.post("/api/v1/listings", json=body, headers=h).status_code == 409
    id = a.json()["listingId"]
    assert client.get("/api/v1/listings/" + id, headers=actors["tenant"]["headers"]).status_code == 404
    assert (
        client.get(
            "/api/v1/listings/" + id + "/space", headers=actors["listing_agent"]["headers"]
        ).status_code
        == 409
    )
    assert (
        client.delete(
            "/api/v1/listings/" + id,
            headers={**actors["listing_agent"]["headers"], "If-Match": a.headers["etag"]},
        ).status_code
        == 204
    )
    assert (
        client.delete("/api/v1/listings/" + id, headers=actors["listing_agent"]["headers"]).status_code == 204
    )
    assert client.delete("/api/v1/listings/" + id, headers=actors["tenant"]["headers"]).status_code == 404


def test_upload_file_integrity_and_freeze(client, actors):
    property = listing(client, actors)
    raw = image()
    descriptor = info(raw, "photo.jpg", "image/jpeg")
    h = actors["listing_agent"]["headers"]
    data = create(
        client,
        "/upload-sessions",
        {
            "purpose": "LISTING_PHOTO",
            "targetType": "LISTING",
            "targetId": property["listingId"],
            "files": [descriptor],
        },
        actors["listing_agent"],
    ).json()
    endpoint = "/api/v1/upload-sessions/" + data["session"]["uploadSessionId"] + "/complete"
    assert client.post(endpoint, headers=h).status_code == 409
    assert (
        client.put(
            data["uploads"][0]["url"], content=b"x" * len(raw), headers=data["uploads"][0]["headers"]
        ).status_code
        == 422
    )
    put(client, data["uploads"][0], raw)
    assert client.post(endpoint, headers=h).status_code == 200
    assert client.post(endpoint, headers=h).status_code == 200
    assert (
        client.put(data["uploads"][0]["url"], content=raw, headers=data["uploads"][0]["headers"]).status_code
        == 409
    )
    file_id = data["session"]["files"][0]["fileId"]
    assert (
        client.get(
            "/api/v1/assets/" + file_id + "/download", headers=actors["stranger"]["headers"]
        ).status_code
        == 404
    )
    grant = client.get("/api/v1/assets/" + file_id + "/download", headers=h).json()
    assert client.get(grant["url"]).content == raw
    upload(
        client,
        actors["listing_agent"],
        "LISTING",
        property["listingId"],
        "ROOM_CAPTURE",
        corrupt_manifest=True,
    )


def test_job_leases_retry_policy_outbox_and_cancel(client, actors, app):
    f = create(client, "/furniture", {"name": "소파", "category": "SOFA"}, actors["tenant"]).json()
    job = capture(client, actors, "FURNITURE", f["furnitureId"])
    app.state.maintenance()
    with app.state.database.connect() as conn:
        assert conn.execute("SELECT job_id FROM queue").fetchone()[0] == job["jobId"]
    base = "/api/v1/internal/ai-jobs/" + job["jobId"]
    assert (
        client.post(
            base + "/claim", json={"workerId": "test-worker"}, headers=actors["tenant"]["headers"]
        ).status_code
        == 401
    )
    lease, assignment = claim(client, actors, job["jobId"])
    assert (
        client.post(base + "/claim", json={"workerId": "test-worker"}, headers=actors["worker"]).status_code
        == 409
    )
    beat = {"progressPercent": 25, "stage": "RECONSTRUCTING", "refreshInputUrls": True}
    r = client.post(base + "/heartbeat", json=beat, headers=lease)
    assert r.status_code == 200 and r.json()["input"]["kind"] == "RECONSTRUCTION"
    beat["progressPercent"] = 24
    assert client.post(base + "/heartbeat", json=beat, headers=lease).status_code == 422
    failure = {"error": {"code": "DEPENDENCY_UNAVAILABLE", "message": "temporary", "retryable": True}}
    r = client.post(base + "/fail", json=failure, headers=lease)
    assert r.status_code == 200 and r.json()["status"] == "RETRY_WAIT"
    assert client.post(base + "/fail", json=failure, headers=lease).json() == r.json()
    assert (
        client.post(base + "/claim", json={"workerId": "test-worker"}, headers=actors["worker"]).status_code
        == 409
    )
    with app.state.database.transaction() as s:
        j = s.get("job", job["jobId"])["value"]
        j["_due"] = time.time() - 1
        s.put("job", j["jobId"], j, actors["tenant"]["user"]["userId"])
    lease2, assignment2 = claim(client, actors, job["jobId"])
    assert assignment2["attemptNo"] == 2
    assert client.post(base + "/heartbeat", json=beat, headers=lease).status_code == 409
    canceled = client.post("/api/v1/ai-jobs/" + job["jobId"] + "/cancel", headers=actors["tenant"]["headers"])
    assert canceled.status_code == 200 and canceled.json()["status"] == "CANCELED"
    assert client.post(base + "/fail", json=failure, headers=lease2).status_code == 409
    assert (
        client.get("/api/v1/ai-jobs/" + job["jobId"], headers=actors["stranger"]["headers"]).status_code
        == 404
    )


def test_end_to_end_space_furniture_placements_versions(client, actors):
    property = listing(client, actors)
    j = capture(client, actors, "LISTING", property["listingId"])
    bundle, lease, complete = complete_room(client, actors, j["jobId"])
    complete_path = "/api/v1/internal/ai-jobs/" + j["jobId"] + "/complete"
    assert client.post(complete_path, json=complete, headers=lease).status_code == 200
    changed = copy.deepcopy(complete)
    changed["bundle"]["quality"]["warnings"].append("OTHER")
    assert client.post(complete_path, json=changed, headers=lease).status_code == 409
    publish(client, actors, property["listingId"])
    f = ready_furniture(client, actors)
    h = actors["tenant"]["headers"]
    assert client.get("/api/v1/listings", headers=h).json()["items"][0]["listingId"] == property["listingId"]
    space = client.get("/api/v1/listings/" + property["listingId"] + "/space", headers=h)
    assert space.status_code == 200 and space.json()["spaceVersion"] == 1
    assert client.get("/api/v1/listings/" + property["listingId"] + "/objects", headers=h).status_code == 200
    p = {
        "space": {"kind": "ORIGINAL", "spaceVersion": 1, "roomStateId": None},
        "placements": [
            {
                "placementId": uid(),
                "furnitureId": f["furnitureId"],
                "modelVersion": 1,
                "transform": {
                    "position": {"x": 0, "y": 0, "z": 0},
                    "rotation": {"x": 0, "y": 0, "z": 0, "w": 1},
                },
            }
        ],
        "clientCollisionReport": None,
    }
    endpoint = "/api/v1/listings/" + property["listingId"] + "/placements"
    assert client.put(endpoint, json=p, headers=h).status_code == 428
    saved = client.put(endpoint, json=p, headers={**h, "If-None-Match": "*"})
    assert saved.status_code == 200 and not saved.json()["isStale"]
    assert client.put(endpoint, json=p, headers={**h, "If-None-Match": "*"}).status_code == 412
    assert client.get(endpoint, headers=actors["stranger"]["headers"]).status_code == 404
    assert (
        client.delete(
            "/api/v1/furniture/" + f["furnitureId"], headers={**h, "If-Match": '"' + str(f["version"]) + '"'}
        ).status_code
        == 409
    )
    p["clientCollisionReport"] = {
        "rulesVersion": "1.0",
        "evaluatedAt": saved.json()["savedAt"],
        "inputSha256": "0" * 64,
        "isPlaceable": True,
        "collisions": [],
    }
    saved2 = client.put(endpoint, json=p, headers={**h, "If-Match": saved.headers["etag"]})
    assert saved2.status_code == 200, saved2.text
    p["clientCollisionReport"].pop("inputSha256")
    saved2 = client.put(endpoint, json=p, headers={**h, "If-Match": saved2.headers["etag"]})
    assert saved2.status_code == 200 and "inputSha256" not in saved2.json()["clientCollisionReport"], (
        saved2.text
    )
    j2 = capture(client, actors, "LISTING", property["listingId"])
    complete_room(client, actors, j2["jobId"])
    stale = client.get(endpoint, headers=h)
    assert stale.json()["isStale"] and stale.headers["etag"] != saved2.headers["etag"]
    assert client.put(endpoint, json=p, headers={**h, "If-Match": stale.headers["etag"]}).status_code == 409
    historic = client.get(
        "/api/v1/listings/" + property["listingId"] + "/space?spaceVersion=1", headers=h
    ).json()
    assert (
        not historic["isCurrent"]
        and historic["bundle"]["sceneAsset"]["sha256"] == bundle["sceneAsset"]["sha256"]
    )


def test_object_classification_removal_private_snapshot(client, actors):
    property = listing(client, actors)
    j = capture(client, actors, "LISTING", property["listingId"])
    bundle, _, _ = complete_room(client, actors, j["jobId"])
    publish(client, actors, property["listingId"])
    object_id = bundle["objects"][0]["objectId"]
    path = "/api/v1/listings/" + property["listingId"]
    payload = {"sourceSpaceVersion": 1, "sourceObjectsVersion": 1, "removedObjectIds": [object_id]}
    assert (
        client.post(
            path + "/room-states",
            json=payload,
            headers={**actors["tenant"]["headers"], "Idempotency-Key": uid()},
        ).status_code
        == 422
    )
    classification = {"spaceVersion": 1, "objects": [{"objectId": object_id, "ownershipType": "PERSONAL"}]}
    result = client.patch(
        path + "/objects",
        json=classification,
        headers={**actors["listing_agent"]["headers"], "If-Match": '"1"'},
    )
    assert result.status_code == 200, result.text
    payload["sourceObjectsVersion"] = result.json()["version"]
    state = create(client, path[7:] + "/room-states", payload, actors["tenant"], 202).json()
    lease, assignment = claim(client, actors, state["jobId"])
    assert assignment["input"]["kind"] == "REMOVAL"
    source = assignment["input"]["sourceBundle"]
    remaining = copy.deepcopy(source)
    remaining["objects"] = [o for o in remaining["objects"] if o["objectId"] != object_id]
    remaining["quality"]["warnings"].append("INPAINTED_REGION")
    names = (
        [o["collisionNode"] for o in remaining["objects"]]
        + [s["nodeName"] for s in remaining["geometry"]["structuralColliders"]]
        + [d["sweepNode"] for d in remaining["geometry"]["doors"] if d["sweepNode"]]
    )
    a = artifacts(
        client,
        state["jobId"],
        lease,
        {"SCENE": ply(), "GEOMETRY": json.dumps(remaining["geometry"]).encode(), "COLLISION": glb(names)},
    )
    remaining.update(sceneAsset=a["SCENE"], geometryAsset=a["GEOMETRY"], collisionAsset=a["COLLISION"])
    result = {
        "type": "ROOM_OBJECT_REMOVAL",
        "roomStateId": state["roomStateId"],
        "removedObjectIds": [object_id],
        "bundle": remaining,
    }
    completed = client.post(
        "/api/v1/internal/ai-jobs/" + state["jobId"] + "/complete", json=result, headers=lease
    )
    assert completed.status_code == 200, completed.text
    s = client.get("/api/v1/room-states/" + state["roomStateId"], headers=actors["tenant"]["headers"])
    assert s.status_code == 200 and s.json()["status"] == "READY"
    assert object_id not in {o["objectId"] for o in s.json()["bundle"]["objects"]}
    assert (
        client.get(
            "/api/v1/room-states/" + state["roomStateId"], headers=actors["stranger"]["headers"]
        ).status_code
        == 404
    )
    assert object_id in {
        o["objectId"]
        for o in client.get(path + "/space", headers=actors["tenant"]["headers"]).json()["bundle"]["objects"]
    }
    h = actors["tenant"]["headers"]
    payload = {
        "space": {"kind": "ROOM_STATE", "spaceVersion": 1, "roomStateId": state["roomStateId"]},
        "placements": [],
        "clientCollisionReport": None,
    }
    placed = client.put(path + "/placements", json=payload, headers={**h, "If-None-Match": "*"})
    assert placed.status_code == 200, placed.text
    assert client.delete("/api/v1/room-states/" + state["roomStateId"], headers=h).status_code == 409
    assert (
        client.delete(path + "/placements", headers={**h, "If-Match": placed.headers["etag"]}).status_code
        == 204
    )
    assert client.delete("/api/v1/room-states/" + state["roomStateId"], headers=h).status_code == 204


def test_concurrent_idempotent_create_and_etag_race(client, actors):
    h = {**actors["tenant"]["headers"], "Idempotency-Key": uid()}

    def submit(_):
        return client.post("/api/v1/furniture", json={"name": "한 개만", "category": "DESK"}, headers=h)

    with ThreadPoolExecutor(max_workers=4) as executor:
        responses = list(executor.map(submit, range(4)))
    assert all(r.status_code == 201 for r in responses)
    assert len({r.json()["furnitureId"] for r in responses}) == 1
    id = responses[0].json()["furnitureId"]

    def patch(index):
        return client.patch(
            "/api/v1/furniture/" + id,
            json={"name": "수정" + str(index)},
            headers={**actors["tenant"]["headers"], "If-Match": '"1"'},
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        statuses = sorted(r.status_code for r in executor.map(patch, range(2)))
    assert statuses == [200, 412]


def test_binary_artifact_validators():
    assert validate_glb(glb()) == {"mesh"}
    validate_ply(ply())
    for raw in (b"not a glb", glb()[:-1]):
        with pytest.raises(Problem):
            validate_glb(raw)
    with pytest.raises(Problem):
        validate_ply(ply().replace(b"property float x", b"property int x"))
    with pytest.raises(Problem):
        validate_glb(glb(), expected_box={"min": {"x": 0, "y": 0, "z": 0}, "max": {"x": 4, "y": 2, "z": 3}})


def test_invalid_complete_geometry_is_atomic(client, actors, app):
    f = create(client, "/furniture", {"name": "치수 검증", "category": "OTHER"}, actors["tenant"]).json()
    accepted = capture(client, actors, "FURNITURE", f["furnitureId"])
    h, _ = claim(client, actors, accepted["jobId"])
    a = artifacts(client, accepted["jobId"], h, {"MODEL": glb(), "COLLISION": glb()})
    body = {
        "type": "FURNITURE_RECONSTRUCTION",
        "modelAsset": a["MODEL"],
        "collisionAsset": a["COLLISION"],
        "dimensions": {"width": 2, "height": 1, "depth": 1},
        "boundingBox": {"min": {"x": -1, "y": 0, "z": -0.5}, "max": {"x": 1, "y": 1, "z": 0.5}},
        "quality": {"scaleSource": "AR_TRACKING", "estimatedScaleErrorM": None, "warnings": []},
    }
    response = client.post(
        "/api/v1/internal/ai-jobs/" + accepted["jobId"] + "/complete", json=body, headers=h
    )
    assert response.status_code == 422 and response.json()["code"] == "MODEL_GEOMETRY_DIMENSION_MISMATCH"
    current = client.get("/api/v1/furniture/" + f["furnitureId"], headers=actors["tenant"]["headers"]).json()
    assert current["model"] is None and current["status"] == "PROCESSING"
    with app.state.database.transaction() as s:
        assert not s.get("file", a["MODEL"]["fileId"])["value"]["_verified"]
        assert s.get("job", accepted["jobId"])["value"]["status"] == "RUNNING"


def test_remaining_crud_lists_cursor_and_historical_models(client, actors):
    h = actors["tenant"]["headers"]
    listing_agent = actors["listing_agent"]["headers"]
    properties = [listing(client, actors) for _ in range(3)]
    mine = client.get("/api/v1/users/me/listings?limit=1", headers=listing_agent)
    assert mine.status_code == 200 and mine.json()["page"]["hasNext"]
    cursor = mine.json()["page"]["nextCursor"]
    next_page = client.get(
        "/api/v1/users/me/listings", params={"limit": 1, "cursor": cursor}, headers=listing_agent
    )
    assert (
        next_page.status_code == 200
        and next_page.json()["items"][0]["listingId"] != mine.json()["items"][0]["listingId"]
    )
    assert (
        client.get(
            "/api/v1/users/me/listings", params={"limit": 2, "cursor": cursor}, headers=listing_agent
        ).status_code
        == 400
    )
    f = ready_furniture(client, actors)
    id = f["furnitureId"]
    all_furniture = client.get("/api/v1/users/me/furniture?status=READY", headers=h)
    assert all_furniture.status_code == 200 and all_furniture.json()["items"][0]["furnitureId"] == id
    historic = client.get("/api/v1/furniture/" + id + "?modelVersion=1", headers=h)
    assert historic.status_code == 200 and "etag" not in historic.headers
    current = client.get("/api/v1/furniture/" + id, headers=h)
    updated = client.patch(
        "/api/v1/furniture/" + id,
        json={"name": "수정된 책상"},
        headers={**h, "If-Match": current.headers["etag"]},
    )
    assert updated.status_code == 200
    assert (
        client.delete(
            "/api/v1/furniture/" + id, headers={**h, "If-Match": updated.headers["etag"]}
        ).status_code
        == 204
    )
    assert client.delete("/api/v1/furniture/" + id, headers=h).status_code == 204
    assert client.get("/api/v1/furniture/" + id, headers=h).status_code == 404
    assert client.get("/api/v1/users/me/placements", headers=h).json()["items"] == []
    f2 = create(client, "/furniture", {"name": "촬영할 의자", "category": "CHAIR"}, actors["tenant"]).json()
    raw = image()
    # URL reissue and session GET work before verification; signing doesn't mutate IDs.
    descriptors = [info(raw, "photo.jpg", "image/jpeg")]
    session = create(
        client,
        "/upload-sessions",
        {
            "purpose": "LISTING_PHOTO",
            "targetType": "LISTING",
            "targetId": properties[0]["listingId"],
            "files": descriptors,
        },
        actors["listing_agent"],
    ).json()
    sid = session["session"]["uploadSessionId"]
    assert client.get("/api/v1/upload-sessions/" + sid, headers=listing_agent).status_code == 200
    renewed = client.post(
        "/api/v1/upload-sessions/" + sid + "/upload-urls",
        json={"clientFileIds": [descriptors[0]["clientFileId"]]},
        headers=listing_agent,
    )
    assert (
        renewed.status_code == 200
        and renewed.json()["uploads"][0]["fileId"] == session["uploads"][0]["fileId"]
    )
    accepted = capture(client, actors, "FURNITURE", f2["furnitureId"])
    captured = client.get("/api/v1/captures/" + accepted["captureId"], headers=h)
    assert captured.status_code == 200 and len(captured.json()["imageAssets"]) == 12


def test_manual_retry_timeout_queue_recovery_and_permanent_error(client, actors, app):
    f = create(client, "/furniture", {"name": "워커 재시도", "category": "OTHER"}, actors["tenant"]).json()
    accepted = capture(client, actors, "FURNITURE", f["furnitureId"])
    id = accepted["jobId"]
    # Simulate a crashed worker for all three attempts, using persisted lease timestamps.
    for attempt in range(1, 4):
        lease, assignment = claim(client, actors, id)
        assert assignment["attemptNo"] == attempt
        with app.state.database.transaction() as s:
            j = s.get("job", id)["value"]
            j["_leaseExpires"] = time.time() - 1
            s.put("job", id, j, actors["tenant"]["user"]["userId"])
        app.state.maintenance()
        status = client.get("/api/v1/ai-jobs/" + id, headers=actors["tenant"]["headers"]).json()
        assert status["status"] == ("FAILED" if attempt == 3 else "RETRY_WAIT")
        if attempt < 3:
            with app.state.database.transaction() as s:
                j = s.get("job", id)["value"]
                j["_due"] = time.time() - 1
                s.put("job", id, j, actors["tenant"]["user"]["userId"])
    retried = client.post(
        "/api/v1/ai-jobs/" + id + "/retries",
        headers={**actors["tenant"]["headers"], "Idempotency-Key": uid()},
    )
    assert retried.status_code == 202, retried.text
    new_id = retried.json()["jobId"]
    assert new_id != id
    assert (
        client.get("/api/v1/ai-jobs/" + id, headers=actors["tenant"]["headers"]).json()["status"] == "FAILED"
    )
    lease, _ = claim(client, actors, new_id)
    failure = {
        "error": {"code": "TRACKING_UNSTABLE", "message": "촬영을 다시 진행해 주세요.", "retryable": True}
    }
    r = client.post("/api/v1/internal/ai-jobs/" + new_id + "/fail", json=failure, headers=lease)
    assert r.status_code == 200 and r.json()["status"] == "FAILED" and not r.json()["error"]["retryable"]
    assert (
        client.post(
            "/api/v1/ai-jobs/" + new_id + "/retries",
            headers={**actors["tenant"]["headers"], "Idempotency-Key": uid()},
        ).status_code
        == 409
    )


def test_roomstate_placement_references_and_current_listing_access(client, actors):
    property = listing(client, actors)
    id = property["listingId"]
    accepted = capture(client, actors, "LISTING", id)
    bundle, _, _ = complete_room(client, actors, accepted["jobId"])
    published = publish(client, actors, id)
    # Listing agent classifies the first recognized item as the departing occupant's furniture.
    object_id = bundle["objects"][0]["objectId"]
    path = "/api/v1/listings/" + id
    c = client.patch(
        path + "/objects",
        json={"spaceVersion": 1, "objects": [{"objectId": object_id, "ownershipType": "PERSONAL"}]},
        headers={**actors["listing_agent"]["headers"], "If-Match": '"1"'},
    )
    assert c.status_code == 200
    state = create(
        client,
        "/listings/" + id + "/room-states",
        {"sourceSpaceVersion": 1, "sourceObjectsVersion": 2, "removedObjectIds": [object_id]},
        actors["tenant"],
        202,
    ).json()
    assert (
        client.get(path + "/room-states", headers=actors["tenant"]["headers"]).json()["items"][0][
            "roomStateId"
        ]
        == state["roomStateId"]
    )
    assert client.get(path + "/room-states", headers=actors["stranger"]["headers"]).json()["items"] == []
    h = actors["tenant"]["headers"]
    assert client.delete("/api/v1/room-states/" + state["roomStateId"], headers=h).status_code == 204
    assert client.delete("/api/v1/room-states/" + state["roomStateId"], headers=h).status_code == 204
    assert client.get("/api/v1/ai-jobs/" + state["jobId"], headers=h).status_code == 404
    # Empty placement set is a valid saved scenario, with separate per-user sets.
    payload = {
        "space": {"kind": "ORIGINAL", "spaceVersion": 1, "roomStateId": None},
        "placements": [],
        "clientCollisionReport": None,
    }
    saved = client.put(path + "/placements", json=payload, headers={**h, "If-None-Match": "*"})
    assert saved.status_code == 200
    assert (
        client.get("/api/v1/users/me/placements", headers=h).json()["items"][0]["placementSetId"]
        == saved.json()["placementSetId"]
    )
    assert (
        client.delete(path + "/placements", headers={**h, "If-Match": saved.headers["etag"]}).status_code
        == 204
    )
    assert client.delete(path + "/placements", headers=h).status_code == 204
    redraft = client.put(
        path + "/publication",
        json={"status": "DRAFT"},
        headers={**actors["listing_agent"]["headers"], "If-Match": '"' + str(published["version"]) + '"'},
    )
    assert redraft.status_code == 200, redraft.text
    assert client.get(path, headers=h).status_code == 404


def test_persistence_restart_health_and_production_config(client, actors, app, tmp_path):
    from fastapi.testclient import TestClient
    from app.main import create_app
    from app.core import Settings

    f = create(client, "/furniture", {"name": "재시작 보존", "category": "BED"}, actors["tenant"]).json()
    reopened = create_app(Settings(data_dir=app.state.settings.data_dir, scheduler=False, rate_limits=False))
    with TestClient(reopened) as second:
        r = second.get("/api/v1/furniture/" + f["furnitureId"], headers=actors["tenant"]["headers"])
        assert r.status_code == 200 and r.json() == f
        assert second.get("/health/live").json() == {"status": "ok"}
        assert second.get("/health/ready").status_code == 200
        assert len(second.get("/openapi.json").json()["paths"]) == 45
        assert second.get("/docs").status_code == 200
    with pytest.raises(Problem):
        create_app(Settings(data_dir=tmp_path / "bad-prod", environment="production"))


def test_rate_limits_and_request_size(tmp_path, monkeypatch):
    from fastapi.testclient import TestClient
    from app.main import create_app
    from app.core import Settings

    server = create_app(Settings(data_dir=tmp_path, scheduler=False, rate_limits=True))
    # The test must stay in one rate-limit window even when the wall clock crosses a minute.
    fixed_time = time.time()
    monkeypatch.setattr("app.main.time.time", lambda: fixed_time)
    with TestClient(server) as c:
        body = {"email": "doesnotexist@example.com", "password": "StrongPassword!123"}
        for _ in range(5):
            assert c.post("/api/v1/auth/login", json=body).status_code == 401
        limited = c.post("/api/v1/auth/login", json=body)
        assert limited.status_code == 429 and limited.headers["retry-after"]
        huge = c.post(
            "/api/v1/auth/login",
            content=b" " * ((10 << 20) + 1),
            headers={"Content-Type": "application/json"},
        )
        assert huge.status_code == 413


def test_public_photo_preserves_orientation_and_removes_metadata():
    from io import BytesIO
    from PIL import Image
    from app.geometry import stripped_photo

    source = Image.new("RGB", (640, 480), "red")
    exif = source.getexif()
    exif[274] = 6
    exif[315] = "private-device-owner"
    raw = BytesIO()
    source.save(raw, "JPEG", exif=exif)
    published = Image.open(BytesIO(stripped_photo(raw.getvalue())))
    assert published.size == (480, 640)
    assert not published.getexif()
    assert "private-device-owner".encode() not in stripped_photo(raw.getvalue())
