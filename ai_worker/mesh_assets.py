"""Read mesh scenes and assemble transformed surfaces and collision meshes."""

import numpy as np
import trimesh
from .coordinates import transform_object


def parts(path):
    scene = trimesh.load_scene(path, process=False)
    result = []
    for node in sorted(scene.graph.nodes_geometry):
        matrix, name = scene.graph[node]
        mesh = scene.geometry[name].copy()
        mesh.apply_transform(matrix)
        if not np.isfinite(mesh.vertices).all():
            raise ValueError("Non-finite mesh vertices")
        if len(mesh.faces) and mesh.area > 0:
            result.append(mesh)
    if not result:
        raise ValueError("Empty mesh export")
    return result


def source_parts(room, objects, object_files):
    meshes = parts(room)
    for obj in objects:
        if obj["objectId"] in object_files:
            for mesh in parts(object_files[obj["objectId"]]):
                mesh.apply_transform(transform_object(obj))
                meshes.append(mesh)
    return meshes


def write_collision(path, room, objects):
    scene = trimesh.Scene()
    room_mesh = trimesh.util.concatenate(parts(room))
    room_mesh = trimesh.Trimesh(vertices=room_mesh.vertices, faces=room_mesh.faces, process=False)
    if len(room_mesh.faces) + len(objects) * 12 > 200_000:
        raise ValueError("Use PR #2 export --collision-faces 50000")
    scene.add_geometry(room_mesh, node_name="room_structure", geom_name="room_structure")
    for obj in objects:
        d = obj["dimensions"]
        box = trimesh.creation.box(extents=[d["width"], d["height"], d["depth"]])
        box.apply_transform(transform_object(obj))
        scene.add_geometry(box, node_name=obj["collisionNode"], geom_name=obj["collisionNode"])
    path.write_bytes(scene.export(file_type="glb"))
