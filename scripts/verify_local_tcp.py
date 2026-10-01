"""Local-only real TCP, shipped worker SDK, concurrency and process restart checks."""

import hashlib
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import httpx

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))
sys.path.insert(0, str(BACKEND / "tests"))
from app.core import Settings, uid, worker_token  # noqa: E402
from app.worker import WorkerClient  # noqa: E402
from conftest import example, glb, image, info  # noqa: E402


def run():
    results = []
    with tempfile.TemporaryDirectory(prefix="dulleobang-tcp-") as folder:
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        base = f"http://127.0.0.1:{port}"
        env = dict(
            os.environ, DATA_DIR=folder, APP_ENV="test", PUBLIC_BASE_URL=base, PYTHONIOENCODING="utf-8"
        )
        for key in ("JWT_SECRET", "WORKER_JWT_SECRET", "STORAGE_SECRET"):
            env.pop(key, None)
        proc = None

        def start():
            process = subprocess.Popen(
                [
                    sys.executable,
                    "-m",
                    "uvicorn",
                    "app.main:app_factory",
                    "--factory",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(port),
                    "--no-access-log",
                ],
                cwd=BACKEND,
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
            )
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline:
                try:
                    if httpx.get(base + "/health/ready", timeout=1, trust_env=False).status_code == 200:
                        return process
                except httpx.RequestError:
                    pass
                if process.poll() is not None:
                    raise RuntimeError("Temporary Uvicorn process exited")
                time.sleep(0.1)
            process.terminate()
            process.wait(timeout=10)
            raise RuntimeError("Temporary Uvicorn did not become ready")

        def stop(process):
            if process and process.poll() is None:
                process.terminate()
                process.wait(timeout=10)

        try:
            proc = start()
            with httpx.Client(base_url=base, timeout=30, trust_env=False) as http:
                api = "/api/v1"
                body = dict(
                    email="tcp-test@example.com", password="StrongPassword!123", displayName="TCP 실습"
                )
                assert http.post(api + "/auth/signup", json=body).status_code == 201
                pair = http.post(api + "/auth/login", json={k: body[k] for k in ("email", "password")}).json()
                h = {"Authorization": "Bearer " + pair["accessToken"]}
                key = uid()

                def concurrent(i):
                    return http.post(
                        api + "/listings",
                        json=example("ListingCreate"),
                        headers={**h, "Idempotency-Key": key},
                    )

                started = time.perf_counter()
                with ThreadPoolExecutor(max_workers=8) as pool:
                    rs = list(pool.map(concurrent, range(8)))
                assert all(r.status_code == 201 for r in rs)
                assert len({r.json()["listingId"] for r in rs}) == 1
                lid = rs[0].json()["listingId"]
                results.append(
                    dict(
                        check="TCP concurrent idempotency",
                        requests=8,
                        seconds=round(time.perf_counter() - started, 3),
                        passed=True,
                    )
                )

                def patch(i):
                    return http.patch(
                        api + "/listings/" + lid,
                        json={"description": "수정" + str(i)},
                        headers={**h, "If-Match": '"1"'},
                    )

                with ThreadPoolExecutor(max_workers=8) as pool:
                    rs = list(pool.map(patch, range(8)))
                assert sorted(r.status_code for r in rs) == [200] + [412] * 7
                results.append(dict(check="TCP ETag race", requests=8, passed=True))
                started = time.perf_counter()
                with ThreadPoolExecutor(max_workers=8) as pool:
                    rs = list(pool.map(lambda i: http.get(api + "/users/me", headers=h), range(64)))
                assert all(r.status_code == 200 for r in rs)
                results.append(
                    dict(
                        check="Local concurrent reads (not production capacity)",
                        requests=64,
                        concurrency=8,
                        seconds=round(time.perf_counter() - started, 3),
                        passed=True,
                    )
                )
                f = http.post(
                    api + "/furniture",
                    json={"name": "네트워크 검증", "category": "DESK"},
                    headers={**h, "Idempotency-Key": uid()},
                ).json()
                fid = f["furnitureId"]
                raw = image()
                files = [info(raw, f"image{i}.jpg", "image/jpeg") for i in range(12)]
                manifest = example("CameraManifest")
                frame = example("CameraFrame")
                frame.update(
                    widthPx=640, heightPx=480, intrinsics={"fx": 500, "fy": 500, "cx": 320, "cy": 240}
                )
                manifest["frames"] = [
                    {**frame, "imageClientFileId": file["clientFileId"], "timestampUs": i * 100000}
                    for i, file in enumerate(files)
                ]
                camraw = json.dumps(manifest).encode()
                files.append(info(camraw, "camera.json", "application/json"))

                def submit_capture():
                    session = http.post(
                        api + "/upload-sessions",
                        json=dict(
                            purpose="FURNITURE_CAPTURE", targetType="FURNITURE", targetId=fid, files=files
                        ),
                        headers={**h, "Idempotency-Key": uid()},
                    )
                    assert session.status_code == 201, session.status_code
                    session = session.json()
                    for grant, content in zip(session["uploads"], [raw] * 12 + [camraw]):
                        assert (
                            http.put(grant["url"], content=content, headers=grant["headers"]).status_code
                            == 200
                        )
                    sid = session["session"]["uploadSessionId"]
                    assert (
                        http.post(api + "/upload-sessions/" + sid + "/complete", headers=h).status_code == 200
                    )
                    accepted = http.post(
                        api + "/furniture/" + fid + "/captures",
                        json={"uploadSessionId": sid},
                        headers={**h, "Idempotency-Key": uid()},
                    )
                    assert accepted.status_code == 202
                    return accepted.json()

                accepted = submit_capture()
                cfg = Settings(data_dir=Path(folder))
                cfg.prepare()
                with WorkerClient(base, worker_token(cfg, "tcp-worker"), "tcp-worker") as worker:
                    assignment = worker.claim(accepted["jobId"])
                    for grant in [assignment["input"]["manifest"], *assignment["input"]["images"]]:
                        content = worker.download(grant)
                        assert hashlib.sha256(content).hexdigest() == grant["asset"]["sha256"]
                    worker.update_progress(25, "RECONSTRUCTING")
                    assert worker.heartbeat(refresh_urls=True)["input"]["kind"] == "RECONSTRUCTION"
                    assets = worker.upload_artifacts({"MODEL": glb(), "COLLISION": glb()})
                    result = dict(
                        type="FURNITURE_RECONSTRUCTION",
                        modelAsset=assets["MODEL"],
                        collisionAsset=assets["COLLISION"],
                        dimensions={"width": 1, "height": 1, "depth": 1},
                        boundingBox={
                            "min": {"x": -0.5, "y": 0, "z": -0.5},
                            "max": {"x": 0.5, "y": 1, "z": 0.5},
                        },
                        quality={"scaleSource": "AR_TRACKING", "estimatedScaleErrorM": None, "warnings": []},
                    )
                    assert worker.complete(result)["status"] == "COMPLETED"
                    assert worker.complete(result)["status"] == "COMPLETED"
                results.append(
                    dict(
                        check="Actual WorkerClient TCP claim/download/heartbeat/upload/complete/replay",
                        syntheticFixture=True,
                        passed=True,
                    )
                )
                pending = submit_capture()
                stop(proc)
                proc = None
                proc = start()
                assert http.get(api + "/users/me", headers=h).status_code == 200
                current = http.get(api + "/furniture/" + fid, headers=h).json()
                assert current["model"]["modelVersion"] == 1
                assert http.get(current["model"]["modelAsset"]["url"]).status_code == 200
                with WorkerClient(base, worker_token(cfg, "restart-worker"), "restart-worker") as worker:
                    assert worker.claim(pending["jobId"])["attemptNo"] == 1
                    assert worker.fail("INVALID_ARTIFACT", "테스트 종료")["status"] == "FAILED"
                results.append(
                    dict(
                        check="Process terminate/restart preserves session/model/blob/pending job and permits worker claim",
                        passed=True,
                    )
                )
        finally:
            stop(proc)
    report = dict(
        passed=True,
        checks=results,
        limitations=[
            "Loopback single Windows host only",
            "Valid synthetic GLB fixture; no real AI algorithm/quality measurement",
            "No production capacity claim",
        ],
    )
    (BACKEND / "validation").mkdir(exist_ok=True)
    (BACKEND / "validation/tcp.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    run()
