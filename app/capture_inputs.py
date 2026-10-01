"""RGB, PNG16 depth, confidence and RoomPlan input validation."""

from io import BytesIO
from PIL import Image
from .core import Problem, require, strict_json
from .geometry import image_info, quaternion, unique, validate_manifest

ROLE_MIME = {
    "IMAGE": {"image/jpeg", "image/png"},
    "CAMERA_MANIFEST": {"application/json"},
    "PHOTO": {"image/jpeg", "image/png"},
    "DEPTH": {"image/png"},
    "CONFIDENCE": {"image/png"},
    "ROOMPLAN": {"application/json"},
    "ROOMPLAN_STRUCTURE": {"application/json"},
    "LISTING_AGENT_DOCUMENT": {"image/jpeg", "image/png"},
}
FORMAT = {
    "CAMERA_MANIFEST": "CAMERA_JSON_V1",
    "DEPTH": "DEPTH_PNG_U16_MM_V1",
    "CONFIDENCE": "CONFIDENCE_PNG_U8_V1",
    "ROOMPLAN": "ROOMPLAN_JSON",
    "ROOMPLAN_STRUCTURE": "ROOMPLAN_STRUCTURE_V1",
}


def input_format(f):
    return FORMAT.get(
        f.get("role"),
        {"image/jpeg": "JPEG", "image/png": "PNG", "application/json": "CAMERA_JSON_V1"}.get(
            f["contentType"]
        ),
    )


def classify_files(body):
    purpose = body["purpose"]
    expected = {
        "ROOM_CAPTURE": "LISTING",
        "FURNITURE_CAPTURE": "FURNITURE",
        "LISTING_PHOTO": "LISTING",
        "LISTING_AGENT_DOCUMENT": "USER",
    }
    require(expected[purpose] == body["targetType"], 422, "INVALID_UPLOAD_PURPOSE")
    unique(body["files"], "clientFileId")
    files = []
    for original in body["files"]:
        f = dict(original)
        f.setdefault(
            "role",
            "PHOTO"
            if purpose == "LISTING_PHOTO"
            else "LISTING_AGENT_DOCUMENT"
            if purpose == "LISTING_AGENT_DOCUMENT"
            else "CAMERA_MANIFEST"
            if f["contentType"] == "application/json"
            else "IMAGE",
        )
        allowed = (
            {"PHOTO"}
            if purpose == "LISTING_PHOTO"
            else {"LISTING_AGENT_DOCUMENT"}
            if purpose == "LISTING_AGENT_DOCUMENT"
            else {"IMAGE", "CAMERA_MANIFEST"}
            if purpose == "FURNITURE_CAPTURE"
            else set(ROLE_MIME) - {"PHOTO", "LISTING_AGENT_DOCUMENT"}
        )
        require(f["role"] in allowed and f["contentType"] in ROLE_MIME[f["role"]], 422, "INVALID_UPLOAD_ROLE")
        require(
            f["byteSize"] <= (10 << 20 if f["contentType"] == "application/json" else 20 << 20),
            422,
            "UPLOAD_LIMIT_EXCEEDED",
        )
        files.append(f)

    def count(role):
        return sum(f["role"] == role for f in files)

    if purpose == "LISTING_PHOTO":
        require(1 <= len(files) <= 20, 422, "INVALID_CAPTURE_FILES")
    elif purpose == "LISTING_AGENT_DOCUMENT":
        require(len(files) == 1, 422, "INVALID_CAPTURE_FILES")
    else:
        room = purpose == "ROOM_CAPTURE"
        require(
            (30 if room else 12) <= count("IMAGE") <= (1000 if room else 300)
            and count("CAMERA_MANIFEST") == 1,
            422,
            "INVALID_CAPTURE_FILES",
        )
        for role in ("DEPTH", "CONFIDENCE"):
            require(count(role) in (0, count("IMAGE")), 422, "INVALID_CAPTURE_FILES")
        require(not count("CONFIDENCE") or count("DEPTH"), 422, "INVALID_CAPTURE_FILES")
        require(count("ROOMPLAN") <= 1 and count("ROOMPLAN_STRUCTURE") <= 1, 422, "INVALID_CAPTURE_FILES")
    require(sum(f["byteSize"] for f in files) <= 5 << 30, 422, "UPLOAD_LIMIT_EXCEEDED")
    return files


def verify_inputs(svc, session, files):
    sizes = {}
    manifest = None
    byclient = {f["clientFileId"]: f for f in files}
    for f in files:
        raw = svc.read_file(f)
        role = f.get("role", "CAMERA_MANIFEST" if f["format"] == "CAMERA_JSON_V1" else "IMAGE")
        if role in ("IMAGE", "PHOTO", "LISTING_AGENT_DOCUMENT"):
            sizes[f["clientFileId"]] = image_info(raw, f["contentType"])
        elif role in ("DEPTH", "CONFIDENCE"):
            try:
                require(
                    raw[:8] == b"\x89PNG\r\n\x1a\n"
                    and raw[24] == (16 if role == "DEPTH" else 8)
                    and raw[25] == 0,
                    422,
                    "INVALID_DEPTH_ENCODING",
                )
                with Image.open(BytesIO(raw)) as im:
                    require(
                        im.format == "PNG" and 1 <= im.width <= 4096 and 1 <= im.height <= 4096,
                        422,
                        "INVALID_DEPTH_ENCODING",
                    )
                    im.load()
                    sizes[f["clientFileId"]] = im.size
                    if role == "CONFIDENCE":
                        require(im.mode == "L" and im.getextrema()[1] <= 2, 422, "INVALID_CONFIDENCE_VALUES")
            except Problem:
                raise
            except Exception as e:
                raise Problem(422, "INVALID_DEPTH_ENCODING") from e
        else:
            try:
                v = strict_json(raw)
            except (ValueError, UnicodeError, RecursionError) as e:
                raise Problem(422, "INVALID_CAPTURE_JSON") from e
            require(isinstance(v, dict), 422, "INVALID_CAPTURE_JSON")
            if role == "CAMERA_MANIFEST":
                svc.contract.validate("CameraManifest", v)
                manifest = v
            elif role == "ROOMPLAN":
                require(
                    all(isinstance(v.get(k), list) for k in ("walls", "doors", "windows", "objects")),
                    422,
                    "INVALID_ROOMPLAN_JSON",
                )
            elif role == "ROOMPLAN_STRUCTURE":
                svc.contract.validate("RoomPlanStructure", v)
                unique(v["surfaces"], "id")
                unique(v["objects"], "id")
                for item in v["surfaces"] + v["objects"]:
                    quaternion(item["transform"]["rotation"])
    if manifest:
        images = [f for f in files if f.get("role", "IMAGE") == "IMAGE"]
        validate_manifest(manifest, images, sizes)
        used = set()
        for frame in manifest["frames"]:
            present = [k in frame for k in ("depthClientFileId", "depthWidthPx", "depthHeightPx")]
            require(not any(present) or all(present), 422, "INVALID_DEPTH_REFERENCE")
            require(
                "confidenceClientFileId" not in frame or all(present), 422, "INVALID_CONFIDENCE_REFERENCE"
            )
            if not all(present):
                continue
            w, h = frame["depthWidthPx"], frame["depthHeightPx"]
            for key, role in [("depthClientFileId", "DEPTH"), ("confidenceClientFileId", "CONFIDENCE")]:
                if key not in frame:
                    continue
                id = frame[key]
                require(
                    id not in used
                    and id in byclient
                    and byclient[id].get("role") == role
                    and sizes[id] == (w, h),
                    422,
                    "INVALID_DEPTH_REFERENCE",
                )
                used.add(id)
        require(
            used == {f["clientFileId"] for f in files if f.get("role") in ("DEPTH", "CONFIDENCE")},
            422,
            "UNREFERENCED_DEPTH_FILE",
        )
        if "roomPlanToWorld" in manifest:
            from .geometry import rigid_matrix

            rigid_matrix(manifest["roomPlanToWorld"])
    return manifest
