"""Derive room polygons and preserve the mobile RoomPlan presentation."""

import math
import numpy as np
from shapely.geometry import LineString, MultiPoint, Point
from shapely.geometry.polygon import orient
from shapely.ops import polygonize, snap, unary_union
from .coordinates import dimensions, identifier, vector


def nearest_room(position, rooms, polygons):
    point = Point(float(position["x"]), float(position["z"]))
    return rooms[min(range(len(rooms)), key=lambda n: polygons[n].distance(point))]["roomId"]


def room_polygons(geometry):
    endpoints = [point for wall in geometry["walls"] for point in (wall["from"], wall["to"])]
    if len(endpoints) < 6:
        raise ValueError("RoomPlan walls are required for the room contract")
    lines = unary_union([LineString([wall["from"], wall["to"]]) for wall in geometry["walls"]])
    lines = snap(lines, MultiPoint(endpoints), 0.05)
    polygons = [polygon for polygon in polygonize(unary_union(lines)) if polygon.area >= 0.01]
    approximate = not polygons
    if approximate:
        polygons = [MultiPoint(endpoints).convex_hull]
    return sorted(polygons, key=lambda polygon: (polygon.bounds, polygon.area))[:30], approximate


def room_record(index, polygon, capture, floor, height):
    polygon = orient(polygon, sign=-1)
    x0, z0, x1, z1 = polygon.bounds
    return {
        "roomId": identifier(index, capture + ":room"),
        "name": "Room " + str(index + 1),
        "floorY": floor,
        "ceilingY": floor + height,
        "floorPolygon": list(map(list, polygon.exterior.coords[:-1])),
        "areaM2": float(polygon.area),
        "dimensions": dimensions([x1 - x0, height, z1 - z0]),
    }


def rooms_of(space):
    geometry = space["roomGeometry"]
    floor, height = float(geometry["floorHeight"]), float(geometry["ceilingHeight"])
    if not math.isfinite(floor + height) or height <= 0:
        raise ValueError("RoomPlan floor and ceiling are required for this API")
    polygons, approximate = room_polygons(geometry)
    warnings = [
        "PR2_MESH_SAMPLED_AS_COLOURED_POINTS",
        "OBJECT_COLLISION_BOUNDING_BOX",
        "DOOR_SWEEP_UNKNOWN",
        "OBJECT_CONFIDENCE_NOT_PROVIDED_DEFAULT_0_5",
    ]
    if approximate:
        warnings.append("ROOM_POLYGON_CONVEX_HULL_APPROXIMATION")
    rooms = [
        room_record(index, polygon, space["capture"], floor, height)
        for index, polygon in enumerate(polygons)
    ]
    bounds = unary_union(polygons).bounds
    return (
        rooms,
        polygons,
        dimensions([bounds[2] - bounds[0], height, bounds[3] - bounds[1]]),
        warnings,
    )


def pane(source, kind):
    return {
        "id": str(source.get("objectId", "")),
        "type": kind,
        "position": source["position"],
        "rotation": {"yaw": math.degrees(float(source["rotation"]["yaw"]))},
        "dimensions": source["dimensions"],
    }


def presentation_bounds(source):
    coords = np.asarray([point for wall in source["walls"] for point in (wall["from"], wall["to"])])
    floor, ceiling = float(source["floorHeight"]), float(source["ceilingHeight"])
    return {
        "min": vector([coords[:, 0].min(), floor, coords[:, 1].min()]),
        "max": vector([coords[:, 0].max(), floor + ceiling, coords[:, 1].max()]),
    }


def presentation(space):
    source = space["roomGeometry"]
    walls = [
        {
            "id": str(wall.get("objectId", index)),
            "from": wall["from"],
            "to": wall["to"],
            "height": float(wall["dimensions"]["height"]),
        }
        for index, wall in enumerate(source["walls"])
    ]
    geometry = {
        "floorHeight": float(source["floorHeight"]),
        "ceilingHeight": float(source["ceilingHeight"]),
        "walls": walls,
        "doors": [pane(item, "door") for item in source.get("doors", [])],
        "windows": [pane(item, "window") for item in source.get("windows", [])],
        "boundingBox": presentation_bounds(source),
    }
    glass = [
        {**item, "rotation": {"yaw": math.degrees(float(item["rotation"]["yaw"]))}}
        for item in space.get("glass", [])
    ]
    return geometry, glass
