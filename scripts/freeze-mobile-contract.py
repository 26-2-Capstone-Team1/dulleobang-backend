"""Freeze the 37 mobile routes, legacy schemas and app projection shapes."""
from pathlib import Path
from tempfile import TemporaryDirectory
from inspect import getclosurevars
import copy
import json
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "legacy-baseline"))
from app.main import create_app
from app.core import Settings
from app.mobile import schemas

def obj(properties, required=()):
    return {"type": "object", "properties": properties, "required": list(required)}
def ref(name): return {"$ref": "#/components/schemas/" + name}
def array(item): return {"type": "array", "items": item}
string = {"type": "string"}
nullable = {"type": ["string", "null"]}
number = {"type": "number"}
integer = {"type": "integer"}
paths = {}
with TemporaryDirectory() as tmp:
    app = create_app(Settings(data_dir=Path(tmp), scheduler=False, rate_limits=False))
    components = copy.deepcopy(app.state.contract.spec["components"])
    model = components["schemas"]
    for name, schema in schemas(app.state.contract).items():
        model["Mobile" + name] = schema
    model["MobilePrice"] = obj({"deposit": integer, "monthly": integer}, ["deposit", "monthly"])
    model["MobileRotation"] = obj({"yaw": number}, ["yaw"])
    model["MobileRoomDimensions"] = obj({"width": number, "height": number, "depth": number, "area": number},
        ["width", "height", "depth"])
    model["MobileObject"] = obj({"objectId": string, "category": string, "position": ref("Vector3"),
        "rotation": ref("MobileRotation"), "dimensions": ref("Dimensions"),
        "ownershipType": {"type": ["string", "null"], "enum": ["PERSONAL", "RENTAL_OPTION", "UNKNOWN", None]},
        "removable": {"type": "boolean"}}, ["objectId", "category", "position", "rotation", "dimensions"])
    model["MobileAgent"] = obj({"userId": string, "name": string, "officeName": nullable, "phone": nullable,
        "isVerifiedBroker": {"type": "boolean"}}, ["userId", "name", "isVerifiedBroker"])
    listing_properties = {"listingId": string, "location": string, "dealType": {"enum": ["MONTHLY", "JEONSE"]},
        "price": ref("MobilePrice"), "maintenanceFee": {"type": ["number", "null"]},
        "latitude": number, "longitude": number, "area": number, "rentalOptions": array(string),
        "imageUris": array(string), "thumbnailUri": nullable, "agent": ref("MobileAgent"),
        "isScrapped": {"type": "boolean"}, "status": string,
        "space3DInfo": obj({"status": string, "jobId": nullable}, ["status"])}
    for name in ["roomType", "availableFrom", "livingEnvironment"]:
        listing_properties[name] = nullable
    for name in ["floor", "totalFloors", "rooms", "bathrooms", "supplyArea"]:
        listing_properties[name] = {"type": ["number", "null"]}
    model["MobileListing"] = obj(listing_properties, ["listingId", "location", "price", "dealType", "agent"])
    model["MobileFurniture"] = obj({"furnitureId": string, "name": string, "category": string, "status": string,
        "modelUri": nullable, "dimensions": {"anyOf": [ref("Dimensions"), {"type": "null"}]},
        "boundingBox": {"anyOf": [ref("BoundingBox"), {"type": "null"}]}, "collisionMeshUri": nullable},
        ["furnitureId", "name", "category", "status"])
    model["MobileWall"] = obj({"id": string, "from": array(number), "to": array(number), "height": number},
        ["id", "from", "to", "height"])
    model["MobileOpening"] = obj({"id": string, "type": string, "position": ref("Vector3"),
        "rotation": ref("MobileRotation"), "dimensions": ref("Dimensions")},
        ["id", "type", "position", "rotation", "dimensions"])
    model["MobileGeometry"] = obj({"floorHeight": number, "ceilingHeight": number,
        "walls": array(ref("MobileWall")), "doors": array(ref("MobileOpening")),
        "windows": array(ref("MobileOpening")), "boundingBox": ref("BoundingBox")})
    space_props = {"spaceVersion": integer, "objectsVersion": integer, "3dgsUri": string,
        "collisionGeometryUri": string, "roomGeometry": ref("MobileGeometry"),
        "roomDimensions": ref("MobileRoomDimensions"), "objects": array(ref("MobileObject"))}
    model["MobileSpace"] = obj(space_props, ["spaceVersion", "objectsVersion", "objects"])
    model["MobileRoomState"] = obj({"roomStateId": string, "listingId": string, "createdBy": string,
        "status": string, "jobId": string, "sourceSpaceVersion": integer, "sourceObjectsVersion": integer,
        "createdAt": string, "removedObjectIds": array(string), "sceneUri": nullable,
        "collisionGeometryUri": nullable, "roomGeometry": ref("MobileGeometry"),
        "remainingObjects": array(ref("MobileObject"))}, ["roomStateId", "status", "removedObjectIds"])
    model["MobilePlacement"] = obj({"furnitureId": string, "position": ref("Vector3"),
        "rotation": ref("MobileRotation")}, ["furnitureId", "position", "rotation"])
    model["MobilePlacements"] = obj({"placementSetId": string, "savedAt": string,
        "placements": array(ref("MobilePlacement"))}, ["placementSetId", "savedAt", "placements"])
    model["MobileMessage"] = obj({"messageId": string, "senderId": string, "senderName": string,
        "message": string, "sentAt": string}, ["messageId", "senderId", "message", "sentAt"])
    model["MobileChat"] = obj({"chatId": string, "listing": ref("MobileListing"), "counterpartName": string,
        "officeName": nullable, "lastMessage": nullable, "lastMessageAt": nullable, "unreadCount": integer,
        "messages": array(ref("MobileMessage"))}, ["chatId", "listing", "counterpartName"])
    model["MobileObjects"] = obj({"objectsVersion": integer, "objects": array(ref("MobileObject"))},
        ["objectsVersion", "objects"])
    model["MobileJob"] = copy.deepcopy(model["Job"])
    model["MobileJob"]["properties"].update({"progress": number, "message": nullable})
    response_overrides = {
        "createListing": "MobileListing", "getListing": "MobileListing", "searchListings": "MobileListing",
        "getMyListings": "MobileListing", "getScraps": "MobileListing", "uploadListingImages": "MobileListing",
        "getFurniture": "MobileFurniture", "createFurniture": "MobileFurniture", "getMyFurniture": "MobileFurniture",
        "getSpace": "MobileSpace", "getObjects": "MobileObjects", "classifyObjects": "MobileObjects",
        "getRoomState": "MobileRoomState", "getRoomStates": "MobileRoomState",
        "getPlacements": "MobilePlacements", "savePlacements": "MobilePlacements",
        "getChat": "MobileChat", "getChats": "MobileChat", "createChat": "MobileChat",
        "getJob": "MobileJob", "submitFurnitureCaptureZip": "CaptureAccepted"}
    list_ops = {"searchListings", "getMyListings", "getScraps", "getMyFurniture", "getRoomStates", "getChats"}
    for route in app.routes:
        path = getattr(route, "path", "")
        if not path.startswith("/api/") or path.startswith("/api/v1/"): continue
        operation = getclosurevars(route.endpoint).nonlocals["operation"]
        oid = operation["oid"]
        source = next((o for o in app.state.contract.ops if o["oid"] == oid), {})
        entry = {"operationId": oid, "x-implementationPhase": "P3-P7", "x-currentStatus": "deferred",
            "parameters": copy.deepcopy(operation.get("params", [])), "responses": {}}
        kind = getclosurevars(route.endpoint).nonlocals.get("kind")
        if kind == "zip":
            entry["requestBody"] = {"required": True, "content": {"application/zip": {"schema": {"type": "string", "format": "binary"}}}}
        elif kind == "multipart":
            props = {"images": array({"type": "string", "format": "binary"})} if oid == "uploadListingImages" else {
                **{k: string for k in ["name", "licenseNumber", "officeName", "documentName"]},
                "document": {"type": "string", "format": "binary"}}
            entry["requestBody"] = {"required": True, "content": {"multipart/form-data": {"schema": obj(props, props.keys())}}}
        elif "Mobile" + oid in model or operation.get("source_req"):
            name = "Mobile" + oid if "Mobile" + oid in model else operation["source_req"]
            entry["requestBody"] = {"required": True, "content": {"application/json": {"schema": ref(name)}}}
        result_name = response_overrides.get(oid, source.get("res"))
        status = str(operation["status"])
        response = {"description": "Legacy success contract; business implementation deferred until its assigned phase."}
        if status != "204" and result_name:
            schema = ref(result_name)
            if oid in list_ops: schema = obj({"items": array(schema), "nextCursor": nullable}, ["items"])
            response["content"] = {"application/json": {"schema": schema}}
        entry["responses"][status] = response
        for code in ["400", "401", "403", "404", "409", "412", "422", "428", "429", "501"]:
            entry["responses"][code] = {"description": "See legacy error code contract; 501 only describes the P0-P2 migration state."}
        for method in route.methods:
            if method not in {"HEAD", "OPTIONS"}: paths.setdefault(path, {})[method.lower()] = entry
doc = {"openapi": "3.1.0", "info": {"title": "Dulleobang mobile compatibility contract", "version": "1.1.1-frozen"},
    "paths": paths, "components": components}
assert sum(len(v) for v in paths.values()) == 37
out = ROOT / "src/main/resources/contract/mobile-openapi.json"
out.write_text(json.dumps(doc, ensure_ascii=False, indent=2), encoding="utf-8")
print("Frozen 37 mobile API declarations and app projection schemas")
