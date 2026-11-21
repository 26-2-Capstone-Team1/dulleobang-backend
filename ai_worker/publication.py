"""Register, upload and bind immutable worker outputs before completion."""

import hashlib
import json
import uuid
import httpx

from .failures import InvalidArtifact


def content_type(role):
    if role == "SCENE":
        return "application/octet-stream"
    if role == "GEOMETRY":
        return "application/json"
    return "model/gltf-binary"


def describe_files(assignment, files, output):
    descriptors, paths = [], {}
    for item in files:
        path = (output / item["path"]).resolve()
        if not path.is_relative_to(output.resolve()) or not path.is_file():
            raise ValueError("Invalid output artifact path")
        raw = path.read_bytes()
        name = str(assignment["attemptNo"]) + ":" + item["key"]
        client_id = str(uuid.uuid5(uuid.UUID(assignment["jobId"]), name))
        descriptors.append(
            {
                "clientFileId": client_id,
                "role": item["role"],
                "contentType": content_type(item["role"]),
                "format": item["format"],
                "byteSize": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
        )
        paths[client_id] = (item["key"], path)
    return descriptors, paths


def upload_files(descriptors, paths, grants):
    refs = {}
    for descriptor, grant in zip(descriptors, grants):
        key, path = paths[descriptor["clientFileId"]]
        response = httpx.put(
            grant["url"],
            content=path.read_bytes(),
            headers=grant["headers"],
            timeout=240,
            follow_redirects=False,
            trust_env=False,
        )
        response.raise_for_status()
        refs[key] = {
            key: descriptor[key] for key in ("contentType", "format", "byteSize", "sha256")
        }
        refs[key]["fileId"] = grant["fileId"]
    return refs


def bind_assets(result, refs):
    if result["type"] == "FURNITURE_RECONSTRUCTION":
        result.update(modelAsset=refs["MODEL"], collisionAsset=refs["COLLISION"])
        return
    bundle = result["bundle"]
    bundle.update(
        sceneAsset=refs["SCENE"], geometryAsset=refs["GEOMETRY"], collisionAsset=refs["COLLISION"]
    )
    source = bundle["meshSource"]
    source.update(roomAsset=refs["room"], roomCollisionAsset=refs["room-collision"])
    for key, field in (("room-mobile", "roomMobileAsset"), ("room-fallback", "roomFallbackAsset")):
        if key in refs:
            source[field] = refs[key]
    for item in source["objectAssets"]:
        item["asset"] = refs["object:" + item["objectId"]]


def publish(client, output):
    try:
        document = json.loads((output / "result.json").read_text())
        result = document["result"]
        files = document["files"]
        if result["type"] != client.assignment["type"]:
            raise ValueError("Output job type does not match the assignment")
        if result["type"] == "FURNITURE_RECONSTRUCTION":
            roles = [(item["key"], item["role"], item["format"]) for item in files]
            if sorted(roles) != [("COLLISION", "COLLISION", "GLB_V2"), ("MODEL", "MODEL", "GLB_V2")]:
                raise ValueError("Expected one MODEL and one COLLISION GLB")
            if not all(isinstance(result[key], dict) for key in ("dimensions", "boundingBox", "quality")):
                raise ValueError("Expected metric dimensions, bounding box, and quality")
        descriptors, paths = describe_files(client.assignment, files, output)
    except (FileNotFoundError, ValueError, KeyError, TypeError) as error:
        raise InvalidArtifact("Invalid output document or files") from error
    grants = client.request("artifacts", {"files": descriptors})["uploads"]
    refs = upload_files(descriptors, paths, grants)
    try:
        bind_assets(result, refs)
    except (KeyError, TypeError, AttributeError) as error:
        raise InvalidArtifact("Output artifacts do not match the result") from error
    (output / "callback.json").write_text(json.dumps(result), encoding="utf-8")
    client.update_progress(99, "VALIDATING")
    client.heartbeat()
    return client.complete(result)
