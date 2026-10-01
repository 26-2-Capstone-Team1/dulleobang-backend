from __future__ import annotations

import asyncio
import base64
import copy
import hashlib
import json
import logging
import os
import re
import sqlite3
import time
from contextlib import asynccontextmanager
from http import HTTPStatus

import jwt
from cryptography.fernet import Fernet
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, Response
from jsonschema import Draft202012Validator, FormatChecker
from starlette.exceptions import HTTPException
from starlette.concurrency import run_in_threadpool

from .core import (
    Contract,
    Database,
    Problem,
    Settings,
    checksum_header,
    digest,
    dumps,
    passwords,
    require,
    strict_json,
    uid,
)
from .service import Service

log = logging.getLogger("dulleobang")
API = "/api/v1"


def problem_response(request, exc):
    request_id = getattr(request.state, "request_id", uid())
    headers = dict(exc.headers)
    headers.update({"X-Request-Id": request_id, "Cache-Control": "no-store"})
    if exc.status == 401:
        headers["WWW-Authenticate"] = "Bearer"
    if exc.status in (429, 503):
        headers.setdefault("Retry-After", "1")
    return JSONResponse(
        {
            "type": "urn:dulleobang:problem:" + exc.code.lower(),
            "title": HTTPStatus(exc.status).phrase,
            "status": exc.status,
            "detail": exc.detail,
            "instance": request.url.path,
            "code": exc.code,
            "requestId": request_id,
            "errors": exc.errors,
        },
        status_code=exc.status,
        media_type="application/problem+json",
        headers=headers,
    )


def parsed_query(operation, request):
    declared = {p["name"]: p["schema"] for p in operation["params"] if p["in"] == "query"}
    require(set(request.query_params) <= declared.keys(), 400, "UNKNOWN_QUERY_PARAMETER")
    output = {}
    for name, schema in declared.items():
        if name not in request.query_params:
            if "default" in schema:
                output[name] = schema["default"]
            continue
        values = request.query_params.getlist(name)
        try:
            typ = schema.get("type")
            if typ != "array":
                require(len(values) == 1, 400, "DUPLICATE_QUERY_PARAMETER")
            if typ == "integer":
                require(re.fullmatch(r"-?\d+", values[0]), 400, "INVALID_QUERY_PARAMETER")
                value = int(values[0])
            elif typ == "number":
                value = float(values[0])
                require(float("-inf") < value < float("inf"), 400, "INVALID_QUERY_PARAMETER")
            elif typ == "boolean":
                require(values[0] in ("true", "false"), 400, "INVALID_QUERY_PARAMETER")
                value = values[0] == "true"
            elif typ == "array":
                value = sorted(set(values))
            else:
                value = values[0]
            Draft202012Validator(schema, format_checker=FormatChecker()).validate(value)
            output[name] = value
        except Problem:
            raise
        except Exception as exc:
            raise Problem(400, "INVALID_QUERY_PARAMETER", "Invalid query: " + name) from exc
    return output


def validate_parameters(operation, request):
    for p in operation["params"]:
        if p["in"] == "path":
            value = request.path_params[p["name"]]
        elif p["in"] == "header":
            value = request.headers.get(p["name"])
            if value is None:
                # Domain methods handle conditional header exceptions (e.g. repeated delete).
                if p["required"] and p["name"] == "Idempotency-Key":
                    raise Problem(400, "IDEMPOTENCY_KEY_REQUIRED")
                if p["required"] and p["name"] == "X-Job-Lease-Token":
                    raise Problem(409, "JOB_LEASE_LOST")
                continue
        else:
            continue
        try:
            Draft202012Validator(p["schema"], format_checker=FormatChecker()).validate(value)
        except Exception as exc:
            raise Problem(400, "INVALID_PARAMETER", "Invalid parameter: " + p["name"]) from exc


def normalize(body):
    if not isinstance(body, dict):
        return body
    value = copy.deepcopy(body)
    for key in (
        "title",
        "name",
        "displayName",
        "fullName",
        "officeName",
        "location",
        "registrationNumber",
        "phone",
    ):
        if isinstance(value.get(key), str):
            value[key] = value[key].strip()
    if isinstance(value.get("email"), str):
        value["email"] = value["email"].strip().lower()
    return value


async def read_json(request, operation):
    chunks, size = [], 0
    async for chunk in request.stream():
        size += len(chunk)
        require(size <= 10 << 20, 413, "BODY_TOO_LARGE")
        chunks.append(chunk)
    raw = b"".join(chunks)
    if not raw:
        require(operation["req"] is None, 422, "BODY_REQUIRED")
        return None
    require(
        request.headers.get("content-type", "").split(";")[0].strip().lower() == "application/json",
        415,
        "UNSUPPORTED_MEDIA_TYPE",
    )
    require(operation["req"] is not None, 400, "UNEXPECTED_BODY")
    try:
        return strict_json(raw)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise Problem(400, "MALFORMED_JSON") from exc


def create_app(settings=None):
    settings = settings or Settings()
    settings.prepare()
    settings.dummy_password_hash = passwords.hash("unknown-account-" + uid())
    database, contract = Database(settings), Contract(settings)
    cipher = Fernet(
        base64.urlsafe_b64encode(hashlib.sha256((settings.jwt_key + ":idempotency").encode()).digest())
    )

    def maintain():
        with database.transaction() as store:
            Service(store, settings, contract).maintenance()

    async def scheduler():
        while True:
            try:
                await run_in_threadpool(maintain)
            except Exception:
                log.exception("Maintenance failed")
            await asyncio.sleep(5)

    @asynccontextmanager
    async def lifespan(app):
        await run_in_threadpool(maintain)
        task = asyncio.create_task(scheduler()) if settings.scheduler else None
        yield
        if task:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    app = FastAPI(
        title="둘러방 API",
        version="1.1.0",
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url=None,
        openapi_url="/openapi.json",
    )
    app.state.settings, app.state.database, app.state.contract = settings, database, contract
    app.state.maintenance = maintain
    # Serve the source contract verbatim; runtime validation uses the same schemas.
    app.openapi = lambda: copy.deepcopy(contract.spec)

    @app.middleware("http")
    async def request_context(request, call_next):
        incoming = request.headers.get("x-request-id", "")
        request.state.request_id = incoming if re.fullmatch(r"[A-Za-z0-9_-]{1,100}", incoming) else uid()
        try:
            response = await call_next(request)
        except Problem as exc:
            response = problem_response(request, exc)
        except Exception:
            # Log no body, Authorization header, refresh token or signed URL.
            log.exception(
                "Request failed: %s %s requestId=%s",
                request.method,
                request.url.path,
                request.state.request_id,
            )
            response = problem_response(request, Problem(500, "INTERNAL_ERROR", "Unexpected server error."))
        response.headers["X-Request-Id"] = request.state.request_id
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    if settings.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origins,
            allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE"],
            allow_headers=[
                "Authorization",
                "Content-Type",
                "Idempotency-Key",
                "If-Match",
                "If-None-Match",
                "X-Request-Id",
                "X-Job-Lease-Token",
                "x-amz-checksum-sha256",
            ],
            expose_headers=["ETag", "Location", "Retry-After", "X-Request-Id"],
        )

    @app.exception_handler(Problem)
    async def business_exception(request, exc):
        return problem_response(request, exc)

    @app.exception_handler(HTTPException)
    async def http_exception(request, exc):
        return problem_response(
            request,
            Problem(
                exc.status_code, "METHOD_NOT_ALLOWED" if exc.status_code == 405 else "RESOURCE_NOT_FOUND"
            ),
        )

    @app.exception_handler(OSError)
    async def storage_exception(request, exc):
        return problem_response(
            request, Problem(503, "STORAGE_UNAVAILABLE", "Storage temporarily unavailable.")
        )

    @app.get("/health/live", include_in_schema=False)
    def live():
        return {"status": "ok"}

    @app.get("/health/ready", include_in_schema=False)
    def ready():
        try:
            with database.connect() as conn:
                conn.execute("SELECT 1 FROM schema_migrations").fetchone()
            return {"status": "ready"}
        except sqlite3.Error as exc:
            raise Problem(503, "DATABASE_UNAVAILABLE") from exc

    def rate(operation, body, auth, ip):
        if not settings.rate_limits:
            return
        # Separate transaction: rejected requests also count, even if business work rolls back.
        with database.transaction() as store:
            svc = Service(store, settings, contract)
            public = operation["oid"] in (
                "signup",
                "login",
                "refreshToken",
                "logout",
                "loginKakao",
                "loginGoogle",
            )
            if not public:
                svc.authenticate(auth, operation["internal"])
            actor = svc.worker if operation["internal"] else (svc.user["userId"] if svc.user else ip)
            buckets = []
            if operation["internal"]:
                buckets.append(("worker:" + actor, 600))
            elif public:
                buckets.append(("auth-ip:" + ip, 10))
                if isinstance(body, dict) and body.get("email"):
                    buckets.append(
                        ("auth-account:" + hashlib.sha256(body["email"].lower().encode()).hexdigest(), 5)
                    )
            else:
                buckets.append(("user:" + actor, 120))
                if operation["oid"] in ("createUploadSession", "renewUploadUrls", "getAssetDownload"):
                    buckets.append(("urls:" + actor, 20))
                if operation["oid"] in (
                    "submitRoomCapture",
                    "submitFurnitureCapture",
                    "createRoomState",
                    "retryJob",
                ):
                    buckets.append(("ai:" + actor, 10))
                if operation["oid"] == "getJob":
                    buckets.append(("poll:" + actor + ":" + operation["_jobId"], 20))
            window = int(time.time() // 60)
            rejected = False
            for bucket, limit in buckets:
                store.conn.execute(
                    "INSERT INTO rate_limits VALUES(?,?,1) ON CONFLICT(bucket,window) DO UPDATE SET count=count+1",
                    (bucket, window),
                )
                count = store.conn.execute(
                    "SELECT count FROM rate_limits WHERE bucket=? AND window=?", (bucket, window)
                ).fetchone()[0]
                rejected |= count > limit
            if rejected:
                raise Problem(
                    429,
                    "RATE_LIMIT_EXCEEDED",
                    commit=True,
                    headers={"Retry-After": str(max(1, 60 - int(time.time()) % 60))},
                )

    def execute(operation, body, params, query, headers):
        try:
            with database.transaction() as store:
                svc = Service(store, settings, contract, headers=headers, query=query, params=params)
                if operation["oid"] not in (
                    "signup",
                    "login",
                    "refreshToken",
                    "logout",
                    "loginKakao",
                    "loginGoogle",
                ):
                    svc.authenticate(headers.get("authorization", ""), operation["internal"])
                if operation["req"]:
                    contract.validate(operation["req"], body)
                idem = next((p for p in operation["params"] if p["name"] == "Idempotency-Key"), None)
                if idem:
                    actor = svc.idempotency_actor(operation["oid"], body)
                    key = headers["idempotency-key"]
                    scope = operation["method"].upper() + " " + headers["_path"]
                    body_hash = digest(body)
                    entry = store.conn.execute(
                        "SELECT * FROM idempotency WHERE actor=? AND operation=? AND key=? AND expires_at>?",
                        (actor, scope, key, time.time()),
                    ).fetchone()
                    if entry:
                        require(entry["digest"] == body_hash, 409, "IDEMPOTENCY_KEY_REUSED")
                        # All stored response bodies are authenticated and encrypted, including refresh tokens.
                        result = json.loads(cipher.decrypt(entry["response"].encode()))
                        result["body"] = svc.present(result["body"])
                        if operation["res"]:
                            contract.validate(operation["res"], result["body"], output=True)
                        return result
                value = getattr(svc, operation["oid"])(body)
                if operation["res"]:
                    contract.validate(operation["res"], svc.present(value), output=True)
                result = {
                    "status": getattr(svc, "status_override", operation["status"]),
                    "body": value,
                    "headers": svc.extra,
                }
                if idem:
                    store.conn.execute(
                        "INSERT OR REPLACE INTO idempotency VALUES(?,?,?,?,?,?)",
                        (
                            actor,
                            scope,
                            key,
                            body_hash,
                            cipher.encrypt(dumps(result).encode()).decode(),
                            time.time() + 86400,
                        ),
                    )
                result["body"] = svc.present(result["body"])
                return result
        except sqlite3.OperationalError as exc:
            raise Problem(503, "DATABASE_UNAVAILABLE", "Storage temporarily unavailable.") from exc

    def endpoint_for(operation):
        async def endpoint(request: Request):
            validate_parameters(operation, request)
            body = normalize(await read_json(request, operation))
            query = parsed_query(operation, request)
            auth = request.headers.get("authorization", "")
            current_op = dict(operation, _jobId=request.path_params.get("jobId"))
            await run_in_threadpool(
                rate, current_op, body, auth, request.client.host if request.client else "unknown"
            )
            headers = dict(request.headers)
            headers["_path"] = request.url.path
            result = await run_in_threadpool(execute, operation, body, request.path_params, query, headers)
            if result["status"] == 204:
                return Response(status_code=204, headers=result["headers"])
            return JSONResponse(result["body"], status_code=result["status"], headers=result["headers"])

        endpoint.__name__ = operation["oid"]
        return endpoint

    for operation in contract.ops:
        app.add_api_route(
            API + operation["path"],
            endpoint_for(operation),
            methods=[operation["method"].upper()],
            operation_id=operation["oid"],
        )

    def storage_record(file_id, token, method):
        try:
            claims = jwt.decode(
                token,
                settings.storage_key,
                algorithms=["HS256"],
                audience="storage",
                issuer="dulleobang-storage",
                options={"require": ["exp", "file", "method", "sha"]},
            )
        except jwt.InvalidTokenError as exc:
            raise Problem(403, "INVALID_STORAGE_GRANT") from exc
        require(claims["file"] == file_id and claims["method"] == method, 403, "INVALID_STORAGE_GRANT")
        with database.transaction() as store:
            f = store.get("file", file_id)["value"]
            require(f["sha256"] == claims["sha"], 403, "INVALID_STORAGE_GRANT")
            Service(store, settings, contract).parent_live(f["_targetType"], f["_targetId"])
            if method == "PUT":
                require(not f["_verified"], 409, "IMMUTABLE_FILE")
                if f["_sessionId"]:
                    session = store.get("upload", f["_sessionId"])["value"]
                    require(session["status"] == "OPEN", 409, "IMMUTABLE_FILE")
                    require(epoch_value(session["expiresAt"]) > time.time(), 410, "UPLOAD_SESSION_EXPIRED")
                else:
                    job = store.get("job", f["_jobId"])["value"]
                    require(
                        job["status"] == "RUNNING"
                        and job["attemptNo"] == f["_attempt"]
                        and job["_leaseExpires"] > time.time(),
                        409,
                        "JOB_LEASE_LOST",
                    )
            else:
                require(f["_stored"] and f["_verified"], 404, "RESOURCE_NOT_FOUND")
            f["_grantExpires"] = claims["exp"]
            return f

    def epoch_value(s):
        from .core import epoch

        return epoch(s)

    @app.put("/storage/{file_id}", include_in_schema=False)
    async def put_object(file_id: str, request: Request):
        token = request.query_params.get("token", "")
        f = await run_in_threadpool(storage_record, file_id, token, "PUT")
        require(
            request.headers.get("content-type") == f["contentType"]
            and request.headers.get("x-amz-checksum-sha256") == checksum_header(f["sha256"]),
            422,
            "UPLOAD_HEADERS_MISMATCH",
        )
        if request.headers.get("content-length"):
            require(
                request.headers["content-length"].isdigit()
                and int(request.headers["content-length"]) == f["byteSize"],
                422,
                "BYTE_SIZE_MISMATCH",
            )
        stage = settings.data_dir / "staging" / uid()
        try:
            sha, size = hashlib.sha256(), 0
            with stage.open("xb") as stream:
                async for chunk in request.stream():
                    size += len(chunk)
                    require(size <= f["byteSize"], 413, "UPLOAD_TOO_LARGE")
                    sha.update(chunk)
                    await run_in_threadpool(stream.write, chunk)
                await run_in_threadpool(stream.flush)
                await run_in_threadpool(os.fsync, stream.fileno())
            require(size == f["byteSize"] and sha.hexdigest() == f["sha256"], 422, "CHECKSUM_MISMATCH")
            # Check again after streaming: verify/cancel/lease expiry may have won meanwhile.
            await run_in_threadpool(storage_record, file_id, token, "PUT")

            def commit_file():
                with database.transaction() as store:
                    latest = store.get("file", file_id)["value"]
                    require(not latest["_verified"], 409, "IMMUTABLE_FILE")
                    require(f["_grantExpires"] > time.time(), 403, "INVALID_STORAGE_GRANT")
                    Service(store, settings, contract).parent_live(latest["_targetType"], latest["_targetId"])
                    if latest["_sessionId"]:
                        session = store.get("upload", latest["_sessionId"])["value"]
                        require(session["status"] == "OPEN", 409, "IMMUTABLE_FILE")
                        require(
                            epoch_value(session["expiresAt"]) > time.time(), 410, "UPLOAD_SESSION_EXPIRED"
                        )
                    else:
                        job = store.get("job", latest["_jobId"])["value"]
                        require(
                            job["status"] == "RUNNING"
                            and job["attemptNo"] == latest["_attempt"]
                            and job["_leaseExpires"] > time.time()
                            and job["_attemptStarted"] + 1800 > time.time(),
                            409,
                            "JOB_LEASE_LOST",
                        )
                    os.replace(stage, settings.data_dir / "objects" / f["sha256"])
                    latest["_stored"] = True
                    store.put("file", file_id, latest, store.get("file", file_id)["owner"])

            await run_in_threadpool(commit_file)
            return Response(status_code=200, headers={"ETag": '"' + sha.hexdigest() + '"'})
        finally:
            if stage.exists():
                stage.unlink()

    @app.get("/storage/{file_id}", include_in_schema=False)
    async def get_object(file_id: str, request: Request):
        f = await run_in_threadpool(storage_record, file_id, request.query_params.get("token", ""), "GET")
        path = settings.data_dir / "objects" / f["sha256"]
        require(path.is_file(), 404, "RESOURCE_NOT_FOUND")
        return FileResponse(
            path,
            media_type=f["contentType"],
            filename=file_id,
            headers={"Content-Disposition": "attachment; filename=" + file_id},
        )

    return app


def app_factory():
    return create_app()
