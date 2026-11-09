"""Resolve export paths and persist immutable backend artifacts."""

import json
import os
import shutil
from .mesh_assets import source_parts, write_collision
from .point_cloud import POINT_FORMAT, write_points


def local_file(folder, name):
    if not isinstance(name, str) or "\\" in name or ":" in name:
        raise ValueError("Export file must be a local relative path")
    path = (folder / name).resolve()
    if not path.is_relative_to(folder.resolve()) or not path.is_file():
        raise ValueError("Export file is outside its directory or missing")
    return path


def add_source(output, files, key, path):
    target = output / "sources" / (key.replace(":", "_") + ".glb")
    target.parent.mkdir(exist_ok=True)
    try:
        os.link(path, target)
    except OSError:
        shutil.copyfile(path, target)
    files.append(
        {
            "key": key,
            "role": "SOURCE_MESH",
            "path": str(target.relative_to(output)),
            "format": "GLB_V2",
        }
    )


def output_files(output, bundle, room, collision_room, object_files):
    files = [
        {"key": r, "role": r, "path": name, "format": fmt}
        for r, name, fmt in [
            ("SCENE", "scene.ply", POINT_FORMAT),
            ("GEOMETRY", "geometry.json", "ROOM_GEOMETRY_V1"),
            ("COLLISION", "collision.glb", "GLB_V2"),
        ]
    ]
    meshes = source_parts(room, bundle["objects"], object_files)
    write_points(output / "scene.ply", meshes, int(os.environ.get("BACKEND_POINT_COUNT", "500000")))
    write_collision(output / "collision.glb", collision_room, bundle["objects"])
    (output / "geometry.json").write_text(
        json.dumps(bundle["geometry"], allow_nan=False), encoding="utf-8"
    )
    add_source(output, files, "room", room)
    add_source(output, files, "room-collision", collision_room)
    for oid, path in object_files.items():
        add_source(output, files, "object:" + oid, path)
    return files


def object_sources(folder, space, objects):
    return {
        canonical["objectId"]: local_file(folder, raw["meshUri"])
        for raw, canonical in zip(space["objects"], objects)
        if canonical["removable"]
    }


def write_result(output, result, files):
    document = {"result": result, "files": files}
    (output / "result.json").write_text(json.dumps(document, allow_nan=False), encoding="utf-8")
    return document
