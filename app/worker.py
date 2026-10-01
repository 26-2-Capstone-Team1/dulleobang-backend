"""HTTP integration helper for a separate AI process; no synthetic reconstruction."""

from __future__ import annotations

import hashlib
import threading
from uuid import uuid4

import httpx


class WorkerClient:
    def __init__(self, base_url, token, worker_id):
        self.base = base_url.rstrip("/") + "/api/v1"
        self.worker_id = worker_id
        self.http = httpx.Client(
            headers={"Authorization": "Bearer " + token}, timeout=60, follow_redirects=False
        )
        self.assignment = None
        self.lost = threading.Event()
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self.progress, self.stage = 0, "PREPROCESSING"
        self._thread = None

    def close(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=65)
        self.http.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def request(self, action, data):
        if self.lost.is_set():
            raise RuntimeError("Worker lease was lost; discard this attempt's output.")
        response = self.http.post(
            self.base + "/internal/ai-jobs/" + self.assignment["jobId"] + "/" + action,
            json=data,
            headers={"X-Job-Lease-Token": self.assignment["leaseToken"]},
        )
        if response.status_code in (401, 403, 409):
            self.lost.set()
        response.raise_for_status()
        return response.json()

    def claim(self, job_id):
        if self.assignment:
            raise RuntimeError("Use one WorkerClient per job.")
        response = self.http.post(
            self.base + "/internal/ai-jobs/" + job_id + "/claim", json={"workerId": self.worker_id}
        )
        response.raise_for_status()
        self.assignment = response.json()
        self._thread = threading.Thread(target=self._heartbeat_loop, daemon=True)
        self._thread.start()
        return self.assignment

    def update_progress(self, percent, stage):
        with self._lock:
            if not self.progress <= percent <= 99:
                raise ValueError("Progress must be monotonic and 0..99.")
            self.progress, self.stage = percent, stage

    def heartbeat(self, refresh_urls=False):
        with self._lock:
            percent, stage = self.progress, self.stage
        result = self.request(
            "heartbeat", {"progressPercent": percent, "stage": stage, "refreshInputUrls": refresh_urls}
        )
        if "input" in result:
            self.assignment["input"] = result["input"]
        return result

    def _heartbeat_loop(self):
        while not self._stop.wait(30):
            try:
                self.heartbeat()
            except httpx.HTTPStatusError:
                self.lost.set()
                return
            except httpx.RequestError:
                # Next heartbeat can recover a short outage; server lease remains authoritative.
                continue

    def download(self, grant):
        # Separate client: never send the worker's Authorization header to an object store.
        response = httpx.get(grant["url"], timeout=120, follow_redirects=False)
        response.raise_for_status()
        raw = response.content
        if (
            len(raw) != grant["asset"]["byteSize"]
            or hashlib.sha256(raw).hexdigest() != grant["asset"]["sha256"]
        ):
            raise ValueError("Input checksum mismatch.")
        return raw

    def upload_artifacts(self, files):
        """files: dict role -> bytes. Returns role -> immutable AssetRef.

        Compute all outputs before registering them. Retain returned IDs when retrying
        callbacks. A new attempt must register its own output files.
        """
        profile = {
            "SCENE": ("application/octet-stream", "DULLEOBANG_GS_PLY_V1"),
            "GEOMETRY": ("application/json", "ROOM_GEOMETRY_V1"),
            "MODEL": ("model/gltf-binary", "GLB_V2"),
            "COLLISION": ("model/gltf-binary", "GLB_V2"),
        }
        descriptors = []
        for role, raw in files.items():
            mime, fmt = profile[role]
            descriptors.append(
                {
                    "clientFileId": str(uuid4()),
                    "role": role,
                    "contentType": mime,
                    "format": fmt,
                    "byteSize": len(raw),
                    "sha256": hashlib.sha256(raw).hexdigest(),
                }
            )
        grants = self.request("artifacts", {"files": descriptors})["uploads"]
        result = {}
        for descriptor, grant in zip(descriptors, grants):
            response = httpx.put(
                grant["url"],
                content=files[descriptor["role"]],
                headers=grant["headers"],
                timeout=180,
                follow_redirects=False,
            )
            response.raise_for_status()
            result[descriptor["role"]] = {
                k: descriptor[k] for k in ("contentType", "format", "byteSize", "sha256")
            }
            result[descriptor["role"]]["fileId"] = grant["fileId"]
        return result

    def complete(self, result):
        response = self.request("complete", result)
        self._stop.set()
        return response

    def fail(self, code, message, retryable=False):
        response = self.request("fail", {"error": {"code": code, "message": message, "retryable": retryable}})
        self._stop.set()
        return response
