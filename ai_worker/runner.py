"""Poll Spring Boot and coordinate leased AI attempts."""

from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import time
import uuid
import httpx
from . import furniture
from .client import WorkerClient
from .execution import run_engine
from .failures import failure_for
from .publication import publish
from .tokens import worker_token

ROOM_TYPES = {"ROOM_RECONSTRUCTION", "ROOM_OBJECT_REMOVAL"}
SUPPORTED = ROOM_TYPES | {furniture.FURNITURE}


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
    environment.pop("FURNITURE_REPO", None)
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
        before_claim = time.time()
        try:
            assignment = client.claim(job_id)
        except httpx.HTTPStatusError as error:
            if error.response.status_code == 409:
                return False
            raise
        path = None
        try:
            directory, output, path = prepare_attempt(args, assignment)
            environment = engine_environment(args, path, output)
            if assignment["type"] == furniture.FURNITURE and args.furniture_repo is not None:
                furniture.run_attempt(
                    args, client, assignment, directory, output, environment, before_claim
                )
            else:
                client.update_progress(5, "RECONSTRUCTING")
                run_engine(environment, directory / "engine.log", client.lost)
                publish(client, output)
        except Exception as error:
            report_failure(client, assignment["type"], error)
            raise
        finally:
            if path is not None:
                path.unlink(missing_ok=True)
    return True


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
    jobs = [job for job in pending_jobs(args) if job["type"] in args.types]
    jobs.sort(key=lambda job: job["type"] != furniture.FURNITURE)
    for job in jobs:
        try:
            if not run_job(args, job["jobId"], worker_token(args.secret_file, args.worker_id)):
                continue
        except httpx.HTTPStatusError as error:
            print("Job HTTP status", error.response.status_code, flush=True)
        except Exception as error:
            print("Job failed", job["jobId"], type(error).__name__, flush=True)
        return


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", default="http://10.178.0.5:8080")
    parser.add_argument("--worker-id", default="pr2-gpu-1")
    parser.add_argument("--secret-file", type=Path, required=True)
    parser.add_argument(
        "--pr2-repo", type=Path, default=Path("/mnt/cdata/dulleobang-worker/ai-poc")
    )
    parser.add_argument("--work-root", type=Path, default=Path("/data/indoor-ai-poc/backend-jobs"))
    engines = parser.add_mutually_exclusive_group()
    engines.add_argument(
        "--furniture-repo", type=Path, help="AI checkout containing the furniture pipeline"
    )
    engines.add_argument(
        "--furniture-engine", type=Path, help="Executable adapter for furniture captures"
    )
    parser.add_argument("--types", help="Comma-separated job types to poll")
    args = parser.parse_args()
    if args.furniture_repo is not None:
        repo = args.furniture_repo.resolve()
        scripts = repo / "scripts/furniture"
        if not all((scripts / name).is_file() for name in ("run_furniture.sh", "self_check.py")):
            parser.error("--furniture-repo must contain the furniture pipeline and self-check")
        args.furniture_repo = repo
    if args.furniture_engine is not None:
        engine = args.furniture_engine.resolve()
        if not engine.is_file() or not os.access(engine, os.X_OK):
            parser.error("--furniture-engine must point to an executable file")
        args.furniture_engine = engine
    enabled = SUPPORTED if args.furniture_repo or args.furniture_engine else ROOM_TYPES
    args.types = set(args.types.split(",")) if args.types else enabled
    if not args.types <= enabled:
        parser.error("--types must name enabled job types; furniture requires a repository or adapter")
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
