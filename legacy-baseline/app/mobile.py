"""Swift app API at /api; canonical worker/API contracts remain at /api/v1."""

import copy
import hashlib
import math
import os

from fastapi import Request
from jsonschema import Draft202012Validator, FormatChecker
from starlette.datastructures import UploadFile
from starlette.formparsers import MultiPartException, MultiPartParser
from starlette.responses import JSONResponse, Response
from starlette.concurrency import run_in_threadpool

from .core import Problem, bump, require, uid

OPTIONS = dict(
    parking="PARKING",
    pet="PET_ALLOWED",
    elevator="ELEVATOR",
    aircon="AIR_CONDITIONER",
    fridge="REFRIGERATOR",
    washer="WASHING_MACHINE",
    bed="BED",
    desk="DESK",
    closet="WARDROBE",
    induction="INDUCTION",
)
FURNITURE_CATEGORIES = dict(
    bed="BED",
    desk="DESK",
    sofa="SOFA",
    closet="WARDROBE",
    storage="SHELF",
    chair="CHAIR",
    other="OTHER",
)
APP_FURNITURE_CATEGORIES = {v: k for k, v in FURNITURE_CATEGORIES.items()}
REASONS = dict(
    zip(
        [
            "허위 매물이에요",
            "실제 공간과 사진·3D 가 달라요",
            "가격 정보가 달라요",
            "이미 거래된 매물이에요",
            "기타",
        ],
        ["FAKE_LISTING", "SPACE_MISMATCH", "WRONG_PRICE", "ALREADY_CONTRACTED", "OTHER"],
    )
)


def object_schema(properties, required=None):
    return dict(
        type="object",
        properties=properties,
        required=list(properties) if required is None else required,
        additionalProperties=False,
    )


def schemas(contract):
    source = contract.spec["components"]["schemas"]
    listing = source["ListingCreate"]["properties"]
    furniture = copy.deepcopy(source["FurnitureCreate"])
    furniture["properties"]["category"]["enum"] += list(FURNITURE_CATEGORIES)
    money = dict(type="integer", minimum=0, maximum=100_000_000)
    number = dict(type="number")
    vec = copy.deepcopy(source["Vector3"])
    placement = object_schema(
        dict(
            furnitureId=dict(type="string", format="uuid"),
            position=vec,
            rotation=object_schema(dict(yaw=number)),
            isPlaceable=dict(type=["boolean", "null"]),
            collisions=dict(
                type=["array", "null"],
                items=object_schema(
                    dict(with_=dict(type="string"), type=dict(type="string"), overlap=number)
                ),
            ),
        ),
        ["furnitureId", "position", "rotation"],
    )
    # Swift's CollisionInfo uses the JSON key "with".
    collision = placement["properties"]["collisions"]["items"]
    collision["properties"]["with"] = collision["properties"].pop("with_")
    collision["required"][0] = "with"
    return {
        "createFurniture": furniture,
        "createListing": object_schema(
            dict(
                location=dict(type="string", minLength=1, maxLength=200),
                dealType=source["Price"]["properties"]["leaseType"],
                price=object_schema(dict(deposit=money, monthly=money)),
                maintenanceFee=dict(type=["integer", "null"], minimum=0, maximum=100_000_000),
                roomType=listing["roomType"],
                floor=listing["floor"],
                totalFloors=listing["totalFloors"],
                area=listing["exclusiveAreaM2"],
                supplyArea=listing["supplyAreaM2"],
                rooms=listing["roomCount"],
                bathrooms=listing["bathroomCount"],
                availableFrom=listing["availableFrom"],
                rentalOptions=dict(
                    type="array", items=dict(type="string", enum=list(OPTIONS)), uniqueItems=True
                ),
                latitude=source["Location"]["properties"]["latitude"],
                longitude=source["Location"]["properties"]["longitude"],
            ),
            ["location", "dealType", "price", "roomType", "area", "availableFrom", "rentalOptions"],
        ),
        "savePlacements": object_schema(dict(placements=dict(type="array", maxItems=100, items=placement))),
        "classifyObjects": object_schema(dict(objects=source["ObjectsPatch"]["properties"]["objects"])),
        "createRoomState": object_schema(
            copy.deepcopy(source["RoomStateCreate"]["properties"]), ["removedObjectIds"]
        ),
        "sendChatMessage": object_schema(dict(message=source["ChatMessageCreate"]["properties"]["text"])),
        "createListingReport": object_schema(
            dict(
                reason=dict(type="string", enum=list(REASONS) + list(REASONS.values())),
                detail=dict(type=["string", "null"], maxLength=1000),
            ),
            ["reason"],
        ),
    }


def validate_request(svc, operation, body):
    oid = operation["oid"]
    schema = schemas(svc.contract).get(oid)
    if schema:
        # Resolve referenced common types against the canonical component set.
        schema = dict(schema, components=svc.contract.spec["components"])
        try:
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(body)
        except Exception as exc:
            raise Problem(422, "VALIDATION_FAILED", "Invalid mobile request.") from exc
    elif operation.get("source_req"):
        svc.contract.validate(operation["source_req"], body)
    if oid == "uploadListingImages":
        svc.target_owned("LISTING", svc.p["listingId"])
    elif oid == "submitFurnitureCaptureZip":
        svc.target_owned("FURNITURE", svc.p["furnitureId"])


def all_pages(svc, method, body):
    result = method(body)
    if not isinstance(result, dict) or "items" not in result or "cursor" in svc.q or "limit" in svc.q:
        return result
    items = list(result["items"])
    cursor = result.get("page", {}).get("nextCursor")
    try:
        while cursor:
            svc.q["cursor"] = cursor
            result = method(body)
            items.extend(result["items"])
            cursor = result.get("page", {}).get("nextCursor")
    finally:
        svc.q.pop("cursor", None)
    return dict(items=items, nextCursor=None)


def store_uploads(svc, body, purpose, target_type, target_id):
    descriptors = [
        {k: f[k] for k in ("clientFileId", "fileName", "contentType", "byteSize", "sha256")}
        for f in body["files"]
    ]
    request = dict(purpose=purpose, targetType=target_type, targetId=target_id, files=descriptors)
    svc.contract.validate("UploadSessionCreate", request)
    upload = svc.createUploadSession(request)
    for public, staged in zip(upload["session"]["files"], body["files"]):
        f = svc.s.get("file", public["fileId"])["value"]
        os.replace(staged["path"], svc.cfg.data_dir / "objects" / f["sha256"])
        f["_stored"] = True
        svc.save("file", f["fileId"], f)
    svc.p["uploadSessionId"] = upload["session"]["uploadSessionId"]
    svc.verifyUploadSession(None)
    return upload["session"]["files"]


def dispatch(svc, operation, body):
    oid = operation["oid"]
    if oid == "createFurniture":
        converted = dict(body, category=FURNITURE_CATEGORIES.get(body["category"], body["category"]))
        svc.contract.validate("FurnitureCreate", converted)
        return svc.createFurniture(converted)
    if oid == "createListing":
        require(
            body.get("latitude") is not None and body.get("longitude") is not None,
            422,
            "LOCATION_COORDINATES_REQUIRED",
            "주소의 위도·경도를 확인한 뒤 다시 등록해 주세요.",
        )
        converted = dict(
            location=dict(
                roadAddress=body["location"], latitude=body["latitude"], longitude=body["longitude"]
            ),
            price=dict(
                leaseType=body["dealType"],
                deposit=body["price"]["deposit"] * 10000,
                monthlyRent=body["price"]["monthly"] * 10000,
                maintenanceFee=None if body.get("maintenanceFee") is None else body["maintenanceFee"] * 10000,
                currency="KRW",
            ),
            exclusiveAreaM2=body["area"],
            roomType=body["roomType"],
            availableFrom=body["availableFrom"],
            rentalOptions=[OPTIONS[v] for v in body["rentalOptions"]],
        )
        for mobile, canonical in [
            ("floor", "floor"),
            ("totalFloors", "totalFloors"),
            ("supplyArea", "supplyAreaM2"),
            ("rooms", "roomCount"),
            ("bathrooms", "bathroomCount"),
        ]:
            if mobile in body:
                converted[canonical] = body[mobile]
        svc.contract.validate("ListingCreate", converted)
        return svc.createListing(converted)
    if oid == "uploadListingImages":
        listing = svc.target_owned("LISTING", svc.p["listingId"])["value"]
        files = store_uploads(svc, body, "LISTING_PHOTO", "LISTING", listing["listingId"])
        listing["photos"] = [
            svc.asset(svc.s.get("file", svc.s.get("file", f["fileId"])["value"]["_derivative"])["value"])
            for f in files
        ]
        # The iOS flow has no separate publication request; photos make its listing visible.
        listing["status"] = "PUBLISHED"
        return svc.response_etag(svc.listing_view(svc.save("listing", listing["listingId"], bump(listing))))
    if oid == "applyAgentVerification":
        fields = body["fields"]
        require(len(fields.get("documentName", "")) <= 255, 422, "VALIDATION_FAILED")
        require(
            set(fields) <= {"name", "licenseNumber", "officeName", "documentName"}, 422, "INVALID_PARAMETER"
        )
        values = {k: fields.get(k, "").strip() for k in ("name", "licenseNumber", "officeName")}
        for key, limit in (("name", 80), ("licenseNumber", 80), ("officeName", 150)):
            require(1 <= len(values[key]) <= limit, 422, "VALIDATION_FAILED")
        files = store_uploads(svc, body, "LISTING_AGENT_DOCUMENT", "USER", svc.user["userId"])
        values.update(documentFileId=files[0]["fileId"], phone=svc.user.get("phone"))
        result = svc.applyAgentVerification(values)
        profile = svc.s.get("agentprofile", svc.user["userId"])["value"]
        profile["_documentName"] = fields.get("documentName", body["files"][0]["fileName"])
        svc.save("agentprofile", svc.user["userId"], profile)
        return result
    if oid == "getAgentProfile":
        result = svc.getAgentProfile(body)
        require(result["status"] != "NONE", 404, "RESOURCE_NOT_FOUND")
        return result
    if oid == "sendChatMessage":
        return svc.sendChatMessage(dict(text=body["message"]))
    if oid == "createListingReport":
        converted = dict(reason=REASONS.get(body["reason"], body["reason"]))
        if body.get("detail") is not None:
            converted["detail"] = body["detail"]
        svc.contract.validate("ListingReportCreate", converted)
        return svc.createListingReport(converted)
    if oid == "classifyObjects":
        converted = dict(body, spaceVersion=svc.space(svc.p["listingId"])["spaceVersion"])
        svc.contract.validate("ObjectsPatch", converted)
        return svc.classifyObjects(converted)
    if oid == "createRoomState":
        space = svc.space(svc.p["listingId"])
        converted = dict(body)
        for key, value in [
            ("sourceSpaceVersion", space["spaceVersion"]),
            ("sourceObjectsVersion", space["objectsVersion"]),
        ]:
            if converted.get(key) is None:
                converted[key] = value
        svc.contract.validate("RoomStateCreate", converted)
        return svc.createRoomState(converted)
    if oid == "savePlacements":
        space = svc.space(svc.p["listingId"])
        placements = []
        for item in body["placements"]:
            furniture = svc.own("furniture", item["furnitureId"])["value"]
            require(furniture.get("model"), 409, "FURNITURE_NOT_READY")
            yaw = math.radians(item["rotation"]["yaw"]) / 2
            placements.append(
                dict(
                    placementId=item["furnitureId"],
                    furnitureId=item["furnitureId"],
                    modelVersion=furniture["model"]["modelVersion"],
                    transform=dict(
                        position=item["position"], rotation=dict(x=0, y=math.sin(yaw), z=0, w=math.cos(yaw))
                    ),
                )
            )
        converted = dict(
            space=dict(kind="ORIGINAL", spaceVersion=space["spaceVersion"], roomStateId=None),
            placements=placements,
            clientCollisionReport=None,
        )
        svc.contract.validate("PlacementPut", converted)
        return svc.savePlacements(converted)
    if oid == "getChat":
        result = svc.getChat(body)
        svc.q = {}
        result["messages"] = all_pages(svc, svc.getChatMessages, None)["items"][::-1]
        if result["messages"]:
            svc.readChat(dict(lastReadMessageId=result["messages"][-1]["messageId"]))
        return result
    return all_pages(svc, getattr(svc, oid), body)


def yaw(rotation):
    x, y, z, w = (rotation[k] for k in "xyzw")
    return math.degrees(math.atan2(2 * (w * y + x * z), 1 - 2 * (y * y + z * z)))


def scene_object(value):
    position = value["transform"]["position"]
    x, y, z, w = (value["transform"]["rotation"][k] for k in "xyzw")
    half_height = value["dimensions"]["height"] / 2
    # Canonical objects use a local bottom-center pivot; Swift expects the world box center.
    up = (2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x))
    return dict(
        objectId=value["objectId"],
        category=APP_FURNITURE_CATEGORIES.get(value["category"], value["category"].lower()),
        position={axis: position[axis] + half_height * component for axis, component in zip("xyz", up)},
        rotation=dict(yaw=yaw(value["transform"]["rotation"])),
        dimensions=value["dimensions"],
        ownershipType=value["ownershipType"],
        removable=value["removable"],
    )


def geometry(value):
    rooms = value["rooms"]
    points = [p for r in rooms for p in r["floorPolygon"]]
    floor, ceiling = min(r["floorY"] for r in rooms), max(r["ceilingY"] for r in rooms)
    walls = [
        dict(
            id=r["roomId"] + ":" + str(i),
            **{"from": p, "to": r["floorPolygon"][(i + 1) % len(r["floorPolygon"])]},
            height=r["ceilingY"] - r["floorY"],
        )
        for r in rooms
        for i, p in enumerate(r["floorPolygon"])
    ]
    openings = {}
    for key, kind, id_key in [("doors", "door", "doorId"), ("windows", "window", "windowId")]:
        openings[key] = [
            dict(
                id=v[id_key],
                type=kind,
                position=v["transform"]["position"],
                rotation=dict(yaw=yaw(v["transform"]["rotation"])),
                dimensions=dict(width=v["width"], height=v["height"], depth=0.1),
            )
            for v in value.get(key, [])
        ]
    return dict(
        floorHeight=floor,
        ceilingHeight=ceiling - floor,
        walls=walls,
        **openings,
        boundingBox=dict(
            min=dict(x=min(p[0] for p in points), y=floor, z=min(p[1] for p in points)),
            max=dict(x=max(p[0] for p in points), y=ceiling, z=max(p[1] for p in points)),
        ),
    )


def listing_projection(svc, value):
    stored = svc.s.optional("listing", value["listingId"])
    if stored and (stored["status"] == "PUBLISHED" or stored["ownerId"] == svc.user["userId"]):
        value = svc.present(svc.listing_view(stored))
    location, price = value["location"], value["price"]
    photos = value.get("photos", [])
    thumb = value.get("thumbnail") or next(iter(photos), None)
    owner = svc.owner_view(value.get("ownerId", value.get("owner", {}).get("userId")))
    job = svc.s.optional("job", value.get("activeJobId")) if value.get("activeJobId") else None
    result = dict(
        listingId=value["listingId"],
        location=location["roadAddress"],
        price=dict(deposit=price["deposit"] / 10000, monthly=price["monthlyRent"] / 10000),
        maintenanceFee=None if price["maintenanceFee"] is None else price["maintenanceFee"] / 10000,
        dealType=price["leaseType"],
        latitude=location["latitude"],
        longitude=location["longitude"],
        area=value["exclusiveAreaM2"],
        rentalOptions=[
            {v: k for k, v in OPTIONS.items()}.get(v, v.lower()) for v in value.get("rentalOptions", [])
        ],
        imageUris=[v["url"] for v in photos],
        thumbnailUri=thumb.get("url") if thumb else None,
        agent=dict(
            userId=owner["userId"],
            name=owner["displayName"],
            officeName=owner.get("officeName"),
            phone=owner.get("phone"),
            isVerifiedBroker=owner["isVerifiedListingAgent"],
        ),
        isScrapped=value["isScrapped"],
        status=value["status"],
        space3DInfo=dict(
            status="COMPLETED"
            if value.get("currentSpaceVersion") or value.get("hasSpace")
            else job["status"]
            if job
            else "NONE",
            jobId=job["jobId"] if job else None,
        ),
    )
    for canonical, mobile in [
        ("roomType", "roomType"),
        ("floor", "floor"),
        ("totalFloors", "totalFloors"),
        ("supplyAreaM2", "supplyArea"),
        ("roomCount", "rooms"),
        ("bathroomCount", "bathrooms"),
        ("availableFrom", "availableFrom"),
        ("livingEnvironment", "livingEnvironment"),
    ]:
        if canonical in value:
            result[mobile] = value[canonical]
    return result


def project(svc, oid, value):
    if isinstance(value, dict) and "items" in value:
        return dict(value, items=[project(svc, oid, v) for v in value["items"]])
    if value is None:
        return None
    if oid in (
        "searchListings",
        "getMyListings",
        "getScraps",
        "getListing",
        "createListing",
        "uploadListingImages",
    ):
        return listing_projection(svc, value)
    if oid in ("getSpace", "getRoomState", "getRoomStates"):
        bundle = value.get("bundle")
        result = {k: v for k, v in value.items() if k != "bundle"}
        if bundle:
            result.update(
                {
                    "3dgsUri" if oid == "getSpace" else "sceneUri": bundle["sceneAsset"]["url"],
                    "collisionGeometryUri": bundle["collisionAsset"]["url"],
                    "roomGeometry": geometry(bundle["geometry"]),
                    "roomDimensions": bundle["roomDimensions"],
                    "objects" if oid == "getSpace" else "remainingObjects": [
                        scene_object(v) for v in bundle["objects"]
                    ],
                }
            )
        return result
    if oid in ("getObjects", "classifyObjects"):
        return dict(objectsVersion=value["version"], objects=[scene_object(v) for v in value["objects"]])
    if oid in ("getFurniture", "getMyFurniture", "createFurniture"):
        model = value.get("model")
        return dict(
            furnitureId=value["furnitureId"],
            name=value["name"],
            category=APP_FURNITURE_CATEGORIES.get(value["category"], value["category"].lower()),
            status="COMPLETED" if value["status"] == "READY" else value["status"],
            modelUri=model["modelAsset"]["url"] if model else None,
            dimensions=model["dimensions"] if model else None,
            boundingBox=model["boundingBox"] if model else None,
            collisionMeshUri=model["collisionAsset"]["url"] if model else None,
        )
    if oid in ("getPlacements", "savePlacements"):
        return dict(
            placementSetId=value["placementSetId"],
            savedAt=value["savedAt"],
            placements=[
                dict(
                    furnitureId=v["furnitureId"],
                    position=v["transform"]["position"],
                    rotation=dict(yaw=yaw(v["transform"]["rotation"])),
                )
                for v in value["placements"]
            ],
        )
    if oid in ("getAgentProfile", "applyAgentVerification"):
        profile = svc.s.optional("agentprofile", svc.user["userId"]) or {}
        return dict(value, documentName=profile.get("_documentName"), verifiedAt=value.get("reviewedAt"))
    if oid in ("getChat", "getChats", "createChat"):
        result = dict(
            value,
            listing=listing_projection(svc, value["listing"]),
            counterpartName=value["counterpart"]["displayName"],
            officeName=value["counterpart"].get("officeName"),
        )
        if "messages" in value:
            result["messages"] = [
                dict(v, message=v["text"], senderName=svc.owner_view(v["senderId"])["displayName"])
                for v in value["messages"]
            ]
        return result
    if oid == "getJob":
        return dict(
            value,
            progress=value.get("progressPercent", 0),
            message=value.get("error", {}).get("message") if value.get("error") else None,
        )
    return value


def register_mobile(
    app, execute, rate, zip_endpoint, read_json, normalize, validate_parameters, parsed_query
):
    contract = app.state.contract
    ops = {o["oid"]: o for o in contract.ops}
    supported = {
        "signup",
        "login",
        "refreshToken",
        "logout",
        "loginKakao",
        "loginGoogle",
        "getMe",
        "searchListings",
        "createListing",
        "getMyListings",
        "getListing",
        "getSpace",
        "getObjects",
        "classifyObjects",
        "createRoomState",
        "getRoomStates",
        "getRoomState",
        "deleteRoomState",
        "createFurniture",
        "getMyFurniture",
        "getFurniture",
        "getPlacements",
        "savePlacements",
        "getScraps",
        "deleteScrap",
        "createListingReport",
        "getJob",
    }
    routes = [(o["method"].upper(), o["path"], o["oid"], None) for o in contract.ops if o["oid"] in supported]
    routes += [
        ("GET", "/users/me/agent-profile", "getAgentProfile", None),
        ("POST", "/users/me/agent-profile", "applyAgentVerification", "multipart"),
        ("POST", "/listings/{listingId}/images", "uploadListingImages", "multipart"),
        ("POST", "/listings/{listingId}/captures", "submitRoomCaptureZip", "zip"),
        ("POST", "/furniture/{furnitureId}/captures", "submitFurnitureCaptureZip", "zip"),
        ("POST", "/listings/{listingId}/scrap", "saveScrap", None),
        ("GET", "/chat", "getChats", None),
        ("POST", "/chat", "createChat", None),
        ("GET", "/chat/{chatId}", "getChat", None),
        ("POST", "/chat/{chatId}", "sendChatMessage", None),
    ]

    def endpoint(operation, kind):
        async def handle(request: Request):
            validate_parameters(operation, request)
            auth = request.headers.get("authorization", "")
            query = parsed_query(operation, request)
            if "limit" not in request.query_params:
                query.pop("limit", None)
            if operation["oid"] == "searchListings":
                for mobile, canonical in [
                    ("location", "q"),
                    ("dealType", "leaseType"),
                    ("maxPrice", "maxMonthlyRent"),
                ]:
                    if mobile in request.query_params:
                        raw = request.query_params[mobile]
                        require(canonical not in request.query_params, 400, "AMBIGUOUS_QUERY_PARAMETER")
                        if mobile == "maxPrice":
                            require(raw.isascii() and raw.isdigit(), 400, "INVALID_QUERY_PARAMETER")
                            query[canonical] = int(raw) * 10000
                        else:
                            query[canonical] = raw
                if "maxDeposit" in query:
                    query["maxDeposit"] *= 10000
                if "leaseType" in query:
                    require(query["leaseType"] in ("MONTHLY", "JEONSE"), 400, "INVALID_QUERY_PARAMETER")
            if kind == "zip":
                return await zip_endpoint(request, operation, query, auth)
            staged = []
            try:
                if kind == "multipart":

                    def authorize():
                        from .service import Service

                        with app.state.database.transaction() as store:
                            svc = Service(store, app.state.settings, contract)
                            svc.authenticate(auth)
                            if operation["oid"] == "uploadListingImages":
                                svc.target_owned("LISTING", request.path_params["listingId"])

                    await run_in_threadpool(authorize)
                    await run_in_threadpool(
                        rate, operation, None, auth, request.client.host if request.client else "unknown"
                    )
                    require(
                        request.headers.get("content-type", "").startswith("multipart/form-data;"),
                        415,
                        "UNSUPPORTED_MEDIA_TYPE",
                    )

                    async def limited_stream():
                        size = 0
                        async for chunk in request.stream():
                            size += len(chunk)
                            require(size <= (601 << 20), 413, "BODY_TOO_LARGE")
                            yield chunk

                    form = None
                    try:
                        form = await MultiPartParser(
                            request.headers,
                            limited_stream(),
                            max_files=30,
                            max_fields=4,
                            max_part_size=1 << 20,
                        ).parse()
                        body = dict(files=[], fields={})
                        for key, item in form.multi_items():
                            if isinstance(item, UploadFile):
                                require(
                                    key
                                    == (
                                        "images" if operation["oid"] == "uploadListingImages" else "document"
                                    ),
                                    422,
                                    "INVALID_UPLOAD_ROLE",
                                )
                                path = app.state.settings.data_dir / "staging" / uid()
                                staged.append(path)
                                sha, size = hashlib.sha256(), 0
                                with path.open("xb") as out:
                                    while chunk := await item.read(1 << 20):
                                        size += len(chunk)
                                        require(size <= 20 << 20, 413, "UPLOAD_LIMIT_EXCEEDED")
                                        sha.update(chunk)
                                        await run_in_threadpool(out.write, chunk)
                                body["files"].append(
                                    dict(
                                        clientFileId=sha.hexdigest()[:8]
                                        + "-0000-4000-8000-"
                                        + f"{len(body['files']):012d}",
                                        fileName=item.filename or "image.jpg",
                                        contentType=item.content_type,
                                        byteSize=size,
                                        sha256=sha.hexdigest(),
                                        path=str(path),
                                    )
                                )
                            else:
                                require(key not in body["fields"], 422, "DUPLICATE_FIELD")
                                body["fields"][key] = item
                        require(
                            1
                            <= len(body["files"])
                            <= (30 if operation["oid"] == "uploadListingImages" else 1),
                            422,
                            "INVALID_CAPTURE_FILES",
                        )
                        require(
                            operation["oid"] != "uploadListingImages" or not body["fields"],
                            422,
                            "INVALID_PARAMETER",
                        )
                    except MultiPartException as exc:
                        raise Problem(422, "INVALID_MULTIPART") from exc
                    finally:
                        if form is not None:
                            await form.close()
                else:
                    body = normalize(
                        await read_json(request, dict(operation, req=operation.get("source_req")))
                    )
                    await run_in_threadpool(
                        rate,
                        dict(operation, _jobId=request.path_params.get("jobId")),
                        body,
                        auth,
                        request.client.host if request.client else "unknown",
                    )
                headers = dict(request.headers, _path=request.url.path)
                # Staging paths vary on retries; the digest includes file metadata and content hashes.
                if kind == "multipart":
                    from .core import digest

                    headers["_body_digest"] = digest(
                        dict(body, files=[{k: v for k, v in f.items() if k != "path"} for f in body["files"]])
                    )
                result = await run_in_threadpool(
                    execute, operation, body, request.path_params, query, headers
                )
                if result["status"] == 204:
                    return Response(status_code=204, headers=result["headers"])
                return JSONResponse(result["body"], status_code=result["status"], headers=result["headers"])
            finally:
                for path in staged:
                    path.unlink(missing_ok=True)

        return handle

    for method, path, oid, kind in routes:
        source = ops.get(
            oid, ops["createListing"] if oid == "uploadListingImages" else ops["submitFurnitureCapture"]
        )
        operation = dict(
            source,
            oid=oid,
            method=method.lower(),
            path=path,
            req=None,
            res=None,
            mobile=True,
            source_req=source["req"],
        )
        if oid == "uploadListingImages":
            operation["params"] = ops["submitRoomCapture"]["params"]
            operation["status"] = 200
        if kind == "zip":
            operation.update(binary=True, source_req=None)
        if kind == "multipart":
            operation["source_req"] = None
        if oid == "saveScrap" and method == "POST":
            operation["status"] = 204
        if oid == "searchListings":
            operation["params"] = copy.deepcopy(operation["params"]) + [
                dict(
                    name="location",
                    **{"in": "query"},
                    required=False,
                    schema=dict(type="string", maxLength=200),
                ),
                dict(
                    name="dealType",
                    **{"in": "query"},
                    required=False,
                    schema=dict(type="string", enum=["MONTHLY", "JEONSE"]),
                ),
                dict(
                    name="maxPrice",
                    **{"in": "query"},
                    required=False,
                    schema=dict(type="integer", minimum=0, maximum=100_000_000),
                ),
            ]
        # Alias query names are parsed explicitly above; canonical validation still checks common filters.
        app.add_api_route("/api" + path, endpoint(operation, kind), methods=[method], include_in_schema=False)
