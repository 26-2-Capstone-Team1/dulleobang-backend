"""Real byte validation, mathematical boundaries and provider failure responses."""

import copy
import json
import math
import struct
import time
from io import BytesIO
from types import SimpleNamespace

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from PIL import Image

import app.oauth as oauth
from app.capture_inputs import classify_files, input_format, verify_inputs
from app.core import Contract, Problem, uid
from app.geometry import (
    bundle_geometry,
    image_info,
    quaternion,
    rigid_matrix,
    validate_glb,
    validate_manifest,
    validate_ply,
)
from conftest import example, glb, image, info, ply


def encode_glb(doc, binary=b"", *, kind=0x4E4F534A):
    body = json.dumps(doc, allow_nan=False).encode()
    body += b" " * (-len(body) % 4)
    binary += b"\0" * (-len(binary) % 4)
    chunks = struct.pack("<II", len(body), kind) + body
    if binary:
        chunks += struct.pack("<II", len(binary), 0x004E4942) + binary
    return struct.pack("<III", 0x46546C67, 2, 12 + len(chunks)) + chunks


def decode_glb(raw):
    length = struct.unpack_from("<I", raw, 12)[0]
    return json.loads(raw[20 : 20 + length]), raw[28 + length :]


def base_bundle():
    bundle = example("SpaceBundle")
    for room in bundle["geometry"]["rooms"]:
        p = room["floorPolygon"]
        area = sum(p[i][0] * p[(i + 1) % len(p)][1] - p[(i + 1) % len(p)][0] * p[i][1] for i in range(len(p)))
        if area > 0:
            p.reverse()
    nodes = (
        {o["collisionNode"] for o in bundle["objects"]}
        | {o["nodeName"] for o in bundle["geometry"]["structuralColliders"]}
        | {d["sweepNode"] for d in bundle["geometry"]["doors"] if d["sweepNode"]}
    )
    return bundle, nodes


@pytest.mark.parametrize(
    "value", [[], None, 1, "wrong", {"asset": []}, {"asset": {"version": "2.0"}, "buffers": [None]}]
)
def test_glb_malformed_json_shapes_are_domain_errors(value):
    with pytest.raises(Problem) as err:
        validate_glb(encode_glb(value))
    assert err.value.status == 422


@pytest.mark.parametrize(
    "case",
    [
        "external-buffer",
        "extension",
        "animation",
        "skin",
        "bad-buffer-size",
        "bad-view",
        "sparse",
        "bad-accessor-index",
        "bad-accessor-component",
        "bad-stride",
        "primitive-mode",
        "morph",
        "bad-position",
        "invalid-material",
        "bad-scene",
        "duplicate-name",
        "graph-cycle",
        "shared-child",
        "node-mesh",
        "nonidentity-root",
        "root-matrix",
        "unknown-image",
        "bad-json-chunk",
        "bad-total-length",
        "float-nan",
        "no-mesh",
    ],
)
def test_glb_structure_negative_matrix(case):
    doc, binary = decode_glb(glb())
    if case == "external-buffer":
        doc["buffers"][0]["uri"] = "https://evil.example/a"
    elif case == "extension":
        doc["nodes"][0]["extensions"] = {"KHR_draco_mesh_compression": {}}
    elif case == "animation":
        doc["animations"] = [{}]
    elif case == "skin":
        doc["nodes"][0]["skin"] = 0
    elif case == "bad-buffer-size":
        doc["buffers"][0]["byteLength"] += 10
    elif case == "bad-view":
        doc["bufferViews"][0]["byteOffset"] = -1
    elif case == "sparse":
        doc["accessors"][0]["sparse"] = {}
    elif case == "bad-accessor-index":
        doc["accessors"][0]["bufferView"] = 99
    elif case == "bad-accessor-component":
        doc["accessors"][0]["componentType"] = 999
    elif case == "bad-stride":
        doc["bufferViews"][0]["byteStride"] = 4
    elif case == "primitive-mode":
        doc["meshes"][0]["primitives"][0]["mode"] = 1
    elif case == "morph":
        doc["meshes"][0]["primitives"][0]["targets"] = [{}]
    elif case == "bad-position":
        doc["meshes"][0]["primitives"][0]["attributes"]["POSITION"] = 99
    elif case == "invalid-material":
        doc["meshes"][0]["primitives"][0]["material"] = 99
    elif case == "bad-scene":
        doc["scene"] = 99
    elif case == "duplicate-name":
        doc["nodes"].append(dict(doc["nodes"][0]))
    elif case == "graph-cycle":
        doc["nodes"][0]["children"] = [0]
    elif case == "shared-child":
        doc["nodes"][0]["children"] = [1, 1]
        doc["nodes"].append({"name": "child", "mesh": 0})
    elif case == "node-mesh":
        doc["nodes"][0]["mesh"] = 99
    elif case == "nonidentity-root":
        doc["nodes"][0]["translation"] = [1, 0, 0]
    elif case == "root-matrix":
        doc["nodes"][0]["matrix"] = [1] * 16
    elif case == "unknown-image":
        doc["images"] = [{"uri": "https://evil.example/i.png"}]
    elif case == "bad-total-length":
        with pytest.raises(Problem):
            validate_glb(glb()[:-4])
        return
    elif case == "float-nan":
        binary = struct.pack("<f", float("nan")) + binary[4:]
    elif case == "no-mesh":
        doc["meshes"] = []
    raw = encode_glb(doc, binary, kind=1 if case == "bad-json-chunk" else 0x4E4F534A)
    with pytest.raises(Problem):
        validate_glb(raw)


def test_glb_indexed_textured_and_nested_models():
    doc, binary = decode_glb(glb())
    offsets = len(binary)
    binary += struct.pack("<3H", 0, 1, 2) + b"\0\0"
    doc["bufferViews"].append({"buffer": 0, "byteOffset": offsets, "byteLength": 6})
    doc["accessors"].append({"bufferView": 1, "componentType": 5123, "count": 3, "type": "SCALAR"})
    doc["meshes"][0]["primitives"][0]["indices"] = 1
    out = BytesIO()
    Image.new("RGB", (2, 2), "red").save(out, "PNG")
    texture = out.getvalue()
    doc["bufferViews"].append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(texture)})
    binary += texture
    doc["buffers"][0]["byteLength"] = len(binary)
    doc["images"] = [{"bufferView": 2, "mimeType": "image/png"}]
    doc["materials"] = [{}]
    doc["meshes"][0]["primitives"][0]["material"] = 0
    doc["nodes"] = [{"name": "root", "children": [1]}, {"name": "mesh", "mesh": 0, "translation": [0, 0, 0]}]
    assert validate_glb(encode_glb(doc, binary)) == {"mesh"}
    with pytest.raises(Problem):
        validate_glb(encode_glb(doc, binary), collision=True)
    bad = binary[:offsets] + struct.pack("<3H", 0, 1, 9) + binary[offsets + 6 :]
    with pytest.raises(Problem):
        validate_glb(encode_glb(doc, bad))


@pytest.mark.parametrize(
    "case",
    [
        "no-header",
        "ascii",
        "profile",
        "element",
        "count-zero",
        "count-too-high",
        "truncated",
        "nan",
        "rotation",
    ],
)
def test_ply_negative_matrix(case):
    raw = ply()
    if case == "no-header":
        raw = raw.replace(b"end_header", b"bad_header")
    elif case == "ascii":
        raw = raw.replace(b"binary_little_endian", b"ascii")
    elif case == "profile":
        raw = raw.replace(b"dulleobang_gs_ply_v1", b"unknown_profile")
    elif case == "element":
        raw = raw.replace(b"element vertex 1", b"element face 1")
    elif case == "count-zero":
        raw = raw.replace(b"element vertex 1", b"element vertex 0")
    elif case == "count-too-high":
        raw = raw.replace(b"element vertex 1", b"element vertex 2000001")
    elif case == "truncated":
        raw = raw[:-1]
    elif case == "nan":
        raw = raw[:-56] + struct.pack("<f", float("nan")) + raw[-52:]
    elif case == "rotation":
        raw = raw[:-16] + struct.pack("<4f", 0, 0, 0, 0)
    with pytest.raises(Problem):
        validate_ply(raw)


@pytest.mark.parametrize(
    "mode,size,mime,expected",
    [
        ("RGB", (640, 480), "image/jpeg", True),
        ("RGB", (640, 480), "image/png", True),
        ("RGB", (639, 480), "image/jpeg", False),
        ("RGB", (640, 479), "image/png", False),
        ("RGB", (8193, 480), "image/png", False),
    ],
)
def test_image_dimension_and_content_type_boundaries(mode, size, mime, expected):
    out = BytesIO()
    Image.new(mode, size).save(out, "PNG" if mime == "image/png" else "JPEG")
    if expected:
        assert image_info(out.getvalue(), mime) == size
    else:
        with pytest.raises(Problem):
            image_info(out.getvalue(), mime)
    with pytest.raises(Problem):
        image_info(out.getvalue(), "image/png" if mime == "image/jpeg" else "image/jpeg")


@pytest.mark.parametrize(
    "case",
    [
        "duplicates",
        "dimension",
        "area",
        "height",
        "winding",
        "self-intersection",
        "room-reference",
        "bbox",
        "missing-collider",
        "duplicate-collider",
        "window-room",
        "window-height",
        "window-above-ceiling",
        "window-rotation",
        "hinged-door",
    ],
)
def test_geometry_cross_field_matrix(case):
    bundle, nodes = base_bundle()
    room = bundle["geometry"]["rooms"][0]
    if case == "duplicates":
        bundle["objects"].append(copy.deepcopy(bundle["objects"][0]))
    elif case == "dimension":
        bundle["roomDimensions"]["width"] += 1
    elif case == "area":
        room["areaM2"] += 1
    elif case == "height":
        room["ceilingY"] = room["floorY"]
    elif case == "winding":
        room["floorPolygon"].reverse()
    elif case == "self-intersection":
        room["floorPolygon"] = [[0, 0], [4, 4], [0, 4], [4, 0]]
    elif case == "room-reference":
        bundle["objects"][0]["roomId"] = uid()
    elif case == "bbox":
        bundle["objects"][0]["boundingBox"]["min"]["y"] = 1
    elif case == "missing-collider":
        nodes.clear()
    elif case == "duplicate-collider":
        bundle["geometry"]["structuralColliders"][0]["nodeName"] = bundle["objects"][0]["collisionNode"]
    elif case == "hinged-door":
        bundle["geometry"]["doors"][0].update(openingType="HINGED", hingePosition=None, sweepNode=None)
    else:
        window = example("Window")
        window["roomId"] = room["roomId"]
        window["transform"]["position"]["y"] = room["floorY"] + window["sillHeight"] + window["height"] / 2
        bundle["geometry"]["windows"] = [window]
        if case == "window-room":
            window["roomId"] = uid()
        elif case == "window-height":
            window["transform"]["position"]["y"] += 0.002
        elif case == "window-above-ceiling":
            window["sillHeight"] = 100
            window["transform"]["position"]["y"] = 100 + window["height"] / 2
        elif case == "window-rotation":
            window["transform"]["rotation"] = {"x": math.sqrt(0.5), "y": 0, "z": 0, "w": math.sqrt(0.5)}
    with pytest.raises(Problem):
        bundle_geometry(bundle, nodes)


def test_quaternion_norm_tolerance_and_rigid_matrices():
    quaternion({"x": 0, "y": 0, "z": 0, "w": 1.000075})
    with pytest.raises(Problem):
        quaternion({"x": 0, "y": 0, "z": 0, "w": 1.0002})
    identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
    rigid_matrix(identity)
    for index, value in [(0, 2), (0, -1), (3, 0.01), (15, 0), (12, float("nan"))]:
        m = identity.copy()
        m[index] = value
        with pytest.raises(Problem):
            rigid_matrix(m)


def camera_fixture():
    f = info(image(), "frame.jpg", "image/jpeg")
    manifest = example("CameraManifest")
    frame = example("CameraFrame")
    frame.update(
        imageClientFileId=f["clientFileId"],
        timestampUs=0,
        widthPx=640,
        heightPx=480,
        intrinsics={"fx": 500, "fy": 500, "cx": 320, "cy": 240},
    )
    manifest["frames"] = [frame]
    return f, manifest


@pytest.mark.parametrize(
    "case",
    [
        "mismatch",
        "duplicate",
        "dimensions",
        "principal",
        "pose-row",
        "pose-scale",
        "pose-reflection",
        "frame-order",
    ],
)
def test_manifest_math_and_reference_errors(case):
    file, manifest = camera_fixture()
    frame = manifest["frames"][0]
    if case == "mismatch":
        frame["imageClientFileId"] = uid()
    elif case == "duplicate":
        manifest["frames"].append(copy.deepcopy(frame))
    elif case == "dimensions":
        frame["widthPx"] = 641
    elif case == "principal":
        frame["intrinsics"]["cx"] = 640
    elif case == "pose-row":
        frame["cameraToWorld"][15] = 0
    elif case == "pose-scale":
        frame["cameraToWorld"][0] = 2
    elif case == "pose-reflection":
        frame["cameraToWorld"][0] = -1
    elif case == "frame-order":
        second = copy.deepcopy(frame)
        second["imageClientFileId"] = uid()
        manifest["frames"].append(second)
        files = [file, {**file, "clientFileId": second["imageClientFileId"]}]
        with pytest.raises(Problem):
            validate_manifest(manifest, files, {f["clientFileId"]: (640, 480) for f in files})
        return
    with pytest.raises(Problem):
        validate_manifest(manifest, [file], {file["clientFileId"]: (640, 480)})


@pytest.mark.parametrize(
    "purpose,count,valid",
    [
        ("LISTING_PHOTO", 1, True),
        ("LISTING_PHOTO", 20, True),
        ("LISTING_PHOTO", 21, False),
        ("LISTING_AGENT_DOCUMENT", 1, True),
        ("LISTING_AGENT_DOCUMENT", 2, False),
        ("ROOM_CAPTURE", 29, False),
        ("ROOM_CAPTURE", 30, True),
        ("ROOM_CAPTURE", 1000, True),
        ("ROOM_CAPTURE", 1001, False),
        ("FURNITURE_CAPTURE", 11, False),
        ("FURNITURE_CAPTURE", 12, True),
        ("FURNITURE_CAPTURE", 300, True),
        ("FURNITURE_CAPTURE", 301, False),
    ],
)
def test_capture_file_count_boundaries(purpose, count, valid):
    files = [
        dict(clientFileId=uid(), fileName="x.jpg", contentType="image/jpeg", byteSize=1, sha256="0" * 64)
        for i in range(count)
    ]
    if "CAPTURE" in purpose:
        files.append(
            dict(
                clientFileId=uid(),
                fileName="camera.json",
                contentType="application/json",
                byteSize=1,
                sha256="0" * 64,
            )
        )
    target = {
        "ROOM_CAPTURE": "LISTING",
        "LISTING_PHOTO": "LISTING",
        "FURNITURE_CAPTURE": "FURNITURE",
        "LISTING_AGENT_DOCUMENT": "USER",
    }[purpose]
    body = dict(purpose=purpose, targetType=target, targetId=uid(), files=files)
    if valid:
        assert len(classify_files(body)) == len(files)
    else:
        with pytest.raises(Problem):
            classify_files(body)


@pytest.mark.parametrize(
    "case",
    [
        "u8-depth",
        "rgb-depth",
        "truncated-png",
        "confidence-value",
        "depth-size",
        "confidence-no-depth",
        "partial-ref",
        "duplicate-ref",
        "role-ref",
        "resolution",
        "roomplan-shape",
        "json-array",
        "json-duplicate",
        "roomplan-structure-rotation",
        "roomplan-world-pose",
    ],
)
def test_supplemental_actual_bytes_and_reference_matrix(case):
    rgb, manifest = camera_fixture()

    def png(mode, size=(2, 2), value=2):
        out = BytesIO()
        Image.new(mode, size, value).save(out, "PNG")
        return out.getvalue()

    d = dict(info(png("I;16", value=1000), "depth.png", "image/png"), role="DEPTH")
    c = dict(info(png("L"), "confidence.png", "image/png"), role="CONFIDENCE")
    rgb["role"] = "IMAGE"
    raws = {
        rgb["clientFileId"]: image(),
        d["clientFileId"]: png("I;16", value=1000),
        c["clientFileId"]: png("L"),
    }
    files = [rgb, d, c]
    frame = manifest["frames"][0]
    frame.update(
        depthClientFileId=d["clientFileId"],
        depthWidthPx=2,
        depthHeightPx=2,
        confidenceClientFileId=c["clientFileId"],
    )
    if case == "u8-depth":
        raws[d["clientFileId"]] = png("L")
    elif case == "rgb-depth":
        raws[d["clientFileId"]] = png("RGB")
    elif case == "truncated-png":
        raws[d["clientFileId"]] = b"\x89PNG\r\n\x1a\n"
    elif case == "confidence-value":
        raws[c["clientFileId"]] = png("L", value=3)
    elif case == "depth-size":
        raws[d["clientFileId"]] = png("I;16", size=(4097, 1))
    elif case == "confidence-no-depth":
        for k in ("depthClientFileId", "depthWidthPx", "depthHeightPx"):
            frame.pop(k)
    elif case == "partial-ref":
        frame.pop("depthHeightPx")
    elif case == "duplicate-ref":
        second = copy.deepcopy(frame)
        second["imageClientFileId"] = uid()
        second["timestampUs"] = 1
        manifest["frames"].append(second)
        secondrgb = {**rgb, "clientFileId": second["imageClientFileId"]}
        files.append(secondrgb)
        raws[secondrgb["clientFileId"]] = image()
    elif case == "role-ref":
        frame["depthClientFileId"] = rgb["clientFileId"]
    elif case == "resolution":
        frame["depthWidthPx"] = 3
    elif case in ("roomplan-shape", "json-array", "json-duplicate", "roomplan-structure-rotation"):
        role = "ROOMPLAN_STRUCTURE" if case == "roomplan-structure-rotation" else "ROOMPLAN"
        value = (
            example("RoomPlanStructure")
            if role == "ROOMPLAN_STRUCTURE"
            else {"walls": [], "doors": [], "windows": [], "objects": []}
        )
        if case == "roomplan-shape":
            value["walls"] = None
        if case == "roomplan-structure-rotation":
            value["surfaces"] = [example("RoomPlanSurface")]
            value["surfaces"][0]["transform"]["rotation"]["w"] = 0.5
        raw = (
            b"[]"
            if case == "json-array"
            else b'{"walls":[],"walls":[]}'
            if case == "json-duplicate"
            else json.dumps(value).encode()
        )
        f = dict(info(raw, "roomplan.json", "application/json"), role=role)
        files.append(f)
        raws[f["clientFileId"]] = raw
    elif case == "roomplan-world-pose":
        manifest["roomPlanToWorld"] = [2] * 16
    camraw = json.dumps(manifest).encode()
    cam = dict(info(camraw, "camera.json", "application/json"), role="CAMERA_MANIFEST")
    files.append(cam)
    raws[cam["clientFileId"]] = camraw
    for file in files:
        file["format"] = input_format(file)
    svc = SimpleNamespace(read_file=lambda f: raws[f["clientFileId"]], contract=Contract())
    with pytest.raises(Problem):
        verify_inputs(svc, {"purpose": "ROOM_CAPTURE"}, files)


@pytest.fixture
def google_provider(monkeypatch):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key()))
    jwk.update(kid="test-key", use="sig", alg="RS256")
    monkeypatch.setattr(oauth, "_keys", None)
    monkeypatch.setattr(oauth, "_expires", 0)
    monkeypatch.setattr(oauth, "provider_json", lambda *a, **k: {"keys": [jwk]})
    cfg = SimpleNamespace(google_client_ids=["client"], kakao_app_id="123")
    claims = dict(
        sub="subject",
        iss="https://accounts.google.com",
        aud="client",
        iat=int(time.time()) - 1,
        exp=int(time.time()) + 300,
    )
    return key, cfg, claims


@pytest.mark.parametrize(
    "case",
    [
        "wrong-issuer",
        "wrong-audience",
        "expired",
        "future-iat",
        "missing-sub",
        "empty-sub",
        "long-sub",
        "wrong-signature",
        "wrong-kid",
        "HS256",
        "none",
        "multi-aud-no-azp",
        "multi-aud-wrong-azp",
    ],
)
def test_google_signed_token_rejections(google_provider, case):
    key, cfg, claims = google_provider
    if case == "wrong-issuer":
        claims["iss"] = "https://evil.example"
    elif case == "wrong-audience":
        claims["aud"] = "evil"
    elif case == "expired":
        claims["exp"] = int(time.time()) - 1
    elif case == "future-iat":
        claims["iat"] = int(time.time()) + 100
    elif case == "missing-sub":
        claims.pop("sub")
    elif case == "empty-sub":
        claims["sub"] = ""
    elif case == "long-sub":
        claims["sub"] = "a" * 256
    elif case == "wrong-signature":
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    elif case.startswith("multi-aud"):
        claims["aud"] = ["client", "other"]
        if case.endswith("wrong-azp"):
            claims["azp"] = "evil"
    alg = "HS256" if case == "HS256" else "none" if case == "none" else "RS256"
    token = jwt.encode(
        claims,
        "x" * 40 if alg == "HS256" else None if alg == "none" else key,
        algorithm=alg,
        headers={"kid": "unknown" if case == "wrong-kid" else "test-key"},
    )
    with pytest.raises(Problem) as raised:
        oauth.google_identity(token, cfg)
    assert raised.value.status == 401


def test_google_multiple_aud_authorized_party_and_key_rotation(google_provider, monkeypatch):
    key, cfg, claims = google_provider
    claims.update(aud=["client", "other"], azp="client", iss="accounts.google.com")
    token = jwt.encode(claims, key, algorithm="RS256", headers={"kid": "test-key"})
    assert oauth.google_identity(token, cfg)["sub"] == "subject"
    old_keys = oauth._keys
    monkeypatch.setattr(oauth, "_keys", {"keys": []})
    assert oauth.google_identity(token, cfg)["sub"] == "subject"
    assert oauth._keys == old_keys


@pytest.mark.parametrize(
    "response",
    [
        {"keys": None},
        {"keys": [None]},
        {"keys": ["wrong"]},
        {"keys": "wrong"},
        {"keys": [{"kid": "test-key", "kty": "RSA", "n": "invalid", "e": "invalid"}]},
    ],
)
def test_google_malformed_jwks_is_provider_error(google_provider, monkeypatch, response):
    key, cfg, claims = google_provider
    monkeypatch.setattr(oauth, "provider_json", lambda *a, **k: response)
    token = jwt.encode(claims, key, algorithm="RS256", headers={"kid": "test-key"})
    with pytest.raises(Problem):
        oauth.google_identity(token, cfg)


@pytest.mark.parametrize("name", [None, "", "  ", 123, [], "x" * 100])
def test_social_login_optional_profile_fields_never_break_response(client, app, monkeypatch, name):
    app.state.settings.kakao_app_id = "123"
    monkeypatch.setattr(
        oauth,
        "provider_json",
        lambda url, *a, **k: (
            {"app_id": 123, "id": 99, "expires_in": 100}
            if url.endswith("access_token_info")
            else {"id": 99, "kakao_account": {"email": "invalid email", "profile": {"nickname": name}}}
        ),
    )
    r = client.post("/api/v1/auth/oauth/kakao", json={"accessToken": "valid-token"})
    assert r.status_code == 200, r.text
    assert r.json()["user"]["email"] is None
    assert 1 <= len(r.json()["user"]["displayName"]) <= 40


@pytest.mark.parametrize(
    "case",
    ["app-id", "user-id", "expiry", "expiry-type", "info-shape", "account-shape", "profile-shape", "id-bool"],
)
def test_kakao_provider_malformed_and_mismatched_claims(case, monkeypatch):
    info = dict(app_id=123, id=99, expires_in=100)
    user = dict(id=99, kakao_account={"profile": {"nickname": "테스트"}})
    if case == "app-id":
        info["app_id"] = 0
    elif case == "user-id":
        user["id"] = 100
    elif case == "expiry":
        info["expires_in"] = 0
    elif case == "expiry-type":
        info["expires_in"] = "wrong"
    elif case == "info-shape":
        info = []
    elif case == "account-shape":
        user["kakao_account"] = "wrong"
    elif case == "profile-shape":
        user["kakao_account"]["profile"] = []
    elif case == "id-bool":
        info["id"] = True
        user["id"] = True
    monkeypatch.setattr(
        oauth, "provider_json", lambda url, *a, **k: info if url.endswith("access_token_info") else user
    )
    with pytest.raises(Problem):
        oauth.kakao_identity("token", SimpleNamespace(kakao_app_id="123"))


@pytest.mark.parametrize(
    "case,expected",
    [
        ("ok", 200),
        ("unauthorized", 401),
        ("forbidden", 401),
        ("rate", 503),
        ("redirect", 503),
        ("server", 503),
        ("bad-json", 503),
        ("duplicate-json", 503),
        ("array-json", 503),
        ("huge", 503),
        ("timeout", 503),
        ("deep-json", 503),
    ],
)
def test_provider_http_error_and_size_matrix(case, expected, monkeypatch):
    original = httpx.Client

    def handler(request):
        if case == "timeout":
            raise httpx.ConnectTimeout("injected timeout", request=request)
        status = {"unauthorized": 401, "forbidden": 403, "rate": 429, "redirect": 302, "server": 500}.get(
            case, 200
        )
        raw = (
            b"{"
            if case == "bad-json"
            else b'{"x":1,"x":2}'
            if case == "duplicate-json"
            else b"[]"
            if case == "array-json"
            else b"x" * ((1 << 20) + 1)
            if case == "huge"
            else b"[" * 1200 + b"0" + b"]" * 1200
            if case == "deep-json"
            else b'{"ok":true}'
        )
        return httpx.Response(status, content=raw)

    monkeypatch.setattr(
        oauth.httpx, "Client", lambda **kw: original(transport=httpx.MockTransport(handler), **kw)
    )
    if expected == 200:
        assert oauth.provider_json("https://provider.example/test") == {"ok": True}
    else:
        with pytest.raises(Problem) as raised:
            oauth.provider_json("https://provider.example/test")
        assert raised.value.status == expected
