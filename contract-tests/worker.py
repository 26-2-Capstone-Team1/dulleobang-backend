"""Exercise the unchanged Python WorkerClient against Spring's real HTTP server."""
from pathlib import Path
import json
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "legacy-baseline"))
from app.worker import WorkerClient

config = json.load(sys.stdin)
checks = []
with WorkerClient(config["baseUrl"], config["token"], "worker") as worker:
    assignment = worker.claim(config["jobId"])
    assert assignment["attemptNo"] == 1
    checks.append("claim")
    # Test server advertises an HTTPS storage origin; route its signed grant to local TCP.
    grant = dict(assignment["input"]["manifest"])
    uri = urlsplit(grant["url"])
    grant["url"] = config["baseUrl"] + uri.path + "?" + uri.query
    manifest = json.loads(worker.download(grant))
    assert len(manifest["frames"]) == 12
    checks.append("download-and-checksum")
    worker.update_progress(25, "RECONSTRUCTING")
    assert "input" in worker.heartbeat(refresh_urls=True)
    checks.append("heartbeat-and-refresh")
    failure = worker.fail("DEPENDENCY_UNAVAILABLE", "test dependency unavailable", retryable=False)
    assert failure["status"] == "RETRY_WAIT" and failure["error"]["retryable"] is True
    checks.append("server-owned-retry-policy")
(ROOT / "verification/python-worker-http-contracts.json").write_text(
    json.dumps({"passed": True, "checks": checks}, indent=2), encoding="utf-8"
)
print("4 unchanged Python WorkerClient HTTP checks passed")
