"""Verify provider tokens through fixed official HTTPS endpoints."""

import threading
import time
import httpx
import jwt
from .core import Problem, require, strict_json

_lock = threading.Lock()
_keys = None
_expires = 0


def google_keys():
    value = provider_json("https://www.googleapis.com/oauth2/v3/certs")
    require(
        isinstance(value, dict)
        and isinstance(value.get("keys"), list)
        and all(isinstance(k, dict) for k in value["keys"]),
        503,
        "OAUTH_PROVIDER_UNAVAILABLE",
    )
    return value


def provider_json(url, headers=None):
    try:
        with httpx.Client(timeout=5, follow_redirects=False, trust_env=False) as c:
            with c.stream("GET", url, headers=headers) as r:
                if r.status_code in (400, 401, 403):
                    raise Problem(401, "INVALID_OAUTH_TOKEN")
                require(r.status_code == 200, 503, "OAUTH_PROVIDER_UNAVAILABLE")
                chunks = []
                size = 0
                for b in r.iter_bytes():
                    size += len(b)
                    require(size <= 1 << 20, 503, "OAUTH_PROVIDER_UNAVAILABLE")
                    chunks.append(b)
                v = strict_json(b"".join(chunks))
                require(isinstance(v, dict), 503, "OAUTH_PROVIDER_UNAVAILABLE")
                return v
    except Problem:
        raise
    except (httpx.HTTPError, ValueError, UnicodeError, RecursionError) as e:
        raise Problem(503, "OAUTH_PROVIDER_UNAVAILABLE") from e


def kakao_identity(token, cfg):
    require(cfg.kakao_app_id, 503, "OAUTH_NOT_CONFIGURED")
    h = {"Authorization": "Bearer " + token}
    i = provider_json("https://kapi.kakao.com/v1/user/access_token_info", h)
    require(isinstance(i, dict), 503, "OAUTH_PROVIDER_UNAVAILABLE")
    require(
        str(i.get("app_id")) == cfg.kakao_app_id
        and type(i.get("id")) is int
        and i["id"] > 0
        and type(i.get("expires_in")) in (int, float)
        and i.get("expires_in", 0) > 0,
        401,
        "INVALID_OAUTH_TOKEN",
    )
    u = provider_json("https://kapi.kakao.com/v2/user/me", h)
    require(isinstance(u, dict), 503, "OAUTH_PROVIDER_UNAVAILABLE")
    require(u.get("id") == i["id"], 401, "INVALID_OAUTH_TOKEN")
    a = u.get("kakao_account", {})
    require(isinstance(a, dict), 503, "OAUTH_PROVIDER_UNAVAILABLE")
    p = a.get("profile", {})
    props = u.get("properties", {})
    require(isinstance(p, dict) and isinstance(props, dict), 503, "OAUTH_PROVIDER_UNAVAILABLE")
    return dict(
        sub=str(i["id"]),
        email=a.get("email"),
        name=p.get("nickname") or props.get("nickname"),
    )


def google_identity(token, cfg):
    require(cfg.google_client_ids, 503, "OAUTH_NOT_CONFIGURED")
    try:
        h = jwt.get_unverified_header(token)
        require(h.get("alg") == "RS256" and isinstance(h.get("kid"), str), 401, "INVALID_OAUTH_TOKEN")
        global _keys, _expires
        with _lock:
            if _keys is None or _expires <= time.time():
                _keys = google_keys()
                _expires = time.time() + 3600
            key = next(
                (k for k in _keys.get("keys", []) if k.get("kid") == h["kid"] and k.get("kty") == "RSA"), None
            )
            if key is None:
                _keys = google_keys()
                _expires = time.time() + 3600
                key = next(
                    (k for k in _keys.get("keys", []) if k.get("kid") == h["kid"] and k.get("kty") == "RSA"),
                    None,
                )
        require(key is not None, 401, "INVALID_OAUTH_TOKEN")
        c = jwt.decode(
            token,
            jwt.PyJWK(key).key,
            algorithms=["RS256"],
            audience=cfg.google_client_ids,
            issuer=["https://accounts.google.com", "accounts.google.com"],
            options={"require": ["sub", "exp", "iat", "aud", "iss"]},
        )
        require(isinstance(c["sub"], str) and 1 <= len(c["sub"]) <= 255, 401, "INVALID_OAUTH_TOKEN")
        require(
            not isinstance(c["aud"], list) or c.get("azp") in cfg.google_client_ids,
            401,
            "INVALID_OAUTH_TOKEN",
        )
        return dict(sub=c["sub"], email=c.get("email"), name=c.get("name"))
    except Problem:
        raise
    except (jwt.PyJWTError, ValueError, TypeError, KeyError) as e:
        raise Problem(401, "INVALID_OAUTH_TOKEN") from e
