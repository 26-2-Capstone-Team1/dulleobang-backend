"""Deterministically sample coloured mesh surfaces and write metric PLY files."""

import numpy as np
import trimesh

POINT_FORMAT = "POINT_CLOUD_PLY_V1"


def sample_mesh(mesh, amount, seed):
    if hasattr(mesh.visual, "uv") and mesh.visual.uv is None:
        colour = getattr(getattr(mesh.visual, "material", None), "main_color", [160, 160, 160, 255])
        mesh.visual = trimesh.visual.ColorVisuals(mesh=mesh, face_colors=colour)
    if hasattr(mesh.visual, "material") and hasattr(mesh.visual.material, "to_simple"):
        mesh.visual.material = mesh.visual.material.to_simple()
    xyz, _, rgb = trimesh.sample.sample_surface(mesh, int(amount), sample_color=True, seed=seed)
    return xyz, np.asarray(rgb)[:, :3]


def sample_surfaces(meshes, count):
    count = max(len(meshes), min(int(count), 2_000_000))
    areas = np.array([mesh.area for mesh in meshes], dtype=float)
    allocated = np.maximum(1, np.floor(count * areas / areas.sum()).astype(int))
    allocated[areas.argmax()] += count - int(allocated.sum())
    samples = [
        sample_mesh(mesh, amount, 42 + index)
        for index, (mesh, amount) in enumerate(zip(meshes, allocated))
    ]
    xyz = np.concatenate([sample[0] for sample in samples]).astype("<f4")
    rgb = np.concatenate([sample[1] for sample in samples]).astype("u1")
    return xyz, rgb


def ply_header(count):
    return (
        f"ply\nformat binary_little_endian 1.0\nelement vertex {count}\n"
        + "".join("property float " + axis + "\n" for axis in "xyz")
        + "".join("property uchar " + axis + "\n" for axis in ("red", "green", "blue"))
        + "end_header\n"
    )


def write_points(path, meshes, count=500_000):
    xyz, rgb = sample_surfaces(meshes, count)
    if not np.isfinite(xyz).all() or np.max(np.abs(xyz)) > 1000:
        raise ValueError("Surface points outside metric bounds")
    rows = np.empty(len(xyz), dtype=[("xyz", "<f4", (3,)), ("rgb", "u1", (3,))])
    rows["xyz"], rows["rgb"] = xyz, rgb
    path.write_bytes(ply_header(len(rows)).encode("ascii") + rows.tobytes())
