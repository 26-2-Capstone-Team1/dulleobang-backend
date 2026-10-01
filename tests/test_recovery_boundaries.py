"""Less common state transitions, callback rollback and retention boundaries."""

import copy
import json
import time

import pytest

from app.core import Database, Problem, Settings, stamp, uid
from app.capture_inputs import classify_files
from app.service import Service
from conftest import (
    artifacts,
    capture,
    claim,
    complete_room,
    create,
    example,
    glb,
    listing,
    ply,
    publish,
    upload,
)


def test_max_active_jobs_and_target_exclusion_are_atomic(client, actors, app):
    owner = actors["tenant"]["user"]["userId"]
    values = [
        create(client, "/furniture", {"name": "작업" + str(i), "category": "OTHER"}, actors["tenant"]).json()
        for i in range(4)
    ]
    sessions = [
        upload(client, actors["tenant"], "FURNITURE", f["furnitureId"], "FURNITURE_CAPTURE") for f in values
    ]
    h = actors["tenant"]["headers"]

    def submit(i):
        return client.post(
            "/api/v1/furniture/" + values[i]["furnitureId"] + "/captures",
            json={"uploadSessionId": sessions[i]["session"]["uploadSessionId"]},
            headers={**h, "Idempotency-Key": uid()},
        )

    jobs = [submit(i) for i in range(3)]
    assert all(r.status_code == 202 for r in jobs)
    limited = submit(3)
    assert limited.status_code == 409 and limited.json()["code"] == "ACTIVE_JOB_LIMIT"
    sid = sessions[3]["session"]["uploadSessionId"]
    assert client.get("/api/v1/upload-sessions/" + sid, headers=h).json()["status"] == "VERIFIED"
    assert client.post("/api/v1/ai-jobs/" + jobs[0].json()["jobId"] + "/cancel", headers=h).status_code == 200
    assert submit(3).status_code == 202
    with app.state.database.transaction() as store:
        svc = Service(store, app.state.settings, app.state.contract)
        with pytest.raises(Problem) as error:
            svc.new_job("FURNITURE_RECONSTRUCTION", "FURNITURE", values[1]["furnitureId"], owner, {})
        assert error.value.code == "ACTIVE_JOB_LIMIT"
    assert client.post("/api/v1/ai-jobs/" + jobs[2].json()["jobId"] + "/cancel", headers=h).status_code == 200
    with app.state.database.transaction() as store:
        svc = Service(store, app.state.settings, app.state.contract)
        with pytest.raises(Problem) as error:
            svc.new_job("FURNITURE_RECONSTRUCTION", "FURNITURE", values[1]["furnitureId"], owner, {})
        assert error.value.code == "ACTIVE_JOB_EXISTS"


def test_tombstone_30_day_delete_boundary(client, actors, app):
    value = listing(client, actors)
    p = "/api/v1/listings/" + value["listingId"]
    h = actors["listing_agent"]["headers"]
    assert client.delete(p, headers={**h, "If-Match": '"1"'}).status_code == 204
    assert client.delete(p, headers=h).status_code == 204
    with app.state.database.transaction() as store:
        store.conn.execute(
            "UPDATE resources SET deleted_at=? WHERE kind='listing' AND id=?",
            (time.time() - 30 * 86400 - 1, value["listingId"]),
        )
    assert client.delete(p, headers=h).status_code == 404


def test_roomstate_failure_retry_snapshot_and_author_only(client, actors, app):
    value = listing(client, actors)
    accepted = capture(client, actors, "LISTING", value["listingId"])
    bundle, _, _ = complete_room(client, actors, accepted["jobId"])
    publish(client, actors, value["listingId"])
    p = "/api/v1/listings/" + value["listingId"]
    obj = bundle["objects"][0]["objectId"]
    owner = actors["listing_agent"]["headers"]
    assert (
        client.patch(
            p + "/objects",
            json={"spaceVersion": 1, "objects": [{"objectId": obj, "ownershipType": "PERSONAL"}]},
            headers={**owner, "If-Match": '"1"'},
        ).status_code
        == 200
    )
    state = create(
        client,
        "/listings/" + value["listingId"] + "/room-states",
        {"sourceSpaceVersion": 1, "sourceObjectsVersion": 2, "removedObjectIds": [obj]},
        actors["listing_agent"],
        202,
    ).json()
    assert (
        client.get(
            "/api/v1/room-states/" + state["roomStateId"], headers=actors["tenant"]["headers"]
        ).status_code
        == 404
    )
    assert client.get(p + "/room-states", headers=actors["tenant"]["headers"]).json()["items"] == []
    with app.state.database.transaction() as store:
        job = store.get("job", state["jobId"])["value"]
        job["createdAt"] = stamp(time.time() - 3601)
        store.put("job", job["jobId"], job, value["ownerId"])
    app.state.maintenance()
    r = client.post(
        "/api/v1/ai-jobs/" + state["jobId"] + "/retries", headers={**owner, "Idempotency-Key": uid()}
    )
    assert r.status_code == 202, r.text
    rs = client.get("/api/v1/room-states/" + state["roomStateId"], headers=owner).json()
    assert rs["status"] == "PROCESSING" and rs["jobId"] == r.json()["jobId"]
    _, assignment = claim(client, actors, r.json()["jobId"])
    assert assignment["input"]["sourceObjectsVersion"] == 2
    assert assignment["input"]["removedObjectIds"] == [obj]
    assert assignment["input"]["sourceBundle"]["objects"][0]["ownershipType"] == "PERSONAL"


def test_complete_lease_expiry_during_verification_rolls_back(client, actors, app, monkeypatch):
    value = listing(client, actors)
    accepted = capture(client, actors, "LISTING", value["listingId"])
    h, _ = claim(client, actors, accepted["jobId"])
    bundle = example("SpaceBundle")
    p = bundle["geometry"]["rooms"][0]["floorPolygon"]
    if sum(p[i][0] * p[(i + 1) % len(p)][1] - p[(i + 1) % len(p)][0] * p[i][1] for i in range(len(p))) > 0:
        p.reverse()
    for obj in bundle["objects"]:
        obj["ownershipType"] = "UNKNOWN"
    names = (
        [o["collisionNode"] for o in bundle["objects"]]
        + [s["nodeName"] for s in bundle["geometry"]["structuralColliders"]]
        + [d["sweepNode"] for d in bundle["geometry"]["doors"] if d["sweepNode"]]
    )
    refs = artifacts(
        client,
        accepted["jobId"],
        h,
        {"SCENE": ply(), "GEOMETRY": json.dumps(bundle["geometry"]).encode(), "COLLISION": glb(names)},
    )
    bundle.update(sceneAsset=refs["SCENE"], geometryAsset=refs["GEOMETRY"], collisionAsset=refs["COLLISION"])
    original = Service.verify_bundle

    def expire(self, job, bundle):
        result = original(self, job, bundle)
        # Advance the shared clock after parsing; no wall-clock wait is needed.
        future = time.time() + 130
        monkeypatch.setattr("app.service.time.time", lambda: future)
        return result

    monkeypatch.setattr(Service, "verify_bundle", expire)
    response = client.post(
        "/api/v1/internal/ai-jobs/" + accepted["jobId"] + "/complete",
        json={"type": "ROOM_RECONSTRUCTION", "bundle": bundle},
        headers=h,
    )
    assert response.status_code == 409 and response.json()["code"] == "JOB_LEASE_LOST"
    with app.state.database.transaction() as store:
        assert store.get("listing", value["listingId"])["value"]["currentSpaceVersion"] is None
        assert store.get("job", accepted["jobId"])["value"]["status"] == "RUNNING"
        assert not store.all("space")
        assert all(not store.get("file", asset["fileId"])["value"]["_verified"] for asset in refs.values())


def test_capture_and_artifact_mime_role_size_cross_constraints():
    rgb = [
        dict(clientFileId=uid(), fileName="x.jpg", contentType="image/jpeg", byteSize=1, sha256="0" * 64)
        for _ in range(300)
    ]
    camera = dict(
        clientFileId=uid(),
        fileName="camera.json",
        contentType="application/json",
        byteSize=1,
        sha256="0" * 64,
    )
    base = dict(purpose="FURNITURE_CAPTURE", targetType="FURNITURE", targetId=uid(), files=rgb + [camera])
    for change in [
        dict(targetType="LISTING"),
        dict(files=[{**rgb[0], "role": "DEPTH"}] + rgb[1:] + [camera]),
        dict(files=[{**rgb[0], "byteSize": (20 << 20) + 1}] + rgb[1:] + [camera]),
        dict(files=rgb + [{**camera, "byteSize": (10 << 20) + 1}]),
        dict(files=[{**f, "byteSize": 20 << 20} for f in rgb] + [camera]),
        dict(files=[rgb[0], rgb[0]] + rgb[2:] + [camera]),
    ]:
        with pytest.raises(Problem):
            classify_files({**base, **change})
    valid = {
        **base,
        "files": [{**rgb[0], "byteSize": 20 << 20}] + rgb[1:] + [{**camera, "byteSize": 10 << 20}],
    }
    assert len(classify_files(valid)) == 301


def test_legacy_flat_draft_migration_and_deleted_owner_alias(tmp_path):
    cfg = Settings(data_dir=tmp_path, scheduler=False)
    cfg.prepare()
    db = Database(cfg)
    owner = uid()
    value = example("ListingCreate")
    value.pop("roomType")
    value.update(
        listingId=uid(),
        listingAgentId=owner,
        propertyType="OFFICETEL",
        status="DRAFT",
        version=1,
        photos=[],
        currentSpaceVersion=None,
        activeJobId=None,
        createdAt=stamp(),
        updatedAt=stamp(),
        location="서울 테스트",
        latitude=37.5,
        longitude=127.03,
        regionCode="1111111111",
        dealType="MONTHLY",
        price={"deposit": 1000.25, "monthly": 65.5},
        maintenanceFee=None,
        area=20,
        supplyArea=25,
        rooms=1,
        bathrooms=1,
        rentalOptions=["parking", "pet"],
        livingEnvironment=["대중교통", "편의점"],
        totalFloors=None,
        availableFrom=None,
    )
    with db.transaction() as store:
        store.put("listing", value["listingId"], value, owner)
        store.delete("listing", value["listingId"])
        store.conn.execute("DELETE FROM schema_migrations WHERE version=3")
    Database(cfg)
    with db.transaction() as store:
        record = store.get("listing", value["listingId"], deleted=True)
        result = record["value"]
        assert record["deleted"] is not None
        assert result["ownerId"] == owner and result["roomType"] == "OFFICETEL"
        assert result["price"]["deposit"] == 10002500 and result["price"]["monthlyRent"] == 655000
        assert result["price"]["maintenanceFee"] is None and result["location"]["regionCode"] == "1111111111"
        assert result["rentalOptions"] == ["PARKING", "PET_ALLOWED"]
        assert "totalFloors" not in result and "availableFrom" not in result
        assert result["livingEnvironment"] == "대중교통 · 편의점"


def test_removal_callback_invariants_and_no_partial_pointer_updates(client, actors, app):
    value = listing(client, actors)
    accepted = capture(client, actors, "LISTING", value["listingId"])
    source, _, _ = complete_room(client, actors, accepted["jobId"])
    publish(client, actors, value["listingId"])
    obj = source["objects"][0]["objectId"]
    owner = actors["listing_agent"]["headers"]
    tenant = actors["tenant"]["headers"]
    p = "/api/v1/listings/" + value["listingId"]
    assert (
        client.patch(
            p + "/objects",
            json={"spaceVersion": 1, "objects": [{"objectId": obj, "ownershipType": "PERSONAL"}]},
            headers={**owner, "If-Match": '"1"'},
        ).status_code
        == 200
    )
    cases = [
        ("target", "REMOVAL_TARGET_MISMATCH"),
        ("collider", "REMOVED_COLLIDER_PRESENT"),
        ("objects", "REMOVAL_OBJECT_MISMATCH"),
        ("geometry", "REMOVAL_GEOMETRY_MISMATCH"),
        ("warning", "REMOVAL_WARNING_REQUIRED"),
    ]
    for case, code in cases:
        state = create(
            client,
            "/listings/" + value["listingId"] + "/room-states",
            {"sourceSpaceVersion": 1, "sourceObjectsVersion": 2, "removedObjectIds": [obj]},
            actors["tenant"],
            202,
        ).json()
        h, assignment = claim(client, actors, state["jobId"])
        bundle = copy.deepcopy(assignment["input"]["sourceBundle"])
        removed_node = bundle["objects"][0]["collisionNode"]
        bundle["objects"] = [o for o in bundle["objects"] if o["objectId"] != obj]
        bundle["quality"]["warnings"] = ["INPAINTED_REGION"]
        if case == "objects":
            changed = copy.deepcopy(assignment["input"]["sourceBundle"]["objects"][0])
            changed["ownershipType"] = "UNKNOWN"
            changed.update(objectId=uid(), collisionNode="unexpected-new-object")
            bundle["objects"].append(changed)
        if case == "geometry":
            for room in bundle["geometry"]["rooms"]:
                room["ceilingY"] += 1
                room["dimensions"]["height"] += 1
            bundle["roomDimensions"]["height"] += 1
        if case == "warning":
            bundle["quality"]["warnings"] = []
        names = (
            [o["collisionNode"] for o in bundle["objects"]]
            + [s["nodeName"] for s in bundle["geometry"]["structuralColliders"]]
            + [d["sweepNode"] for d in bundle["geometry"]["doors"] if d["sweepNode"]]
        )
        if case == "collider":
            names.append(removed_node)
        refs = artifacts(
            client,
            state["jobId"],
            h,
            {"SCENE": ply(), "GEOMETRY": json.dumps(bundle["geometry"]).encode(), "COLLISION": glb(names)},
        )
        bundle.update(
            sceneAsset=refs["SCENE"], geometryAsset=refs["GEOMETRY"], collisionAsset=refs["COLLISION"]
        )
        response = client.post(
            "/api/v1/internal/ai-jobs/" + state["jobId"] + "/complete",
            json={
                "type": "ROOM_OBJECT_REMOVAL",
                "roomStateId": state["roomStateId"],
                "removedObjectIds": [uid() if case == "target" else obj],
                "bundle": bundle,
            },
            headers=h,
        )
        assert response.status_code == 422 and response.json()["code"] == code, (case, response.text)
        with app.state.database.transaction() as store:
            assert store.get("roomstate", state["roomStateId"])["value"]["bundle"] is None
            assert store.get("job", state["jobId"])["value"]["status"] == "RUNNING"
            assert all(
                not store.get("file", asset["fileId"])["value"]["_verified"] for asset in refs.values()
            )
        assert client.post("/api/v1/ai-jobs/" + state["jobId"] + "/cancel", headers=tenant).status_code == 200
