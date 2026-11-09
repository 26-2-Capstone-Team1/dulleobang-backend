import copy
import json
import math

import pytest

from app.admin import review_agent
from app.core import uid
from app.mobile import geometry, project, scene_object
from conftest import capture, complete_room, example, image, listing, ready_furniture
from test_capture_zip import archive, capture_files


def mobile_listing():
    return dict(
        location="서울 동작구 상도로 10",
        dealType="MONTHLY",
        price=dict(deposit=500, monthly=31),
        maintenanceFee=5,
        roomType="ONE_ROOM",
        area=23.8,
        availableFrom="IMMEDIATE",
        rentalOptions=["aircon", "washer"],
        latitude=37.5,
        longitude=126.9,
        floor=3,
        totalFloors=5,
        supplyArea=33.05,
        rooms=1,
        bathrooms=1,
    )


def post(client, path, actor, body=None, key=None, **kwargs):
    headers = dict(actor["headers"], **{"Idempotency-Key": key or uid()})
    if body is not None:
        kwargs["json"] = body
    return client.post("/api" + path, headers=headers, **kwargs)


def create_mobile(client, actor):
    r = post(client, "/listings", actor, mobile_listing())
    assert r.status_code == 201, r.text
    return r.json()["listingId"]


def photos(client, actor, lid, count=1, key=None, raw=None):
    return post(
        client,
        f"/listings/{lid}/images",
        actor,
        key=key,
        files=[("images", (f"photo_{i}.jpg", raw or image(), "image/jpeg")) for i in range(count)],
    )


def test_mobile_auth_refresh_logout_and_no_demo_identity(client):
    r = client.post(
        "/api/auth/signup",
        json=dict(email="ios@example.com", password="StrongPassword!123", displayName="앱 사용자"),
    )
    assert r.status_code == 201, r.text
    assert r.json()["roles"] == ["TENANT"]
    r = client.post("/api/auth/login", json=dict(email="ios@example.com", password="StrongPassword!123"))
    assert r.status_code == 200, r.text
    pair = r.json()
    r = client.post(
        "/api/auth/refresh", json=dict(refreshToken=pair["refreshToken"]), headers={"Idempotency-Key": uid()}
    )
    assert r.status_code == 200, r.text
    assert r.json()["refreshToken"] != pair["refreshToken"]
    latest = r.json()
    assert client.post("/api/auth/logout", json=dict(refreshToken=latest["refreshToken"])).status_code == 204
    assert (
        client.get("/api/users/me", headers={"Authorization": "Bearer " + latest["accessToken"]}).status_code
        == 401
    )
    assert client.get("/api/listings", headers={"X-User-Id": pair["user"]["userId"]}).status_code == 401
    assert client.get("/api/auth/health").status_code == 404


def test_mobile_listing_money_images_search_scrap_and_canonical_preserved(client, actors, app):
    owner, viewer = actors["tenant"], actors["stranger"]
    key = uid()
    r = post(client, "/listings", owner, mobile_listing(), key=key)
    assert r.status_code == 201, r.text
    lid = r.json()["listingId"]
    assert post(client, "/listings", owner, mobile_listing(), key=key).json() == r.json()
    assert r.json()["price"] == dict(deposit=500, monthly=31)
    canonical = client.get("/api/v1/listings/" + lid, headers=owner["headers"]).json()
    assert canonical["price"]["deposit"] == 5_000_000
    assert canonical["price"]["monthlyRent"] == 310_000
    assert canonical["rentalOptions"] == ["AIR_CONDITIONER", "WASHING_MACHINE"]
    assert client.get("/api/listings/" + lid, headers=viewer["headers"]).status_code == 404
    key = uid()
    r = photos(client, owner, lid, count=30, key=key)
    assert r.status_code == 200, r.text
    assert len(r.json()["imageUris"]) == 30
    assert r.json()["status"] == "PUBLISHED"
    assert photos(client, owner, lid, count=30, key=key).status_code == 200
    assert photos(client, owner, lid, raw=b"bad image", key=key).status_code == 409
    assert photos(client, viewer, lid).status_code == 404
    assert list((app.state.settings.data_dir / "staging").iterdir()) == []
    r = client.get(
        "/api/listings",
        params=dict(
            location="동작구 상도", dealType="MONTHLY", roomType="ONE_ROOM", maxDeposit=500, maxPrice=31
        ),
        headers=viewer["headers"],
    )
    assert r.status_code == 200, r.text
    assert len(r.json()["items"]) == 1
    card = r.json()["items"][0]
    assert card["location"] == mobile_listing()["location"]
    assert card["area"] == 23.8 and card["rentalOptions"] == ["aircon", "washer"]
    assert client.get(card["thumbnailUri"]).status_code == 200
    assert client.get("/api/listings?maxPrice=30", headers=viewer["headers"]).json()["items"] == []
    assert client.get("/api/listings?maxDeposit=499", headers=viewer["headers"]).json()["items"] == []
    assert post(client, f"/listings/{lid}/scrap", viewer).status_code == 204
    assert (
        client.get("/api/users/me/scraps", headers=viewer["headers"]).json()["items"][0]["listingId"] == lid
    )
    assert client.delete(f"/api/listings/{lid}/scrap", headers=viewer["headers"]).status_code == 204


@pytest.mark.parametrize(
    "change",
    [
        dict(latitude=None),
        dict(price=dict(deposit=-1, monthly=31)),
        dict(rentalOptions=["unknown"]),
        dict(area=0),
        dict(dealType="SALE"),
        dict(price=dict(deposit=500, monthly=0)),
    ],
)
def test_mobile_listing_validation(client, actors, change):
    body = dict(mobile_listing(), **change)
    if body.get("latitude") is None:
        body.pop("latitude")
    r = post(client, "/listings", actors["tenant"], body)
    assert r.status_code == 422, r.text


def test_mobile_agent_multipart_review_and_evidence_privacy(client, actors, app):
    actor = actors["tenant"]
    assert client.get("/api/users/me/agent-profile", headers=actor["headers"]).status_code == 404
    key = uid()
    kwargs = dict(
        data=dict(
            name="중개사", licenseNumber="TEST-001", officeName="테스트 부동산", documentName="license.jpg"
        ),
        files=dict(document=("license.jpg", image(), "image/jpeg")),
    )
    r = post(client, "/users/me/agent-profile", actor, key=key, **kwargs)
    assert r.status_code == 201, r.text
    assert r.json()["status"] == "PENDING" and r.json()["documentName"] == "license.jpg"
    assert post(client, "/users/me/agent-profile", actor, key=key, **kwargs).status_code == 201
    with app.state.database.transaction() as store:
        profile = store.get("agentprofile", actor["user"]["userId"])["value"]
        evidence_id = profile["documentFileId"]
        review_agent(store, profile["applicationId"], "approve")
    assert client.get("/api/users/me/agent-profile", headers=actor["headers"]).json()["status"] == "APPROVED"
    assert (
        client.get(
            "/api/v1/assets/" + evidence_id + "/download", headers=actors["stranger"]["headers"]
        ).status_code
        == 404
    )


def test_mobile_chat_report_and_long_message_history(client, actors):
    owner, viewer = actors["tenant"], actors["stranger"]
    lid = create_mobile(client, owner)
    assert photos(client, owner, lid).status_code == 200
    r = post(client, "/chat", viewer, dict(listingId=lid))
    assert r.status_code == 201, r.text
    chat_id = r.json()["chatId"]
    assert r.json()["listing"]["price"]["monthly"] == 31
    assert post(client, "/chat", viewer, dict(listingId=lid)).json()["chatId"] == chat_id
    for i in range(23):
        r = post(client, "/chat/" + chat_id, viewer, dict(message=f"문의 {i}"))
        assert r.status_code == 201, r.text
    r = client.get("/api/chat/" + chat_id, headers=owner["headers"])
    assert r.status_code == 200, r.text
    assert [m["message"] for m in r.json()["messages"]] == [f"문의 {i}" for i in range(23)]
    assert client.get("/api/chat/" + chat_id, headers=actors["listing_agent"]["headers"]).status_code == 404
    r = post(client, f"/listings/{lid}/reports", viewer, dict(reason="가격 정보가 달라요"))
    assert r.status_code == 201, r.text
    assert (
        client.get("/api/chat", headers=owner["headers"]).json()["items"][0]["counterpartName"]
        == viewer["user"]["displayName"]
    )


@pytest.mark.parametrize("kind", ["room", "furniture"])
def test_mobile_zip_room_and_furniture_without_lidar(client, actors, app, kind):
    actor = actors["tenant"]
    if kind == "room":
        target = "listings/" + create_mobile(client, actor)
    else:
        r = post(client, "/furniture", actor, dict(name="책상", category="desk"))
        assert r.status_code == 201, r.text
        target = "furniture/" + r.json()["furnitureId"]
    files = capture_files()
    files = {k: v for k, v in files.items() if k.startswith("frames/") or k in ("meta.json", "poses.json")}
    meta = json.loads(files["meta.json"])
    meta.update(kind=kind)
    meta.pop("floorHeightM")
    files["meta.json"] = json.dumps(meta).encode()
    raw = archive(files)
    key = uid()

    def send(actor, raw=raw):
        return client.post(
            "/api/" + target + "/captures",
            content=raw,
            headers={**actor["headers"], "Idempotency-Key": key, "Content-Type": "application/zip"},
        )

    r = send(actor)
    assert r.status_code == 202, r.text
    assert send(actor).json() == r.json()
    assert send(actors["stranger"]).status_code == 404
    job = client.get("/api/ai-jobs/" + r.json()["jobId"], headers=actor["headers"])
    assert job.status_code == 200 and job.json()["progress"] == 0
    assert list((app.state.settings.data_dir / "staging").iterdir()) == []


def test_mobile_space_objects_room_state_furniture_placements_and_etag(client, actors):
    lid = listing(client, actors)["listingId"]
    accepted = capture(client, actors, "LISTING", lid)
    complete_room(client, actors, accepted["jobId"])
    assert photos(client, actors["listing_agent"], lid).status_code == 200
    r = client.get(f"/api/listings/{lid}/space", headers=actors["tenant"]["headers"])
    assert r.status_code == 200, r.text
    space = r.json()
    assert client.get(space["3dgsUri"]).status_code == 200
    assert len(space["roomGeometry"]["walls"]) == 4
    assert space["objects"][0]["position"] == dict(x=1, y=0.375, z=-1)
    response = client.get(f"/api/listings/{lid}/objects", headers=actors["tenant"]["headers"])
    assert response.status_code == 200, response.text
    assert response.json()["objects"] == space["objects"]
    objects = dict(objects=[dict(objectId=space["objects"][0]["objectId"], ownershipType="PERSONAL")])
    r = client.patch(
        f"/api/listings/{lid}/objects",
        json=objects,
        headers={**actors["listing_agent"]["headers"], "If-Match": f'"{space["objectsVersion"]}"'},
    )
    assert r.status_code == 200, r.text
    assert r.json()["objectsVersion"] == space["objectsVersion"] + 1
    assert r.json()["objects"][0]["position"] == dict(x=1, y=0.375, z=-1)
    assert (
        client.patch(
            f"/api/listings/{lid}/objects",
            json=objects,
            headers={**actors["listing_agent"]["headers"], "If-Match": '"1"'},
        ).status_code
        == 412
    )
    r = post(
        client,
        f"/listings/{lid}/room-states",
        actors["tenant"],
        dict(removedObjectIds=[space["objects"][0]["objectId"]]),
    )
    assert r.status_code == 202, r.text
    assert (
        client.get(
            "/api/room-states/" + r.json()["roomStateId"], headers=actors["stranger"]["headers"]
        ).status_code
        == 404
    )
    f = ready_furniture(client, actors)
    r = client.get("/api/furniture/" + f["furnitureId"], headers=actors["tenant"]["headers"])
    assert r.status_code == 200 and r.json()["status"] == "COMPLETED", r.text
    assert client.get(r.json()["modelUri"]).status_code == 200
    body = dict(
        placements=[dict(furnitureId=f["furnitureId"], position=dict(x=1, y=0, z=-1), rotation=dict(yaw=90))]
    )
    r = client.put(
        f"/api/listings/{lid}/placements",
        json=body,
        headers={**actors["tenant"]["headers"], "If-None-Match": "*"},
    )
    assert r.status_code == 200, r.text
    assert r.json()["placements"][0]["rotation"]["yaw"] == pytest.approx(90)
    assert (
        client.put(
            f"/api/listings/{lid}/placements",
            json=body,
            headers={**actors["tenant"]["headers"], "If-None-Match": "*"},
        ).status_code
        == 412
    )
    r = client.get(f"/api/listings/{lid}/placements", headers=actors["tenant"]["headers"])
    assert r.status_code == 200 and "ETag" in r.headers


def test_mobile_invalid_upload_is_atomic(client, actors, app):
    lid = create_mobile(client, actors["tenant"])
    r = photos(client, actors["tenant"], lid, raw=b"not jpeg")
    assert r.status_code == 422, r.text
    assert client.get("/api/listings/" + lid, headers=actors["tenant"]["headers"]).json()["status"] == "DRAFT"
    with app.state.database.transaction() as store:
        assert store.all("uploadsession") == []
    assert list((app.state.settings.data_dir / "staging").iterdir()) == []


@pytest.mark.parametrize(
    "category,canonical,expected",
    [
        ("bed", "BED", "bed"),
        ("desk", "DESK", "desk"),
        ("sofa", "SOFA", "sofa"),
        ("closet", "WARDROBE", "closet"),
        ("storage", "SHELF", "storage"),
        ("chair", "CHAIR", "chair"),
        ("other", "OTHER", "other"),
        ("WARDROBE", "WARDROBE", "closet"),
        ("SHELF", "SHELF", "storage"),
    ],
)
def test_mobile_furniture_app_categories_round_trip(client, actors, category, canonical, expected):
    actor = actors["tenant"]
    key = uid()
    body = dict(name="가구", category=category)
    response = post(client, "/furniture", actor, body, key=key)
    assert response.status_code == 201, response.text
    furniture = response.json()
    fid = furniture["furnitureId"]
    assert furniture["category"] == expected
    assert post(client, "/furniture", actor, body, key=key).json() == furniture
    assert client.get("/api/furniture/" + fid, headers=actor["headers"]).json()["category"] == expected
    mine = client.get("/api/users/me/furniture", headers=actor["headers"]).json()["items"]
    assert next(f for f in mine if f["furnitureId"] == fid)["category"] == expected
    stored = client.get("/api/v1/furniture/" + fid, headers=actor["headers"])
    assert stored.status_code == 200, stored.text
    assert stored.json()["category"] == canonical
    # Only the app API accepts aliases; the worker/API enum stays canonical.
    assert (
        client.post(
            "/api/v1/furniture",
            json=dict(name="잘못된 가구", category=expected),
            headers={**actor["headers"], "Idempotency-Key": uid()},
        ).status_code
        == 422
    )


def test_mobile_furniture_rejects_invalid_category_and_fields(client, actors):
    for change in [dict(category="unknown"), dict(category=None), dict(name=""), dict(extra=True)]:
        response = post(
            client, "/furniture", actors["tenant"], dict(dict(name="가구", category="bed"), **change)
        )
        assert response.status_code == 422, response.text


@pytest.mark.parametrize("floor", [-1.42, 1.0])
def test_mobile_nonzero_floor_and_object_center(client, actors, floor):
    lid = listing(client, actors)["listingId"]
    accepted = capture(client, actors, "LISTING", lid)
    bundle = example("SpaceBundle")
    for room in bundle["geometry"]["rooms"]:
        room["floorY"] += floor
        room["ceilingY"] += floor
    for obj in bundle["objects"]:
        obj["transform"]["position"]["y"] += floor
    for key in ("doors", "windows"):
        for opening in bundle["geometry"].get(key, []):
            opening["transform"]["position"]["y"] += floor
            if opening.get("hingePosition"):
                opening["hingePosition"]["y"] += floor
    complete_room(client, actors, accepted["jobId"], bundle)
    response = client.get(f"/api/listings/{lid}/space", headers=actors["listing_agent"]["headers"])
    assert response.status_code == 200, response.text
    space = response.json()
    room = space["roomGeometry"]
    assert room["floorHeight"] == pytest.approx(floor)
    assert room["ceilingHeight"] == pytest.approx(2.4)
    # This is how Room.swift reconstructs the absolute ceiling Y.
    assert room["floorHeight"] + room["ceilingHeight"] == pytest.approx(floor + 2.4)
    assert room["boundingBox"]["max"]["y"] == pytest.approx(floor + 2.4)
    assert room["walls"][0]["height"] == pytest.approx(2.4)
    assert space["objects"][0]["position"]["y"] == pytest.approx(floor + 0.375)
    canonical = client.get(f"/api/v1/listings/{lid}/space", headers=actors["listing_agent"]["headers"])
    assert canonical.status_code == 200, canonical.text
    assert canonical.json()["bundle"]["objects"][0]["transform"]["position"]["y"] == pytest.approx(floor)


def test_mobile_rotated_object_center_does_not_mutate_source():
    obj = example("SpaceObject")
    obj["transform"]["position"] = dict(x=1, y=-1.42, z=-1)
    obj["transform"]["rotation"] = dict(x=math.sqrt(0.5), y=0, z=0, w=math.sqrt(0.5))
    before = copy.deepcopy(obj)
    result = scene_object(obj)
    # A 90-degree X rotation moves the local half-height offset along world +Z.
    assert result["position"] == pytest.approx(dict(x=1, y=-1.42, z=-0.625))
    assert obj == before


def test_mobile_room_state_geometry_and_centers():
    bundle = example("SpaceBundle")
    bundle["geometry"]["rooms"][0].update(floorY=-1.42, ceilingY=0.98)
    bundle["objects"][0]["transform"]["position"]["y"] = -1.42
    bundle["sceneAsset"]["url"] = "https://example.com/scene.ply"
    bundle["collisionAsset"]["url"] = "https://example.com/collision.glb"
    before = copy.deepcopy(bundle)
    for oid in ("getRoomState", "getRoomStates"):
        state = project(None, oid, dict(bundle=bundle))
        assert state["roomGeometry"]["ceilingHeight"] == pytest.approx(2.4)
        assert state["remainingObjects"][0]["position"]["y"] == pytest.approx(-1.045)
    assert bundle == before


def test_mobile_multiroom_ceiling_is_relative_to_lowest_floor():
    rooms = [
        dict(roomId="a", floorY=-1, ceilingY=1.4, floorPolygon=[[0, 0], [0, -2], [2, -2], [2, 0]]),
        dict(roomId="b", floorY=0, ceilingY=2.8, floorPolygon=[[2, 0], [2, -2], [4, -2], [4, 0]]),
    ]
    room = geometry(dict(rooms=rooms))
    assert room["floorHeight"] == -1
    assert room["ceilingHeight"] == pytest.approx(3.8)
    assert room["floorHeight"] + room["ceilingHeight"] == pytest.approx(2.8)
    assert [w["height"] for w in room["walls"]] == pytest.approx([2.4] * 4 + [2.8] * 4)
