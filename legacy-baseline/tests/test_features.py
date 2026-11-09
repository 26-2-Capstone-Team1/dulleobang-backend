import copy
import json
import time
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from PIL import Image
from app.core import Problem, Settings, Database, stamp, uid
from app.admin import review_agent
from conftest import (
    create,
    example,
    listing,
    capture,
    complete_room,
    publish,
    upload,
    info,
    put,
    image,
    claim,
)


def public_listing(client, actors):
    v = listing(client, actors)
    j = capture(client, actors, "LISTING", v["listingId"])
    complete_room(client, actors, j["jobId"])
    return publish(client, actors, v["listingId"])


def test_anyone_can_register_fields_privacy_and_map(client, actors, app):
    h = actors["tenant"]["headers"]
    b = example("ListingCreate")
    b.pop("title")
    b.pop("description")
    b.pop("floor")
    b["location"].pop("regionCode")
    b["location"].update(roadAddress="서울 상도동 예시로 10", detailAddress="302호")
    b["price"]["maintenanceFee"] = None
    b.update(
        totalFloors=10,
        supplyAreaM2=30,
        roomCount=1,
        bathroomCount=1,
        availableFrom="IMMEDIATE",
        livingEnvironment="지하철 근처",
        rentalOptions=["PARKING", "PET_ALLOWED", "ELEVATOR"],
    )
    v = create(client, "/listings", b, actors["tenant"]).json()
    p = "/api/v1/listings/" + v["listingId"]
    assert v["ownerId"] == actors["tenant"]["user"]["userId"] and not v["owner"]["isVerifiedListingAgent"]
    assert v["location"]["detailAddress"] == "302호" and "title" not in v and v["floor"] is None
    with app.state.database.transaction() as s:
        x = s.get("listing", v["listingId"])["value"]
        x.update(status="PUBLISHED", currentSpaceVersion=1)
        s.put("listing", v["listingId"], x, v["ownerId"])
    me = client.get("/api/v1/users/me", headers=h)
    assert (
        client.patch(
            "/api/v1/users/me",
            json=dict(phone="010-1234-5678"),
            headers={**h, "If-Match": me.headers["etag"]},
        ).status_code
        == 200
    )
    other = actors["stranger"]["headers"]
    x = client.get(p, headers=other).json()
    assert (
        "detailAddress" not in x["location"]
        and x["owner"]["phone"] is None
        and x["price"]["maintenanceFee"] is None
    )
    me = client.get("/api/v1/users/me", headers=h)
    assert (
        client.patch(
            "/api/v1/users/me", json=dict(isPhonePublic=True), headers={**h, "If-Match": me.headers["etag"]}
        ).status_code
        == 200
    )
    assert client.get(p, headers=other).json()["owner"]["phone"] == "010-1234-5678"
    q = [
        ("q", "서울 상도"),
        ("roomType", "ONE_ROOM"),
        ("roomType", "OFFICETEL"),
        ("minLat", "37"),
        ("maxLat", "38"),
        ("minLng", "127"),
        ("maxLng", "128"),
        ("limit", "200"),
    ]
    r = client.get("/api/v1/listings", params=q, headers=other)
    assert r.status_code == 200, r.text
    assert (
        r.json()["items"][0]["listingId"] == v["listingId"]
        and "detailAddress" not in r.json()["items"][0]["location"]
    )
    assert client.get("/api/v1/listings?q=상도없는단어", headers=other).json()["items"] == []
    for q in ("minLat=37", "limit=51", "roomType=VILLA"):
        assert client.get("/api/v1/listings?" + q, headers=other).status_code == 400
    for patch in (
        {"availableFrom": "2026-02-31"},
        {"livingEnvironment": "a" * 501},
        {"propertyType": "STUDIO"},
        {"price": {**b["price"], "deposit": 1.5}},
        {"supplyAreaM2": 1},
    ):
        et = client.get(p, headers=h).headers["etag"]
        assert client.patch(p, json=patch, headers={**h, "If-Match": et}).status_code == 422
    assert (
        client.get("/api/v1/users/me/listings", headers=h).json()["items"][0]["listingId"] == v["listingId"]
    )
    # General ownership, not an agent role, authorizes capture upload.
    session = upload(client, actors["tenant"], "LISTING", v["listingId"], "ROOM_CAPTURE")
    assert session["session"]["status"] == "OPEN"


def test_asset_inline_urls_and_refresh(client, actors):
    v = public_listing(client, actors)
    h = actors["tenant"]["headers"]
    p = "/api/v1/listings/" + v["listingId"]
    x = client.get(p, headers=h).json()
    a = x["photos"][0]
    assert a["urlExpiresAt"] and client.get(a["url"]).status_code == 200
    a = client.get("/api/v1/listings", headers=h).json()["items"][0]["thumbnail"]
    assert a["url"]
    space = client.get(p + "/space", headers=h).json()
    a = space["bundle"]["sceneAsset"]
    assert client.get(a["url"]).status_code == 200
    assert client.get("/api/v1/assets/" + a["fileId"] + "/download", headers=h).status_code == 200
    assert (
        client.get(
            "/api/v1/assets/" + v["photos"][0]["fileId"] + "/download", headers=actors["stranger"]["headers"]
        ).status_code
        == 200
    )


def test_favorites_reports_chat_idempotency_and_read_markers(client, actors, app):
    v = public_listing(client, actors)
    p = "/api/v1/listings/" + v["listingId"]
    h = actors["tenant"]["headers"]
    owner = actors["listing_agent"]["headers"]
    other = actors["stranger"]["headers"]
    assert client.put(p + "/scrap", headers=h).status_code == 204
    assert client.put(p + "/scrap", headers=h).status_code == 204
    assert client.get("/api/v1/users/me/scraps", headers=h).json()["items"][0]["isScrapped"]
    assert (
        client.delete(p + "/scrap", headers=h).status_code == 204
        and client.delete(p + "/scrap", headers=h).status_code == 204
    )
    assert client.put(p + "/scrap", headers=h).status_code == 204
    key = uid()
    rb = dict(reason="WRONG_PRICE")
    r = client.post(p + "/reports", json=rb, headers={**h, "Idempotency-Key": key})
    assert r.status_code == 201, r.text
    assert client.post(p + "/reports", json=rb, headers={**h, "Idempotency-Key": key}).status_code == 201
    duplicate = client.post(
        p + "/reports", json=dict(reason="OTHER", detail="다시 신고"), headers={**h, "Idempotency-Key": uid()}
    )
    assert duplicate.status_code == 200 and duplicate.json() == r.json()
    with app.state.database.transaction() as s:
        report = s.get("report", r.json()["reportId"])["value"]
        report["createdAt"] = stamp(time.time() - 86401)
        s.put("report", report["reportId"], report, actors["tenant"]["user"]["userId"], v["listingId"])
    assert client.post(p + "/reports", json=rb, headers={**h, "Idempotency-Key": uid()}).status_code == 201
    assert (
        client.post(
            "/api/v1/chats", json=dict(listingId=v["listingId"]), headers={**owner, "Idempotency-Key": uid()}
        ).status_code
        == 409
    )

    def open_chat(_):
        return client.post(
            "/api/v1/chats", json=dict(listingId=v["listingId"]), headers={**h, "Idempotency-Key": uid()}
        )

    with ThreadPoolExecutor(max_workers=2) as pool:
        rs = list(pool.map(open_chat, range(2)))
    assert (
        sorted(r.status_code for r in rs) == [200, 201] and rs[0].json()["chatId"] == rs[1].json()["chatId"]
    )
    cp = "/api/v1/chats/" + rs[0].json()["chatId"]
    assert client.get(cp, headers=h).status_code == 200 and client.get(cp, headers=other).status_code == 404
    assert client.get("/api/v1/chats", headers=other).json()["items"] == []
    messages = []
    for i in range(3):
        key = uid()
        headers = {**h, "Idempotency-Key": key}
        r = client.post(cp + "/messages", json=dict(text=str(i)), headers=headers)
        assert r.status_code == 201, r.text
        messages.append(r.json())
        assert client.post(cp + "/messages", json=dict(text=str(i)), headers=headers).json() == r.json()
        assert client.post(cp + "/messages", json=dict(text="다름"), headers=headers).status_code == 409
    assert client.get(cp, headers=owner).json()["unreadCount"] == 3
    assert (
        client.post(
            cp + "/read", json=dict(lastReadMessageId=messages[1]["messageId"]), headers=owner
        ).status_code
        == 204
    )
    assert (
        client.post(
            cp + "/read", json=dict(lastReadMessageId=messages[0]["messageId"]), headers=owner
        ).status_code
        == 204
    )
    assert client.get("/api/v1/chats", headers=owner).json()["items"][0]["unreadCount"] == 1
    first = client.get(cp + "/messages?limit=1", headers=h).json()
    assert first["items"] == [messages[-1]]
    nextpage = client.get(
        cp + "/messages", params=dict(limit=1, cursor=first["page"]["nextCursor"]), headers=h
    ).json()
    assert nextpage["items"] == [messages[1]]
    assert client.get(cp + "/messages?before=" + messages[1]["messageId"], headers=h).json()["items"] == [
        messages[0]
    ]
    assert client.post(cp + "/messages", json=dict(text="x"), headers=h).status_code == 400
    assert (
        client.post(
            cp + "/messages", json=dict(text="x" * 1001), headers={**h, "Idempotency-Key": uid()}
        ).status_code
        == 422
    )
    et = client.get(p, headers=owner).headers["etag"]
    assert client.delete(p, headers={**owner, "If-Match": et}).status_code == 204
    assert (
        client.get(cp, headers=h).json()["readOnly"]
        and client.get(cp, headers=h).json()["listing"]["thumbnail"] is None
    )
    assert client.get(cp + "/messages", headers=h).status_code == 200
    assert (
        client.post(
            cp + "/messages", json=dict(text="x"), headers={**h, "Idempotency-Key": uid()}
        ).status_code
        == 409
    )
    assert client.get("/api/v1/users/me/scraps", headers=h).json()["items"] == []
    assert client.delete(p + "/scrap", headers=h).status_code == 204


def test_agent_verification_private_proof_rejection_approval(client, actors, app):
    h = actors["tenant"]["headers"]
    p = "/api/v1/users/me/listing-agent-verification"
    assert client.get(p, headers=h).json()["status"] == "NONE"
    raw = image()
    f = info(raw, "registration.jpg", "image/jpeg")
    body = dict(
        purpose="LISTING_AGENT_DOCUMENT",
        targetType="USER",
        targetId=actors["tenant"]["user"]["userId"],
        files=[f],
    )
    session = create(client, "/upload-sessions", body, actors["tenant"]).json()
    put(client, session["uploads"][0], raw)
    assert (
        client.post(
            "/api/v1/upload-sessions/" + session["session"]["uploadSessionId"] + "/complete", headers=h
        ).status_code
        == 200
    )
    b = dict(
        name="차유현",
        licenseNumber="TEST-001",
        officeName="상도 공인중개사",
        phone="02-1234-5678",
        documentFileId=session["session"]["files"][0]["fileId"],
    )
    r = create(client, "/users/me/listing-agent-verification", b, actors["tenant"])
    assert r.json()["status"] == "PENDING"
    assert (
        client.post(p, json=b, headers={**h, "Idempotency-Key": uid()}).json()["code"]
        == "VERIFICATION_PENDING"
    )
    assert (
        client.get(
            "/api/v1/assets/" + b["documentFileId"] + "/download", headers=actors["stranger"]["headers"]
        ).status_code
        == 404
    )
    with app.state.database.transaction() as s:
        profile = s.get("agentprofile", actors["tenant"]["user"]["userId"])["value"]
        review_agent(s, profile["applicationId"], "reject", "서류 재확인")
    assert client.get(p, headers=h).json()["rejectReason"] == "서류 재확인"
    assert (
        create(client, "/users/me/listing-agent-verification", b, actors["tenant"]).json()["status"]
        == "PENDING"
    )
    with app.state.database.transaction() as s:
        profile = s.get("agentprofile", actors["tenant"]["user"]["userId"])["value"]
        review_agent(s, profile["applicationId"], "approve")
    assert "LISTING_AGENT" in client.get("/api/v1/users/me", headers=h).json()["roles"]
    v = create(client, "/listings", example("ListingCreate"), actors["tenant"]).json()
    assert (
        v["owner"]["isVerifiedListingAgent"]
        and v["owner"]["officeName"] == b["officeName"]
        and v["owner"]["phone"] == b["phone"]
    )
    assert client.get(p, headers=h).json()["status"] == "APPROVED"


@pytest.mark.parametrize("corrupt", [None, "confidence", "reference", "partial", "unreferenced"])
def test_png_depth_roomplan_worker_inputs(client, actors, corrupt):
    v = listing(client, actors)
    rgb = image()
    files = [dict(info(rgb, str(i) + ".jpg", "image/jpeg"), role="IMAGE") for i in range(30)]
    raws = [rgb] * 30
    out = BytesIO()
    Image.new("I;16", (2, 2), 1000).save(out, "PNG")
    depthraw = out.getvalue()
    out = BytesIO()
    Image.new("L", (2, 2), 3 if corrupt == "confidence" else 2).save(out, "PNG")
    confraw = out.getvalue()
    manifest = example("CameraManifest")
    manifest["platform"] = "ARKIT"
    manifest["frames"] = []
    framebase = example("CameraFrame")
    framebase["intrinsics"] = dict(fx=500, fy=500, cx=320, cy=240)
    for i in range(30):
        depth = dict(info(depthraw, f"d{i}.png", "image/png"), role="DEPTH")
        confidence = dict(info(confraw, f"c{i}.png", "image/png"), role="CONFIDENCE")
        files += [depth, confidence]
        raws += [depthraw, confraw]
        frame = copy.deepcopy(framebase)
        frame.update(
            imageClientFileId=files[i]["clientFileId"],
            timestampUs=i * 100000,
            widthPx=640,
            heightPx=480,
            depthClientFileId=depth["clientFileId"],
            confidenceClientFileId=confidence["clientFileId"],
            depthWidthPx=2,
            depthHeightPx=2,
        )
        if corrupt == "reference" and i == 0:
            frame["depthClientFileId"] = uid()
        if corrupt == "unreferenced" and i == 0:
            for k in ("depthClientFileId", "confidenceClientFileId", "depthWidthPx", "depthHeightPx"):
                frame.pop(k)
        manifest["frames"].append(frame)
    rp = json.dumps(dict(walls=[], doors=[], windows=[], objects=[])).encode()
    st = json.dumps(example("RoomPlanStructure")).encode()
    for role, raw in [("ROOMPLAN", rp), ("ROOMPLAN_STRUCTURE", st)]:
        files.append(dict(info(raw, role + ".json", "application/json"), role=role))
        raws.append(raw)
    cam = json.dumps(manifest).encode()
    files.append(dict(info(cam, "camera.json", "application/json"), role="CAMERA_MANIFEST"))
    raws.append(cam)
    if corrupt == "partial":
        files.pop(30)
        raws.pop(30)
    r = client.post(
        "/api/v1/upload-sessions",
        json=dict(purpose="ROOM_CAPTURE", targetType="LISTING", targetId=v["listingId"], files=files),
        headers={**actors["listing_agent"]["headers"], "Idempotency-Key": uid()},
    )
    if corrupt == "partial":
        assert r.status_code == 422
        return
    assert r.status_code == 201, r.text
    s = r.json()
    for g, raw in zip(s["uploads"], raws):
        put(client, g, raw)
    done = client.post(
        "/api/v1/upload-sessions/" + s["session"]["uploadSessionId"] + "/complete",
        headers=actors["listing_agent"]["headers"],
    )
    if corrupt:
        assert done.status_code == 422, done.text
        assert (
            done.json()["code"]
            == {
                "confidence": "INVALID_CONFIDENCE_VALUES",
                "reference": "INVALID_DEPTH_REFERENCE",
                "unreferenced": "UNREFERENCED_DEPTH_FILE",
            }[corrupt]
        )
        return
    assert done.status_code == 200, done.text
    accepted = create(
        client,
        "/listings/" + v["listingId"] + "/captures",
        dict(uploadSessionId=s["session"]["uploadSessionId"]),
        actors["listing_agent"],
        202,
    ).json()
    _, assignment = claim(client, actors, accepted["jobId"])
    inp = assignment["input"]
    assert (
        len(inp["depths"]) == len(inp["confidences"]) == 30 and inp["roomplan"] and inp["roomplanStructure"]
    )
    assert client.get(inp["depths"][0]["url"]).content == depthraw


def test_windows_center_sill_and_no_collision(client, actors):
    v = listing(client, actors)
    b = example("SpaceBundle")
    w = example("Window")
    room = b["geometry"]["rooms"][0]
    w["roomId"] = room["roomId"]
    w["transform"]["position"]["y"] = room["floorY"] + w["sillHeight"] + w["height"] / 2
    b["geometry"]["windows"] = [w]
    j = capture(client, actors, "LISTING", v["listingId"])
    complete_room(client, actors, j["jobId"], b)
    r = client.get(
        "/api/v1/listings/" + v["listingId"] + "/space", headers=actors["listing_agent"]["headers"]
    )
    assert r.json()["bundle"]["geometry"]["windows"] == [w]
    assert "collisionNode" not in w


def test_legacy_migration_preserves_won_and_general_ownership(tmp_path):
    cfg = Settings(data_dir=tmp_path, scheduler=False)
    cfg.prepare()
    db = Database(cfg)
    user = uid()
    id = uid()
    b = example("ListingCreate")
    b["propertyType"] = "VILLA"
    b.pop("roomType")
    b["price"]["monthlyRent"] = 650001
    b.update(
        listingId=id,
        brokerId=user,
        status="PUBLISHED",
        version=1,
        photos=[],
        currentSpaceVersion=1,
        activeJobId=None,
        createdAt=stamp(),
        updatedAt=stamp(),
    )
    with db.transaction() as s:
        u = example("User")
        u.update(userId=user, roles=["BROKER"])
        u.pop("authProviders")
        u.pop("phone")
        u.pop("isPhonePublic")
        s.put("user", user, u, user)
        s.put("listing", id, b, user)
        s.conn.execute("DELETE FROM schema_migrations WHERE version=3")
    Database(cfg)
    with db.transaction() as s:
        v = s.get("listing", id)["value"]
        assert (
            v["price"]["monthlyRent"] == 650001
            and v["ownerId"] == user
            and v["_migrationNeedsReview"]
            and v["status"] == "DRAFT"
        )
        assert s.get("user", user)["value"]["roles"] == ["LISTING_AGENT"]
        version = v["version"]
    Database(cfg)
    with db.transaction() as s:
        assert s.get("listing", id)["value"]["version"] == version


def test_oauth_kakao_google_validation_and_no_email_link(client, actors, app, monkeypatch):
    import app.oauth as oauth

    app.state.settings.kakao_app_id = "123"
    app.state.settings.google_client_ids = ["demo-client"]

    def kakao(url, headers=None):
        assert headers["Authorization"] == "Bearer valid-token"
        if url.endswith("access_token_info"):
            return dict(app_id=123, id=99, expires_in=100)
        return dict(
            id=99, kakao_account=dict(email="tenant@example.com", profile=dict(nickname="카카오 사용자"))
        )

    monkeypatch.setattr(oauth, "provider_json", kakao)
    a = client.post("/api/v1/auth/oauth/kakao", json=dict(accessToken="valid-token"))
    assert a.status_code == 200, a.text
    assert a.json()["user"]["userId"] != actors["tenant"]["user"]["userId"] and a.json()["user"][
        "authProviders"
    ] == ["KAKAO"]
    assert (
        client.post("/api/v1/auth/oauth/kakao", json=dict(accessToken="valid-token")).json()["user"]["userId"]
        == a.json()["user"]["userId"]
    )
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key()))
    jwk.update(kid="test-key", alg="RS256", use="sig")
    monkeypatch.setattr(oauth, "provider_json", lambda url, headers=None: dict(keys=[jwk]))
    oauth._keys = None
    oauth._expires = 0

    def token(**kw):
        return jwt.encode(
            dict(
                sub="google-99",
                iss="https://accounts.google.com",
                aud="demo-client",
                iat=int(time.time()),
                exp=int(time.time()) + 300,
                email="tenant@example.com",
                **kw,
            ),
            key,
            algorithm="RS256",
            headers=dict(kid="test-key"),
        )

    r = client.post("/api/v1/auth/oauth/google", json=dict(idToken=token()))
    assert r.status_code == 200, r.text
    assert r.json()["user"]["userId"] not in (a.json()["user"]["userId"], actors["tenant"]["user"]["userId"])
    claims = dict(
        sub="google-99",
        iss="https://accounts.google.com",
        aud="evil-client",
        iat=int(time.time()),
        exp=int(time.time()) + 300,
    )
    wrong = jwt.encode(claims, key, algorithm="RS256", headers=dict(kid="test-key"))
    assert (
        client.post("/api/v1/auth/oauth/google", json=dict(idToken=wrong)).json()["code"]
        == "INVALID_OAUTH_TOKEN"
    )
    assert client.post("/api/v1/auth/oauth/google", json=dict(idToken="malformed")).status_code == 401
    monkeypatch.setattr(
        oauth,
        "provider_json",
        lambda *a, **kw: (_ for _ in ()).throw(Problem(503, "OAUTH_PROVIDER_UNAVAILABLE")),
    )
    assert client.post("/api/v1/auth/oauth/kakao", json=dict(accessToken="anything")).status_code == 503


def test_room_capture_maximum_3003_files(client, actors):
    v = listing(client, actors)
    files = []
    for role, count, mime in [
        ("IMAGE", 1000, "image/jpeg"),
        ("DEPTH", 1000, "image/png"),
        ("CONFIDENCE", 1000, "image/png"),
        ("CAMERA_MANIFEST", 1, "application/json"),
        ("ROOMPLAN", 1, "application/json"),
        ("ROOMPLAN_STRUCTURE", 1, "application/json"),
    ]:
        for i in range(count):
            files.append(
                dict(
                    clientFileId=uid(),
                    fileName=f"{role}_{i}.jpg"
                    if mime == "image/jpeg"
                    else f"{role}_{i}.png"
                    if mime == "image/png"
                    else f"{role}_{i}.json",
                    contentType=mime,
                    byteSize=1,
                    sha256="0" * 64,
                    role=role,
                )
            )
    r = create(
        client,
        "/upload-sessions",
        dict(purpose="ROOM_CAPTURE", targetType="LISTING", targetId=v["listingId"], files=files),
        actors["listing_agent"],
    )
    assert len(r.json()["uploads"]) == len(r.json()["session"]["files"]) == 3003
    # Never-uploaded files cannot pass complete or create a reconstruction job.
    assert (
        client.post(
            "/api/v1/upload-sessions/" + r.json()["session"]["uploadSessionId"] + "/complete",
            headers=actors["listing_agent"]["headers"],
        ).status_code
        == 409
    )
