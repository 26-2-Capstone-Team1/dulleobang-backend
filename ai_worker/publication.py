"""Register, upload and bind immutable worker outputs before completion."""

import hashlib
import json
import uuid
import httpx


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
    bundle = result["bundle"]
    bundle.update(
        sceneAsset=refs["SCENE"], geometryAsset=refs["GEOMETRY"], collisionAsset=refs["COLLISION"]
    )
    source = bundle["meshSource"]
    source.update(roomAsset=refs["room"], roomCollisionAsset=refs["room-collision"])
    for item in source["objectAssets"]:
        item["asset"] = refs["object:" + item["objectId"]]


def publish(client, output):
    document = json.loads((output / "result.json").read_text())
    descriptors, paths = describe_files(client.assignment, document["files"], output)
    grants = client.request("artifacts", {"files": descriptors})["uploads"]
    refs = upload_files(descriptors, paths, grants)
    result = document["result"]
    bind_assets(result, refs)
    (output / "callback.json").write_text(json.dumps(result), encoding="utf-8")
    client.update_progress(99, "VALIDATING")
    return client.complete(result)
