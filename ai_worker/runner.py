"""Poll Spring Boot and coordinate leased PR #2 attempts."""

from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import time
import uuid
import httpx
from .client import WorkerClient
from .execution import run_engine
from .failures import failure_for
from .publication import publish
from .tokens import worker_token

SUPPORTED = {"ROOM_RECONSTRUCTION", "ROOM_OBJECT_REMOVAL"}
FURNITURE_RECONSTRUCTION = "FURNITURE_RECONSTRUCTION"


def prepare_attempt(args, assignment):
    directory = args.work_root / assignment["jobId"] / str(assignment["attemptNo"])
    directory.mkdir(parents=True, exist_ok=False)
    output = directory / "output"
    output.mkdir()
    path = directory / "assignment.json"
    path.write_text(json.dumps(assignment))
    path.chmod(0o600)
    return directory, output, path


def engine_environment(args, path, output):
    environment = os.environ.copy()
    environment.update(
        DULLEOBANG_ASSIGNMENT=str(path),
        DULLEOBANG_OUTPUT=str(output),
        PR2_REPO=str(args.pr2_repo),
    )
    if args.furniture_engine is not None:
        environment["DULLEOBANG_FURNITURE_ENGINE"] = str(args.furniture_engine)
    return environment


def report_failure(client, job_type, error):
    if client.lost.is_set():
        return
    failure = failure_for(error, job_type)
    if failure is not None:
        try:
            client.fail(*failure)
        except (httpx.HTTPError, RuntimeError, ValueError) as report_error:
            print("Job failure callback rejected:", type(report_error).__name__, flush=True)


def run_job(args, job_id, token):
    job_id = str(uuid.UUID(job_id))
    with WorkerClient(args.backend, token, args.worker_id) as client:
        assignment = client.claim(job_id)
        path = None
        try:
            directory, output, path = prepare_attempt(args, assignment)
            client.update_progress(5, "RECONSTRUCTING")
            run_engine(
                engine_environment(args, path, output), directory / "engine.log", client.lost
            )
            publish(client, output)
        except Exception as error:
            report_failure(client, assignment["type"], error)
            raise
        finally:
            if path is not None:
                path.unlink(missing_ok=True)


def pending_jobs(args):
    response = httpx.get(
        args.backend.rstrip("/") + "/api/v1/internal/ai-jobs",
        headers={"Authorization": "Bearer " + worker_token(args.secret_file, args.worker_id)},
        timeout=30,
        trust_env=False,
    )
    response.raise_for_status()
    return response.json()["items"]


def process_queue(args):
    for job in pending_jobs(args):
        if job["type"] not in SUPPORTED and not (
            job["type"] == FURNITURE_RECONSTRUCTION and args.furniture_engine is not None
        ):
            continue
        try:
            run_job(args, job["jobId"], worker_token(args.secret_file, args.worker_id))
        except httpx.HTTPStatusError as error:
            print("Job HTTP status", error.response.status_code, flush=True)
        except Exception as error:
            print("Job failed", job["jobId"], type(error).__name__, flush=True)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", default="http://10.178.0.5:8080")
    parser.add_argument("--worker-id", default="pr2-gpu-1")
    parser.add_argument("--secret-file", type=Path, required=True)
    parser.add_argument(
        "--pr2-repo", type=Path, default=Path("/mnt/cdata/dulleobang-worker/ai-poc")
    )
    parser.add_argument("--work-root", type=Path, default=Path("/data/indoor-ai-poc/backend-jobs"))
    parser.add_argument(
        "--furniture-engine", type=Path, help="Executable adapter for furniture captures"
    )
    args = parser.parse_args()
    if args.furniture_engine is not None:
        engine = args.furniture_engine.resolve()
        if not engine.is_file() or not os.access(engine, os.X_OK):
            parser.error("--furniture-engine must point to an executable file")
        args.furniture_engine = engine
    return args


def main():
    args = arguments()
    args.work_root = args.work_root.resolve()
    args.work_root.mkdir(parents=True, exist_ok=True)
    args.work_root.chmod(0o700)
    while True:
        try:
            process_queue(args)
        except (httpx.HTTPError, OSError, ValueError):
            print("Backend unavailable; retrying", flush=True)
        time.sleep(5)


if __name__ == "__main__":
    main()
