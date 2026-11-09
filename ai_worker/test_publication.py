"""Immutable artifact identity and output containment are publication contracts."""

import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import uuid
from unittest.mock import Mock, patch
from .publication import describe_files, publish


class PublicationTests(unittest.TestCase):
    def test_attempt_ids_are_stable_and_scoped(self):
        with tempfile.TemporaryDirectory() as scratch:
            output = Path(scratch)
            raw = b"actual output bytes"
            (output / "scene.ply").write_bytes(raw)
            files = [
                {
                    "path": "scene.ply",
                    "key": "SCENE",
                    "role": "SCENE",
                    "format": "POINT_CLOUD_PLY_V1",
                }
            ]
            assignment = {"jobId": str(uuid.uuid4()), "attemptNo": 1}
            first, _ = describe_files(assignment, files, output)
            again, _ = describe_files(assignment, files, output)
            next_attempt, _ = describe_files({**assignment, "attemptNo": 2}, files, output)
            self.assertEqual(first, again)
            self.assertNotEqual(first[0]["clientFileId"], next_attempt[0]["clientFileId"])
            self.assertEqual(first[0]["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(first[0]["byteSize"], len(raw))

    def test_external_artifact_is_rejected_before_registration(self):
        with tempfile.TemporaryDirectory() as scratch:
            folder = Path(scratch)
            output = folder / "output"
            output.mkdir()
            (folder / "external.glb").write_bytes(b"outside")
            (output / "result.json").write_text(
                json.dumps({"files": [{"path": "../external.glb"}]})
            )
            client = Mock(assignment={"jobId": str(uuid.uuid4()), "attemptNo": 1})
            with patch("ai_worker.publication.httpx.put") as upload:
                with self.assertRaisesRegex(ValueError, "artifact path"):
                    publish(client, output)
            client.request.assert_not_called()
            client.complete.assert_not_called()
            upload.assert_not_called()
