from __future__ import annotations

import copy
import hashlib
import secrets
import sqlite3
import time

import jwt
from argon2.exceptions import VerificationError

from .core import (
    Problem,
    bump,
    checksum_header,
    digest,
    encode_token,
    epoch,
    etag,
    passwords,
    require,
    stamp,
    strict_json,
    token_hash,
    uid,
)
from .features import ProductFeatures
from .capture_inputs import classify_files, input_format, verify_inputs

from .geometry import (
    bbox,
    bundle_geometry,
    quaternion,
    stripped_photo,
    unique,
    validate_glb,
    validate_ply,
)

ACTIVE = {"QUEUED", "RUNNING", "RETRY_WAIT"}
TRANSIENT = {"WORKER_TIMEOUT", "DEPENDENCY_UNAVAILABLE", "INTERNAL_ERROR"}


class Service(ProductFeatures):
    """One request, one serializable transaction; internal metadata never leaves this layer."""

    def __init__(self, store, settings, contract, actor=None, headers=None, query=None, params=None):
        self.s, self.cfg, self.contract = store, settings, contract
        self.user = actor
        self.h = headers or {}
        self.q = query or {}
        self.p = params or {}
        self.extra = {}

    def role(self, role):
        require(self.user and role in self.user["roles"], 403, "FORBIDDEN")

    def own(self, kind, id, *, deleted=False):
        r = self.s.get(kind, id, deleted=deleted)
        require(r["owner"] == self.user["userId"], 404, "RESOURCE_NOT_FOUND")
        if r["deleted"] is not None:
            require(time.time() - r["deleted"] < 30 * 86400, 404, "RESOURCE_NOT_FOUND")
        return r

    def listing(self, id, *, own=False, deleted=False):
        if own:
            return self.own("listing", id, deleted=deleted)
        r = self.s.get("listing", id)
        require(
            r["value"]["status"] == "PUBLISHED" or r["owner"] == self.user["userId"],
            404,
            "RESOURCE_NOT_FOUND",
        )
        return r

    def parent_live(self, target_type, target_id):
        if target_type == "LISTING":
            return self.s.get("listing", target_id)
        if target_type == "FURNITURE":
            return self.s.get("furniture", target_id)
        if target_type == "USER":
            return self.s.get("user", target_id)
        state = self.s.get("roomstate", target_id)
        self.s.get("listing", state["parent"])
        return state

    def job_own(self, id):
        r = self.own("job", id)
        self.parent_live(r["value"]["targetType"], r["value"]["targetId"])
        return r

    def response_etag(self, value):
        self.extra["ETag"] = f'"{value["version"]}"'
        return value

    def save(self, kind, id, value):
        r = self.s.get(kind, id)
        return self.s.put(kind, id, value, r["owner"], r["parent"])

    def public_job(self, job):
        return {k: v for k, v in job.items() if not k.startswith("_")}

    def public_session(self, session):
        return {k: v for k, v in session.items() if not k.startswith("_")}

    def authenticate(self, authorization, internal=False):
        require(authorization.startswith("Bearer "), 401, "UNAUTHORIZED")
        try:
            claims = jwt.decode(
                authorization[7:],
                self.cfg.worker_key if internal else self.cfg.jwt_key,
                algorithms=["HS256"],
                audience="dulleobang-internal" if internal else "dulleobang-mobile",
                issuer="dulleobang-worker" if internal else "dulleobang",
                options={"require": ["exp", "iat", "sub", "iss", "aud"] + ([] if internal else ["sid"])},
            )
            if internal:
                require(claims.get("scope") == "AI_WORKER", 403, "FORBIDDEN")
                self.worker = claims["sub"]
                return None
            session = self.s.conn.execute("SELECT * FROM sessions WHERE id=?", (claims["sid"],)).fetchone()
            require(
                session
                and session["user_id"] == claims["sub"]
                and not session["revoked"]
                and session["expires_at"] > time.time(),
                401,
                "UNAUTHORIZED",
            )
            self.user = self.s.get("user", claims["sub"])["value"]
            return self.user
        except jwt.InvalidTokenError as exc:
            raise Problem(401, "UNAUTHORIZED") from exc

    def tokens(self, user, session_id=None):
        now = time.time()
        if session_id is None:
            session_id = uid()
            self.s.conn.execute(
                "INSERT INTO sessions VALUES(?,?,?,0)", (session_id, user["userId"], now + 14 * 86400)
            )
        self.s.conn.execute("UPDATE sessions SET expires_at=? WHERE id=?", (now + 14 * 86400, session_id))
        raw = secrets.token_urlsafe(64)
        self.s.conn.execute(
            "INSERT INTO refresh_tokens VALUES(?,?,?,0)", (token_hash(raw), session_id, now + 14 * 86400)
        )
        return {
            "accessToken": encode_token(self.cfg, user, session_id),
            "refreshToken": raw,
            "tokenType": "Bearer",
            "expiresIn": 900,
            "refreshExpiresIn": 1209600,
            "user": user,
        }

    def signup(self, b):
        user = {
            "userId": uid(),
            "email": b["email"].lower().strip(),
            "displayName": b["displayName"],
            "roles": ["TENANT"],
            "authProviders": ["EMAIL"],
            "phone": None,
            "isPhonePublic": False,
            "version": 1,
            "createdAt": stamp(),
        }
        try:
            self.s.conn.execute(
                "INSERT INTO credentials VALUES(?,?,?)",
                (user["userId"], user["email"], passwords.hash(b["password"])),
            )
        except sqlite3.IntegrityError as exc:
            raise Problem(409, "EMAIL_ALREADY_EXISTS") from exc
        return self.s.put("user", user["userId"], user, user["userId"])

    def login(self, b):
        row = self.s.conn.execute(
            "SELECT * FROM credentials WHERE email=?", (b["email"].strip().lower(),)
        ).fetchone()
        # A real hash is also checked for unknown accounts to avoid a cheap timing oracle.
        stored = row["password_hash"] if row else self.cfg.dummy_password_hash
        try:
            passwords.verify(stored, b["password"])
        except VerificationError as exc:
            raise Problem(401, "INVALID_CREDENTIALS") from exc
        require(row is not None, 401, "INVALID_CREDENTIALS")
        if passwords.check_needs_rehash(stored):
            self.s.conn.execute(
                "UPDATE credentials SET password_hash=? WHERE user_id=?",
                (passwords.hash(b["password"]), row["user_id"]),
            )
        return self.tokens(self.s.get("user", row["user_id"])["value"])

    def refresh_record(self, raw):
        row = self.s.conn.execute(
            "SELECT r.*,s.user_id,s.revoked,s.expires_at AS session_expires FROM refresh_tokens r JOIN sessions s ON r.session_id=s.id WHERE digest=?",
            (token_hash(raw),),
        ).fetchone()
        require(
            row
            and not row["revoked"]
            and row["expires_at"] > time.time()
            and row["session_expires"] > time.time(),
            401,
            "INVALID_REFRESH_TOKEN",
        )
        return row

    def refreshToken(self, b):
        row = self.refresh_record(b["refreshToken"])
        if row["consumed"]:
            self.s.conn.execute("UPDATE sessions SET revoked=1 WHERE id=?", (row["session_id"],))
            raise Problem(401, "TOKEN_REUSED", commit=True)
        self.s.conn.execute("UPDATE refresh_tokens SET consumed=1 WHERE digest=?", (row["digest"],))
        return self.tokens(self.s.get("user", row["user_id"])["value"], row["session_id"])

    def logout(self, b):
        row = self.s.conn.execute(
            "SELECT session_id FROM refresh_tokens WHERE digest=?", (token_hash(b["refreshToken"]),)
        ).fetchone()
        if row:
            self.s.conn.execute("UPDATE sessions SET revoked=1 WHERE id=?", (row[0],))

    def getMe(self, b):
        return self.response_etag(self.user)

    def updateMe(self, b):
        etag(self.user, self.h.get("if-match"))
        self.user.update(b)
        return self.response_etag(self.save("user", self.user["userId"], bump(self.user)))

    def idempotency_actor(self, operation, b):
        """Authorization is evaluated before cached responses; references may have changed."""
        if operation == "refreshToken":
            return self.refresh_record(b["refreshToken"])["session_id"]
        if operation == "createFurniture":
            self.role("TENANT")
        elif operation == "createUploadSession":
            self.target_owned(b["targetType"], b["targetId"])
        elif operation == "submitRoomCapture":
            self.listing(self.p["listingId"], own=True)
        elif operation == "submitFurnitureCapture":
            self.role("TENANT")
            self.own("furniture", self.p["furnitureId"])
        elif operation == "createRoomState":
            self.listing(self.p["listingId"])
        elif operation == "createListingReport":
            self.listing(self.p["listingId"])
        elif operation == "createChat":
            self.listing(b["listingId"])
        elif operation == "sendChatMessage":
            self.chat_owned(self.p["chatId"])
        elif operation == "retryJob":
            self.job_own(self.p["jobId"])
        return self.user["userId"]

    def target_owned(self, typ, id):
        if typ == "LISTING":
            r = self.listing(id, own=True)
            require(r["value"]["status"] != "ARCHIVED", 409, "INVALID_STATE")
            return r
        if typ == "USER":
            require(id == self.user["userId"], 404, "RESOURCE_NOT_FOUND")
            return self.own("user", id)
        self.role("TENANT")
        return self.own("furniture", id)

    def page(self, items, key):
        # A cursor binds the full ordered ID snapshot, caller and normalized filters.
        # IDs are resolved from the current authorized collection on every page.
        filters = {k: v for k, v in self.q.items() if k != "cursor"}
        binding = digest({"path": self.h["_path"], "user": self.user["userId"], "filters": filters})
        if self.q.get("cursor"):
            try:
                c = jwt.decode(
                    self.q["cursor"],
                    self.cfg.storage_key,
                    algorithms=["HS256"],
                    audience="cursor",
                    options={"require": ["exp", "binding", "ids", "offset"]},
                )
            except jwt.InvalidTokenError as exc:
                raise Problem(400, "INVALID_CURSOR") from exc
            require(c["binding"] == binding, 400, "INVALID_CURSOR")
            ids, offset, expires = c["ids"], c["offset"], c["exp"]
        else:
            ids, offset, expires = [x[key] for x in items], 0, int(time.time()) + 1800
        # Keep the cursor bounded: use a server-side persistent snapshot, not embedded IDs.
        if isinstance(ids, str):
            snap = self.s.optional("cursor", ids)
            require(snap and snap["expires"] > time.time(), 400, "INVALID_CURSOR")
            snapshot_id, ids = ids, snap["ids"]
        else:
            snapshot_id = uid()
            self.s.put("cursor", snapshot_id, {"ids": ids, "expires": expires}, self.user["userId"])
        current = {x[key]: x for x in items}
        remaining = [i for i in ids[offset:] if i in current]
        chosen = remaining[: self.q.get("limit", 20)]
        next_offset = ids.index(chosen[-1]) + 1 if chosen else len(ids)
        has_next = len(remaining) > len(chosen)
        cursor = (
            jwt.encode(
                {
                    "aud": "cursor",
                    "exp": expires,
                    "binding": binding,
                    "ids": snapshot_id,
                    "offset": next_offset,
                },
                self.cfg.storage_key,
                algorithm="HS256",
            )
            if has_next
            else None
        )
        return {"items": [current[i] for i in chosen], "page": {"nextCursor": cursor, "hasNext": has_next}}

    def cancel_jobs(self, target_type, target_id):
        for j in self.s.all("job"):
            if j["status"] in ACTIVE and (
                (j["targetType"] == target_type and j["targetId"] == target_id)
                or (target_type == "LISTING" and j["_listingId"] == target_id)
            ):
                self.finish(j, "CANCELED")

    def deleteListing(self, b):
        r = self.listing(self.p["listingId"], own=True, deleted=True)
        if r["deleted"] is not None:
            return
        etag(r["value"], self.h.get("if-match"))
        self.cancel_jobs("LISTING", self.p["listingId"])
        self.s.delete("listing", self.p["listingId"])

    def asset(self, file):
        return {k: file[k] for k in ("fileId", "contentType", "byteSize", "sha256", "format")}

    def signed(self, file, method):
        exp = int(time.time()) + 900
        token = jwt.encode(
            {
                "iss": "dulleobang-storage",
                "aud": "storage",
                "exp": exp,
                "file": file["fileId"],
                "method": method,
                "sha": file["sha256"],
            },
            self.cfg.storage_key,
            algorithm="HS256",
        )
        url = self.cfg.base_url + "/storage/" + file["fileId"] + "?token=" + token
        if method == "GET":
            return {
                "asset": self.asset(file),
                "clientFileId": file["clientFileId"],
                "url": url,
                "method": method,
                "expiresAt": stamp(exp),
            }
        return {
            "clientFileId": file["clientFileId"],
            "fileId": file["fileId"],
            "url": url,
            "method": "PUT",
            "headers": {
                "Content-Type": file["contentType"],
                "x-amz-checksum-sha256": checksum_header(file["sha256"]),
            },
            "expiresAt": stamp(exp),
        }

    def new_file(self, info, owner, target_type, target_id, purpose, *, session=None, job=None, attempt=None):
        file = dict(
            info,
            fileId=uid(),
            format=info.get("format", input_format(info)),
            _targetType=target_type,
            _targetId=target_id,
            _purpose=purpose,
            _sessionId=session,
            _jobId=job,
            _attempt=attempt,
            _verified=False,
            _stored=False,
            _created=time.time(),
        )
        self.s.put("file", file["fileId"], file, owner, target_id)
        return file

    def createUploadSession(self, b):
        self.target_owned(b["targetType"], b["targetId"])
        inputs = classify_files(b)
        id = uid()
        files = [
            self.new_file(f, self.user["userId"], b["targetType"], b["targetId"], b["purpose"], session=id)
            for f in inputs
        ]
        public_files = [dict(f, fileId=x["fileId"], status="PENDING") for f, x in zip(inputs, files)]
        session = {
            "uploadSessionId": id,
            "purpose": b["purpose"],
            "targetType": b["targetType"],
            "targetId": b["targetId"],
            "status": "OPEN",
            "files": public_files,
            "expiresAt": stamp(time.time() + 86400),
            "createdAt": stamp(),
        }
        self.s.put("upload", id, session, self.user["userId"], b["targetId"])
        self.extra["Location"] = "/api/v1/upload-sessions/" + id
        return {"session": session, "uploads": [self.signed(f, "PUT") for f in files]}

    def session_owned(self, id, *, live=True):
        session = self.own("upload", id)["value"]
        self.target_owned(session["targetType"], session["targetId"])
        expired = epoch(session["expiresAt"]) <= time.time() and session["status"] in (
            "OPEN",
            "VERIFIED",
            "EXPIRED",
        )
        if expired:
            session["status"] = "EXPIRED"
            self.save("upload", id, session)
            require(not live, 410, "UPLOAD_SESSION_EXPIRED")
        return session

    def getUploadSession(self, b):
        return self.public_session(self.session_owned(self.p["uploadSessionId"], live=False))

    def renewUploadUrls(self, b):
        session = self.session_owned(self.p["uploadSessionId"])
        require(session["status"] == "OPEN", 409, "INVALID_STATE")
        files = {f["clientFileId"]: f for f in session["files"]}
        require(set(b["clientFileIds"]) <= files.keys(), 422, "UNKNOWN_FILE")
        return {
            "uploads": [
                self.signed(self.s.get("file", files[id]["fileId"])["value"], "PUT")
                for id in b["clientFileIds"]
            ]
        }

    def read_file(self, file):
        require(file["_stored"], 409, "UPLOAD_INCOMPLETE")
        path = self.cfg.data_dir / "objects" / file["sha256"]
        require(path.exists(), 409, "UPLOAD_INCOMPLETE")
        raw = path.read_bytes()
        require(
            len(raw) == file["byteSize"] and hashlib.sha256(raw).hexdigest() == file["sha256"],
            422,
            "CHECKSUM_MISMATCH",
        )
        return raw

    def verifyUploadSession(self, b):
        session = self.session_owned(self.p["uploadSessionId"])
        if session["status"] in ("VERIFIED", "CONSUMED"):
            return self.public_session(session)
        require(session["status"] == "OPEN", 409, "INVALID_STATE")
        files = [self.s.get("file", f["fileId"])["value"] for f in session["files"]]
        verify_inputs(self, session, files)
        for f in files:
            if session["purpose"] == "LISTING_PHOTO":
                raw = stripped_photo(self.read_file(f))
                sha = hashlib.sha256(raw).hexdigest()
                derived = self.new_file(
                    {
                        "clientFileId": uid(),
                        "fileName": "photo.jpg",
                        "contentType": "image/jpeg",
                        "byteSize": len(raw),
                        "sha256": sha,
                    },
                    self.user["userId"],
                    "LISTING",
                    session["targetId"],
                    "PUBLIC_PHOTO",
                )
                (self.cfg.data_dir / "objects" / sha).write_bytes(raw)
                derived.update(_stored=True, _verified=True)
                self.save("file", derived["fileId"], derived)
                f["_derivative"] = derived["fileId"]
            f["_verified"] = True
            self.save("file", f["fileId"], f)
        session["status"] = "VERIFIED"
        for f in session["files"]:
            f["status"] = "VERIFIED"
        return self.public_session(self.save("upload", session["uploadSessionId"], session))

    def asset_access(self, id):
        r = self.s.get("file", id)
        f = r["value"]
        self.parent_live(f["_targetType"], f["_targetId"])
        if f["_targetType"] == "ROOM_STATE":
            self.listing(self.s.get("roomstate", f["_targetId"])["value"]["listingId"])
        require(f["_verified"], 404, "RESOURCE_NOT_FOUND")
        if r["owner"] == self.user["userId"]:
            return f
        if f["_targetType"] == "LISTING" and f["_purpose"] in ("PUBLIC_PHOTO", "RESULT"):
            listing = self.listing(f["_targetId"])["value"]
            allowed = {a["fileId"] for a in listing["photos"]}
            for space in self.s.all("space", parent=listing["listingId"]):
                allowed.update(
                    space["bundle"][k]["fileId"] for k in ("sceneAsset", "geometryAsset", "collisionAsset")
                )
            require(id in allowed, 404, "RESOURCE_NOT_FOUND")
            return f
        raise Problem(404, "RESOURCE_NOT_FOUND")

    def getAssetDownload(self, b):
        return self.signed(self.asset_access(self.p["fileId"]), "GET")

    def new_job(self, typ, target_type, target_id, owner, input_value, *, retry_of=None, listing_id=None):
        existing = self.s.all("job")
        require(
            sum(j["status"] in ACTIVE for j in existing if self.s.get("job", j["jobId"])["owner"] == owner)
            < 3,
            409,
            "ACTIVE_JOB_LIMIT",
        )
        require(
            not any(
                j["status"] in ACTIVE and j["targetType"] == target_type and j["targetId"] == target_id
                for j in existing
            ),
            409,
            "ACTIVE_JOB_EXISTS",
        )
        id = uid()
        job = {
            "jobId": id,
            "type": typ,
            "status": "QUEUED",
            "targetType": target_type,
            "targetId": target_id,
            "attemptNo": 0,
            "progressPercent": 0,
            "stage": "QUEUED",
            "result": None,
            "error": None,
            "retryOfJobId": retry_of,
            "createdAt": stamp(),
            "updatedAt": stamp(),
            "finishedAt": None,
            "pollAfterSeconds": 3,
            "_input": input_value,
            "_listingId": listing_id,
            "_leaseHash": None,
            "_leaseExpires": 0,
            "_attemptStarted": 0,
            "_due": time.time(),
            "_receipts": {},
        }
        self.s.put("job", id, job, owner, target_id)
        self.s.conn.execute(
            "INSERT INTO outbox(event_id,job_id,available_at) VALUES(?,?,?)", (uid(), id, time.time())
        )
        if target_type in ("LISTING", "FURNITURE"):
            kind = "listing" if target_type == "LISTING" else "furniture"
            target = self.s.get(kind, target_id)["value"]
            target["activeJobId"] = id
            if kind == "furniture":
                target["status"] = "PROCESSING"
            self.save(kind, target_id, bump(target))
        return job

    def submit_capture(self, target_type, target_id, b):
        self.target_owned(target_type, target_id)
        session = self.session_owned(b["uploadSessionId"])
        purpose = "ROOM_CAPTURE" if target_type == "LISTING" else "FURNITURE_CAPTURE"
        require(
            session["status"] == "VERIFIED"
            and session["targetType"] == target_type
            and session["targetId"] == target_id
            and session["purpose"] == purpose,
            409,
            "INVALID_UPLOAD_SESSION",
        )
        files = [self.s.get("file", f["fileId"])["value"] for f in session["files"]]
        id = uid()
        capture = {
            "captureId": id,
            "targetType": target_type,
            "targetId": target_id,
            "uploadSessionId": session["uploadSessionId"],
            "manifestAsset": self.asset(
                next(
                    f
                    for f in files
                    if f.get("role", "CAMERA_MANIFEST" if f["format"] == "CAMERA_JSON_V1" else "IMAGE")
                    == "CAMERA_MANIFEST"
                )
            ),
            "imageAssets": [self.asset(f) for f in files if f.get("role", "IMAGE") == "IMAGE"],
            "supplementalAssets": [
                dict(clientFileId=f["clientFileId"], role=f["role"], asset=self.asset(f))
                for f in files
                if f.get("role") not in (None, "IMAGE", "CAMERA_MANIFEST")
            ],
            "latestJobId": "",
            "createdAt": stamp(),
        }
        job = self.new_job(
            "ROOM_RECONSTRUCTION" if target_type == "LISTING" else "FURNITURE_RECONSTRUCTION",
            target_type,
            target_id,
            self.user["userId"],
            {"captureId": id},
            listing_id=target_id if target_type == "LISTING" else None,
        )
        capture["latestJobId"] = job["jobId"]
        self.s.put("capture", id, capture, self.user["userId"], target_id)
        session["status"] = "CONSUMED"
        self.save("upload", session["uploadSessionId"], session)
        self.extra.update(Location="/api/v1/ai-jobs/" + job["jobId"], **{"Retry-After": "3"})
        return {"captureId": id, "jobId": job["jobId"], "status": "QUEUED", "pollAfterSeconds": 3}

    def submitRoomCapture(self, b):
        return self.submit_capture("LISTING", self.p["listingId"], b)

    def submitFurnitureCapture(self, b):
        return self.submit_capture("FURNITURE", self.p["furnitureId"], b)

    def getCapture(self, b):
        c = self.own("capture", self.p["captureId"])["value"]
        self.parent_live(c["targetType"], c["targetId"])
        require(
            epoch(self.s.get("job", c["latestJobId"])["value"]["updatedAt"]) + 30 * 86400 > time.time(),
            410,
            "INPUT_EXPIRED",
        )
        return c

    def getJob(self, b):
        return self.public_job(self.job_own(self.p["jobId"])["value"])

    def retryJob(self, b):
        old = self.job_own(self.p["jobId"])["value"]
        require(old["status"] == "FAILED" and old["error"]["retryable"], 409, "JOB_NOT_RETRYABLE")
        if old["targetType"] == "LISTING":
            self.target_owned("LISTING", old["targetId"])
        if old["_input"].get("captureId"):
            c = self.s.get("capture", old["_input"]["captureId"])["value"]
            latest = self.s.get("job", c["latestJobId"])["value"]
            require(
                latest["status"] in ACTIVE or epoch(latest["updatedAt"]) + 30 * 86400 > time.time(),
                410,
                "INPUT_EXPIRED",
            )
            require(
                all(
                    self.s.get("file", a["fileId"])["value"]["_stored"]
                    for a in [c["manifestAsset"], *c["imageAssets"]]
                ),
                410,
                "INPUT_EXPIRED",
            )
        j = self.new_job(
            old["type"],
            old["targetType"],
            old["targetId"],
            self.user["userId"],
            old["_input"],
            retry_of=old["jobId"],
            listing_id=old["_listingId"],
        )
        if j["targetType"] == "ROOM_STATE":
            state = self.s.get("roomstate", j["targetId"])["value"]
            self.listing(state["listingId"])
            state.update(status="PROCESSING", jobId=j["jobId"])
            self.save("roomstate", state["roomStateId"], state)
        else:
            c["latestJobId"] = j["jobId"]
            self.save("capture", c["captureId"], c)
        self.extra.update(Location="/api/v1/ai-jobs/" + j["jobId"], **{"Retry-After": "3"})
        return {"jobId": j["jobId"], "status": "QUEUED", "pollAfterSeconds": 3}

    def finish(self, job, status, error=None, result=None):
        job.update(
            status=status,
            stage=status,
            error=error,
            result=result,
            finishedAt=stamp(),
            updatedAt=stamp(),
            pollAfterSeconds=0,
        )
        if status == "COMPLETED":
            job["progressPercent"] = 100
        self.save("job", job["jobId"], job)
        self.s.conn.execute("DELETE FROM queue WHERE job_id=?", (job["jobId"],))
        if job["targetType"] == "ROOM_STATE":
            state = self.s.optional("roomstate", job["targetId"])
            if state:
                state["status"] = {"COMPLETED": "READY", "FAILED": "FAILED", "CANCELED": "CANCELED"}[status]
                self.save("roomstate", state["roomStateId"], state)
        else:
            kind = "listing" if job["targetType"] == "LISTING" else "furniture"
            target = self.s.optional(kind, job["targetId"])
            if target and target["activeJobId"] == job["jobId"]:
                target["activeJobId"] = None
                if kind == "furniture":
                    target["status"] = (
                        "READY" if target["model"] else ("FAILED" if status == "FAILED" else "DRAFT")
                    )
                self.save(kind, job["targetId"], bump(target))
        return self.public_job(job)

    def cancelJob(self, b):
        job = self.job_own(self.p["jobId"])["value"]
        if job["status"] == "CANCELED":
            return self.public_job(job)
        require(job["status"] in ACTIVE, 409, "JOB_TERMINAL")
        return self.finish(job, "CANCELED")

    def space(self, listing_id, version=None):
        listing = self.listing(listing_id)["value"]
        version = version or listing["currentSpaceVersion"]
        require(version is not None, 409, "SPACE_NOT_READY")
        value = self.s.get("space", listing_id + ":" + str(version))["value"]
        value["isCurrent"] = version == listing["currentSpaceVersion"]
        return value

    def getSpace(self, b):
        return self.space(self.p["listingId"], self.q.get("spaceVersion"))

    def objects(self, space):
        return {
            "listingId": space["listingId"],
            "spaceVersion": space["spaceVersion"],
            "version": space["objectsVersion"],
            "objects": space["bundle"]["objects"],
        }

    def getObjects(self, b):
        return self.response_etag(self.objects(self.space(self.p["listingId"], self.q.get("spaceVersion"))))

    def classifyObjects(self, b):
        self.listing(self.p["listingId"], own=True)
        space = self.space(self.p["listingId"], b["spaceVersion"])
        require(space["isCurrent"], 409, "STALE_SPACE_VERSION")
        require(
            self.s.get("listing", space["listingId"])["value"]["status"] != "ARCHIVED", 409, "INVALID_STATE"
        )
        etag({"version": space["objectsVersion"]}, self.h.get("if-match"))
        unique(b["objects"], "objectId")
        objects = {o["objectId"]: o for o in space["bundle"]["objects"]}
        require({o["objectId"] for o in b["objects"]} <= objects.keys(), 422, "UNKNOWN_OBJECT")
        for obj in b["objects"]:
            objects[obj["objectId"]]["ownershipType"] = obj["ownershipType"]
        space["objectsVersion"] += 1
        self.save("space", space["listingId"] + ":" + str(space["spaceVersion"]), space)
        return self.response_etag(self.objects(space))

    def public_state(self, state):
        return {k: v for k, v in state.items() if not k.startswith("_")}

    def createRoomState(self, b):
        listing = self.listing(self.p["listingId"])["value"]
        if listing["ownerId"] != self.user["userId"]:
            self.role("TENANT")
        require(listing["status"] != "ARCHIVED", 409, "INVALID_STATE")
        space = self.space(listing["listingId"], b["sourceSpaceVersion"])
        require(
            space["isCurrent"] and space["objectsVersion"] == b["sourceObjectsVersion"],
            409,
            "STALE_SPACE_VERSION",
        )
        objects = {o["objectId"]: o for o in space["bundle"]["objects"]}
        require(set(b["removedObjectIds"]) <= objects.keys(), 422, "UNKNOWN_OBJECT")
        require(
            all(
                objects[id]["ownershipType"] == "PERSONAL" and objects[id]["removable"]
                for id in b["removedObjectIds"]
            ),
            422,
            "OBJECT_NOT_REMOVABLE",
        )
        id = uid()
        job = self.new_job(
            "ROOM_OBJECT_REMOVAL",
            "ROOM_STATE",
            id,
            self.user["userId"],
            {"sourceBundle": copy.deepcopy(space["bundle"])},
            listing_id=listing["listingId"],
        )
        state = {
            "roomStateId": id,
            "listingId": listing["listingId"],
            "createdBy": self.user["userId"],
            **b,
            "status": "PROCESSING",
            "jobId": job["jobId"],
            "bundle": None,
            "createdAt": stamp(),
        }
        self.s.put("roomstate", id, state, self.user["userId"], listing["listingId"])
        self.extra.update(Location="/api/v1/ai-jobs/" + job["jobId"], **{"Retry-After": "3"})
        return {"roomStateId": id, "jobId": job["jobId"], "status": "QUEUED", "pollAfterSeconds": 3}

    def getRoomStates(self, b):
        self.listing(self.p["listingId"])
        values = self.s.all("roomstate", owner=self.user["userId"], parent=self.p["listingId"])
        return self.page(
            [
                self.public_state(x)
                for x in sorted(values, key=lambda x: (-epoch(x["createdAt"]), x["roomStateId"]))
            ],
            "roomStateId",
        )

    def getRoomState(self, b):
        state = self.own("roomstate", self.p["roomStateId"])["value"]
        self.listing(state["listingId"])
        return self.public_state(state)

    def deleteRoomState(self, b):
        r = self.own("roomstate", self.p["roomStateId"], deleted=True)
        self.listing(r["value"]["listingId"])
        if r["deleted"]:
            return
        require(
            not any(p["space"]["roomStateId"] == self.p["roomStateId"] for p in self.s.all("placement")),
            409,
            "RESOURCE_IN_USE",
        )
        self.cancel_jobs("ROOM_STATE", self.p["roomStateId"])
        self.s.delete("roomstate", self.p["roomStateId"])

    def createFurniture(self, b):
        self.role("TENANT")
        id = uid()
        value = {
            **b,
            "furnitureId": id,
            "ownerId": self.user["userId"],
            "measuredDimensions": b.get("measuredDimensions"),
            "status": "DRAFT",
            "version": 1,
            "model": None,
            "activeJobId": None,
            "createdAt": stamp(),
            "updatedAt": stamp(),
        }
        self.extra["Location"] = "/api/v1/furniture/" + id
        return self.response_etag(self.s.put("furniture", id, value, self.user["userId"]))

    def getMyFurniture(self, b):
        self.role("TENANT")
        values = [
            x
            for x in self.s.all("furniture", owner=self.user["userId"])
            if "status" not in self.q or x["status"] == self.q["status"]
        ]
        return self.page(
            sorted(values, key=lambda x: (-epoch(x["createdAt"]), x["furnitureId"])), "furnitureId"
        )

    def getFurniture(self, b):
        value = self.own("furniture", self.p["furnitureId"])["value"]
        if "modelVersion" in self.q:
            value["model"] = self.s.get("model", self.p["furnitureId"] + ":" + str(self.q["modelVersion"]))[
                "value"
            ]
            return value
        return self.response_etag(value)

    def updateFurniture(self, b):
        value = self.own("furniture", self.p["furnitureId"])["value"]
        etag(value, self.h.get("if-match"))
        value.update(b)
        return self.response_etag(self.save("furniture", self.p["furnitureId"], bump(value)))

    def deleteFurniture(self, b):
        r = self.own("furniture", self.p["furnitureId"], deleted=True)
        if r["deleted"]:
            return
        etag(r["value"], self.h.get("if-match"))
        require(
            not any(
                i["furnitureId"] == self.p["furnitureId"]
                for p in self.s.all("placement")
                for i in p["placements"]
            ),
            409,
            "RESOURCE_IN_USE",
        )
        self.cancel_jobs("FURNITURE", self.p["furnitureId"])
        self.s.delete("furniture", self.p["furnitureId"])

    def placement_key(self):
        self.role("TENANT")
        self.listing(self.p["listingId"])
        return self.user["userId"] + ":" + self.p["listingId"]

    def getPlacements(self, b):
        value = self.s.optional("placement", self.placement_key())
        require(value is not None, 404, "PLACEMENT_NOT_FOUND")
        return self.response_etag(value)

    def savePlacements(self, b):
        key = self.placement_key()
        existing = self.s.optional("placement", key)
        match, none = self.h.get("if-match"), self.h.get("if-none-match")
        require(not (match and none), 400, "INVALID_PRECONDITION")
        require(match or none, 428, "PRECONDITION_REQUIRED")
        if none:
            require(none == "*", 400, "INVALID_PRECONDITION")
            require(existing is None, 412, "PRECONDITION_FAILED")
        else:
            require(existing is not None, 412, "PRECONDITION_FAILED")
            etag(existing, match)
        selected = b["space"]
        space = self.space(self.p["listingId"], selected["spaceVersion"])
        require(space["isCurrent"], 409, "STALE_SPACE_VERSION")
        if selected["kind"] == "ROOM_STATE":
            state = self.own("roomstate", selected["roomStateId"])["value"]
            require(
                state["listingId"] == self.p["listingId"]
                and state["sourceSpaceVersion"] == selected["spaceVersion"]
                and state["status"] == "READY",
                409,
                "ROOM_STATE_NOT_READY",
            )
        unique(b["placements"], "placementId")
        unique(b["placements"], "furnitureId")
        for item in b["placements"]:
            f = self.own("furniture", item["furnitureId"])["value"]
            require(f["status"] == "READY" and f["model"], 409, "FURNITURE_NOT_READY")
            require(f["model"]["modelVersion"] == item["modelVersion"], 409, "STALE_MODEL_VERSION")
            quaternion(item["transform"]["rotation"])
            q = item["transform"]["rotation"]
            norm = sum(v * v for v in q.values()) ** 0.5
            first = next((q[k] for k in ("x", "y", "z") if q[k] != 0), 0)
            sign = -1 if q["w"] < 0 or (q["w"] == 0 and first < 0) else 1
            item["transform"]["rotation"] = {k: (v / norm * sign) or 0 for k, v in q.items()}
        report = b["clientCollisionReport"]
        if report:
            require(report["isPlaceable"] == (not report["collisions"]), 422, "INVALID_COLLISION_REPORT")
            ids = {x["placementId"] for x in b["placements"]}
            require(
                all(c["placementId"] in ids for c in report["collisions"]), 422, "INVALID_COLLISION_REPORT"
            )
            require(epoch(report["evaluatedAt"]) <= time.time() + 60, 422, "INVALID_COLLISION_REPORT")
        value = {
            **b,
            "placementSetId": existing["placementSetId"] if existing else uid(),
            "listingId": self.p["listingId"],
            "ownerId": self.user["userId"],
            "version": existing["version"] + 1 if existing else 1,
            "validationAuthority": "CLIENT_ONLY",
            "isStale": False,
            "savedAt": stamp(),
        }
        if existing:
            self.save("placement", key, value)
        else:
            # Revive a deleted set without reusing its public ID.
            self.s.conn.execute("DELETE FROM resources WHERE kind='placement' AND id=?", (key,))
            self.s.put("placement", key, value, self.user["userId"], self.p["listingId"])
        return self.response_etag(value)

    def deletePlacements(self, b):
        key = self.placement_key()
        value = self.s.optional("placement", key)
        if value:
            etag(value, self.h.get("if-match"))
            self.s.delete("placement", key)

    def getMyPlacements(self, b):
        self.role("TENANT")
        values = []
        for p in self.s.all("placement", owner=self.user["userId"]):
            try:
                self.listing(p["listingId"])
            except Problem as exc:
                if exc.status == 404:
                    continue
                raise
            values.append(p)
        return self.page(
            sorted(values, key=lambda x: (-epoch(x["savedAt"]), x["placementSetId"])), "placementSetId"
        )

    def invalidate_placements(self, *, listing_id=None, furniture_id=None):
        for p in self.s.all("placement"):
            affected = (
                p["listingId"] == listing_id
                if listing_id
                else any(x["furnitureId"] == furniture_id for x in p["placements"])
            )
            if affected and not p["isStale"]:
                p["isStale"] = True
                p["version"] += 1
                self.save("placement", p["ownerId"] + ":" + p["listingId"], p)

    def worker_job(self, *, lease=True):
        job = self.s.get("job", self.p["jobId"])["value"]
        self.parent_live(job["targetType"], job["targetId"])
        if lease:
            token = self.h.get("x-job-lease-token", "")
            require(
                job["status"] == "RUNNING"
                and job["_leaseHash"] == token_hash(token)
                and job.get("_workerId") == self.worker
                and job["_leaseExpires"] > time.time()
                and job["_attemptStarted"] + 1800 > time.time(),
                409,
                "JOB_LEASE_LOST",
            )
        return job

    def job_input(self, job):
        if job["type"] == "ROOM_OBJECT_REMOVAL":
            state = self.s.get("roomstate", job["targetId"])["value"]
            bundle = job["_input"]["sourceBundle"]
            return {
                "kind": "REMOVAL",
                "roomStateId": state["roomStateId"],
                "sourceSpaceVersion": state["sourceSpaceVersion"],
                "sourceObjectsVersion": state["sourceObjectsVersion"],
                "sourceBundle": bundle,
                "sourceDownloads": [
                    self.signed(self.s.get("file", bundle[k]["fileId"])["value"], "GET")
                    for k in ("sceneAsset", "geometryAsset", "collisionAsset")
                ],
                "removedObjectIds": state["removedObjectIds"],
            }
        capture = self.s.get("capture", job["_input"]["captureId"])["value"]
        grants = [
            self.signed(self.s.get("file", a["fileId"])["value"], "GET")
            for a in [capture["manifestAsset"], *capture["imageAssets"]]
        ]
        dims = (
            self.s.get("furniture", job["targetId"])["value"]["measuredDimensions"]
            if job["targetType"] == "FURNITURE"
            else None
        )
        return {
            "kind": "RECONSTRUCTION",
            "captureId": capture["captureId"],
            "manifest": grants[0],
            "images": grants[1:],
            "depths": self.capture_grants(capture, "DEPTH"),
            "confidences": self.capture_grants(capture, "CONFIDENCE"),
            "roomplan": next(iter(self.capture_grants(capture, "ROOMPLAN")), None),
            "roomplanStructure": next(iter(self.capture_grants(capture, "ROOMPLAN_STRUCTURE")), None),
            "measuredDimensions": dims,
        }

    def capture_grants(self, capture, role):
        return [
            self.signed(self.s.get("file", a["asset"]["fileId"])["value"], "GET")
            for a in capture.get("supplementalAssets", [])
            if a["role"] == role
        ]

    def present(self, value):
        if isinstance(value, list):
            return [self.present(v) for v in value]
        if not isinstance(value, dict):
            return value
        out = {k: self.present(v) for k, v in value.items()}
        if {"fileId", "contentType", "byteSize", "sha256", "format"} <= out.keys():
            f = self.s.get("file", out["fileId"])["value"]
            if f["_verified"] and f["_stored"]:
                grant = self.signed(f, "GET")
                out.update(url=grant["url"], urlExpiresAt=grant["expiresAt"])
        return out

    def social_login(self, provider, identity):
        key = provider + ":" + identity["sub"]
        link = self.s.optional("oauthidentity", key)
        if link:
            return self.tokens(self.s.get("user", link["userId"])["value"])
        from jsonschema import FormatChecker

        email = identity.get("email")
        if not isinstance(email, str) or len(email) > 254 or not FormatChecker().conforms(email, "email"):
            email = None
        name = identity.get("name")
        name = name.strip() if isinstance(name, str) else ""
        name = name[:40] or ("카카오 사용자" if provider == "KAKAO" else "구글 사용자")
        user = dict(
            userId=uid(),
            email=email,
            displayName=name,
            roles=["TENANT"],
            authProviders=[provider],
            phone=None,
            isPhonePublic=False,
            version=1,
            createdAt=stamp(),
        )
        self.s.put("user", user["userId"], user, user["userId"])
        self.s.put(
            "oauthidentity",
            key,
            dict(userId=user["userId"], provider=provider, subject=identity["sub"]),
            user["userId"],
        )
        return self.tokens(user)

    def loginKakao(self, b):
        from .oauth import kakao_identity

        return self.social_login("KAKAO", kakao_identity(b["accessToken"], self.cfg))

    def loginGoogle(self, b):
        from .oauth import google_identity

        return self.social_login("GOOGLE", google_identity(b["idToken"], self.cfg))

    def claimJob(self, b):
        require(b["workerId"] == self.worker, 403, "WORKER_ID_MISMATCH")
        job = self.worker_job(lease=False)
        require(
            job["status"] in ("QUEUED", "RETRY_WAIT") and job["_due"] <= time.time() and job["attemptNo"] < 3,
            409,
            "JOB_NOT_CLAIMABLE",
        )
        token = secrets.token_urlsafe(48)
        job.update(
            status="RUNNING",
            stage="PREPROCESSING",
            attemptNo=job["attemptNo"] + 1,
            progressPercent=0,
            error=None,
            updatedAt=stamp(),
            _leaseHash=token_hash(token),
            _leaseExpires=time.time() + 120,
            _attemptStarted=time.time(),
            _workerId=self.worker,
        )
        self.save("job", job["jobId"], job)
        self.s.conn.execute("DELETE FROM queue WHERE job_id=?", (job["jobId"],))
        return {
            "jobId": job["jobId"],
            "type": job["type"],
            "attemptNo": job["attemptNo"],
            "leaseToken": token,
            "leaseExpiresAt": stamp(job["_leaseExpires"]),
            "input": self.job_input(job),
        }

    def heartbeatJob(self, b):
        job = self.worker_job()
        require(b["progressPercent"] >= job["progressPercent"], 422, "PROGRESS_REGRESSION")
        job.update(
            progressPercent=b["progressPercent"],
            stage=b["stage"],
            updatedAt=stamp(),
            _leaseExpires=min(time.time() + 120, job["_attemptStarted"] + 1800),
        )
        self.save("job", job["jobId"], job)
        result = {"leaseExpiresAt": stamp(job["_leaseExpires"])}
        if b["refreshInputUrls"]:
            result["input"] = self.job_input(job)
        return result

    def createJobArtifacts(self, b):
        job = self.worker_job()
        required_roles = (
            {"MODEL", "COLLISION"}
            if job["type"] == "FURNITURE_RECONSTRUCTION"
            else {"SCENE", "GEOMETRY", "COLLISION"}
        )
        unique(b["files"], "clientFileId")
        unique(b["files"], "role")
        require({f["role"] for f in b["files"]} == required_roles, 422, "INVALID_ARTIFACT_ROLES")
        limits = {"MODEL": 256 << 20, "SCENE": 256 << 20, "GEOMETRY": 10 << 20, "COLLISION": 64 << 20}
        formats = {
            "MODEL": ("model/gltf-binary", "GLB_V2"),
            "COLLISION": ("model/gltf-binary", "GLB_V2"),
            "SCENE": ("application/octet-stream", "DULLEOBANG_GS_PLY_V1"),
            "GEOMETRY": ("application/json", "ROOM_GEOMETRY_V1"),
        }
        owner = self.s.get("job", job["jobId"])["owner"]
        current = [
            f for f in self.s.all("file") if f["_jobId"] == job["jobId"] and f["_attempt"] == job["attemptNo"]
        ]
        uploads = []
        for info in b["files"]:
            require(
                info["byteSize"] <= limits[info["role"]]
                and (info["contentType"], info["format"]) == formats[info["role"]],
                422,
                "INVALID_ARTIFACT",
            )
            candidates = [
                f
                for f in current
                if f["clientFileId"] == info["clientFileId"] or f.get("role") == info["role"]
            ]
            if candidates:
                f = candidates[0]
                require(all(f[k] == v for k, v in info.items()), 409, "ARTIFACT_CONFLICT")
            else:
                f = self.new_file(
                    info,
                    owner,
                    job["targetType"],
                    job["targetId"],
                    "RESULT",
                    job=job["jobId"],
                    attempt=job["attemptNo"],
                )
            uploads.append(self.signed(f, "PUT"))
        return {"uploads": uploads}

    def verify_asset(self, job, asset, role, expected_box=None):
        f = self.s.get("file", asset["fileId"])["value"]
        require(
            self.asset(f) == {k: asset[k] for k in ("fileId", "contentType", "byteSize", "sha256", "format")}
            and f["_jobId"] == job["jobId"]
            and f["_attempt"] == job["attemptNo"]
            and f.get("role") == role,
            422,
            "INVALID_ARTIFACT",
        )
        raw = self.read_file(f)
        if role == "SCENE":
            validate_ply(raw)
            parsed = None
        elif role == "GEOMETRY":
            try:
                parsed = strict_json(raw)
            except (ValueError, UnicodeError, RecursionError) as exc:
                raise Problem(422, "INVALID_ARTIFACT") from exc
            self.contract.validate("RoomGeometry", parsed)
        else:
            parsed = validate_glb(raw, collision=role == "COLLISION", expected_box=expected_box)
        f["_verified"] = True
        self.save("file", f["fileId"], f)
        return parsed

    def verify_bundle(self, job, bundle):
        self.verify_asset(job, bundle["sceneAsset"], "SCENE")
        geometry = self.verify_asset(job, bundle["geometryAsset"], "GEOMETRY")
        nodes = self.verify_asset(job, bundle["collisionAsset"], "COLLISION")
        require(geometry == bundle["geometry"], 422, "GEOMETRY_MISMATCH")
        bundle_geometry(bundle, nodes)
        return nodes

    def callback_replay(self, job, operation, b):
        receipt_key = str(job["attemptNo"]) + ":" + operation
        receipt = job["_receipts"].get(receipt_key)
        if receipt:
            require(
                receipt["worker"] == self.worker
                and receipt["lease"] == token_hash(self.h.get("x-job-lease-token", "")),
                409,
                "JOB_LEASE_LOST",
            )
            require(receipt["digest"] == digest(b), 409, "CALLBACK_CONFLICT")
            return receipt["response"]
        return None

    def record_callback(self, job, operation, b, response):
        job["_receipts"][str(job["attemptNo"]) + ":" + operation] = {
            "worker": self.worker,
            "lease": token_hash(self.h["x-job-lease-token"]),
            "digest": digest(b),
            "response": response,
        }
        self.save("job", job["jobId"], job)
        return response

    def completeJob(self, b):
        job = self.worker_job(lease=False)
        replay = self.callback_replay(job, "complete", b)
        if replay:
            return replay
        job = self.worker_job()
        require(b["type"] == job["type"], 422, "JOB_RESULT_TYPE_MISMATCH")
        owner = self.s.get("job", job["jobId"])["owner"]
        if job["type"] == "FURNITURE_RECONSTRUCTION":
            self.verify_asset(job, b["modelAsset"], "MODEL", b["boundingBox"])
            self.verify_asset(job, b["collisionAsset"], "COLLISION")
            bbox(b["dimensions"], b["boundingBox"])
            furniture = self.s.get("furniture", job["targetId"])["value"]
            version = 1 + (furniture["model"]["modelVersion"] if furniture["model"] else 0)
            model = {
                k: b[k] for k in ("modelAsset", "collisionAsset", "dimensions", "boundingBox", "quality")
            }
            model.update(modelVersion=version, createdAt=stamp())
            self.s.put("model", job["targetId"] + ":" + str(version), model, owner, job["targetId"])
            furniture["model"] = model
            self.save("furniture", job["targetId"], bump(furniture))
            self.invalidate_placements(furniture_id=job["targetId"])
            result = {"resourceType": "FURNITURE_MODEL", "resourceId": job["targetId"], "version": version}
        elif job["type"] == "ROOM_RECONSTRUCTION":
            self.verify_bundle(job, b["bundle"])
            require(
                all(o["ownershipType"] == "UNKNOWN" for o in b["bundle"]["objects"]),
                422,
                "INVALID_INITIAL_CLASSIFICATION",
            )
            listing = self.s.get("listing", job["targetId"])["value"]
            version = (listing["currentSpaceVersion"] or 0) + 1
            space = {
                "listingId": job["targetId"],
                "spaceVersion": version,
                "objectsVersion": 1,
                "isCurrent": True,
                "bundle": b["bundle"],
                "createdAt": stamp(),
            }
            self.s.put("space", job["targetId"] + ":" + str(version), space, owner, job["targetId"])
            listing["currentSpaceVersion"] = version
            self.save("listing", job["targetId"], bump(listing))
            self.invalidate_placements(listing_id=job["targetId"])
            result = {"resourceType": "SPACE", "resourceId": job["targetId"], "version": version}
        else:
            state = self.s.get("roomstate", job["targetId"])["value"]
            require(
                b["roomStateId"] == state["roomStateId"]
                and set(b["removedObjectIds"]) == set(state["removedObjectIds"]),
                422,
                "REMOVAL_TARGET_MISMATCH",
            )
            nodes = self.verify_bundle(job, b["bundle"])
            source = job["_input"]["sourceBundle"]
            removed_nodes = {
                o["collisionNode"] for o in source["objects"] if o["objectId"] in state["removedObjectIds"]
            }
            require(not (removed_nodes & nodes), 422, "REMOVED_COLLIDER_PRESENT")
            remaining = [o for o in source["objects"] if o["objectId"] not in state["removedObjectIds"]]
            require(
                {o["objectId"]: o for o in b["bundle"]["objects"]} == {o["objectId"]: o for o in remaining},
                422,
                "REMOVAL_OBJECT_MISMATCH",
            )
            require(
                b["bundle"]["geometry"] == source["geometry"]
                and b["bundle"]["roomDimensions"] == source["roomDimensions"],
                422,
                "REMOVAL_GEOMETRY_MISMATCH",
            )
            require("INPAINTED_REGION" in b["bundle"]["quality"]["warnings"], 422, "REMOVAL_WARNING_REQUIRED")
            state["bundle"] = b["bundle"]
            self.save("roomstate", state["roomStateId"], state)
            result = {"resourceType": "ROOM_STATE", "resourceId": state["roomStateId"], "version": 1}
        # Large artifact parsing can outlast the lease; roll back every pointer write if so.
        self.worker_job()
        response = self.finish(job, "COMPLETED", result=result)
        return self.record_callback(job, "complete", b, response)

    def fail_job(self, job, error):
        transient = error["code"] in TRANSIENT
        # Retry policy belongs to the server; a worker cannot enable retry for permanent errors.
        error = {"code": error["code"], "message": error["message"], "retryable": transient}
        if transient and job["attemptNo"] < 3:
            job.update(
                status="RETRY_WAIT",
                stage="RETRY_WAIT",
                error=error,
                updatedAt=stamp(),
                _due=time.time() + (10 if job["attemptNo"] == 1 else 30),
            )
            self.save("job", job["jobId"], job)
            self.s.conn.execute(
                "INSERT INTO outbox(event_id,job_id,available_at) VALUES(?,?,?)",
                (uid(), job["jobId"], job["_due"]),
            )
            return self.public_job(job)
        return self.finish(job, "FAILED", error=error)

    def failJob(self, b):
        job = self.worker_job(lease=False)
        replay = self.callback_replay(job, "fail", b)
        if replay:
            return replay
        job = self.worker_job()
        response = self.fail_job(job, b["error"])
        return self.record_callback(job, "fail", b, response)

    def maintenance(self):
        now = time.time()
        for job in self.s.all("job"):
            if job["status"] == "RUNNING" and (
                job["_leaseExpires"] <= now or job["_attemptStarted"] + 1800 <= now
            ):
                self.fail_job(
                    job, {"code": "WORKER_TIMEOUT", "message": "AI worker lease expired.", "retryable": True}
                )
            elif job["status"] == "QUEUED" and epoch(job["createdAt"]) + 3600 <= now:
                self.finish(
                    job,
                    "FAILED",
                    error={
                        "code": "INTERNAL_ERROR",
                        "message": "AI queue wait exceeded 60 minutes.",
                        "retryable": True,
                    },
                )
        for event in self.s.conn.execute(
            "SELECT * FROM outbox WHERE delivered_at IS NULL AND available_at<=?", (now,)
        ).fetchall():
            job = self.s.optional("job", event["job_id"])
            if job and job["status"] in ("QUEUED", "RETRY_WAIT"):
                self.s.conn.execute(
                    "INSERT INTO queue VALUES(?,?) ON CONFLICT(job_id) DO UPDATE SET available_at=excluded.available_at",
                    (event["job_id"], event["available_at"]),
                )
            self.s.conn.execute("UPDATE outbox SET delivered_at=? WHERE event_id=?", (now, event["event_id"]))
        self.s.conn.execute("DELETE FROM idempotency WHERE expires_at<=?", (now,))
        self.s.conn.execute("DELETE FROM rate_limits WHERE window<?", (int(now // 60) - 2,))
        self.s.conn.execute(
            "DELETE FROM resources WHERE kind='cursor' AND json_extract(data,'$.expires')<=?", (now,)
        )
        self.s.conn.execute(
            "DELETE FROM refresh_tokens WHERE expires_at<=? AND session_id IN (SELECT id FROM sessions WHERE expires_at<=? OR revoked=1)",
            (now, now),
        )
        self.s.conn.execute(
            "DELETE FROM sessions WHERE expires_at<=? AND id NOT IN (SELECT session_id FROM refresh_tokens)",
            (now,),
        )
        self.cleanup_files(now)

    def cleanup_files(self, now):
        # Object hashes may be shared by different file IDs. Remove a blob only
        # after every surviving reference has been considered, never on one delete.
        retained = set()
        for f in self.s.all("file"):
            keep = True
            try:
                self.parent_live(f["_targetType"], f["_targetId"])
            except Problem as exc:
                if exc.status != 404:
                    raise
                keep = False
            if keep and f["_sessionId"] and not f.get("_applicationId"):
                session = self.s.optional("upload", f["_sessionId"])
                if not session:
                    keep = False
                elif session["status"] == "CONSUMED":
                    captures = self.s.all("capture", parent=f["_targetId"])
                    matching = [c for c in captures if c["uploadSessionId"] == f["_sessionId"]]
                    if matching:
                        job = self.s.optional("job", matching[0]["latestJobId"])
                        keep = bool(
                            job and (job["status"] in ACTIVE or epoch(job["updatedAt"]) + 30 * 86400 > now)
                        )
                elif epoch(session["expiresAt"]) <= now:
                    if session["status"] != "EXPIRED":
                        session["status"] = "EXPIRED"
                        self.save("upload", session["uploadSessionId"], session)
                    keep = epoch(session["expiresAt"]) + 86400 > now
            if keep and f["_jobId"] and not f["_verified"]:
                job = self.s.optional("job", f["_jobId"])
                keep = (
                    bool(job and job["status"] == "RUNNING" and job["attemptNo"] == f["_attempt"])
                    or f["_created"] + 86400 > now
                )
            if keep:
                if f["_stored"]:
                    retained.add(f["sha256"])
            else:
                f["_stored"] = False
                self.save("file", f["fileId"], f)
                self.s.delete("file", f["fileId"])
        objects = self.cfg.data_dir / "objects"
        for path in objects.iterdir():
            # A write that later rolls back leaves an orphan. Grace period also
            # protects upload staging and downloads while cleanup runs.
            if path.is_file() and path.name not in retained and path.stat().st_mtime + 86400 <= now:
                path.unlink(missing_ok=True)
        for path in (self.cfg.data_dir / "staging").iterdir():
            if path.is_file() and path.stat().st_mtime + 86400 <= now:
                path.unlink(missing_ok=True)
