"""Import the mobile capture ZIP without extracting untrusted archive paths."""

import hashlib
import math
import shutil
import stat
import zipfile
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
from tempfile import TemporaryDirectory

from .capture_inputs import classify_files, input_format, verify_inputs
from .core import Problem, dumps, epoch, require, stamp, strict_json, uid
from .geometry import rigid_matrix

MAX_ZIP_BYTES = 2 << 30
MAX_EXPANDED_BYTES = 5 << 30
MAX_ENTRIES = 7000


def number(value):
    require(type(value) in (int, float) and math.isfinite(value), 422, "INVALID_CAPTURE_METADATA")
    return value


def integer(value):
    require(type(value) is int and value >= 0, 422, "INVALID_CAPTURE_METADATA")
    return value


def matrix(value):
    require(isinstance(value, list), 422, "INVALID_CAMERA_POSE")
    if len(value) == 4 and all(isinstance(row, list) and len(row) == 4 for row in value):
        value = [value[r][c] for c in range(4) for r in range(4)]
    require(len(value) == 16, 422, "INVALID_CAMERA_POSE")
    value = [number(v) for v in value]
    rigid_matrix(value)
    return value


def multiply(a, b):
    return [sum(a[k * 4 + r] * b[c * 4 + k] for k in range(4)) for c in range(4) for r in range(4)]


def transform_point(m, p):
    return {
        axis: sum(m[c * 4 + r] * number(p[component]) for c, component in enumerate("xyz")) + m[12 + r]
        for r, axis in enumerate("xyz")
    }


def quaternion_from_matrix(m):
    # Stable conversion, including rotations close to 180 degrees.
    r = [[m[c * 4 + row] for c in range(3)] for row in range(3)]
    trace = sum(r[i][i] for i in range(3))
    if trace > 0:
        s = math.sqrt(trace + 1) * 2
        q = [(r[2][1] - r[1][2]) / s, (r[0][2] - r[2][0]) / s, (r[1][0] - r[0][1]) / s, s / 4]
    else:
        i = max(range(3), key=lambda n: r[n][n])
        j, k = (i + 1) % 3, (i + 2) % 3
        s = math.sqrt(max(0, 1 + r[i][i] - r[j][j] - r[k][k])) * 2
        require(s > 0, 422, "INVALID_CAMERA_POSE")
        q = [0.0] * 4
        q[i], q[j], q[k], q[3] = (
            s / 4,
            (r[i][j] + r[j][i]) / s,
            (r[i][k] + r[k][i]) / s,
            (r[k][j] - r[j][k]) / s,
        )
    norm = math.sqrt(sum(v * v for v in q))
    sign = -1 if q[3] < 0 else 1
    return {axis: sign * v / norm for axis, v in zip("xyzw", q)}


def yaw_matrix(yaw, position):
    a = math.radians(number(yaw))
    c, s = math.cos(a), math.sin(a)
    return [c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, *[number(position[k]) for k in "xyz"], 1]


def converted_transform(m, world):
    result = multiply(world, m)
    return dict(position=dict(zip("xyz", result[12:15])), rotation=quaternion_from_matrix(result))


def world_transform(first, floor):
    # Put the first RGB camera's horizontal position at the origin, the floor at
    # Y=0, and its initial horizontal forward direction at -Z. Preserve meters.
    length = math.hypot(first[8], first[10])
    require(
        length > 1e-4,
        422,
        "INVALID_CAPTURE_ORIGIN",
        "Initial camera must have a horizontal forward direction.",
    )
    c, s = first[10] / length, -first[8] / length
    x, z = first[12], first[14]
    return [c, 0, -s, 0, 0, 1, 0, 0, s, 0, c, 0, -(c * x + s * z), -floor, s * x - c * z, 1]


def archive_entries(archive):
    entries = {}
    infos = archive.infolist()
    require(len(infos) <= MAX_ENTRIES, 413, "CAPTURE_ZIP_TOO_LARGE")
    require(sum(f.file_size for f in infos) <= MAX_EXPANDED_BYTES, 413, "CAPTURE_ZIP_TOO_LARGE")
    for info in infos:
        name = info.orig_filename
        path = PurePosixPath(name)
        require(
            name
            and not any(ord(c) < 32 for c in name)
            and "\\" not in name
            and ":" not in name
            and not path.is_absolute()
            and all(p not in ("", ".", "..") for p in name.rstrip("/").split("/"))
            and not stat.S_ISLNK(info.external_attr >> 16)
            and not info.flag_bits & 1,
            422,
            "UNSAFE_CAPTURE_ZIP",
        )
        require(
            info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
            422,
            "UNSUPPORTED_ZIP_COMPRESSION",
        )
        if info.is_dir() or name.startswith("__MACOSX/") or path.name == ".DS_Store":
            continue
        require(name not in entries, 422, "DUPLICATE_ZIP_ENTRY")
        entries[name] = info
    roots = [name[: -len("meta.json")] for name in entries if PurePosixPath(name).name == "meta.json"]
    require(
        len(roots) == 1, 422, "INVALID_CAPTURE_ZIP", "ZIP must contain one meta.json and poses.json capture."
    )
    root = roots[0]
    require(all(name.startswith(root) for name in entries), 422, "INVALID_CAPTURE_ZIP")
    return {name[len(root) :]: info for name, info in entries.items()}


class PreparedCapture:
    def __init__(self, archive, entries, folder, contract):
        self.archive, self.entries, self.folder, self.contract = archive, entries, folder, contract
        self.files, self.paths = [], {}

    def json(self, name, required=True):
        if name not in self.entries:
            require(not required, 422, "MISSING_CAPTURE_FILE", name)
            return None
        info = self.entries[name]
        require(info.file_size <= 10 << 20, 413, "CAPTURE_ZIP_TOO_LARGE")
        return strict_json(self.archive.read(info))

    def add(self, name, role, raw=None):
        cid = uid()
        path = self.folder / cid
        if raw is None:
            entry = self.entries[name]
            require(entry.file_size <= 20 << 20, 413, "CAPTURE_ZIP_TOO_LARGE")
            with self.archive.open(entry) as source, path.open("xb") as dest:
                shutil.copyfileobj(source, dest, length=1 << 20)
        else:
            path.write_bytes(raw)
        size = path.stat().st_size
        require(size <= (10 << 20 if name.endswith(".json") else 20 << 20), 413, "CAPTURE_ZIP_TOO_LARGE")
        sha = hashlib.sha256(path.read_bytes()).hexdigest()
        suffix = Path(name).suffix.lower()
        mime = "application/json" if suffix == ".json" else "image/png" if suffix == ".png" else "image/jpeg"
        self.files.append(
            dict(
                clientFileId=cid,
                fileName=f"{role.lower()}_{cid}{Path(name).suffix}",
                contentType=mime,
                byteSize=size,
                sha256=sha,
                role=role,
            )
        )
        self.paths[cid] = path
        return cid

    def read_file(self, f):
        return self.paths[f["clientFileId"]].read_bytes()


def normalize_structure(source, raw, world):
    surfaces, objects = [], []
    if source is not None:
        require(
            isinstance(source, dict) and isinstance(source.get("objects"), list), 422, "INVALID_ROOMPLAN_JSON"
        )
        geometry = source["roomGeometry"]
        require(isinstance(geometry, dict), 422, "INVALID_ROOMPLAN_JSON")
        floor = number(geometry["floorHeight"])
        for wall in geometry.get("walls", []):
            a, b = wall["from"], wall["to"]
            require(len(a) == len(b) == 2, 422, "INVALID_ROOMPLAN_JSON")
            dx, dz = number(b[0]) - number(a[0]), number(b[1]) - number(a[1])
            height = number(wall["height"])
            position = dict(x=(a[0] + b[0]) / 2, y=floor + height / 2, z=(a[1] + b[1]) / 2)
            surfaces.append(
                dict(
                    id=wall["id"],
                    kind="WALL",
                    transform=converted_transform(
                        yaw_matrix(math.degrees(math.atan2(-dz, dx)), position), world
                    ),
                    dimensions=dict(width=math.hypot(dx, dz), height=height, depth=0),
                )
            )
        for field, kind in [("doors", "DOOR"), ("windows", "WINDOW")]:
            for item in geometry.get(field, []):
                surfaces.append(
                    dict(
                        id=item["id"],
                        kind=kind,
                        transform=converted_transform(
                            yaw_matrix(item["rotation"]["yaw"], item["position"]), world
                        ),
                        dimensions=item["dimensions"],
                    )
                )
        for item in source["objects"]:
            objects.append(
                dict(
                    id=item["objectId"],
                    category=item["category"],
                    dimensions=item["dimensions"],
                    transform=converted_transform(
                        yaw_matrix(item["rotation"]["yaw"], item["position"]), world
                    ),
                )
            )
        # The flattened export omits floor surfaces; keep the measured originals.
        for item in (raw or {}).get("floors", []):
            surfaces.append(
                dict(
                    id=item["identifier"],
                    kind="FLOOR",
                    transform=converted_transform(matrix(item["transform"]), world),
                    dimensions=dict(zip(("width", "height", "depth"), item["dimensions"])),
                )
            )
    elif raw is not None:
        for field, kind in [
            ("walls", "WALL"),
            ("doors", "DOOR"),
            ("windows", "WINDOW"),
            ("openings", "OPENING"),
            ("floors", "FLOOR"),
        ]:
            for item in raw.get(field, []):
                surfaces.append(
                    dict(
                        id=item["identifier"],
                        kind=kind,
                        transform=converted_transform(matrix(item["transform"]), world),
                        dimensions=dict(zip(("width", "height", "depth"), item["dimensions"])),
                    )
                )
        for item in raw["objects"]:
            category = item["category"]
            require(isinstance(category, dict) and len(category) == 1, 422, "INVALID_ROOMPLAN_JSON")
            objects.append(
                dict(
                    id=item["identifier"],
                    category=next(iter(category)),
                    transform=converted_transform(matrix(item["transform"]), world),
                    dimensions=dict(zip(("width", "height", "depth"), item["dimensions"])),
                )
            )
    return dict(
        schemaVersion="1.0",
        coordinateSystem="RH_Y_UP_Z_BACK",
        unit="METER",
        surfaces=surfaces,
        objects=objects,
    )


def prepare(prepared, target_type="LISTING"):
    meta, poses = prepared.json("meta.json"), prepared.json("poses.json")
    room = target_type == "LISTING"
    require(
        isinstance(meta, dict)
        and isinstance(poses, list)
        and (30 if room else 12) <= len(poses) <= (1000 if room else 300),
        422,
        "INVALID_CAPTURE_METADATA",
    )
    require(
        meta.get("kind") == ("room" if room else "furniture") and meta.get("arSdk") == "ARKit",
        422,
        "UNSUPPORTED_CAPTURE_PROFILE",
    )
    require(
        isinstance(meta.get("coordinate"), str)
        and meta["coordinate"].startswith("arkit_world; meters; y-up; right-handed"),
        422,
        "UNSUPPORTED_CAPTURE_PROFILE",
    )
    require(
        meta.get("imageOrientation") == "sensor_native_landscape (not rotated; K matches the JPG)",
        422,
        "UNSUPPORTED_CAPTURE_PROFILE",
    )
    require(integer(meta["frameCount"]) == len(poses), 422, "INVALID_CAPTURE_METADATA")
    capture_id = meta["captureId"]
    require(isinstance(capture_id, str), 422, "INVALID_CAPTURE_METADATA")
    import uuid

    uuid.UUID(capture_id)
    created_at = stamp(epoch(meta["createdAt"]))
    ids = [integer(p["frameId"]) for p in poses]
    require(len(set(ids)) == len(ids), 422, "DUPLICATE_ID")
    raw_room = prepared.json("roomplan.json", required=False)
    structure = prepared.json("roomplan_structure.json", required=False)
    if raw_room is not None:
        require(
            isinstance(raw_room, dict)
            and all(isinstance(raw_room.get(k), list) for k in ("walls", "doors", "windows", "objects")),
            422,
            "INVALID_ROOMPLAN_JSON",
        )
    floor = number(
        structure["roomGeometry"]["floorHeight"]
        if structure is not None
        else (0 if meta.get("floorHeightM") is None else meta["floorHeightM"])
    )
    world = world_transform(matrix(poses[0]["T_wc"]), floor)
    room_to_ar = matrix(meta.get("roomPlanToWorld", [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]))
    room_world = multiply(world, room_to_ar)
    if structure is not None or raw_room is not None:
        require(
            not any(abs(room_to_ar[i] - v) > 1e-6 for i, v in zip((1, 4, 6, 9, 5), (0, 0, 0, 0, 1))),
            422,
            "INVALID_CAPTURE_ORIGIN",
            "RoomPlan alignment must preserve gravity.",
        )
        # A different raw RoomPlan frame changes the floor elevation in AR world.
        floor_ar = transform_point(room_to_ar, dict(x=0, y=floor, z=0))["y"]
        world = world_transform(matrix(poses[0]["T_wc"]), floor_ar)
        room_world = multiply(world, room_to_ar)
    manifest = dict(
        schemaVersion="1.0",
        platform="ARKIT",
        coordinateSystem="RH_Y_UP_Z_BACK",
        unit="METER",
        matrixLayout="COLUMN_MAJOR",
        captureStartedAt=created_at,
        frames=[],
        captureToWorld=world,
        roomPlanToWorld=room_world,
    )
    frame_names = {}
    for name in prepared.entries:
        parts = name.split("/")
        if len(parts) == 2 and parts[0] in ("frames", "depth", "confidence"):
            suffix = Path(name).suffix.lower()
            stem = Path(name).stem
            require(
                stem.isascii()
                and stem.isdigit()
                and suffix in ((".jpg", ".jpeg", ".png") if parts[0] == "frames" else (".png",)),
                422,
                "INVALID_CAPTURE_FILE_NAME",
            )
            key = (parts[0], int(stem))
            require(key not in frame_names, 422, "DUPLICATE_ID")
            frame_names[key] = name
    require(
        {i for folder, i in frame_names if folder == "frames"} == set(ids), 422, "MANIFEST_IMAGE_MISMATCH"
    )
    depth_ids = {i for folder, i in frame_names if folder == "depth"}
    confidence_ids = {i for folder, i in frame_names if folder == "confidence"}
    require(
        depth_ids in (set(), set(ids))
        and confidence_ids in (set(), set(ids))
        and (not confidence_ids or depth_ids),
        422,
        "INVALID_DEPTH_REFERENCE",
    )
    if depth_ids:
        depth_meta = prepared.json("depth.json")
        require(
            isinstance(depth_meta, dict)
            and depth_meta["captureId"] == capture_id
            and depth_meta["frameCount"] == len(poses)
            and depth_meta["depthSize"] == meta["depthSize"]
            and depth_meta["colorSize"] == meta["colorSize"],
            422,
            "INVALID_DEPTH_REFERENCE",
        )
        require(
            isinstance(depth_meta.get("frames"), list)
            and len(depth_meta["frames"]) == len(poses)
            and {integer(p["frameId"]) for p in depth_meta["frames"]} == set(ids),
            422,
            "INVALID_DEPTH_REFERENCE",
        )
        require(
            meta.get("depthUnit") == "uint16 PNG, millimeters, 0 = invalid",
            422,
            "UNSUPPORTED_CAPTURE_PROFILE",
        )
        w, h = meta["depthSize"]
        integer(w), integer(h)
    previous = None
    for p in poses:
        fid, ts = integer(p["frameId"]), number(p["ts"])
        require(previous is None or ts > previous, 422, "INVALID_FRAME_ORDER")
        previous = ts
        require(p["tracking"] == "normal", 422, "INVALID_TRACKING_STATE")
        require([p["width"], p["height"]] == meta["colorSize"], 422, "IMAGE_DIMENSION_MISMATCH")
        f = dict(
            imageClientFileId=prepared.add(frame_names[("frames", fid)], "IMAGE"),
            timestampUs=round((ts - number(poses[0]["ts"])) * 1_000_000),
            widthPx=p["width"],
            heightPx=p["height"],
            intrinsics=p["K"],
            cameraToWorld=multiply(world, matrix(p["T_wc"])),
            trackingState="NORMAL",
        )
        if depth_ids and room:
            f.update(
                depthClientFileId=prepared.add(frame_names[("depth", fid)], "DEPTH"),
                depthWidthPx=w,
                depthHeightPx=h,
            )
        if confidence_ids and room:
            f["confidenceClientFileId"] = prepared.add(frame_names[("confidence", fid)], "CONFIDENCE")
        manifest["frames"].append(f)
    prepared.add("camera.json", "CAMERA_MANIFEST", dumps(manifest).encode())
    if raw_room is not None and room:
        prepared.add("roomplan.json", "ROOMPLAN", prepared.archive.read(prepared.entries["roomplan.json"]))
    if room and (structure is not None or raw_room is not None):
        normalized = normalize_structure(structure, raw_room, room_world)
        prepared.add("roomplan_structure.json", "ROOMPLAN_STRUCTURE", dumps(normalized).encode())
    body = dict(
        purpose="ROOM_CAPTURE" if room else "FURNITURE_CAPTURE",
        targetType=target_type,
        targetId=uid(),
        files=prepared.files,
    )
    prepared.contract.validate("UploadSessionCreate", body)
    classify_files(body)
    verify_inputs(prepared, {}, [dict(f, format=input_format(f)) for f in prepared.files])


@contextmanager
def prepare_capture_zip(path, staging, contract, target_type="LISTING"):
    with TemporaryDirectory(prefix="capture-zip-", dir=staging) as temp:
        try:
            with zipfile.ZipFile(path) as archive:
                prepared = PreparedCapture(archive, archive_entries(archive), Path(temp), contract)
                prepare(prepared, target_type)
        except Problem:
            raise
        except (
            zipfile.BadZipFile,
            KeyError,
            IndexError,
            TypeError,
            ValueError,
            UnicodeError,
            RecursionError,
            EOFError,
            RuntimeError,
            NotImplementedError,
        ) as exc:
            raise Problem(422, "INVALID_CAPTURE_ZIP", "Invalid or incomplete mobile capture ZIP.") from exc
        yield prepared
