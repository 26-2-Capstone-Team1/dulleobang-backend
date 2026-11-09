"""Stable IDs, metric values and ARKit object transforms."""

import math
import uuid
import numpy as np


def identifier(value, namespace):
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError):
        return str(uuid.uuid5(uuid.NAMESPACE_URL, "dulleobang:" + namespace + ":" + str(value)))


def vector(values):
    return dict(zip("xyz", (float(v) for v in values)))


def dimensions(values):
    values = np.asarray(values, dtype=float)
    if not np.isfinite(values).all() or np.any(values <= 0) or np.any(values > 1000):
        raise ValueError("Non-finite or invalid metric dimensions")
    return dict(zip(("width", "height", "depth"), map(float, values)))


def rotation(yaw):
    return {"x": 0.0, "y": math.sin(yaw / 2), "z": 0.0, "w": math.cos(yaw / 2)}


def yaw_of(value):
    r = value["transform"]["rotation"]
    return math.atan2(2 * (r["w"] * r["y"] + r["x"] * r["z"]), 1 - 2 * (r["y"] ** 2 + r["z"] ** 2))


def transform_object(value):
    yaw = yaw_of(value)
    turn = np.array(
        [
            [math.cos(yaw), 0, math.sin(yaw), 0],
            [0, 1, 0, 0],
            [-math.sin(yaw), 0, math.cos(yaw), 0],
            [0, 0, 0, 1],
        ]
    )
    turn[:3, 3] = [value["transform"]["position"][a] for a in "xyz"]
    turn[1, 3] += value["dimensions"]["height"] / 2
    return turn
