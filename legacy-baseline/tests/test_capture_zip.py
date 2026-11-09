import copy
import hashlib
import json
import stat
import zipfile
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO

import pytest
from PIL import Image

from app.core import uid
from conftest import create, example, image


def capture_files():
    rgb = image()
    depth, confidence = BytesIO(), BytesIO()
    Image.new("I;16", (2, 2), 1200).save(depth, "PNG")
    Image.new("L", (2, 2), 2).save(confidence, "PNG")
    meta = dict(
        arSdk="ARKit",
        captureId=uid(),
        colorSize=[640, 480],
        depthSize=[2, 2],
        coordinate="arkit_world; meters; y-up; right-handed; camera x-right y-up -z-forward (OpenGL)",
        createdAt="2026-10-01T10:42:15Z",
        floorHeightM=0.5,
        frameCount=30,
        kind="room",
        depthUnit="uint16 PNG, millimeters, 0 = invalid",
        imageOrientation="sensor_native_landscape (not rotated; K matches the JPG)",
    )
    poses = [
        dict(
            frameId=i,
            width=640,
            height=480,
            ts=100 + i * 0.1,
            tracking="normal",
            K=dict(fx=500, fy=500, cx=320, cy=240),
            T_wc=[[0, 0, 1, 3 + i * 0.01], [0, 1, 0, 2], [-1, 0, 0, 4], [0, 0, 0, 1]],
        )
        for i in range(30)
    ]
    dep = dict(
        captureId=meta["captureId"],
        frameCount=30,
        depthSize=[2, 2],
        colorSize=[640, 480],
        frames=[dict(frameId=i, depth=f"depth/{i:06d}.png") for i in range(30)],
    )
    structure = dict(
        objects=[
            dict(
                objectId=uid(),
                category="chair",
                position=dict(x=4, y=1.5, z=4),
                rotation=dict(yaw=0),
                dimensions=dict(width=1, height=1, depth=1),
            )
        ],
        roomDimensions=dict(width=2, height=2, depth=2, area=4),
        roomGeometry=dict(
            floorHeight=0.5,
            walls=[dict(id=uid(), height=2, **{"from": [3, 4], "to": [5, 4]})],
            doors=[],
            windows=[],
        ),
    )
    files = {
        "meta.json": json.dumps(meta).encode(),
        "poses.json": json.dumps(poses).encode(),
        "depth.json": json.dumps(dep).encode(),
        "roomplan_structure.json": json.dumps(structure).encode(),
        "roomplan.json": json.dumps(dict(walls=[], doors=[], windows=[], objects=[], floors=[])).encode(),
        "roomplan.usdz": b"original USDZ preserved",
        "depth_vis/000000.jpg": b"preview is not AI depth",
    }
    for i in range(30):
        files[f"frames/{i:06d}.jpg"] = rgb
        files[f"depth/{i:06d}.png"] = depth.getvalue()
        files[f"confidence/{i:06d}.png"] = confidence.getvalue()
    return files


def archive(files, root="capture/"):
    out = BytesIO()
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for name, raw in files.items():
            z.writestr(root + name, raw)
    return out.getvalue()


def submit(client, listing_id, actor, raw, key=None, **headers):
    return client.post(
        f"/api/v1/listings/{listing_id}/captures/zip",
        content=raw,
        headers={
            **actor["headers"],
            "Idempotency-Key": key or uid(),
            "Content-Type": "application/zip",
            **headers,
        },
    )


def my_listing(client, actors):
    return create(client, "/listings", example("ListingCreate"), actors["tenant"]).json()["listingId"]


def test_zip_actual_formats_normalization_archive_worker_and_replay(client, actors, app):
    listing_id = my_listing(client, actors)
    raw = archive(capture_files())
    key = uid()
    response = submit(client, listing_id, actors["tenant"], raw, key)
    assert response.status_code == 202, response.text
    assert response.headers["location"].endswith(response.json()["jobId"])
    replay = submit(client, listing_id, actors["tenant"], raw, key)
    assert replay.json() == response.json()
    assert submit(client, listing_id, actors["tenant"], archive(capture_files()), key).status_code == 409
    capture = client.get(
        "/api/v1/captures/" + response.json()["captureId"], headers=actors["tenant"]["headers"]
    )
    assert capture.status_code == 200, capture.text
    assert capture.json()["archiveAsset"]["format"] == "CAPTURE_ZIP_V1"
    assert (
        client.get(
            "/api/v1/assets/" + capture.json()["archiveAsset"]["fileId"] + "/download",
            headers=actors["stranger"]["headers"],
        ).status_code
        == 404
    )
    claim = client.post(
        "/api/v1/internal/ai-jobs/" + response.json()["jobId"] + "/claim",
        json={"workerId": "test-worker"},
        headers=actors["worker"],
    )
    assert claim.status_code == 200, claim.text
    inp = claim.json()["input"]
    assert [len(inp[k]) for k in ("images", "depths", "confidences")] == [30, 30, 30]
    assert client.get(inp["archive"]["url"]).content == raw
    assert hashlib.sha256(raw).hexdigest() == inp["archive"]["asset"]["sha256"]
    manifest = client.get(inp["manifest"]["url"]).json()
    first = manifest["frames"][0]
    assert manifest["captureToWorld"] == pytest.approx([0, 0, 1, 0, 0, 1, 0, 0, -1, 0, 0, 0, 4, -0.5, -3, 1])
    assert first["timestampUs"] == 0
    assert first["cameraToWorld"][12:15] == pytest.approx([0, 1.5, 0])
    assert first["cameraToWorld"][8:11] == pytest.approx([0, 0, 1])
    assert first["intrinsics"] == dict(fx=500, fy=500, cx=320, cy=240)
    structure = client.get(inp["roomplanStructure"]["url"]).json()
    wall = structure["surfaces"][0]
    assert wall["transform"]["position"] == pytest.approx(dict(x=0, y=1, z=1))
    assert wall["dimensions"]["depth"] == 0
    assert structure["objects"][0]["transform"]["position"] == pytest.approx(dict(x=0, y=1, z=1))
    assert client.get(inp["roomplan"]["url"]).json()["walls"] == []
    assert not list((app.state.settings.data_dir / "staging").iterdir())
    with app.state.database.transaction() as store:
        assert len(store.all("job")) == len(store.all("capture")) == len(store.all("upload")) == 1
        assert len(store.conn.execute("SELECT * FROM outbox").fetchall()) == 1


@pytest.mark.parametrize(
    "case",
    [
        "missing_poses",
        "missing_rgb",
        "missing_depth",
        "bad_tracking",
        "duplicate_frame",
        "reverse_time",
        "bad_pose",
        "confidence_rgb",
        "capture_id",
        "bad_structure",
        "bad_json",
        "unsafe_path",
    ],
)
def test_zip_invalid_archive_no_partial_state(client, actors, app, case):
    listing_id = my_listing(client, actors)
    files = capture_files()
    if case == "missing_poses":
        del files["poses.json"]
    elif case == "missing_rgb":
        del files["frames/000005.jpg"]
    elif case == "missing_depth":
        del files["depth/000005.png"]
    elif case in ("bad_tracking", "duplicate_frame", "reverse_time", "bad_pose"):
        poses = json.loads(files["poses.json"])
        if case == "bad_tracking":
            poses[0]["tracking"] = "limited"
        elif case == "duplicate_frame":
            poses[1]["frameId"] = poses[0]["frameId"]
        elif case == "reverse_time":
            poses[1]["ts"] = poses[0]["ts"]
        else:
            poses[0]["T_wc"][0][0] = 10
        files["poses.json"] = json.dumps(poses).encode()
    elif case == "confidence_rgb":
        out = BytesIO()
        Image.new("RGB", (2, 2)).save(out, "PNG")
        files["confidence/000001.png"] = out.getvalue()
    elif case == "capture_id":
        dep = json.loads(files["depth.json"])
        dep["captureId"] = uid()
        files["depth.json"] = json.dumps(dep).encode()
    elif case == "bad_structure":
        files["roomplan_structure.json"] = b"{}"
    elif case == "bad_json":
        files["meta.json"] = b'{"frameCount":NaN}'
    elif case == "unsafe_path":
        files["../escape.txt"] = b"unsafe"
    response = submit(client, listing_id, actors["tenant"], archive(files))
    assert response.status_code == 422, response.text
    with app.state.database.transaction() as store:
        for kind in ("capture", "job", "upload", "file"):
            assert store.all(kind) == []
        assert store.conn.execute("SELECT count(*) FROM outbox").fetchone()[0] == 0
    assert not list((app.state.settings.data_dir / "staging").iterdir())


def test_zip_headers_ownership_and_limits(client, actors, app):
    listing_id = my_listing(client, actors)
    raw = archive(capture_files())
    assert submit(client, listing_id, actors["stranger"], raw).status_code == 404
    assert (
        client.post(
            f"/api/v1/listings/{listing_id}/captures/zip",
            content=raw,
            headers={"Content-Type": "application/zip", "Idempotency-Key": uid()},
        ).status_code
        == 401
    )
    assert (
        submit(
            client, listing_id, actors["tenant"], raw, **{"Content-Type": "multipart/form-data"}
        ).status_code
        == 415
    )
    assert submit(client, listing_id, actors["tenant"], raw, **{"Idempotency-Key": "bad"}).status_code == 400
    assert submit(client, listing_id, actors["tenant"], b"badzip").status_code == 422
    assert (
        submit(
            client, listing_id, actors["tenant"], raw, **{"Content-Length": str((2 << 30) + 1)}
        ).status_code
        == 413
    )
    assert (
        submit(client, listing_id, actors["tenant"], raw, **{"Content-Length": str(len(raw) + 1)}).status_code
        == 422
    )
    assert not list((app.state.settings.data_dir / "staging").iterdir())


def test_zip_concurrent_submission_creates_one_capture(client, actors, app):
    listing_id = my_listing(client, actors)
    raw = archive(capture_files())
    key = uid()
    with ThreadPoolExecutor(max_workers=2) as pool:
        replies = list(pool.map(lambda _: submit(client, listing_id, actors["tenant"], raw, key), range(2)))
    assert [r.status_code for r in replies] == [202, 202]
    assert replies[0].json() == replies[1].json()
    with app.state.database.transaction() as store:
        assert len(store.all("capture")) == len(store.all("job")) == 1


@pytest.mark.parametrize(
    "kind", ["duplicate", "symlink", "absolute", "backslash", "many_entries", "expanded_limit"]
)
def test_zip_archive_container_validation(client, actors, kind):
    from app.capture_zip import MAX_EXPANDED_BYTES, archive_entries
    from app.core import Problem

    out = BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        z.writestr("capture/meta.json", b"{}")
        if kind == "duplicate":
            with pytest.warns(UserWarning):
                z.writestr("capture/meta.json", b"{}")
        elif kind == "symlink":
            info = zipfile.ZipInfo("capture/link")
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(info, b"outside")
        elif kind == "absolute":
            z.writestr("/outside", b"x")
        elif kind == "backslash":
            info = zipfile.ZipInfo("capture/outside")
            info.filename = "capture\\outside"
            z.writestr(info, b"x")
        elif kind == "many_entries":
            for i in range(7000):
                z.writestr(f"capture/{i}", b"")
    with zipfile.ZipFile(BytesIO(out.getvalue())) as z:
        if kind == "expanded_limit":
            z.infolist()[0].file_size = MAX_EXPANDED_BYTES + 1
        with pytest.raises(Problem) as raised:
            archive_entries(z)
        assert raised.value.status == (413 if kind in ("many_entries", "expanded_limit") else 422)


def test_zip_flat_root_and_rgb_only(client, actors):
    listing_id = my_listing(client, actors)
    files = capture_files()
    files = {k: v for k, v in files.items() if not k.startswith(("depth/", "confidence/"))}
    response = submit(client, listing_id, actors["tenant"], archive(files, root=""))
    assert response.status_code == 202, response.text


def test_zip_surface_schema_accepts_zero_thickness_only(app):
    surface = copy.deepcopy(example("RoomPlanSurface"))
    surface["dimensions"]["depth"] = 0
    app.state.contract.validate("RoomPlanSurface", surface)
    assert not app.state.contract.validators["Dimensions"].is_valid(surface["dimensions"])


def test_zip_png_frame_extension_and_explicit_room_alignment(client, actors):
    listing_id = my_listing(client, actors)
    files = capture_files()
    out = BytesIO()
    Image.new("RGB", (640, 480), "white").save(out, "PNG")
    del files["frames/000000.jpg"]
    files["frames/000000.PNG"] = out.getvalue()
    meta = json.loads(files["meta.json"])
    meta["roomPlanToWorld"] = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 2, 1, 4, 1]
    files["meta.json"] = json.dumps(meta).encode()
    response = submit(client, listing_id, actors["tenant"], archive(files))
    assert response.status_code == 202, response.text
    claim = client.post(
        "/api/v1/internal/ai-jobs/" + response.json()["jobId"] + "/claim",
        json={"workerId": "test-worker"},
        headers=actors["worker"],
    )
    assert claim.status_code == 200, claim.text
    inp = claim.json()["input"]
    first = client.get(inp["manifest"]["url"]).json()["frames"][0]
    assert first["cameraToWorld"][12:15] == pytest.approx([0, 0.5, 0])
    wall = client.get(inp["roomplanStructure"]["url"]).json()["surfaces"][0]
    assert wall["transform"]["position"] == pytest.approx(dict(x=-4, y=1, z=3))
