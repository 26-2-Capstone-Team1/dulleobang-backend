"""Domain, authorization, concurrency, persistence and failure-injection checks."""

import copy
import hashlib
import json
import os
import sqlite3
import time
from concurrent.futures import ThreadPoolExecutor

import jwt
import pytest
from fastapi.testclient import TestClient

from app.core import Problem, Settings, stamp, uid, worker_token
from app.main import create_app
from app.service import Service
from conftest import (
    capture,
    claim,
    complete_room,
    create,
    example,
    glb,
    image,
    info,
    listing,
    publish,
    put,
    ready_furniture,
    upload,
)


def public(client, actors):
    value = listing(client, actors)
    job = capture(client, actors, "LISTING", value["listingId"])
    bundle, _, _ = complete_room(client, actors, job["jobId"])
    publish(client, actors, value["listingId"])
    return value, bundle


def test_resource_ownership_all_private_resource_routes(client, actors):
    value = listing(client, actors)
    fid = create(client, "/furniture", {"name": "개인", "category": "DESK"}, actors["tenant"]).json()[
        "furnitureId"
    ]
    captured = capture(client, actors, "FURNITURE", fid)
    session = upload(client, actors["listing_agent"], "LISTING", value["listingId"], "LISTING_PHOTO")
    sid = session["session"]["uploadSessionId"]
    file = session["session"]["files"][0]
    stranger = actors["stranger"]["headers"]
    routes = [
        ("GET", "/listings/" + value["listingId"], None),
        ("PATCH", "/listings/" + value["listingId"], {"description": "x"}),
        ("DELETE", "/listings/" + value["listingId"], None),
        ("PUT", "/listings/" + value["listingId"] + "/publication", {"status": "ARCHIVED"}),
        ("POST", "/listings/" + value["listingId"] + "/captures", {"uploadSessionId": sid}),
        ("GET", "/listings/" + value["listingId"] + "/space", None),
        ("GET", "/listings/" + value["listingId"] + "/objects", None),
        ("PATCH", "/listings/" + value["listingId"] + "/objects", example("ObjectsPatch")),
        ("GET", "/upload-sessions/" + sid, None),
        ("POST", "/upload-sessions/" + sid + "/complete", None),
        ("POST", "/upload-sessions/" + sid + "/upload-urls", {"clientFileIds": [file["clientFileId"]]}),
        ("GET", "/assets/" + file["fileId"] + "/download", None),
        ("GET", "/captures/" + captured["captureId"], None),
        ("GET", "/ai-jobs/" + captured["jobId"], None),
        ("POST", "/ai-jobs/" + captured["jobId"] + "/cancel", None),
        ("POST", "/ai-jobs/" + captured["jobId"] + "/retries", None),
        ("GET", "/furniture/" + fid, None),
        ("PATCH", "/furniture/" + fid, {"name": "x"}),
        ("DELETE", "/furniture/" + fid, None),
        ("POST", "/furniture/" + fid + "/captures", {"uploadSessionId": sid}),
    ]
    for method, path, body in routes:
        r = client.request(
            method,
            "/api/v1" + path,
            json=body,
            headers={**stranger, "If-Match": '"1"', "Idempotency-Key": uid()},
        )
        assert r.status_code == 404, (method, path, r.text)
    for typ, target in [
        ("LISTING", value["listingId"]),
        ("FURNITURE", fid),
        ("USER", actors["tenant"]["user"]["userId"]),
    ]:
        body = dict(
            purpose="LISTING_AGENT_DOCUMENT" if typ == "USER" else "LISTING_PHOTO",
            targetType=typ,
            targetId=target,
            files=[info(image(), "x.jpg", "image/jpeg")],
        )
        assert (
            client.post(
                "/api/v1/upload-sessions", json=body, headers={**stranger, "Idempotency-Key": uid()}
            ).status_code
            == 404
        )
    assert client.get("/api/v1/users/me/listings", headers=stranger).json()["items"] == []
    assert client.get("/api/v1/users/me/furniture", headers=stranger).json()["items"] == []


def test_listing_cross_fields_publication_all_transitions(client, actors):
    h = actors["tenant"]["headers"]
    for change, code in [
        ({"price": {**example("Price"), "leaseType": "JEONSE", "monthlyRent": 1}}, "VALIDATION_FAILED"),
        ({"price": {**example("Price"), "leaseType": "MONTHLY", "monthlyRent": 0}}, "VALIDATION_FAILED"),
        ({"supplyAreaM2": 1}, "INVALID_AREA"),
        ({"floor": 3, "totalFloors": 2}, "INVALID_FLOOR"),
        ({"location": {**example("Location"), "roadAddress": "  "}}, "INVALID_LOCATION"),
    ]:
        response = client.post(
            "/api/v1/listings",
            json={**example("ListingCreate"), **change},
            headers={**h, "Idempotency-Key": uid()},
        )
        assert response.status_code == 422 and response.json()["code"] == code, response.text
    value = create(client, "/listings", example("ListingCreate"), actors["tenant"]).json()
    p = "/api/v1/listings/" + value["listingId"]
    for status, expected in [
        ("PUBLISHED", 409),
        ("ARCHIVED", 200),
        ("PUBLISHED", 409),
        ("DRAFT", 200),
        ("DRAFT", 200),
    ]:
        before = client.get(p, headers=h)
        r = client.put(
            p + "/publication", json={"status": status}, headers={**h, "If-Match": before.headers["etag"]}
        )
        assert r.status_code == expected, r.text
    archived = client.put(
        p + "/publication",
        json={"status": "ARCHIVED"},
        headers={**h, "If-Match": client.get(p, headers=h).headers["etag"]},
    )
    assert (
        client.patch(
            p, json={"description": "x"}, headers={**h, "If-Match": archived.headers["etag"]}
        ).status_code
        == 409
    )
    assert client.get(p + "/space", headers=h).status_code == 409


def test_search_filters_inclusive_ranges_all_sort_modes_cursor_binding(client, actors, app):
    value, _ = public(client, actors)
    original = app.state.database
    ids = []
    with original.transaction() as store:
        template = store.get("listing", value["listingId"])["value"]
        for i in range(4):
            v = copy.deepcopy(template)
            v.update(
                listingId=uid(),
                roomType=["ONE_ROOM", "TWO_ROOM", "THREE_ROOM", "OFFICETEL"][i],
                exclusiveAreaM2=20 + i,
                createdAt=stamp(time.time() - 100 + i),
            )
            v["price"].update(deposit=100 + i, monthlyRent=10 + i)
            v["location"].update(
                roadAddress="SEOUL Mapo 동교동 " + str(i),
                regionCode="1111111111",
                latitude=37 + i / 100,
                longitude=127 + i / 100,
            )
            v["rentalOptions"] = ["PARKING", "ELEVATOR"] if i % 2 == 0 else ["PARKING"]
            store.put("listing", v["listingId"], v, v["ownerId"])
            ids.append(v["listingId"])
    h = actors["tenant"]["headers"]
    query = {"q": "seoul 동교동", "regionCode": "1111111111"}
    for mode, expected in [
        ("LATEST", ids[::-1]),
        ("DEPOSIT_ASC", ids),
        ("MONTHLY_RENT_ASC", ids),
        ("AREA_DESC", ids[::-1]),
    ]:
        r = client.get("/api/v1/listings", params={**query, "sort": mode}, headers=h)
        assert [x["listingId"] for x in r.json()["items"]] == expected
    for filters, expected in [
        ({"minDeposit": 101, "maxDeposit": 102}, ids[1:3][::-1]),
        ({"minMonthlyRent": 11, "maxMonthlyRent": 12}, ids[1:3][::-1]),
        ({"minAreaM2": 21, "maxAreaM2": 22}, ids[1:3][::-1]),
        ({"hasSpace": "false"}, []),
        ({"regionCode": "9999999999"}, []),
    ]:
        r = client.get("/api/v1/listings", params={**query, **filters}, headers=h)
        assert [x["listingId"] for x in r.json()["items"]] == expected, r.text
    r = client.get(
        "/api/v1/listings",
        params=[*(query.items()), ("roomType", "ONE_ROOM"), ("roomType", "THREE_ROOM")],
        headers=h,
    )
    assert [x["listingId"] for x in r.json()["items"]] == [ids[2], ids[0]]
    r = client.get(
        "/api/v1/listings",
        params=[*(query.items()), ("rentalOptions", "PARKING"), ("rentalOptions", "ELEVATOR")],
        headers=h,
    )
    assert [x["listingId"] for x in r.json()["items"]] == [ids[2], ids[0]]
    r = client.get(
        "/api/v1/listings",
        params={**query, "minLat": 37.01, "maxLat": 37.02, "minLng": 127.01, "maxLng": 127.02, "limit": 200},
        headers=h,
    )
    assert [x["listingId"] for x in r.json()["items"]] == [ids[2], ids[1]]
    first = client.get("/api/v1/listings", params={**query, "limit": 1}, headers=h).json()
    cursor = first["page"]["nextCursor"]
    for badquery, actor in [
        ({**query, "limit": 2}, h),
        ({**query, "limit": 1, "q": "Mapo"}, h),
        ({**query, "limit": 1}, actors["stranger"]["headers"]),
    ]:
        assert (
            client.get("/api/v1/listings", params={**badquery, "cursor": cursor}, headers=actor).status_code
            == 400
        )
    decoded = jwt.decode(cursor, app.state.settings.storage_key, algorithms=["HS256"], audience="cursor")
    decoded["exp"] = int(time.time()) - 1
    expired = jwt.encode(decoded, app.state.settings.storage_key, algorithm="HS256")
    assert (
        client.get("/api/v1/listings", params={**query, "limit": 1, "cursor": expired}, headers=h).status_code
        == 400
    )
    with original.transaction() as store:
        store.delete("listing", ids[2])
    second = client.get("/api/v1/listings", params={**query, "limit": 1, "cursor": cursor}, headers=h).json()
    assert second["items"][0]["listingId"] == ids[1]
    assert (
        client.get("/api/v1/users/me/scraps", params={"limit": 1, "cursor": cursor}, headers=h).status_code
        == 400
    )


def test_chat_cross_room_pagination_read_and_concurrent_messages(client, actors):
    value, _ = public(client, actors)
    h, owner = actors["tenant"]["headers"], actors["listing_agent"]["headers"]
    chat = create(client, "/chats", {"listingId": value["listingId"]}, actors["tenant"]).json()
    another = create(client, "/chats", {"listingId": value["listingId"]}, actors["stranger"]).json()
    path = "/api/v1/chats/" + chat["chatId"]
    other_path = "/api/v1/chats/" + another["chatId"]
    other_message = client.post(
        other_path + "/messages", json={"text": "타인 방"}, headers={**owner, "Idempotency-Key": uid()}
    ).json()
    for suffix, method, body in [
        ("/read", "POST", {"lastReadMessageId": other_message["messageId"]}),
        ("/messages?before=" + other_message["messageId"], "GET", None),
    ]:
        assert client.request(method, path + suffix, json=body, headers=h).status_code == 404
    for body in ({"text": " "}, {"text": "\n\t"}):
        assert (
            client.post(path + "/messages", json=body, headers={**h, "Idempotency-Key": uid()}).status_code
            == 422
        )
    key = uid()

    def send(i):
        return client.post(
            path + "/messages", json={"text": "한 번만"}, headers={**h, "Idempotency-Key": key}
        )

    with ThreadPoolExecutor(max_workers=8) as pool:
        replies = list(pool.map(send, range(8)))
    assert all(r.status_code == 201 for r in replies)
    assert len({r.json()["messageId"] for r in replies}) == 1
    assert client.get(path, headers=owner).json()["unreadCount"] == 1

    def distinct(i):
        return client.post(path + "/messages", json={"text": str(i)}, headers={**h, "Idempotency-Key": uid()})

    with ThreadPoolExecutor(max_workers=8) as pool:
        replies = list(pool.map(distinct, range(16)))
    assert all(r.status_code == 201 for r in replies)
    messages = client.get(path + "/messages", headers=h).json()["items"]
    assert len(messages) == 17 and len({m["messageId"] for m in messages}) == 17
    assert client.get(path, headers=owner).json()["unreadCount"] == 17
    page = client.get(
        path + "/messages", params={"before": messages[0]["messageId"], "limit": 1}, headers=h
    ).json()
    cursor = page["page"]["nextCursor"]
    assert (
        client.get(
            path + "/messages",
            params={"before": messages[1]["messageId"], "limit": 1, "cursor": cursor},
            headers=h,
        ).status_code
        == 400
    )
    for status in ("DRAFT", "PUBLISHED", "ARCHIVED"):
        p = "/api/v1/listings/" + value["listingId"]
        et = client.get(p, headers=owner).headers["etag"]
        assert (
            client.put(
                p + "/publication", json={"status": status}, headers={**owner, "If-Match": et}
            ).status_code
            == 200
        )
        r = client.get(path, headers=h)
        assert r.json()["readOnly"] == (status != "PUBLISHED")
        if status != "PUBLISHED":
            assert r.json()["listing"]["thumbnail"] is None
            assert (
                client.post(
                    path + "/messages", json={"text": "불가"}, headers={**h, "Idempotency-Key": uid()}
                ).status_code
                == 409
            )


def test_upload_signed_grants_failure_expiry_freeze_and_no_partial_verify(client, actors, app):
    value = listing(client, actors)
    actor = actors["listing_agent"]
    raw = image()
    descriptors = [info(raw, "a.jpg", "image/jpeg"), info(raw, "b.jpg", "image/jpeg")]
    session = create(
        client,
        "/upload-sessions",
        dict(purpose="LISTING_PHOTO", targetType="LISTING", targetId=value["listingId"], files=descriptors),
        actor,
    ).json()
    grants = session["uploads"]
    p = "/api/v1/upload-sessions/" + session["session"]["uploadSessionId"]
    assert client.get(grants[0]["url"]).status_code == 403
    assert (
        client.put(
            grants[0]["url"], content=raw, headers={**grants[0]["headers"], "Content-Type": "image/png"}
        ).status_code
        == 422
    )
    assert (
        client.put(
            grants[0]["url"],
            content=raw,
            headers={**grants[0]["headers"], "x-amz-checksum-sha256": "invalid"},
        ).status_code
        == 422
    )
    assert client.put(grants[0]["url"], content=b"bad", headers=grants[0]["headers"]).status_code == 422
    assert (
        client.put(
            grants[0]["url"], content=raw, headers={**grants[0]["headers"], "Content-Length": "x"}
        ).status_code
        == 422
    )
    put(client, grants[0], raw)
    assert client.post(p + "/complete", headers=actor["headers"]).status_code == 409
    with app.state.database.transaction() as store:
        assert not store.get("file", grants[0]["fileId"])["value"]["_verified"]
    assert (
        client.post(p + "/upload-urls", json={"clientFileIds": [uid()]}, headers=actor["headers"]).status_code
        == 422
    )
    put(client, grants[1], raw)
    assert client.post(p + "/complete", headers=actor["headers"]).status_code == 200
    assert client.post(p + "/complete", headers=actor["headers"]).status_code == 200
    assert client.put(grants[0]["url"], content=raw, headers=grants[0]["headers"]).status_code == 409
    grant = client.get("/api/v1/assets/" + grants[0]["fileId"] + "/download", headers=actor["headers"]).json()
    assert client.get(grant["url"]).content == raw
    from urllib.parse import parse_qs, urlsplit

    parts = urlsplit(grant["url"])
    claims = jwt.decode(
        parse_qs(parts.query)["token"][0],
        app.state.settings.storage_key,
        algorithms=["HS256"],
        audience="storage",
    )
    for key, bad in [("exp", int(time.time()) - 1), ("file", uid()), ("sha", "0" * 64), ("method", "PUT")]:
        token = jwt.encode({**claims, key: bad}, app.state.settings.storage_key, algorithm="HS256")
        assert client.get(parts.path, params={"token": token}).status_code == 403
    assert list((app.state.settings.data_dir / "staging").iterdir()) == []
    with app.state.database.transaction() as store:
        s = store.get("upload", session["session"]["uploadSessionId"])["value"]
        s["expiresAt"] = stamp(time.time() - 1)
        store.put("upload", s["uploadSessionId"], s, actor["user"]["userId"])
    assert client.get(p, headers=actor["headers"]).json()["status"] == "EXPIRED"
    assert client.post(p + "/complete", headers=actor["headers"]).status_code == 410


def test_worker_claim_concurrency_artifact_replay_and_cancel_complete_race(client, actors, app):
    value = create(client, "/furniture", {"name": "경합", "category": "DESK"}, actors["tenant"]).json()
    accepted = capture(client, actors, "FURNITURE", value["furnitureId"])
    p = "/api/v1/internal/ai-jobs/" + accepted["jobId"]

    def request_claim(i):
        return client.post(p + "/claim", json={"workerId": "test-worker"}, headers=actors["worker"])

    with ThreadPoolExecutor(max_workers=8) as pool:
        responses = list(pool.map(request_claim, range(8)))
    assert sorted(r.status_code for r in responses) == [200] + [409] * 7
    assignment = next(r.json() for r in responses if r.status_code == 200)
    h = {**actors["worker"], "X-Job-Lease-Token": assignment["leaseToken"]}
    descriptors = [
        dict(
            clientFileId=uid(),
            role=role,
            contentType="model/gltf-binary",
            format="GLB_V2",
            byteSize=len(glb()),
            sha256=hashlib.sha256(glb()).hexdigest(),
        )
        for role in ("MODEL", "COLLISION")
    ]
    first = client.post(p + "/artifacts", json={"files": descriptors}, headers=h)
    assert first.status_code == 200
    repeated = client.post(p + "/artifacts", json={"files": descriptors}, headers=h)
    assert [g["fileId"] for g in first.json()["uploads"]] == [g["fileId"] for g in repeated.json()["uploads"]]
    bad = copy.deepcopy(descriptors)
    bad[0]["byteSize"] += 1
    assert client.post(p + "/artifacts", json={"files": bad}, headers=h).status_code == 409
    assert client.post(p + "/artifacts", json={"files": descriptors[:1]}, headers=h).status_code == 422
    assets = {}
    for desc, grant in zip(descriptors, first.json()["uploads"]):
        put(client, grant, glb())
        assets[desc["role"]] = {k: desc[k] for k in ("contentType", "format", "byteSize", "sha256")}
        assets[desc["role"]]["fileId"] = grant["fileId"]
    result = dict(
        type="FURNITURE_RECONSTRUCTION",
        modelAsset=assets["MODEL"],
        collisionAsset=assets["COLLISION"],
        dimensions={"width": 1, "height": 1, "depth": 1},
        boundingBox={"min": {"x": -0.5, "y": 0, "z": -0.5}, "max": {"x": 0.5, "y": 1, "z": 0.5}},
        quality={"scaleSource": "AR_TRACKING", "estimatedScaleErrorM": None, "warnings": []},
    )

    def race(action):
        if action == "cancel":
            return client.post(
                "/api/v1/ai-jobs/" + accepted["jobId"] + "/cancel", headers=actors["tenant"]["headers"]
            )
        return client.post(p + "/complete", json=result, headers=h)

    with ThreadPoolExecutor(max_workers=2) as pool:
        race_responses = list(pool.map(race, ("cancel", "complete")))
    assert sorted(r.status_code for r in race_responses) == [200, 409]
    current = client.get("/api/v1/ai-jobs/" + accepted["jobId"], headers=actors["tenant"]["headers"]).json()
    furniture = client.get(
        "/api/v1/furniture/" + value["furnitureId"], headers=actors["tenant"]["headers"]
    ).json()
    assert current["status"] in ("CANCELED", "COMPLETED")
    assert bool(furniture["model"]) == (current["status"] == "COMPLETED")


def test_worker_attempt_deadline_token_identity_and_failure_replay(client, actors, app):
    value = create(client, "/furniture", {"name": "시간 제한", "category": "DESK"}, actors["tenant"]).json()
    accepted = capture(client, actors, "FURNITURE", value["furnitureId"])
    p = "/api/v1/internal/ai-jobs/" + accepted["jobId"]
    assert client.post(p + "/claim", json={"workerId": "other"}, headers=actors["worker"]).status_code == 403
    h, _ = claim(client, actors, accepted["jobId"])
    other = {
        "Authorization": "Bearer " + worker_token(app.state.settings, "other"),
        "X-Job-Lease-Token": h["X-Job-Lease-Token"],
    }
    beat = dict(progressPercent=10, stage="RECONSTRUCTING", refreshInputUrls=False)
    assert client.post(p + "/heartbeat", json=beat, headers=other).status_code == 409
    assert (
        client.post(p + "/heartbeat", json=beat, headers={**h, "X-Job-Lease-Token": "a" * 64}).status_code
        == 409
    )
    with app.state.database.transaction() as store:
        j = store.get("job", accepted["jobId"])["value"]
        j["_attemptStarted"] = time.time() - 1790
        store.put("job", j["jobId"], j, value["ownerId"])
    response = client.post(p + "/heartbeat", json=beat, headers=h)
    assert response.status_code == 200
    assert (
        time.time()
        < __import__("app.core", fromlist=["epoch"]).epoch(response.json()["leaseExpiresAt"])
        <= time.time() + 11
    )
    failure = {"error": {"code": "INVALID_ARTIFACT", "message": "영구 오류", "retryable": True}}
    first = client.post(p + "/fail", json=failure, headers=h)
    assert first.status_code == 200 and not first.json()["error"]["retryable"]
    assert client.post(p + "/fail", json=failure, headers=h).json() == first.json()
    assert (
        client.post(
            p + "/fail", json={"error": {**failure["error"], "message": "다름"}}, headers=h
        ).status_code
        == 409
    )
    assert client.post(p + "/fail", json=failure, headers=other).status_code == 409


def test_placement_validation_and_private_isolation(client, actors):
    value, _ = public(client, actors)
    furniture = ready_furniture(client, actors)
    path = "/api/v1/listings/" + value["listingId"] + "/placements"
    h = actors["tenant"]["headers"]
    item = dict(
        placementId=uid(),
        furnitureId=furniture["furnitureId"],
        modelVersion=1,
        transform={"position": {"x": 0, "y": 0, "z": 0}, "rotation": {"x": 0, "y": 0, "z": 0, "w": -1}},
    )
    base = dict(
        space={"kind": "ORIGINAL", "spaceVersion": 1, "roomStateId": None},
        placements=[item],
        clientCollisionReport=None,
    )
    for changed, expected in [
        ({**base, "placements": [item, item]}, 422),
        ({**base, "placements": [{**item, "modelVersion": 2}]}, 409),
        (
            {
                **base,
                "placements": [
                    {
                        **item,
                        "transform": {**item["transform"], "rotation": {"x": 0, "y": 0, "z": 0, "w": 0.5}},
                    }
                ],
            },
            422,
        ),
    ]:
        assert client.put(path, json=changed, headers={**h, "If-None-Match": "*"}).status_code == expected
    assert (
        client.put(
            path, json=base, headers={**actors["stranger"]["headers"], "If-None-Match": "*"}
        ).status_code
        == 404
    )
    assert (
        client.put(path, json=base, headers={**h, "If-Match": '"1"', "If-None-Match": "*"}).status_code == 400
    )
    saved = client.put(path, json=base, headers={**h, "If-None-Match": "*"})
    assert saved.status_code == 200 and saved.json()["placements"][0]["transform"]["rotation"]["w"] == 1
    report = dict(rulesVersion="1.0", evaluatedAt=stamp(), isPlaceable=True, collisions=[])
    for changed in [{**report, "isPlaceable": False}, {**report, "evaluatedAt": stamp(time.time() + 120)}]:
        assert (
            client.put(
                path,
                json={**base, "clientCollisionReport": changed},
                headers={**h, "If-Match": saved.headers["etag"]},
            ).status_code
            == 422
        )
    assert client.get(path, headers=actors["stranger"]["headers"]).status_code == 404
    assert client.delete(path, headers={**h, "If-Match": saved.headers["etag"]}).status_code == 204
    revived = client.put(path, json={**base, "placements": []}, headers={**h, "If-None-Match": "*"})
    assert revived.status_code == 200 and revived.json()["placementSetId"] != saved.json()["placementSetId"]


def test_cleanup_shared_blob_orphans_and_capture_retention(client, actors, app):
    values = [listing(client, actors), listing(client, actors)]
    uploaded = [
        upload(client, actors["listing_agent"], "LISTING", v["listingId"], "LISTING_PHOTO") for v in values
    ]
    raw_hash = uploaded[0]["session"]["files"][0]["sha256"]
    blob = app.state.settings.data_dir / "objects" / raw_hash
    orphan = app.state.settings.data_dir / "objects" / ("e" * 64)
    orphan.write_bytes(b"orphan")
    stage = app.state.settings.data_dir / "staging" / uid()
    stage.write_bytes(b"interrupted")
    for file in (blob, orphan, stage):
        os.utime(file, (time.time() - 172800, time.time() - 172800))
    owner = actors["listing_agent"]["headers"]
    p = "/api/v1/listings/" + values[0]["listingId"]
    assert (
        client.delete(
            p, headers={**owner, "If-Match": client.get(p, headers=owner).headers["etag"]}
        ).status_code
        == 204
    )
    app.state.maintenance()
    assert blob.exists() and not orphan.exists() and not stage.exists()
    assert (
        client.get(
            "/api/v1/assets/" + uploaded[1]["session"]["files"][0]["fileId"] + "/download", headers=owner
        ).status_code
        == 200
    )
    furniture = create(client, "/furniture", {"name": "정리", "category": "DESK"}, actors["tenant"]).json()
    accepted = capture(client, actors, "FURNITURE", furniture["furnitureId"])
    with app.state.database.transaction() as store:
        job = store.get("job", accepted["jobId"])["value"]
        job["createdAt"] = stamp(time.time() - 3601)
        store.put("job", job["jobId"], job, furniture["ownerId"])
    app.state.maintenance()
    job_path = "/api/v1/ai-jobs/" + accepted["jobId"]
    assert client.get(job_path, headers=actors["tenant"]["headers"]).json()["status"] == "FAILED"
    with app.state.database.transaction() as store:
        job = store.get("job", accepted["jobId"])["value"]
        job["updatedAt"] = stamp(time.time() - 31 * 86400)
        store.put("job", job["jobId"], job, furniture["ownerId"])
    app.state.maintenance()
    assert (
        client.post(
            job_path + "/retries", headers={**actors["tenant"]["headers"], "Idempotency-Key": uid()}
        ).status_code
        == 410
    )
    assert (
        client.get(
            "/api/v1/captures/" + accepted["captureId"], headers=actors["tenant"]["headers"]
        ).status_code
        == 410
    )


def test_database_atomic_rollback_outbox_restart_and_backup_restore(client, actors, app, tmp_path):
    database = app.state.database
    rid = uid()
    with pytest.raises(RuntimeError):
        with database.transaction() as store:
            store.put("probe", rid, {"secret": "never-return"}, "probe")
            store.conn.execute(
                "INSERT INTO outbox(event_id,job_id,available_at) VALUES(?,?,?)", (uid(), rid, time.time())
            )
            raise RuntimeError("injected crash before commit")
    with database.transaction() as store:
        assert store.optional("probe", rid) is None
        assert not store.conn.execute("SELECT 1 FROM outbox WHERE job_id=?", (rid,)).fetchone()
    value = create(client, "/furniture", {"name": "복구", "category": "DESK"}, actors["tenant"]).json()
    accepted = capture(client, actors, "FURNITURE", value["furnitureId"])
    app.state.maintenance()
    restarted = create_app(app.state.settings)
    with TestClient(restarted) as second:
        assert (
            second.get("/api/v1/ai-jobs/" + accepted["jobId"], headers=actors["tenant"]["headers"]).json()[
                "status"
            ]
            == "QUEUED"
        )
    with database.connect() as conn:
        assert (
            conn.execute("SELECT COUNT(*) FROM queue WHERE job_id=?", (accepted["jobId"],)).fetchone()[0] == 1
        )
    backup = tmp_path / "backup.sqlite3"
    with database.connect() as source, sqlite3.connect(backup) as destination:
        source.backup(destination)
    import shutil

    restored_dir = tmp_path / "restore"
    restored_dir.mkdir()
    shutil.copyfile(backup, restored_dir / "backend.sqlite3")
    shutil.copytree(app.state.settings.data_dir / "objects", restored_dir / "objects")
    shutil.copyfile(
        app.state.settings.data_dir / "development-secrets.json", restored_dir / "development-secrets.json"
    )
    restored = create_app(Settings(data_dir=restored_dir, scheduler=False, rate_limits=False))
    with TestClient(restored) as c:
        assert c.get("/api/v1/users/me", headers=actors["tenant"]["headers"]).status_code == 200
        assert (
            c.get(
                "/api/v1/furniture/" + value["furnitureId"], headers=actors["tenant"]["headers"]
            ).status_code
            == 200
        )
        assignment = c.post(
            "/api/v1/internal/ai-jobs/" + accepted["jobId"] + "/claim",
            json={"workerId": "test-worker"},
            headers=actors["worker"],
        )
        assert assignment.status_code == 200
        assert c.get(assignment.json()["input"]["images"][0]["url"]).status_code == 200


def test_database_unavailable_and_safe_internal_errors(client, actors, app, monkeypatch, caplog):
    def unavailable():
        raise sqlite3.OperationalError("injected lock")

    monkeypatch.setattr(app.state.database, "connect", unavailable)
    r = client.get("/api/v1/users/me", headers=actors["tenant"]["headers"])
    assert r.status_code == 503 and r.headers["retry-after"]
    assert "injected lock" not in r.text
    assert client.get("/health/ready").status_code == 503
    assert client.get("/health/live").status_code == 200
    monkeypatch.undo()

    def fail(self, body):
        raise RuntimeError("injected unexpected fault")

    monkeypatch.setattr(Service, "getMe", fail)
    r = client.get("/api/v1/users/me", headers={**actors["tenant"]["headers"], "X-Request-Id": "review-test"})
    assert (
        r.status_code == 500
        and r.json()["code"] == "INTERNAL_ERROR"
        and r.json()["requestId"] == "review-test"
    )
    assert "injected unexpected fault" not in r.text
    assert actors["tenant"]["pair"]["accessToken"] not in caplog.text
    assert actors["tenant"]["pair"]["refreshToken"] not in caplog.text


def test_cors_security_headers_health_routes_and_production_settings(tmp_path):
    cfg = Settings(
        data_dir=tmp_path / "cors",
        scheduler=False,
        rate_limits=False,
        cors_origins=["https://app.example.com"],
    )
    with TestClient(create_app(cfg)) as c:
        r = c.options(
            "/api/v1/users/me",
            headers={
                "Origin": "https://app.example.com",
                "Access-Control-Request-Method": "PATCH",
                "Access-Control-Request-Headers": "authorization,if-match",
            },
        )
        assert r.status_code == 200 and r.headers["access-control-allow-origin"] == "https://app.example.com"
        assert (
            c.options(
                "/api/v1/users/me",
                headers={"Origin": "https://evil.example.com", "Access-Control-Request-Method": "PATCH"},
            ).status_code
            == 400
        )
        assert c.post("/health/live").status_code == 405
        assert c.get("/missing").status_code == 404
        r = c.get("/health/live", headers={"X-Request-Id": "bad request id"})
        assert (
            r.headers["x-request-id"] != "bad request id" and r.headers["x-content-type-options"] == "nosniff"
        )
    base = dict(
        environment="production",
        jwt_key="a" * 40,
        worker_key="b" * 40,
        storage_key="c" * 40,
        base_url="https://api.example.com",
        scheduler=False,
    )
    Settings(data_dir=tmp_path / "good", **base).prepare()
    for change in [
        dict(jwt_key="short"),
        dict(worker_key="a" * 40),
        dict(base_url="http://api.example.com"),
        dict(cors_origins=["*"]),
        dict(environment="unknown"),
    ]:
        with pytest.raises(Problem) as error:
            Settings(data_dir=tmp_path / uid(), **{**base, **change}).prepare()
        assert error.value.code == "CONFIGURATION_ERROR"


def test_rate_limit_all_buckets_persist_restart_and_window_reset(client, actors, app, monkeypatch):
    cfg = app.state.settings
    cfg.rate_limits = True
    now = time.time()
    monkeypatch.setattr("app.main.time.time", lambda: now)
    window = int(now // 60)
    user_id = actors["tenant"]["user"]["userId"]

    def fill(bucket, count):
        with app.state.database.transaction() as store:
            store.conn.execute("INSERT OR REPLACE INTO rate_limits VALUES(?,?,?)", (bucket, window, count))

    fill("user:" + user_id, 120)
    assert client.get("/api/v1/users/me", headers=actors["tenant"]["headers"]).status_code == 429
    with app.state.database.transaction() as store:
        store.conn.execute("DELETE FROM rate_limits")
    fill("urls:" + user_id, 20)
    assert (
        client.get("/api/v1/assets/" + uid() + "/download", headers=actors["tenant"]["headers"]).status_code
        == 429
    )
    fill("ai:" + user_id, 10)
    assert (
        client.post(
            "/api/v1/ai-jobs/" + uid() + "/retries",
            headers={**actors["tenant"]["headers"], "Idempotency-Key": uid()},
        ).status_code
        == 429
    )
    job = uid()
    fill("poll:" + user_id + ":" + job, 20)
    assert client.get("/api/v1/ai-jobs/" + job, headers=actors["tenant"]["headers"]).status_code == 429
    fill("worker:test-worker", 600)
    assert (
        client.post(
            "/api/v1/internal/ai-jobs/" + uid() + "/claim",
            json={"workerId": "test-worker"},
            headers=actors["worker"],
        ).status_code
        == 429
    )
    restarted = create_app(cfg)
    with TestClient(restarted) as c:
        assert (
            c.get("/api/v1/assets/" + uid() + "/download", headers=actors["tenant"]["headers"]).status_code
            == 429
        )
    monkeypatch.setattr("app.main.time.time", lambda: now + 61)
    assert client.get("/api/v1/users/me", headers=actors["tenant"]["headers"]).status_code == 200


def test_offline_cli_all_commands_and_report_review(client, actors, app, monkeypatch, capsys, tmp_path):
    import sys
    from app.cli import main
    from app.admin import review_report

    cfg = app.state.settings
    monkeypatch.setenv("DATA_DIR", str(cfg.data_dir))
    monkeypatch.setenv("APP_ENV", "test")

    def run(*args):
        monkeypatch.setattr(sys, "argv", ["dulleobang", *args])
        main()
        return capsys.readouterr().out

    assert "initialized" in run("init")
    assert "completed" in run("maintenance")
    assert json.loads(run("queue")) == []
    assert json.loads(run("pending-reviews")) == {"applications": [], "reports": []}
    assert "LISTING_AGENT" in json.loads(run("grant-listing-agent", "tenant@example.com"))["roles"]
    assert "LISTING_AGENT" in json.loads(run("grant-listing-agent", "tenant@example.com"))["roles"]
    token = run("worker-token", "test-worker", "--seconds", "60").strip()
    assert (
        jwt.decode(token, cfg.worker_key, algorithms=["HS256"], audience="dulleobang-internal")["scope"]
        == "AI_WORKER"
    )
    backup = tmp_path / "cli-backup.sqlite3"
    assert "backed up" in run("backup", str(backup)) and backup.is_file()
    for args in [
        ("queue", "--limit", "0"),
        ("worker-token", "w", "--seconds", "86401"),
        ("grant-listing-agent", "missing@example.com"),
        ("review-agent", uid(), "--decision", "approve"),
        ("review-report", uid(), "--decision", "resolve", "--reason", "x"),
    ]:
        with pytest.raises(SystemExit) as exit:
            run(*args)
        assert exit.value.code == 2
        capsys.readouterr()
    for decision in ("resolve", "dismiss"):
        rid = uid()
        with app.state.database.transaction() as store:
            store.put("report", rid, dict(reportId=rid, status="OPEN"), "reporter", "listing")
        result = json.loads(run("review-report", rid, "--decision", decision, "--reason", "  검토  "))
        assert result["status"] == ("RESOLVED" if decision == "resolve" else "DISMISSED")
        with app.state.database.transaction() as store:
            assert len(store.all("audit")) >= 1
            with pytest.raises(Problem):
                review_report(store, rid, decision, "again")
    with app.state.database.transaction() as store:
        for decision, reason in [("bad", "x"), ("resolve", "  "), ("dismiss", "x" * 2001)]:
            with pytest.raises(Problem):
                review_report(store, uid(), decision, reason)


def test_auth_signed_claims_sessions_and_no_role_escalation(client, actors, app):
    cfg = app.state.settings
    pair = actors["tenant"]["pair"]
    original = jwt.decode(
        pair["accessToken"], cfg.jwt_key, algorithms=["HS256"], audience="dulleobang-mobile"
    )
    for field, value in [
        ("exp", int(time.time()) - 1),
        ("iat", int(time.time()) + 100),
        ("aud", "wrong"),
        ("iss", "wrong"),
        ("sid", uid()),
        ("sub", actors["stranger"]["user"]["userId"]),
    ]:
        token = jwt.encode({**original, field: value}, cfg.jwt_key, algorithm="HS256")
        assert client.get("/api/v1/users/me", headers={"Authorization": "Bearer " + token}).status_code == 401
    for field in ("exp", "iat", "sid", "sub", "iss", "aud"):
        claims = dict(original)
        claims.pop(field)
        token = jwt.encode(claims, cfg.jwt_key, algorithm="HS256")
        assert client.get("/api/v1/users/me", headers={"Authorization": "Bearer " + token}).status_code == 401
    token = jwt.encode({**original, "roles": ["LISTING_AGENT", "ADMIN"]}, cfg.jwt_key, algorithm="HS256")
    assert client.get("/api/v1/users/me", headers={"Authorization": "Bearer " + token}).json()["roles"] == [
        "TENANT"
    ]
    assert client.get("/api/v1/users/me", headers=actors["worker"]).status_code == 401
    worker = jwt.decode(
        actors["worker"]["Authorization"].removeprefix("Bearer "),
        cfg.worker_key,
        algorithms=["HS256"],
        audience="dulleobang-internal",
    )
    worker["scope"] = "ADMIN"
    token = jwt.encode(worker, cfg.worker_key, algorithm="HS256")
    assert (
        client.post(
            "/api/v1/internal/ai-jobs/" + uid() + "/claim",
            json={"workerId": "test-worker"},
            headers={"Authorization": "Bearer " + token},
        ).status_code
        == 403
    )
    body = dict(email="TENANT@EXAMPLE.COM", password="StrongPassword!123", displayName="중복")
    assert client.post("/api/v1/auth/signup", json=body).status_code == 409
    for email in ("tenant@example.com", "doesnotexist@example.com"):
        response = client.post("/api/v1/auth/login", json={"email": email, "password": "WrongPassword!123"})
        assert response.status_code == 401 and response.json()["code"] == "INVALID_CREDENTIALS"
    with app.state.database.connect() as conn:
        row = conn.execute(
            "SELECT password_hash FROM credentials WHERE email='tenant@example.com'"
        ).fetchone()
        assert row[0].startswith("$argon2") and "StrongPassword" not in row[0]


def test_disk_write_failure_returns_503_and_leaves_no_verified_file(client, actors, app, monkeypatch):
    value = listing(client, actors)
    raw = image()
    session = create(
        client,
        "/upload-sessions",
        {
            "purpose": "LISTING_PHOTO",
            "targetType": "LISTING",
            "targetId": value["listingId"],
            "files": [info(raw, "photo.jpg", "image/jpeg")],
        },
        actors["listing_agent"],
    ).json()

    def disk_full(fd):
        raise OSError("injected disk-full detail")

    monkeypatch.setattr("app.main.os.fsync", disk_full)
    grant = session["uploads"][0]
    response = client.put(grant["url"], content=raw, headers=grant["headers"])
    assert response.status_code == 503 and response.json()["code"] == "STORAGE_UNAVAILABLE"
    assert response.headers["retry-after"] and "disk-full" not in response.text
    with app.state.database.transaction() as store:
        record = store.get("file", grant["fileId"])["value"]
        assert not record["_stored"] and not record["_verified"]
    assert list((app.state.settings.data_dir / "staging").iterdir()) == []


def test_scheduler_recovers_after_temporary_maintenance_failure(tmp_path, monkeypatch, caplog):
    import threading
    import asyncio

    original_sleep = asyncio.sleep
    original = Service.maintenance
    observed = threading.Event()
    calls = [0]

    def maintain(self):
        calls[0] += 1
        if calls[0] == 2:
            raise RuntimeError("injected temporary maintenance fault")
        result = original(self)
        if calls[0] >= 3:
            observed.set()
        return result

    async def short_sleep(seconds):
        await original_sleep(0.01)

    monkeypatch.setattr(Service, "maintenance", maintain)
    monkeypatch.setattr("app.main.asyncio.sleep", short_sleep)
    cfg = Settings(data_dir=tmp_path, scheduler=True, rate_limits=False)
    with TestClient(create_app(cfg)) as c:
        assert observed.wait(3)
        assert c.get("/health/ready").status_code == 200
    assert calls[0] >= 3 and "Maintenance failed" in caplog.text
