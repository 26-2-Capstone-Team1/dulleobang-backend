"""Connect leased furniture captures to the AI team's furniture pipeline."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid
import zipfile
from datetime import datetime

from .execution import ENGINE_TIMEOUT_SECONDS, run_engine
from .failures import FurnitureFailure, InvalidArtifact, InvalidEngineData
from .publication import publish

FURNITURE = "FURNITURE_RECONSTRUCTION"
LEASE_SECONDS = 120
ATTEMPT_SECONDS = 7200
PUBLICATION_RESERVE = 600
STAGES = {"PREPROCESSING", "SEGMENTING", "RECONSTRUCTING", "SCALING", "BUILDING_GEOMETRY"}
PERMANENT_CODES = {
    "INSUFFICIENT_FRAMES", "TRACKING_UNSTABLE", "SEGMENTATION_FAILED",
    "SCALE_UNRELIABLE", "RECONSTRUCTION_FAILED", "INVALID_ARTIFACT",
}
TRANSIENT_CODES = {"DEPENDENCY_UNAVAILABLE", "WORKER_TIMEOUT"}
RESULT_FIELDS = {"dimensions", "boundingBox", "quality"}


def epoch(timestamp):
    return datetime.fromisoformat(timestamp.replace("Z", "+00:00")).timestamp()


def compute_deadline(assignment, before_claim):
    claimed = min(before_claim, epoch(assignment["leaseExpiresAt"]) - LEASE_SECONDS)
    return int(claimed + ATTEMPT_SECONDS - PUBLICATION_RESERVE)


def current_grant(client, initial):
    file_id = initial["asset"]["fileId"]

    def find():
        data = client.assignment["input"]
        candidates = (data.get("archive"), data.get("manifest"), *data.get("images", []))
        return next(entry for entry in candidates if entry and entry["asset"]["fileId"] == file_id)

    current = find()
    if epoch(current["expiresAt"]) - time.time() < 60:
        client.heartbeat(refresh_urls=True)
        return find()
    return current


def download_capture(client, directory, deadline):
    from .engine import download, extract

    def check():
        if client.lost.is_set():
            raise RuntimeError("Worker lease was lost")
        if time.time() >= deadline:
            raise TimeoutError("Furniture input download exceeded the attempt budget")

    def save(grant, path):
        check()
        download(current_grant(client, grant), path, check=check)

    data = client.assignment["input"]
    try:
        if data.get("archive"):
            archive = directory / "capture.zip"
            save(data["archive"], archive)
            check()
            return extract(archive, directory / "zip")
        capture = directory / "capture"
        images = capture / "images"
        images.mkdir(parents=True)
        save(data["manifest"], capture / "camera.json")
        for image in data["images"]:
            image_id = str(uuid.UUID(image["clientFileId"]))
            suffix = ".png" if image["asset"]["contentType"] == "image/png" else ".jpg"
            save(image, images / (image_id + suffix))
        return capture
    except (KeyError, StopIteration, TypeError, ValueError, zipfile.BadZipFile) as error:
        raise InvalidEngineData("Invalid furniture capture input") from error


def pipeline_environment(repo, data, capture, output, deadline):
    target = data.get("furniture")
    if not isinstance(target, dict):
        raise InvalidEngineData("Furniture input requires a name and category")
    name, category = target.get("name"), target.get("category")
    if not isinstance(name, str) or not name or not isinstance(category, str) or not category.strip():
        raise InvalidEngineData("Furniture input requires a name and category")
    gpu = os.environ.get("GPU_INDEX", "0")
    measured = data.get("measuredDimensions")
    return {
        "FURNITURE_REPO": str(repo),
        "CAPTURE_DIR": str(capture),
        "OUTPUT": str(output),
        "WORK": str(output.parent / "work"),
        "CHECK": "0",
        "DEADLINE_EPOCH": str(deadline),
        "MEASURED": json.dumps(measured) if measured is not None else "",
        "CATEGORY": category,
        "GPU_INDEX": gpu,
        "GPU_LOCK": os.environ.get("GPU_LOCK", f"/data/indoor-ai-poc/gpu{gpu}.lock"),
    }


def forward_progress(client, output):
    try:
        value = json.loads((output / "progress.json").read_text(encoding="utf-8"))
        stage, percent = value["stage"], value["percent"]
        if isinstance(stage, str) and stage in STAGES and type(percent) is int:
            capped = min(percent, 95)
            if capped >= client.progress:
                client.update_progress(capped, stage)
    except (OSError, TypeError, KeyError, ValueError):
        pass


def pipeline_failure(path, allowed):
    try:
        error = json.loads(path.read_text(encoding="utf-8"))
        code, message = error["code"], error["message"]
        if (
            isinstance(code, str) and code in allowed
            and isinstance(message, str) and 1 <= len(message) <= 500
        ):
            return FurnitureFailure(code, message, code in TRANSIENT_CODES)
    except (OSError, ValueError, KeyError, TypeError):
        pass
    return None


def run_attempt(args, client, assignment, directory, output, environment, before_claim):
    deadline = compute_deadline(assignment, before_claim)
    client.update_progress(1, "PREPROCESSING")
    capture = download_capture(client, directory, deadline)
    environment.update(pipeline_environment(
        args.furniture_repo, assignment["input"], capture, output, deadline
    ))
    try:
        run_engine(
            environment, directory / "engine.log", client.lost,
            timeout=min(ENGINE_TIMEOUT_SECONDS, deadline + 300 - time.time()),
            tick=lambda: forward_progress(client, output),
        )
    except RuntimeError as error:
        report = pipeline_failure(output / "failure.json", PERMANENT_CODES | TRANSIENT_CODES)
        if report is not None:
            raise report from error
        raise
    client.update_progress(96, "UPLOADING")
    publish(client, output)
    (directory / "capture.zip").unlink(missing_ok=True)
    for name in ("zip", "capture", "work"):
        shutil.rmtree(directory / name, ignore_errors=True)


def write_result(output):
    try:
        value = json.loads((output / "furniture.json").read_text(encoding="utf-8"))
        if not isinstance(value, dict) or value.keys() != RESULT_FIELDS:
            raise ValueError("Invalid furniture result fields")
        result = {"type": FURNITURE, **value}
        files = [
            {"role": role, "key": role, "format": "GLB_V2", "path": name}
            for role, name in (("MODEL", "model.glb"), ("COLLISION", "collision.glb"))
        ]
        data = json.dumps({"result": result, "files": files}, allow_nan=False)
    except (FileNotFoundError, ValueError) as error:
        raise InvalidArtifact("Invalid furniture result") from error
    (output / "result.json").write_text(data, encoding="utf-8")


def check_output(repo, output):
    check = subprocess.run(
        [sys.executable, "-I", str(repo / "scripts/furniture/self_check.py"), str(output)],
        capture_output=True, timeout=300, check=False,
    )
    (output / "worker_self_check.json").write_bytes(check.stdout)
    if check.returncode == 1:
        raise InvalidArtifact("Furniture self-check rejected the result")
    check.check_returncode()


def run_pipeline(output):
    repo = Path(os.environ["FURNITURE_REPO"])
    process = subprocess.run(
        ["bash", str(repo / "scripts/furniture/run_furniture.sh")], cwd=output.parent, check=False
    )
    try:
        allowed = {2: PERMANENT_CODES, 3: TRANSIENT_CODES}.get(process.returncode, set())
        if allowed:
            report = pipeline_failure(output / "error.json", allowed)
            if report is not None:
                raise report
        process.check_returncode()
        check_output(repo, output)
        write_result(output)
    except (FurnitureFailure, InvalidArtifact) as error:
        if isinstance(error, InvalidArtifact):
            error = FurnitureFailure("INVALID_ARTIFACT", "결과 파일 검증에 실패했습니다.", False)
        code, message, retryable = error.report
        (output / "failure.json").write_text(
            json.dumps({"code": code, "message": message, "retryable": retryable}), encoding="utf-8"
        )
        raise SystemExit(1) from error
