"""Issue short-lived worker-only tokens from the VM-local signing key."""

import base64
import hashlib
import hmac
import json
import time

WORKER_TOKEN_TTL_SECONDS = 3 * 60 * 60


def worker_token(secret_file, worker_id):
    def encode(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).rstrip(
            b"="
        )

    now = int(time.time())
    message = b".".join(
        [
            encode({"alg": "HS256", "typ": "JWT"}),
            encode(
                {
                    "iss": "dulleobang-worker",
                    "aud": "dulleobang-internal",
                    "sub": worker_id,
                    "scope": "AI_WORKER",
                    "iat": now,
                    "exp": now + WORKER_TOKEN_TTL_SECONDS,
                }
            ),
        ]
    )
    signature = hmac.new(secret_file.read_text().strip().encode(), message, hashlib.sha256).digest()
    return (message + b"." + base64.urlsafe_b64encode(signature).rstrip(b"=")).decode()
