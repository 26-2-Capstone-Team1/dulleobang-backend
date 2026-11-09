"""Real TCP scenarios against the copied legacy backend and disposable data."""
from pathlib import Path
from tempfile import TemporaryDirectory
import os
import socket
import subprocess
import sys
import time
import urllib.request
ROOT = Path(__file__).resolve().parents[1]
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
with TemporaryDirectory() as scratch, (ROOT / "verification/legacy-http-server.log").open("w", encoding="utf-8") as log:
    env = dict(os.environ, DATA_DIR=scratch, PUBLIC_BASE_URL=f"http://127.0.0.1:{port}")
    server = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app_factory", "--factory",
                               "--host", "127.0.0.1", "--port", str(port), "--no-access-log"],
                              cwd=ROOT / "legacy-baseline", env=env, stdout=log, stderr=log)
    try:
        for _ in range(100):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{port}/health/ready", timeout=1)
                break
            except OSError:
                if server.poll() is not None: raise RuntimeError("Copied legacy failed: see verification/legacy-http-server.log")
                time.sleep(0.1)
        else: raise RuntimeError("Copied legacy readiness timeout")
        subprocess.run([sys.executable, str(ROOT / "contract-tests/run.py"), "--base-url", f"http://127.0.0.1:{port}",
                        "--profile", "legacy", "--report", str(ROOT / "verification/legacy-http-contracts.json")], check=True)
    finally:
        server.terminate()
        try: server.wait(timeout=10)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
