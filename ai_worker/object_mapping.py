"""Map exported objects, openings and room metadata to the backend contract."""

from .coordinates import dimensions, identifier, rotation, vector
from .room_geometry import nearest_room, presentation

CATEGORIES = {
    "BED",
    "SOFA",
    "DESK",
    "TABLE",
    "CHAIR",
    "WARDROBE",
    "SHELF",
    "REFRIGERATOR",
    "WASHING_MACHINE",
    "AIR_CONDITIONER",
    "TV",
    "OTHER",
}


def canonical_object(source, capture, rooms, polygons):
    dims = dimensions([source["dimensions"][k] for k in ("width", "height", "depth")])
    w, h, d = dims["width"], dims["height"], dims["depth"]
    pos = {a: float(source["position"][a]) for a in "xyz"}
    pos["y"] -= h / 2
    category = str(source.get("category", "OTHER")).upper()
    category = {"STORAGE": "SHELF", "TELEVISION": "TV", "STORAGE_CABINET": "WARDROBE"}.get(
        category, category
    )
    if category not in CATEGORIES:
        category = "OTHER"
    oid = identifier(source["objectId"], capture + ":object")
    return {
        "objectId": oid,
        "roomId": nearest_room(pos, rooms, polygons),
        "category": category,
        "transform": {"position": pos, "rotation": rotation(float(source["rotation"]["yaw"]))},
        "dimensions": dims,
        "boundingBox": {"min": vector([-w / 2, 0, -d / 2]), "max": vector([w / 2, h, d / 2])},
        "ownershipType": "UNKNOWN",
        "removable": bool(source.get("removable") and source.get("meshUri")),
        "confidence": 0.5,
        "collisionNode": "object_" + oid,
    }


def opening(source, kind, capture, rooms, polygons):
    pos = {a: float(source["position"][a]) for a in "xyz"}
    result = {
        kind + "Id": identifier(source.get("objectId"), capture + ":" + kind),
        "roomId": nearest_room(pos, rooms, polygons),
        "transform": {"position": pos, "rotation": rotation(float(source["rotation"]["yaw"]))},
        "width": float(source["dimensions"]["width"]),
        "height": float(source["dimensions"]["height"]),
    }
    if kind == "door":
        result.update(
            openingType="OPENING" if source.get("open") else "UNKNOWN",
            hingePosition=None,
            sweepNode=None,
        )
    else:
        result["sillHeight"] = pos["y"] - rooms[0]["floorY"] - result["height"] / 2
        if result["sillHeight"] < 0:
            raise ValueError("Window below measured floor")
    return result


def canonical_geometry(space, rooms, polygons):
    return {
        "schemaVersion": "1.0",
        "coordinateSystem": "RH_Y_UP_Z_BACK",
        "unit": "METER",
        "rooms": rooms,
        "doors": [
            opening(item, "door", space["capture"], rooms, polygons)
            for item in space["roomGeometry"].get("doors", [])
        ],
        "windows": [
            opening(item, "window", space["capture"], rooms, polygons)
            for item in space["roomGeometry"].get("windows", [])
        ],
        "structuralColliders": [
            {
                "colliderId": identifier("structure", space["capture"]),
                "roomId": rooms[0]["roomId"],
                "kind": "WALL",
                "nodeName": "room_structure",
            }
        ],
    }


def canonical_objects(space, rooms, polygons):
    objects = [
        canonical_object(item, space["capture"], rooms, polygons) for item in space["objects"]
    ]
    if len({item["objectId"] for item in objects}) != len(objects):
        raise ValueError("Duplicate exported object ID")
    return objects


def build_bundle(space, dims, geometry, objects, warnings, object_files):
    mobile, glass = presentation(space)
    return {
        "roomDimensions": dims,
        "geometry": geometry,
        "objects": objects,
        "quality": {
            "scaleSource": "AR_TRACKING",
            "estimatedScaleErrorM": None,
            "warnings": warnings,
        },
        "meshSource": {
            "roomAsset": None,
            "roomCollisionAsset": None,
            "objectAssets": [{"objectId": oid, "asset": None} for oid in object_files],
            "roomGeometry": mobile,
            "glass": glass,
        },
    }
