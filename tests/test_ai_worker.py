import hashlib
from datetime import datetime, timedelta, timezone
import io
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import time
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import zipfile

import httpx

from ai_worker import engine, furniture, runner
from ai_worker.client import WorkerClient
from ai_worker.execution import run_engine
from ai_worker.failures import InvalidArtifact, InvalidEngineData, failure_for
from ai_worker.publication import bind_assets, publish


JOB_ID = "00000000-0000-4000-8000-000000000001"
JOB_TYPE = "ROOM_OBJECT_REMOVAL"
ASSIGNMENT = {"jobId": JOB_ID, "type": JOB_TYPE, "attemptNo": 1, "leaseToken": "test-lease"}


class WorkerFailureTest(unittest.TestCase):
    def reject_completion(self, status, body, fail_status=200):
        calls = []

        def handle(request):
            action = request.url.path.rsplit("/", 1)[-1]
            calls.append((action, json.loads(request.content)))
            if action == "claim":
                return httpx.Response(200, json=ASSIGNMENT)
            if action == "complete":
                return httpx.Response(status, json=body)
            if action == "fail":
                return httpx.Response(fail_status, json={})
            self.fail(f"Unexpected action: {action}")

        client = WorkerClient("https://backend.example.test", "test-token", "test-worker")
        client.http.close()
        client.http = httpx.Client(transport=httpx.MockTransport(handle))
        with TemporaryDirectory() as temp:
            args = SimpleNamespace(
                backend="https://backend.example.test", worker_id="test-worker",
                work_root=Path(temp), pr2_repo=Path(temp), furniture_engine=None, furniture_repo=None,
            )
            with (
                patch.object(runner, "WorkerClient", return_value=client),
                patch.object(runner, "run_engine"),
                patch.object(runner, "publish", side_effect=lambda c, _: c.complete({"type": JOB_TYPE})),
                patch("builtins.print"),
                self.assertRaises(httpx.HTTPStatusError) as raised,
            ):
                runner.run_job(args, JOB_ID, "test-token")
            self.assertFalse((Path(temp) / JOB_ID / "1" / "assignment.json").exists())
        self.assertEqual(raised.exception.response.status_code, status)
        return [body["error"] for action, body in calls if action == "fail"]

    def test_validation_rejections_are_permanent(self):
        for status, body in (
            (400, ["non-object problem body"]),
            (409, {"code": "UPLOAD_INCOMPLETE"}),
            (413, {"code": "BODY_TOO_LARGE"}),
            (422, {"code": "INVALID_ARTIFACT"}),
        ):
            with self.subTest(status=status):
                [failure] = self.reject_completion(status, body)
                self.assertEqual(failure["code"], "INVALID_ARTIFACT")
                self.assertFalse(failure["retryable"])

    def test_temporary_rejections_remain_retryable(self):
        for status in (408, 429, 503):
            with self.subTest(status=status):
                [failure] = self.reject_completion(status, {})
                self.assertEqual(failure["code"], "DEPENDENCY_UNAVAILABLE")
                self.assertTrue(failure["retryable"])

    def test_no_failure_report_without_ownership(self):
        for status, code in (
            (401, "UNAUTHORIZED"), (403, "FORBIDDEN"),
            (409, "JOB_LEASE_LOST"), (409, "CALLBACK_CONFLICT"),
        ):
            with self.subTest(code=code):
                self.assertEqual(self.reject_completion(status, {"code": code}), [])

    def test_rejected_failure_report_preserves_original_error(self):
        self.reject_completion(422, {"code": "INVALID_ARTIFACT"}, fail_status=503)

    def test_engine_invalid_input_is_non_retryable(self):
        with TemporaryDirectory() as temp:
            path = Path(temp) / "assignment.json"
            path.write_text(json.dumps({"type": "ROOM_RECONSTRUCTION", "input": {}}))
            env = dict(os.environ, DULLEOBANG_ASSIGNMENT=str(path), DULLEOBANG_OUTPUT=temp)
            with self.assertRaises(InvalidEngineData) as raised:
                run_engine(env, Path(temp) / "engine.log", threading.Event())
        code, _, retry = failure_for(raised.exception, "ROOM_RECONSTRUCTION")
        self.assertEqual((code, retry), ("RECONSTRUCTION_FAILED", False))

    def test_network_errors_and_timeouts_remain_retryable(self):
        for error, expected in (
            (httpx.ConnectError("offline"), "DEPENDENCY_UNAVAILABLE"),
            (TimeoutError("engine deadline"), "WORKER_TIMEOUT"),
        ):
            with self.subTest(expected=expected):
                code, _, retry = failure_for(error, JOB_TYPE)
                self.assertEqual((code, retry), (expected, True))

    def test_signed_storage_grant_failure_can_retry_with_new_urls(self):
        response = httpx.Response(
            403,
            request=httpx.Request("PUT", "https://storage.example.test/model"),
            json={"code": "INVALID_STORAGE_GRANT"},
        )
        with self.assertRaises(httpx.HTTPStatusError) as raised:
            response.raise_for_status()
        code, _, retry = failure_for(raised.exception, JOB_TYPE)
        self.assertEqual((code, retry), ("DEPENDENCY_UNAVAILABLE", True))


class PublicationTest(unittest.TestCase):
    def test_furniture_result_uploads_both_glbs_before_complete(self):
        artifacts = {"MODEL": b"model bytes", "COLLISION": b"collision bytes"}
        ids = {
            "MODEL": "00000000-0000-4000-8000-000000000011",
            "COLLISION": "00000000-0000-4000-8000-000000000012",
        }
        result_type = "FURNITURE_RECONSTRUCTION"
        result = {
            "type": result_type,
            "dimensions": {"width": 1, "height": 1, "depth": 1},
            "boundingBox": {"min": {"x": -0.5, "y": 0, "z": -0.5}, "max": {"x": 0.5, "y": 1, "z": 0.5}},
            "quality": {"scaleSource": "MANUAL_REFERENCE", "estimatedScaleErrorM": None, "warnings": []},
        }
        events = []

        def register(action, data):
            self.assertEqual(action, "artifacts")
            self.assertEqual([file["role"] for file in data["files"]], list(artifacts))
            for file in data["files"]:
                raw = artifacts[file["role"]]
                self.assertEqual(file["format"], "GLB_V2")
                self.assertEqual(file["contentType"], "model/gltf-binary")
                self.assertEqual(file["byteSize"], len(raw))
                self.assertEqual(file["sha256"], hashlib.sha256(raw).hexdigest())
            events.append("register")
            return {"uploads": [
                {"fileId": ids[role], "url": f"https://storage.example.test/{role}", "headers": {}}
                for role in artifacts
            ]}

        def put(url, *, content, **_):
            role = url.rsplit("/", 1)[-1]
            self.assertEqual(content, artifacts[role])
            events.append(role)
            return httpx.Response(200, request=httpx.Request("PUT", url))

        def complete(body):
            events.append("complete")
            self.assertEqual(body["modelAsset"]["fileId"], ids["MODEL"])
            self.assertEqual(body["collisionAsset"]["fileId"], ids["COLLISION"])
            for key, value in result.items():
                self.assertEqual(body[key], value)
            self.assertNotIn("bundle", body)
            return {"status": "COMPLETED"}

        client = Mock(assignment={**ASSIGNMENT, "type": result_type})
        client.request.side_effect = register
        client.complete.side_effect = complete
        client.heartbeat.side_effect = lambda: events.append("heartbeat")
        with TemporaryDirectory() as temp:
            output = Path(temp)
            files = []
            for role, raw in artifacts.items():
                name = f"{role.lower()}.glb"
                (output / name).write_bytes(raw)
                files.append({"key": role, "role": role, "format": "GLB_V2", "path": name})
            (output / "result.json").write_text(json.dumps({"result": result, "files": files}))
            with patch("ai_worker.publication.httpx.put", side_effect=put):
                self.assertEqual(publish(client, output), {"status": "COMPLETED"})
        self.assertEqual(events, ["register", "MODEL", "COLLISION", "heartbeat", "complete"])

    def test_completion_retries_identical_body_after_transport_error(self):
        calls = []

        def handle(request):
            calls.append(request.content)
            if len(calls) == 1:
                raise httpx.ReadTimeout("Response lost", request=request)
            return httpx.Response(200, json={"status": "COMPLETED"})

        with WorkerClient("https://backend.example.test", "test-token", "test-worker") as client:
            client.http.close()
            client.http = httpx.Client(transport=httpx.MockTransport(handle))
            client.assignment = ASSIGNMENT
            self.assertEqual(client.complete({"type": JOB_TYPE}), {"status": "COMPLETED"})
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0], calls[1])

    def test_room_bundle_binding_is_unchanged(self):
        result = {"type": JOB_TYPE, "bundle": {"meshSource": {"objectAssets": []}}}
        refs = {key: {"fileId": key} for key in ("SCENE", "GEOMETRY", "COLLISION", "room", "room-collision")}
        bind_assets(result, refs)
        self.assertEqual(result["bundle"]["sceneAsset"], refs["SCENE"])
        self.assertEqual(result["bundle"]["meshSource"]["roomAsset"], refs["room"])

    def test_missing_output_is_invalid_artifact(self):
        client = Mock()
        with TemporaryDirectory() as temp, self.assertRaises(InvalidArtifact) as raised:
            publish(client, Path(temp))
        client.request.assert_not_called()
        code, _, retry = failure_for(raised.exception, JOB_TYPE)
        self.assertEqual((code, retry), ("INVALID_ARTIFACT", False))


class FurnitureEngineTest(unittest.TestCase):
    def test_polling_requires_a_configured_engine(self):
        job = {"type": "FURNITURE_RECONSTRUCTION", "jobId": JOB_ID}
        args = SimpleNamespace(secret_file=Path("secret"), worker_id="worker", types=runner.ROOM_TYPES)
        with (
            patch.object(runner, "pending_jobs", return_value=[job]),
            patch.object(runner, "worker_token", return_value="worker-token"),
            patch.object(runner, "run_job", return_value=True) as run,
        ):
            runner.process_queue(args)
            run.assert_not_called()
            args.types = runner.SUPPORTED
            runner.process_queue(args)
            run.assert_called_once_with(args, JOB_ID, "worker-token")

    def test_downloads_and_checks_capture_before_invoking_adapter(self):
        raw = b"fixture-capture"
        asset = {
            "fileId": JOB_ID, "byteSize": len(raw), "contentType": "application/zip",
            "format": "CAPTURE_ZIP_V1", "sha256": hashlib.sha256(raw).hexdigest(),
        }
        grant = {"url": "https://storage.example.test/capture?token=secret", "asset": asset}
        dimensions = {"width": 0.9, "height": 0.7, "depth": 0.5}
        assignment = {"input": {
            "captureId": JOB_ID, "category": "TV", "archive": grant, "images": [grant],
            "measuredDimensions": dimensions,
        }}
        downloads = httpx.Client(
            transport=httpx.MockTransport(lambda request: httpx.Response(200, content=raw)),
            trust_env=False,
        )

        def download(method, url, *, trust_env, **kwargs):
            self.assertFalse(trust_env)
            return downloads.stream(method, url, **kwargs)

        def invoke(command, *, env, check):
            executable, source, destination = command
            self.assertEqual(executable, "fixture-engine")
            self.assertEqual(Path(destination), output)
            self.assertNotIn("DULLEOBANG_ASSIGNMENT", env)
            manifest = json.loads(Path(source).read_text(encoding="utf-8"))
            self.assertEqual(manifest["category"], "TV")
            self.assertEqual(manifest["measuredDimensions"], dimensions)
            self.assertEqual(manifest["images"], [])
            self.assertNotIn("url", manifest["archive"])
            self.assertEqual(Path(manifest["archive"]["path"]).read_bytes(), raw)
            return SimpleNamespace(returncode=0, check_returncode=lambda: None)

        with TemporaryDirectory() as temp, downloads:
            output = Path(temp) / "output"
            output.mkdir()
            with (
                patch.dict(os.environ, DULLEOBANG_FURNITURE_ENGINE="fixture-engine",
                           DULLEOBANG_ASSIGNMENT="private.json"),
                patch.object(engine.httpx, "stream", side_effect=download) as stream,
                patch.object(engine.subprocess, "run", side_effect=invoke) as script,
            ):
                engine.reconstruct_furniture(assignment, output)
                script.assert_called_once()
                self.assertEqual(stream.call_count, 1)
                asset["sha256"] = "0" * 64
                with self.assertRaisesRegex(ValueError, "checksum"):
                    engine.reconstruct_furniture(assignment, output)
                script.assert_called_once()
                del assignment["input"]["category"]
                with self.assertRaisesRegex(ValueError, "category"):
                    engine.reconstruct_furniture(assignment, output)
                self.assertEqual(stream.call_count, 2)
                script.assert_called_once()
            self.assertEqual(list(Path(temp).iterdir()), [output])

    def test_furniture_first_and_only_one_claimed_job_per_poll(self):
        jobs = [
            {"type": "ROOM_RECONSTRUCTION", "jobId": "room"},
            {"type": furniture.FURNITURE, "jobId": "stale"},
            {"type": furniture.FURNITURE, "jobId": "furniture"},
        ]
        args = SimpleNamespace(secret_file=Path("secret"), worker_id="worker", types=runner.SUPPORTED)
        with (
            patch.object(runner, "pending_jobs", return_value=jobs),
            patch.object(runner, "worker_token", return_value="worker-token"),
            patch.object(runner, "run_job", side_effect=[False, True]) as run,
        ):
            runner.process_queue(args)
        self.assertEqual([call.args[1] for call in run.call_args_list], ["stale", "furniture"])


class FurniturePipelineTest(unittest.TestCase):
    @staticmethod
    def grant(data, seconds, mime="application/zip"):
        return {
            "clientFileId": JOB_ID,
            "expiresAt": (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat(),
            "url": "https://storage.example.test/capture",
            "asset": {
                "fileId": JOB_ID, "contentType": mime,
                "byteSize": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            },
        }

    def test_zip_claim_passes_category_and_publishes_both_glbs(self):
        with io.BytesIO() as buffer:
            with zipfile.ZipFile(buffer, "w") as archive:
                archive.writestr("capture/meta.json", '{"kind":"furniture"}')
            raw = buffer.getvalue()
        data = {
            "archive": self.grant(raw, 900),
            "furniture": {"name": "TV 받침", "category": "TABLE"},
            "category": "TV",
            "measuredDimensions": {"width": 1, "height": 0.8, "depth": 0.5},
        }
        assignment = {
            **ASSIGNMENT, "type": furniture.FURNITURE, "input": data,
            "leaseExpiresAt": (datetime.now(timezone.utc) + timedelta(seconds=120)).isoformat(),
        }
        events = []

        def route(request):
            action = request.url.path.rsplit("/", 1)[-1]
            events.append(action)
            body = json.loads(request.content)
            if action == "claim":
                return httpx.Response(200, json=assignment)
            if action == "artifacts":
                self.assertEqual([f["role"] for f in body["files"]], ["MODEL", "COLLISION"])
                return httpx.Response(200, json={"uploads": [
                    {"fileId": JOB_ID, "url": f"https://storage.example.test/{f['role']}", "headers": {}}
                    for f in body["files"]
                ]})
            if action == "heartbeat":
                self.assertEqual(body["progressPercent"], 99)
                return httpx.Response(200, json={})
            if action == "complete":
                self.assertIn("modelAsset", body)
                self.assertIn("collisionAsset", body)
                return httpx.Response(200, json={"status": "COMPLETED"})
            self.fail(f"Unexpected action: {action}")

        def execute(environment, log, lost, timeout, tick):
            self.assertEqual(environment["CATEGORY"], "TABLE")
            self.assertEqual(json.loads(environment["MEASURED"]), data["measuredDimensions"])
            self.assertTrue(6500 < int(environment["DEADLINE_EPOCH"]) - time.time() < 6601)
            self.assertEqual(Path(environment["CAPTURE_DIR"]).name, "capture")
            self.assertTrue((Path(environment["CAPTURE_DIR"]) / "meta.json").is_file())
            output = Path(environment["OUTPUT"])
            (output / "progress.json").write_text(json.dumps({"percent": 50, "stage": "SEGMENTING"}))
            tick()
            self.assertEqual(client.progress, 50)
            for filename in ("model.glb", "collision.glb"):
                (output / filename).write_bytes(filename.encode())
            (output / "furniture.json").write_text(json.dumps({
                "dimensions": data["measuredDimensions"], "boundingBox": {}, "quality": {},
            }))
            furniture.write_result(output)

        def upload(url, **kwargs):
            events.append(url.rsplit("/", 1)[-1])
            return httpx.Response(200, request=httpx.Request("PUT", url))

        with TemporaryDirectory() as temp, WorkerClient("https://backend.test", "test-token", "worker") as client:
            root = Path(temp)
            args = SimpleNamespace(
                backend="https://backend.test", worker_id="worker", work_root=root,
                pr2_repo=root, furniture_repo=root, furniture_engine=None,
            )
            client.http.close()
            client.http = httpx.Client(transport=httpx.MockTransport(route))
            with (
                httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, content=raw))) as storage,
                patch.object(runner, "WorkerClient", return_value=client),
                patch.object(engine.httpx, "stream", side_effect=lambda method, url, **_: storage.stream(method, url)),
                patch.object(furniture, "run_engine", side_effect=execute),
                patch("ai_worker.publication.httpx.put", side_effect=upload),
            ):
                self.assertTrue(runner.run_job(args, JOB_ID, "test-token"))
            attempt = root / JOB_ID / "1"
            self.assertFalse((attempt / "capture.zip").exists())
            self.assertFalse((attempt / "zip").exists())
        self.assertEqual(events, ["claim", "artifacts", "MODEL", "COLLISION", "heartbeat", "complete"])

    def test_refresh_reuses_latest_grant(self):
        old = self.grant(b"example", 5, "image/jpeg")
        fresh = self.grant(b"example", 900, "image/jpeg")
        client = Mock(assignment={"input": {"images": [old]}})
        client.heartbeat.side_effect = lambda **_: client.assignment.update(input={"images": [fresh]})
        self.assertIs(furniture.current_grant(client, old), fresh)
        self.assertIs(furniture.current_grant(client, old), fresh)
        client.heartbeat.assert_called_once_with(refresh_urls=True)

    def test_missing_category_never_falls_back_to_generic_detection(self):
        with self.assertRaises(InvalidEngineData):
            furniture.pipeline_environment(Path("."), {"furniture": {"name": "TV"}}, Path("."), Path("."), 0)

    def test_script_exit_code_controls_job_retry(self):
        for status, code, retryable in (
            (2, "SEGMENTATION_FAILED", False), (3, "DEPENDENCY_UNAVAILABLE", True),
        ):
            with self.subTest(status=status), TemporaryDirectory() as temp:
                output = Path(temp)
                (output / "error.json").write_text(json.dumps({"code": code, "message": "시험"}))
                with (
                    patch.dict(os.environ, FURNITURE_REPO=temp),
                    patch.object(furniture.subprocess, "run", return_value=Mock(returncode=status)),
                    self.assertRaises(SystemExit),
                ):
                    furniture.run_pipeline(output)
                problem = furniture.pipeline_failure(output / "failure.json", {code})
                self.assertEqual(problem.report, (code, "시험", retryable))
