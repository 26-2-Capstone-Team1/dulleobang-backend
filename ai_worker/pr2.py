"""Coordinate PR #2 conversion without changing its meshes or the mobile API."""

import copy
import json
from pathlib import Path
from .artifacts import local_file, object_sources, output_files, write_result
from .object_mapping import build_bundle, canonical_geometry, canonical_objects
from .room_geometry import rooms_of


def read_export(folder):
    space = json.loads((folder / "space.json").read_text(encoding="utf-8"))
    check = space.get("selfCheck", {})
    if space.get("frame") != "ARKit world, metres, Y up" or not check.get("room", {}).get("ok"):
        raise ValueError("PR #2 ARKit frame and room self-check are required")
    if not all(row.get("ok") for row in check.get("objects", {}).values()):
        raise ValueError("PR #2 object self-check failed")
    return space


def convert_export(folder, output):
    folder, output = Path(folder).resolve(), Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    space = read_export(folder)
    rooms, polygons, dims, warnings = rooms_of(space)
    objects = canonical_objects(space, rooms, polygons)
    geometry = canonical_geometry(space, rooms, polygons)
    room = local_file(folder, space["meshRoom"]["uri"])
    collision_room = local_file(folder, space["collisionGeometryUri"])
    object_files = object_sources(folder, space, objects)
    bundle = build_bundle(space, dims, geometry, objects, warnings, object_files)
    files = output_files(output, bundle, room, collision_room, object_files)
    return write_result(output, {"type": "ROOM_RECONSTRUCTION", "bundle": bundle}, files)


def removal_bundle(data):
    bundle = copy.deepcopy(data["sourceBundle"])
    source = bundle.get("meshSource")
    if not source:
        raise ValueError("Removal requires a PR #2 mesh source")
    removed = set(data["removedObjectIds"])
    bundle["objects"] = [item for item in bundle["objects"] if item["objectId"] not in removed]
    source["objectAssets"] = [
        item for item in source["objectAssets"] if item["objectId"] not in removed
    ]
    return bundle


def clear_source_refs(source):
    source["roomAsset"] = source["roomCollisionAsset"] = None
    for item in source["objectAssets"]:
        item["asset"] = None


def convert_removal(assignment, output, downloaded):
    data = assignment["input"]
    bundle = removal_bundle(data)
    source = bundle["meshSource"]
    object_files = {
        item["objectId"]: downloaded[item["asset"]["fileId"]] for item in source["objectAssets"]
    }
    files = output_files(
        output,
        bundle,
        downloaded[source["roomAsset"]["fileId"]],
        downloaded[source["roomCollisionAsset"]["fileId"]],
        object_files,
    )
    clear_source_refs(source)
    if "INPAINTED_REGION" not in bundle["quality"]["warnings"]:
        bundle["quality"]["warnings"].append("INPAINTED_REGION")
    result = {
        "type": "ROOM_OBJECT_REMOVAL",
        "roomStateId": data["roomStateId"],
        "removedObjectIds": list(data["removedObjectIds"]),
        "bundle": bundle,
    }
    write_result(output, result, files)
    return result
