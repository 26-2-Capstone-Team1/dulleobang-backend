"""Exercise the shipped worker SDK, including heartbeat and integrity failures."""

import hashlib

import httpx
import pytest

import app.worker as worker_module
from app.worker import WorkerClient
from conftest import capture, create, glb


def test_worker_sdk_full_http_adapter(client, actors, monkeypatch):
    furniture = create(client, "/furniture", {"name": "SDK", "category": "DESK"}, actors["tenant"]).json()
    accepted = capture(client, actors, "FURNITURE", furniture["furnitureId"])
    seen_headers = []

    def get(url, **kwargs):
        assert "Authorization" not in kwargs.get("headers", {})
        seen_headers.append(kwargs.get("headers", {}))
        return client.get(url)

    def put(url, **kwargs):
        assert "Authorization" not in kwargs.get("headers", {})
        seen_headers.append(kwargs.get("headers", {}))
        return client.put(url, content=kwargs["content"], headers=kwargs["headers"])

    monkeypatch.setattr(worker_module.httpx, "get", get)
    monkeypatch.setattr(worker_module.httpx, "put", put)
    token = actors["worker"]["Authorization"].removeprefix("Bearer ")
    with WorkerClient("http://testserver", token, "test-worker") as worker:
        worker.http.close()

        class Adapter:
            def post(self, url, json, headers=None):
                return client.post(url, json=json, headers={**actors["worker"], **(headers or {})})

            def close(self):
                pass

        worker.http = Adapter()
        assignment = worker.claim(accepted["jobId"])
        with pytest.raises(RuntimeError):
            worker.claim(accepted["jobId"])
        raw = worker.download(assignment["input"]["manifest"])
        assert hashlib.sha256(raw).hexdigest() == assignment["input"]["manifest"]["asset"]["sha256"]
        worker.update_progress(10, "RECONSTRUCTING")
        with pytest.raises(ValueError):
            worker.update_progress(9, "RECONSTRUCTING")
        with pytest.raises(ValueError):
            worker.update_progress(100, "RECONSTRUCTING")
        assert worker.heartbeat(refresh_urls=True)["input"]["kind"] == "RECONSTRUCTION"
        assets = worker.upload_artifacts({"MODEL": glb(), "COLLISION": glb()})
        result = dict(
            type="FURNITURE_RECONSTRUCTION",
            modelAsset=assets["MODEL"],
            collisionAsset=assets["COLLISION"],
            dimensions={"width": 1, "height": 1, "depth": 1},
            boundingBox={"min": {"x": -0.5, "y": 0, "z": -0.5}, "max": {"x": 0.5, "y": 1, "z": 0.5}},
            quality={"scaleSource": "AR_TRACKING", "estimatedScaleErrorM": None, "warnings": []},
        )
        assert worker.complete(result)["status"] == "COMPLETED"
        assert worker.complete(result)["status"] == "COMPLETED"
        assert worker._stop.is_set()
        assert worker._thread.is_alive() or worker._stop.is_set()
    assert len(seen_headers) == 3


def test_worker_failure_lease_loss_and_download_integrity(monkeypatch):
    with WorkerClient("http://127.0.0.1:8000", "synthetic-test-token", "w") as worker:
        worker.assignment = dict(jobId="j", leaseToken="lease", input={})
        worker.http.close()
        transport = httpx.MockTransport(lambda req: httpx.Response(409, json={"code": "JOB_LEASE_LOST"}))
        worker.http = httpx.Client(transport=transport)
        with pytest.raises(httpx.HTTPStatusError):
            worker.heartbeat()
        assert worker.lost.is_set()
        with pytest.raises(RuntimeError):
            worker.request("fail", {})
    with WorkerClient("http://127.0.0.1:8000", "synthetic-test-token", "w") as worker:
        grant = dict(url="https://storage.example/a", asset=dict(byteSize=3, sha256="0" * 64))
        monkeypatch.setattr(
            worker_module.httpx,
            "get",
            lambda *a, **kw: httpx.Response(200, content=b"bad", request=httpx.Request("GET", grant["url"])),
        )
        with pytest.raises(ValueError):
            worker.download(grant)


def test_worker_heartbeat_loop_network_recovery_terminal_failure(monkeypatch):
    with WorkerClient("http://127.0.0.1:8000", "synthetic-test-token", "w") as worker:

        class Stop:
            count = 0

            def wait(self, seconds):
                self.count += 1
                return self.count > 3

            def set(self):
                pass

        worker._stop = Stop()
        outcomes = [
            httpx.ConnectError("short outage"),
            None,
            httpx.HTTPStatusError(
                "lease lost",
                request=httpx.Request("POST", "https://api.example"),
                response=httpx.Response(409),
            ),
        ]

        def heartbeat():
            outcome = outcomes.pop(0)
            if outcome:
                raise outcome

        monkeypatch.setattr(worker, "heartbeat", heartbeat)
        worker._heartbeat_loop()
        assert worker.lost.is_set() and outcomes == []


def test_worker_fail_callback_stops_heartbeat(monkeypatch):
    with WorkerClient("http://127.0.0.1:8000", "synthetic-test-token", "w") as worker:
        seen = []

        def request(action, body):
            seen.append((action, body))
            return {"status": "FAILED"}

        monkeypatch.setattr(worker, "request", request)
        assert worker.fail("INVALID_ARTIFACT", "bad artifact")["status"] == "FAILED"
        assert seen[0][0] == "fail" and not seen[0][1]["error"]["retryable"]
        assert worker._stop.is_set()
