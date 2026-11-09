"""Cross-field checks that JSON Schema cannot express."""

from __future__ import annotations

import math
import struct
import warnings
from io import BytesIO

from PIL import Image, ImageOps

from .core import Problem, require, strict_json


def unique(items, key):
    require(len({x[key] for x in items}) == len(items), 422, "DUPLICATE_ID", key)


def quaternion(q):
    require(abs(math.sqrt(sum(v * v for v in q.values())) - 1) <= 1e-4, 422, "INVALID_ROTATION")


def rigid_matrix(m):
    require(len(m) == 16 and all(math.isfinite(v) for v in m), 422, "INVALID_CAMERA_POSE")
    require(
        all(abs(m[i] - v) <= 1e-3 for i, v in zip((3, 7, 11, 15), (0, 0, 0, 1))), 422, "INVALID_CAMERA_POSE"
    )
    cols = [[m[c * 4 + r] for r in range(3)] for c in range(3)]
    require(
        all(
            abs(sum(cols[a][r] * cols[b][r] for r in range(3)) - int(a == b)) <= 1e-3
            for a in range(3)
            for b in range(3)
        ),
        422,
        "INVALID_CAMERA_POSE",
    )
    a, b, c = cols
    det = (
        a[0] * (b[1] * c[2] - b[2] * c[1])
        - b[0] * (a[1] * c[2] - a[2] * c[1])
        + c[0] * (a[1] * b[2] - a[2] * b[1])
    )
    require(abs(det - 1) <= 1e-3, 422, "INVALID_CAMERA_POSE")


def bbox(dim, box):
    expected = (
        (-dim["width"] / 2, 0, -dim["depth"] / 2),
        (dim["width"] / 2, dim["height"], dim["depth"] / 2),
    )
    for key, values in zip(("min", "max"), expected):
        require(
            all(abs(box[key][axis] - v) <= 0.001 for axis, v in zip(("x", "y", "z"), values)),
            422,
            "INVALID_BOUNDING_BOX",
        )


def validate_manifest(manifest, images, sizes):
    frames = manifest["frames"]
    unique(frames, "imageClientFileId")
    require(
        {f["imageClientFileId"] for f in frames} == {i["clientFileId"] for i in images},
        422,
        "MANIFEST_IMAGE_MISMATCH",
    )
    previous = -1
    for f in frames:
        require(f["timestampUs"] > previous, 422, "INVALID_FRAME_ORDER")
        previous = f["timestampUs"]
        require(
            sizes[f["imageClientFileId"]] == (f["widthPx"], f["heightPx"]), 422, "IMAGE_DIMENSION_MISMATCH"
        )
        k = f["intrinsics"]
        require(k["cx"] < f["widthPx"] and k["cy"] < f["heightPx"], 422, "INVALID_INTRINSICS")
        m = f["cameraToWorld"]
        require(
            all(abs(m[i] - v) <= 1e-3 for i, v in zip((3, 7, 11, 15), (0, 0, 0, 1))),
            422,
            "INVALID_CAMERA_POSE",
        )
        cols = [[m[c * 4 + r] for r in range(3)] for c in range(3)]
        for a in range(3):
            for b in range(3):
                require(
                    abs(sum(cols[a][r] * cols[b][r] for r in range(3)) - int(a == b)) <= 1e-3,
                    422,
                    "INVALID_CAMERA_POSE",
                )
        a, b, c = cols
        det = (
            a[0] * (b[1] * c[2] - b[2] * c[1])
            - b[0] * (a[1] * c[2] - a[2] * c[1])
            + c[0] * (a[1] * b[2] - a[2] * b[1])
        )
        require(abs(det - 1) <= 1e-3, 422, "INVALID_CAMERA_POSE")


def image_info(raw, mime):
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(raw)) as im:
                require(im.format == ("PNG" if mime == "image/png" else "JPEG"), 422, "CONTENT_TYPE_MISMATCH")
                w, h = im.size
                require(
                    640 <= w <= 8192 and 480 <= h <= 8192 and w * h <= 40_000_000,
                    422,
                    "INVALID_IMAGE_DIMENSIONS",
                )
                im.verify()
            # verify() alone does not fully decode every JPEG.
            with Image.open(BytesIO(raw)) as im:
                im.load()
                require(getattr(im, "n_frames", 1) == 1, 422, "INVALID_IMAGE")
            return w, h
    except Problem:
        raise
    except Exception as exc:
        raise Problem(422, "INVALID_IMAGE") from exc


def stripped_photo(raw):
    with Image.open(BytesIO(raw)) as im:
        # Re-encode pixels only: no EXIF, GPS, comments or original metadata.
        image = ImageOps.exif_transpose(im).convert("RGB")
        image.thumbnail((2048, 2048))
        out = BytesIO()
        image.save(out, "JPEG", quality=90)
        return out.getvalue()


def cross(a, b, c):
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def intersects(a, b, c, d):
    def on(p, q, r):
        return (
            abs(cross(p, q, r)) < 1e-9
            and min(p[0], q[0]) - 1e-9 <= r[0] <= max(p[0], q[0]) + 1e-9
            and min(p[1], q[1]) - 1e-9 <= r[1] <= max(p[1], q[1]) + 1e-9
        )

    return (cross(a, b, c) * cross(a, b, d) < 0 and cross(c, d, a) * cross(c, d, b) < 0) or any(
        (on(a, b, c), on(a, b, d), on(c, d, a), on(c, d, b))
    )


def bundle_geometry(bundle, collision_nodes):
    g = bundle["geometry"]
    for items, key in (
        (g["rooms"], "roomId"),
        (g["doors"], "doorId"),
        (g.get("windows", []), "windowId"),
        (g["structuralColliders"], "colliderId"),
        (bundle["objects"], "objectId"),
    ):
        unique(items, key)
    rooms = {r["roomId"] for r in g["rooms"]}
    referenced = []
    xs = [p[0] for r in g["rooms"] for p in r["floorPolygon"]]
    zs = [p[1] for r in g["rooms"] for p in r["floorPolygon"]]
    overall = {
        "width": max(xs) - min(xs),
        "depth": max(zs) - min(zs),
        "height": max(r["ceilingY"] for r in g["rooms"]) - min(r["floorY"] for r in g["rooms"]),
    }
    require(
        all(abs(bundle["roomDimensions"][k] - v) <= 0.001 for k, v in overall.items()),
        422,
        "ROOM_DIMENSION_MISMATCH",
    )
    for room in g["rooms"]:
        p = room["floorPolygon"]
        require(len(set(map(tuple, p))) == len(p), 422, "INVALID_FLOOR_POLYGON")
        signed_area = (
            sum(p[i][0] * p[(i + 1) % len(p)][1] - p[(i + 1) % len(p)][0] * p[i][1] for i in range(len(p)))
            / 2
        )
        require(signed_area < 0 and abs(-signed_area - room["areaM2"]) <= 0.01, 422, "INVALID_FLOOR_POLYGON")
        require(
            room["ceilingY"] > room["floorY"]
            and abs(room["dimensions"]["height"] - (room["ceilingY"] - room["floorY"])) <= 0.001,
            422,
            "INVALID_ROOM_HEIGHT",
        )
        require(
            abs(room["dimensions"]["width"] - (max(q[0] for q in p) - min(q[0] for q in p))) <= 0.001
            and abs(room["dimensions"]["depth"] - (max(q[1] for q in p) - min(q[1] for q in p))) <= 0.001,
            422,
            "ROOM_DIMENSION_MISMATCH",
        )
        for i in range(len(p)):
            for j in range(i + 1, len(p)):
                if j == i + 1 or (i == 0 and j == len(p) - 1):
                    continue
                require(
                    not intersects(p[i], p[(i + 1) % len(p)], p[j], p[(j + 1) % len(p)]),
                    422,
                    "INVALID_FLOOR_POLYGON",
                )
    for obj in bundle["objects"]:
        require(obj["roomId"] in rooms, 422, "INVALID_ROOM_REFERENCE")
        quaternion(obj["transform"]["rotation"])
        bbox(obj["dimensions"], obj["boundingBox"])
        referenced.append(obj["collisionNode"])
    for door in g["doors"]:
        require(door["roomId"] in rooms, 422, "INVALID_ROOM_REFERENCE")
        quaternion(door["transform"]["rotation"])
        if door["openingType"] == "HINGED":
            require(door["hingePosition"] is not None and door["sweepNode"] is not None, 422, "INVALID_DOOR")
        if door["sweepNode"]:
            referenced.append(door["sweepNode"])
    for node in g["structuralColliders"]:
        require(node["roomId"] in rooms, 422, "INVALID_ROOM_REFERENCE")
        referenced.append(node["nodeName"])
    room_by_id = {r["roomId"]: r for r in g["rooms"]}
    for window in g.get("windows", []):
        require(window["roomId"] in rooms, 422, "INVALID_ROOM_REFERENCE")
        quaternion(window["transform"]["rotation"])
        q = window["transform"]["rotation"]
        require(abs(q["x"]) <= 1e-4 and abs(q["z"]) <= 1e-4, 422, "INVALID_WINDOW_ROTATION")
        r = room_by_id[window["roomId"]]
        y = window["transform"]["position"]["y"]
        require(
            abs(y - (r["floorY"] + window["sillHeight"] + window["height"] / 2)) <= 0.001
            and y + window["height"] / 2 <= r["ceilingY"] + 0.001,
            422,
            "INVALID_WINDOW_HEIGHT",
        )
    require(
        len(referenced) == len(set(referenced)) and set(referenced) <= collision_nodes,
        422,
        "INVALID_COLLISION_REFERENCE",
    )


def validate_ply(raw):
    end = raw.find(b"end_header\n")
    require(0 < end < 16384, 422, "INVALID_ARTIFACT")
    try:
        lines = raw[:end].decode("ascii").splitlines()
        require(lines[:2] == ["ply", "format binary_little_endian 1.0"], 422, "INVALID_ARTIFACT")
        require("comment dulleobang_gs_ply_v1" in lines, 422, "INVALID_ARTIFACT")
        elements = [s for s in lines if s.startswith("element ")]
        require(len(elements) == 1 and elements[0].startswith("element vertex "), 422, "INVALID_ARTIFACT")
        count = int(elements[0].split()[2])
        require(1 <= count <= 2_000_000, 422, "INVALID_ARTIFACT")
        names = [
            "x",
            "y",
            "z",
            "f_dc_0",
            "f_dc_1",
            "f_dc_2",
            "opacity",
            "scale_0",
            "scale_1",
            "scale_2",
            "rot_0",
            "rot_1",
            "rot_2",
            "rot_3",
        ]
        require(
            [s for s in lines if s.startswith("property ")] == ["property float " + n for n in names],
            422,
            "INVALID_ARTIFACT",
        )
        data = raw[end + len(b"end_header\n") :]
        require(len(data) == count * 56, 422, "INVALID_ARTIFACT")
        for row in struct.iter_unpack("<14f", data):
            require(all(math.isfinite(x) for x in row), 422, "INVALID_ARTIFACT")
            require(abs(math.sqrt(sum(v * v for v in row[10:14])) - 1) <= 1e-4, 422, "INVALID_ROTATION")
    except (ValueError, UnicodeError, struct.error) as exc:
        raise Problem(422, "INVALID_ARTIFACT") from exc


def validate_glb(raw, *, collision=False, expected_box=None):
    """Validate the supported static embedded glTF subset and accessor bounds."""
    try:
        require(len(raw) >= 20, 422, "INVALID_ARTIFACT")
        magic, version, total = struct.unpack_from("<III", raw)
        require(magic == 0x46546C67 and version == 2 and total == len(raw), 422, "INVALID_ARTIFACT")
        chunks, pos = [], 12
        while pos < len(raw):
            length, kind = struct.unpack_from("<II", raw, pos)
            pos += 8
            require(length % 4 == 0 and pos + length <= len(raw), 422, "INVALID_ARTIFACT")
            chunks.append((kind, raw[pos : pos + length]))
            pos += length
        require(
            pos == len(raw) and 1 <= len(chunks) <= 2 and chunks[0][0] == 0x4E4F534A, 422, "INVALID_ARTIFACT"
        )
        require(len(chunks) == 1 or chunks[1][0] == 0x004E4942, 422, "INVALID_ARTIFACT")
        doc = strict_json(chunks[0][1].rstrip(b" \x00"))
        binary = chunks[1][1] if len(chunks) == 2 else b""
        require(isinstance(doc, dict), 422, "INVALID_ARTIFACT")
        require(doc.get("asset", {}).get("version") == "2.0", 422, "INVALID_ARTIFACT")
        require(
            not any(doc.get(k) for k in ("extensionsUsed", "extensionsRequired", "animations", "skins")),
            422,
            "INVALID_ARTIFACT",
        )

        def no_extensions(v):
            if isinstance(v, dict):
                require(not v.get("extensions"), 422, "INVALID_ARTIFACT")
                for x in v.values():
                    no_extensions(x)
            elif isinstance(v, list):
                for x in v:
                    no_extensions(x)

        no_extensions(doc)
        require(
            not collision or not any(doc.get(k) for k in ("images", "textures", "samplers")),
            422,
            "INVALID_ARTIFACT",
        )
        buffers = doc.get("buffers", [])
        require(len(buffers) == 1 and "uri" not in buffers[0], 422, "INVALID_ARTIFACT")
        require(
            0 < buffers[0]["byteLength"] <= len(binary) <= buffers[0]["byteLength"] + 3,
            422,
            "INVALID_ARTIFACT",
        )
        views = doc.get("bufferViews", [])
        for v in views:
            require(
                v.get("buffer", 0) == 0
                and v.get("byteOffset", 0) >= 0
                and v["byteLength"] > 0
                and v.get("byteOffset", 0) + v["byteLength"] <= buffers[0]["byteLength"],
                422,
                "INVALID_ARTIFACT",
            )
        sizes = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}
        types = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT2": 4, "MAT3": 9, "MAT4": 16}
        accessors = doc.get("accessors", [])
        for a in accessors:
            require(
                "sparse" not in a and isinstance(a.get("count"), int) and a["count"] > 0,
                422,
                "INVALID_ARTIFACT",
            )
            require(
                type(a.get("bufferView")) is int and 0 <= a["bufferView"] < len(views),
                422,
                "INVALID_ARTIFACT",
            )
            view = views[a["bufferView"]]
            size = sizes[a["componentType"]] * types[a["type"]]
            stride = view.get("byteStride", size)
            require(
                stride >= size
                and a.get("byteOffset", 0) >= 0
                and a.get("byteOffset", 0) + (a["count"] - 1) * stride + size <= view["byteLength"],
                422,
                "INVALID_ARTIFACT",
            )
            if a["componentType"] == 5126:
                offset = view.get("byteOffset", 0) + a.get("byteOffset", 0)
                for i in range(a["count"]):
                    vals = struct.unpack_from("<" + "f" * types[a["type"]], binary, offset + i * stride)
                    require(all(math.isfinite(v) for v in vals), 422, "INVALID_ARTIFACT")
        triangles = 0
        require(bool(doc.get("meshes")), 422, "INVALID_ARTIFACT")
        for mesh in doc["meshes"]:
            require(bool(mesh.get("primitives")), 422, "INVALID_ARTIFACT")
            for p in mesh["primitives"]:
                require(p.get("mode", 4) == 4 and not p.get("targets"), 422, "INVALID_ARTIFACT")
                for index in p["attributes"].values():
                    require(type(index) is int and 0 <= index < len(accessors), 422, "INVALID_ARTIFACT")
                if "indices" in p:
                    require(
                        type(p["indices"]) is int and 0 <= p["indices"] < len(accessors),
                        422,
                        "INVALID_ARTIFACT",
                    )
                if "material" in p:
                    require(
                        type(p["material"]) is int and 0 <= p["material"] < len(doc.get("materials", [])),
                        422,
                        "INVALID_ARTIFACT",
                    )
                a = accessors[p["attributes"]["POSITION"]]
                require(a["type"] == "VEC3" and a["componentType"] == 5126, 422, "INVALID_ARTIFACT")
                n = accessors[p["indices"]]["count"] if "indices" in p else a["count"]
                require(n % 3 == 0, 422, "INVALID_ARTIFACT")
                if "indices" in p:
                    idx = accessors[p["indices"]]
                    require(
                        idx["type"] == "SCALAR" and idx["componentType"] in (5121, 5123, 5125),
                        422,
                        "INVALID_ARTIFACT",
                    )
                    v = views[idx["bufferView"]]
                    fmt = {5121: "B", 5123: "H", 5125: "I"}[idx["componentType"]]
                    offset = v.get("byteOffset", 0) + idx.get("byteOffset", 0)
                    stride = v.get("byteStride", sizes[idx["componentType"]])
                    require(
                        all(
                            struct.unpack_from("<" + fmt, binary, offset + i * stride)[0] < a["count"]
                            for i in range(idx["count"])
                        ),
                        422,
                        "INVALID_ARTIFACT",
                    )
                triangles += n // 3
        require(not collision or triangles <= 200_000, 422, "INVALID_ARTIFACT")
        for im in doc.get("images", []):
            require(
                "uri" not in im
                and im.get("mimeType") in ("image/png", "image/jpeg")
                and 0 <= im["bufferView"] < len(views),
                422,
                "INVALID_ARTIFACT",
            )
            view = views[im["bufferView"]]
            offset = view.get("byteOffset", 0)
            with Image.open(BytesIO(binary[offset : offset + view["byteLength"]])) as texture:
                require(
                    texture.format == ("PNG" if im["mimeType"] == "image/png" else "JPEG"),
                    422,
                    "INVALID_ARTIFACT",
                )
                texture.verify()
        nodes = doc.get("nodes", [])
        names = [n["name"] for n in nodes if n.get("name")]
        require(len(names) == len(set(names)), 422, "INVALID_ARTIFACT")
        scenes = doc.get("scenes", [])
        scene_index = doc.get("scene", 0)
        require(type(scene_index) is int and 0 <= scene_index < len(scenes), 422, "INVALID_ARTIFACT")
        roots = scenes[scene_index]["nodes"]
        require(bool(roots), 422, "INVALID_ARTIFACT")
        visited, visiting = set(), set()

        identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]
        minimum, maximum = [float("inf")] * 3, [float("-inf")] * 3

        def transform_matrix(n):
            require(
                not ("matrix" in n and any(k in n for k in ("translation", "rotation", "scale"))),
                422,
                "INVALID_ARTIFACT",
            )
            if "matrix" in n:
                m = n["matrix"]
                require(
                    len(m) == 16 and all(type(x) in (int, float) and math.isfinite(x) for x in m),
                    422,
                    "INVALID_ARTIFACT",
                )
                require(
                    all(abs(m[i] - v) <= 1e-6 for i, v in zip((3, 7, 11, 15), (0, 0, 0, 1))),
                    422,
                    "INVALID_ARTIFACT",
                )
                return m
            t, s, r = (
                n.get("translation", [0, 0, 0]),
                n.get("scale", [1, 1, 1]),
                n.get("rotation", [0, 0, 0, 1]),
            )
            require(
                len(t) == 3
                and len(s) == 3
                and len(r) == 4
                and all(type(x) in (int, float) and math.isfinite(x) for x in t + s + r),
                422,
                "INVALID_ARTIFACT",
            )
            quaternion(dict(zip(("x", "y", "z", "w"), r)))
            x, y, z, w = r
            return [
                (1 - 2 * y * y - 2 * z * z) * s[0],
                (2 * x * y + 2 * z * w) * s[0],
                (2 * x * z - 2 * y * w) * s[0],
                0,
                (2 * x * y - 2 * z * w) * s[1],
                (1 - 2 * x * x - 2 * z * z) * s[1],
                (2 * y * z + 2 * x * w) * s[1],
                0,
                (2 * x * z + 2 * y * w) * s[2],
                (2 * y * z - 2 * x * w) * s[2],
                (1 - 2 * x * x - 2 * y * y) * s[2],
                0,
                *t,
                1,
            ]

        def multiply(a, b):
            return [sum(a[k * 4 + r] * b[c * 4 + k] for k in range(4)) for c in range(4) for r in range(4)]

        def walk(index, parent_matrix):
            require(
                type(index) is int
                and 0 <= index < len(nodes)
                and index not in visiting
                and index not in visited,
                422,
                "INVALID_ARTIFACT",
            )
            visiting.add(index)
            n = nodes[index]
            require("skin" not in n, 422, "INVALID_ARTIFACT")
            local = transform_matrix(n)
            require(not collision or local == identity, 422, "COLLISION_TRANSFORM_NOT_BAKED")
            matrix = multiply(parent_matrix, local)
            if "mesh" in n:
                require(
                    type(n["mesh"]) is int and 0 <= n["mesh"] < len(doc["meshes"]), 422, "INVALID_ARTIFACT"
                )
                if expected_box:
                    for primitive in doc["meshes"][n["mesh"]]["primitives"]:
                        a = accessors[primitive["attributes"]["POSITION"]]
                        view = views[a["bufferView"]]
                        offset = view.get("byteOffset", 0) + a.get("byteOffset", 0)
                        stride = view.get("byteStride", 12)
                        for j in range(a["count"]):
                            point = struct.unpack_from("<3f", binary, offset + j * stride)
                            world = [
                                sum(matrix[c * 4 + r] * point[c] for c in range(3)) + matrix[12 + r]
                                for r in range(3)
                            ]
                            for r in range(3):
                                minimum[r] = min(minimum[r], world[r])
                                maximum[r] = max(maximum[r], world[r])
            for child in n.get("children", []):
                walk(child, matrix)
            visiting.remove(index)
            visited.add(index)

        for i in roots:
            require(type(i) is int and 0 <= i < len(nodes), 422, "INVALID_ARTIFACT")
            n = nodes[i]
            require(
                n.get("translation", [0, 0, 0]) == [0, 0, 0]
                and n.get("rotation", [0, 0, 0, 1]) == [0, 0, 0, 1]
                and n.get("scale", [1, 1, 1]) == [1, 1, 1],
                422,
                "INVALID_ARTIFACT",
            )
            require(
                n.get("matrix", [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1])
                == [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
                422,
                "INVALID_ARTIFACT",
            )
            walk(i, identity)
        if expected_box:
            for key, actual in (("min", minimum), ("max", maximum)):
                require(
                    all(
                        math.isfinite(v) and abs(v - expected_box[key][axis]) <= 0.001
                        for v, axis in zip(actual, ("x", "y", "z"))
                    ),
                    422,
                    "MODEL_GEOMETRY_DIMENSION_MISMATCH",
                )
        return {nodes[i]["name"] for i in visited if nodes[i].get("name") and "mesh" in nodes[i]}
    except Problem:
        raise
    except (
        ValueError,
        KeyError,
        IndexError,
        TypeError,
        struct.error,
        OverflowError,
        RecursionError,
        OSError,
        AttributeError,
    ) as exc:
        raise Problem(422, "INVALID_ARTIFACT") from exc
