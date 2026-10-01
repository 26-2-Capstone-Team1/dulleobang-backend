from __future__ import annotations

import base64
import hashlib
import json
import math
import os
import secrets
import sqlite3
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

import jwt
import rfc8785
from argon2 import PasswordHasher
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


def uid():
    return str(uuid4())


def stamp(t=None):
    return (
        datetime.fromtimestamp(time.time() if t is None else t, timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


def epoch(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).timestamp()


def canonical(value):
    return rfc8785.dumps(value)


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def dumps(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def strict_json(raw):
    def finite_float(s):
        value = float(s)
        if not math.isfinite(value):
            raise ValueError("Non-finite JSON")
        return value

    def pairs(items):
        d = {}
        for k, v in items:
            if k in d:
                raise ValueError("Duplicate JSON key")
            d[k] = v
        return d

    return json.loads(
        raw,
        object_pairs_hook=pairs,
        parse_float=finite_float,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite JSON")),
    )


class Problem(Exception):
    def __init__(self, status, code, detail=None, errors=None, *, commit=False, headers=None):
        self.status, self.code = status, code
        self.detail = detail or code
        self.errors = errors or []
        self.commit = commit
        self.headers = headers or {}
        super().__init__(self.detail)


def require(condition, status=422, code="VALIDATION_FAILED", detail=None):
    if not condition:
        raise Problem(status, code, detail)


@dataclass
class Settings:
    data_dir: Path = field(default_factory=lambda: Path(os.getenv("DATA_DIR", "data")).resolve())
    base_url: str = field(
        default_factory=lambda: os.getenv("PUBLIC_BASE_URL", "http://127.0.0.1:8000").rstrip("/")
    )
    jwt_key: str = field(default_factory=lambda: os.getenv("JWT_SECRET", ""))
    worker_key: str = field(default_factory=lambda: os.getenv("WORKER_JWT_SECRET", ""))
    storage_key: str = field(default_factory=lambda: os.getenv("STORAGE_SECRET", ""))
    environment: str = field(default_factory=lambda: os.getenv("APP_ENV", "development"))
    cors_origins: list[str] = field(
        default_factory=lambda: [v.strip() for v in os.getenv("CORS_ORIGINS", "").split(",") if v.strip()]
    )
    scheduler: bool = True
    rate_limits: bool = True
    kakao_app_id: str = field(default_factory=lambda: os.getenv("KAKAO_APP_ID", ""))
    google_client_ids: list[str] = field(
        default_factory=lambda: [
            v.strip() for v in os.getenv("GOOGLE_CLIENT_IDS", "").split(",") if v.strip()
        ]
    )

    def prepare(self):
        require(
            self.environment in ("development", "test", "production"),
            500,
            "CONFIGURATION_ERROR",
            "APP_ENV must be development, test or production.",
        )
        self.data_dir.mkdir(parents=True, exist_ok=True)
        (self.data_dir / "objects").mkdir(exist_ok=True)
        (self.data_dir / "staging").mkdir(exist_ok=True)
        names = ("jwt_key", "worker_key", "storage_key")
        if self.environment == "production":
            require(
                all(len(getattr(self, n)) >= 32 for n in names),
                500,
                "CONFIGURATION_ERROR",
                "Set three secrets of at least 32 characters.",
            )
            require(
                len({getattr(self, n) for n in names}) == 3,
                500,
                "CONFIGURATION_ERROR",
                "Use distinct secrets.",
            )
            require(
                self.base_url.startswith("https://"),
                500,
                "CONFIGURATION_ERROR",
                "PUBLIC_BASE_URL must use HTTPS.",
            )
            require(
                "*" not in self.cors_origins,
                500,
                "CONFIGURATION_ERROR",
                "Production CORS origins must be explicit.",
            )
        else:
            # Persist development secrets so restarts do not revoke sessions or grants.
            path = self.data_dir / "development-secrets.json"
            if not path.exists():
                try:
                    with path.open("x", encoding="utf-8") as f:
                        f.write(dumps({n: secrets.token_urlsafe(48) for n in names}))
                    path.chmod(0o600)
                except FileExistsError:
                    pass
            saved = json.loads(path.read_text(encoding="utf-8"))
            for n in names:
                if not getattr(self, n):
                    setattr(self, n, saved[n])


class Database:
    def __init__(self, settings):
        self.settings = settings
        self.path = settings.data_dir / "backend.sqlite3"
        with self.connect() as conn:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.executescript(
                (Path(__file__).parent / "migrations/001_initial.sql").read_text(encoding="utf-8")
            )

        from .migrate_v11 import migrate

        with self.transaction() as store:
            migrate(store)

    def connect(self):
        conn = sqlite3.connect(self.path, timeout=15, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys=ON")
        conn.execute("PRAGMA synchronous=FULL")
        return conn

    @contextmanager
    def transaction(self):
        conn = self.connect()
        try:
            conn.execute("BEGIN IMMEDIATE")
            yield Store(conn)
            conn.commit()
        except Problem as exc:
            conn.commit() if exc.commit else conn.rollback()
            raise
        except BaseException:
            conn.rollback()
            raise
        finally:
            conn.close()


class Store:
    def __init__(self, conn):
        self.conn = conn

    def get(self, kind, id, *, deleted=False):
        row = self.conn.execute("SELECT * FROM resources WHERE kind=? AND id=?", (kind, id)).fetchone()
        if row is None or (row["deleted_at"] is not None and not deleted):
            raise Problem(404, "RESOURCE_NOT_FOUND")
        return {
            "value": json.loads(row["data"]),
            "owner": row["owner"],
            "parent": row["parent"],
            "deleted": row["deleted_at"],
        }

    def optional(self, kind, id):
        try:
            return self.get(kind, id)["value"]
        except Problem as exc:
            if exc.status != 404:
                raise
            return None

    def put(self, kind, id, value, owner, parent=None):
        self.conn.execute(
            "INSERT INTO resources(kind,id,owner,parent,data) VALUES(?,?,?,?,?) ON CONFLICT(kind,id) DO UPDATE SET data=excluded.data",
            (kind, id, owner, parent, dumps(value)),
        )
        return value

    def delete(self, kind, id):
        self.conn.execute("UPDATE resources SET deleted_at=? WHERE kind=? AND id=?", (time.time(), kind, id))

    def all(self, kind, owner=None, parent=None):
        sql = "SELECT data FROM resources WHERE kind=? AND deleted_at IS NULL"
        args = [kind]
        if owner is not None:
            sql += " AND owner=?"
            args.append(owner)
        if parent is not None:
            sql += " AND parent=?"
            args.append(parent)
        return [json.loads(r[0]) for r in self.conn.execute(sql, args)]


class Contract:
    def __init__(self, settings=None):
        path = Path(__file__).parent / "contract"
        self.spec = json.loads((path / "openapi.json").read_text(encoding="utf-8"))
        self.ops = json.loads((path / "operations.json").read_text(encoding="utf-8"))
        if settings and settings.environment != "production":
            # Local development has no TLS terminator; production keeps the HTTPS-only source contract.
            for name in ("UploadGrant", "DownloadGrant"):
                self.spec["components"]["schemas"][name]["properties"]["url"]["pattern"] = "^https?://"

            def local_urls(v):
                if isinstance(v, dict):
                    if v.get("pattern") == "^https://":
                        v["pattern"] = "^https?://"
                    for value in v.values():
                        local_urls(value)
                elif isinstance(v, list):
                    for value in v:
                        local_urls(value)

            local_urls(self.spec)
        root = dict(self.spec, **{"$schema": "https://json-schema.org/draft/2020-12/schema"})
        registry = Registry().with_resource("urn:dulleobang", Resource.from_contents(root))
        self.validators = {
            n: Draft202012Validator(
                {"$ref": f"urn:dulleobang#/components/schemas/{n}"},
                registry=registry,
                format_checker=FormatChecker(),
            )
            for n in self.spec["components"]["schemas"]
        }

    def validate(self, name, value, *, output=False):
        errs = list(self.validators[name].iter_errors(value))
        if errs:
            if output:
                # Validation messages can contain a token or a signed URL value.
                raise RuntimeError(f"Response violates schema {name}, constraint {errs[0].validator}")
            raise Problem(
                422,
                "VALIDATION_FAILED",
                errors=[
                    {
                        "field": ".".join(map(str, e.absolute_path)) or "$",
                        "reason": "Failed " + e.validator + " constraint",
                    }
                    for e in errs[:20]
                ],
            )


passwords = PasswordHasher()


def encode_token(settings, user, session_id):
    now = int(time.time())
    return jwt.encode(
        {
            "iss": "dulleobang",
            "aud": "dulleobang-mobile",
            "sub": user["userId"],
            "sid": session_id,
            "iat": now,
            "exp": now + 900,
        },
        settings.jwt_key,
        algorithm="HS256",
    )


def worker_token(settings, worker_id, seconds=3600):
    now = int(time.time())
    return jwt.encode(
        {
            "iss": "dulleobang-worker",
            "aud": "dulleobang-internal",
            "sub": worker_id,
            "scope": "AI_WORKER",
            "iat": now,
            "exp": now + seconds,
        },
        settings.worker_key,
        algorithm="HS256",
    )


def token_hash(raw):
    return hashlib.sha256(raw.encode()).hexdigest()


def etag(value, supplied):
    if not supplied:
        raise Problem(428, "PRECONDITION_REQUIRED")
    require(supplied == f'"{value["version"]}"', 412, "VERSION_MISMATCH")


def bump(value):
    value["version"] += 1
    if "updatedAt" in value:
        value["updatedAt"] = stamp()
    return value


def checksum_header(sha):
    return base64.b64encode(bytes.fromhex(sha)).decode()
