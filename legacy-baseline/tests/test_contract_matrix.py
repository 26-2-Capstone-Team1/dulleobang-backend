"""Reproducible declared-schema and HTTP rejection matrix (not all value combinations)."""

import copy
import json
import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker

from app.core import Contract, Settings, worker_token, uid
from app.main import create_app

ROOT = Path(__file__).parents[1]
SPEC = json.loads((ROOT / "app/contract/openapi.json").read_text(encoding="utf-8"))
OPS = json.loads((ROOT / "app/contract/operations.json").read_text(encoding="utf-8"))
SCHEMAS = SPEC["components"]["schemas"]
CONTRACT = Contract()
PUBLIC = {"signup", "login", "refreshToken", "logout", "loginKakao", "loginGoogle"}


def resolve(schema):
    if "$ref" in schema:
        return {**SCHEMAS[schema["$ref"].split("/")[-1]], **{k: v for k, v in schema.items() if k != "$ref"}}
    return schema


def sample(schema):
    schema = resolve(schema)
    if schema.get("examples"):
        return copy.deepcopy(schema["examples"][0])
    if "const" in schema:
        return schema["const"]
    if schema.get("enum"):
        return schema["enum"][0]
    for kind in ("oneOf", "anyOf"):
        if kind in schema:
            return sample(schema[kind][0])
    typ = schema.get("type")
    if isinstance(typ, list):
        typ = next((x for x in typ if x != "null"), "null")
    if typ == "object":
        return {k: sample(schema["properties"][k]) for k in schema.get("required", [])}
    if typ == "array":
        return [sample(schema.get("items", {})) for _ in range(schema.get("minItems", 0))]
    if typ == "string":
        return {
            "uuid": "11111111-1111-4111-8111-111111111111",
            "date-time": "2026-10-01T00:00:00Z",
            "date": "2026-10-01",
            "email": "test@example.com",
            "uri": "https://example.com/a",
        }.get(schema.get("format"), "x" * max(1, schema.get("minLength", 1)))
    if typ in ("integer", "number"):
        return max(1, schema.get("minimum", 1), schema.get("exclusiveMinimum", 0) + 1)
    if typ == "boolean":
        return False
    return None


def mutations(schema, value, path=(), depth=0):
    """Mutate one declared node at a time; retain nested optional/ref coverage."""
    schema = resolve(schema)
    typ = schema.get("type")
    if typ:
        allowed = typ if isinstance(typ, list) else [typ]
        wrong = [] if "array" not in allowed else {}
        if "object" in allowed and "array" in allowed:
            wrong = "wrong"
        yield path, "wrong-type", wrong
        if "null" not in allowed:
            yield path, "null", None
    if "enum" in schema:
        yield path, "invalid-enum", "__INVALID_ENUM__"
    if "const" in schema:
        yield path, "invalid-const", "__INVALID_CONST__"
    for name in ("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum"):
        if name in schema:
            bound = schema[name]
            delta = 1 if typ == "integer" else max(0.001, abs(bound) * 1e-6)
            invalid = bound - delta if name == "minimum" else bound + delta if name == "maximum" else bound
            yield path, name, invalid
    if schema.get("format"):
        yield path, "format", "not-a-valid-" + schema["format"]
    if schema.get("pattern"):
        for bad in ("__INVALID_PATTERN__", "", "a b"):
            if not re.search(schema["pattern"], bad):
                yield path, "pattern", bad
                break
    if "minLength" in schema and schema["minLength"] > 0:
        yield path, "minLength", "x" * (schema["minLength"] - 1)
    if "maxLength" in schema:
        yield path, "maxLength", "x" * (schema["maxLength"] + 1)
    if depth > 12:
        return
    if isinstance(value, dict):
        if schema.get("additionalProperties") is False:
            yield path, "additionalProperties", {**value, "__undeclared__": True}
        for key in schema.get("required", []):
            if key in value:
                changed = copy.deepcopy(value)
                changed.pop(key)
                yield path, "required-" + key, changed
        if schema.get("minProperties", 0):
            yield path, "minProperties", {}
        for key, prop in schema.get("properties", {}).items():
            existing = value.get(key, sample(prop))
            if existing is None:
                existing = sample(prop)
            yield from mutations(prop, existing, path + (key,), depth + 1)
    if isinstance(value, list):
        item = copy.deepcopy(value[0]) if value else sample(schema.get("items", {}))
        if schema.get("minItems", 0):
            yield path, "minItems", [copy.deepcopy(item) for _ in range(schema["minItems"] - 1)]
        if "maxItems" in schema:
            yield path, "maxItems", [copy.deepcopy(item) for _ in range(schema["maxItems"] + 1)]
        if schema.get("uniqueItems"):
            yield path, "uniqueItems", [copy.deepcopy(item), copy.deepcopy(item)]
        if "items" in schema:
            yield from mutations(schema["items"], item, path + (0,), depth + 1)
    for kind in ("oneOf", "anyOf", "allOf"):
        for child in schema.get(kind, []):
            yield from mutations(child, value, path, depth + 1)


def replace_at(root, path, value):
    root = copy.deepcopy(root)
    if not path:
        return copy.deepcopy(value)
    current = root
    for i, key in enumerate(path[:-1]):
        nxt = path[i + 1]
        if isinstance(current, list):
            while len(current) <= key:
                current.append({} if isinstance(nxt, str) else [])
            if current[key] is None:
                current[key] = {} if isinstance(nxt, str) else []
        elif key not in current or current[key] is None:
            current[key] = {} if isinstance(nxt, str) else []
        current = current[key]
    key = path[-1]
    if isinstance(current, list):
        while len(current) <= key:
            current.append(None)
    current[key] = copy.deepcopy(value)
    return root


def invalid_cases(name):
    base = sample(SCHEMAS[name])
    seen = set()
    for path, rule, value in mutations(SCHEMAS[name], base):
        changed = replace_at(base, path, value)
        if not CONTRACT.validators[name].is_valid(changed):
            signature = json.dumps(changed, sort_keys=True)
            if signature not in seen:
                seen.add(signature)
                yield ".".join(map(str, path)) + ":" + rule, changed


@pytest.mark.parametrize("name", sorted(SCHEMAS))
def test_all_schema_examples_and_declared_rejections(name, subtests):
    validator = CONTRACT.validators[name]
    for example in SCHEMAS[name]["examples"]:
        assert validator.is_valid(example), name
    cases = list(invalid_cases(name))
    assert cases, name
    for label, changed in cases:
        with subtests.test(schema=name, constraint=label):
            with pytest.raises(Exception) as raised:
                CONTRACT.validate(name, changed)
            assert getattr(raised.value, "status", None) == 422


@pytest.mark.parametrize("name", sorted(SCHEMAS))
def test_declared_scalar_positive_boundaries(name, subtests):
    def walk(schema, path=(), depth=0):
        schema = resolve(schema)
        if depth > 12:
            return
        if schema.get("type") in ("string", "integer", "number", "boolean"):
            yield path, schema
        for key, child in schema.get("properties", {}).items():
            yield from walk(child, path + (key,), depth + 1)
        if "items" in schema:
            yield from walk(schema["items"], path + ("items",), depth + 1)
        for kind in ("oneOf", "anyOf", "allOf"):
            for i, child in enumerate(schema.get(kind, [])):
                yield from walk(child, path + (kind, i), depth + 1)

    for path, schema in walk(SCHEMAS[name]):
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        typ = schema["type"]
        values = list(schema.get("enum", []))
        if typ in ("integer", "number"):
            for key in ("minimum", "maximum"):
                if key in schema:
                    values.append(schema[key])
            if typ == "integer":
                with subtests.test(schema=name, field=path, rule="fractional-integer"):
                    assert not validator.is_valid(1.5)
                    assert not validator.is_valid(True)
        if typ == "boolean":
            values += [True, False]
        if typ == "string" and not any(k in schema for k in ("pattern", "format", "const", "enum")):
            for key in ("minLength", "maxLength"):
                if key in schema:
                    values.append("x" * schema[key])
        for value in values:
            with subtests.test(schema=name, field=path, rule="positive-boundary"):
                assert validator.is_valid(value)


@pytest.fixture(scope="module")
def matrix_server(tmp_path_factory):
    cfg = Settings(data_dir=tmp_path_factory.mktemp("http-matrix"), scheduler=False, rate_limits=False)
    app = create_app(cfg)
    with TestClient(app) as client:
        assert (
            client.post(
                "/api/v1/auth/signup",
                json={"email": "matrix@example.com", "password": "StrongPassword!123", "displayName": "검증"},
            ).status_code
            == 201
        )
        pair = client.post(
            "/api/v1/auth/login", json={"email": "matrix@example.com", "password": "StrongPassword!123"}
        ).json()
        yield app, client, {"Authorization": "Bearer " + pair["accessToken"]}


def request_parts(op, user, cfg):
    path = "/api/v1" + re.sub(r"\{[^}]+\}", "11111111-1111-4111-8111-111111111111", op["path"])
    headers = dict(user)
    if op["internal"]:
        headers["Authorization"] = "Bearer " + worker_token(cfg, "matrix-worker")
    for p in op["params"]:
        if p["in"] == "header":
            headers[p["name"]] = (
                uid()
                if p["name"] == "Idempotency-Key"
                else "lease-test-token-" + "a" * 48
                if p["name"] == "X-Job-Lease-Token"
                else '"1"'
                if p["name"] == "If-Match"
                else "*"
            )
    return path, headers


@pytest.mark.parametrize("op", OPS, ids=lambda op: op["oid"])
def test_http_common_matrix(matrix_server, op, subtests):
    if op.get("binary"):
        pytest.skip("ZIP request validation is exercised by test_capture_zip.py")
    app, client, user = matrix_server
    path, headers = request_parts(op, user, app.state.settings)
    body = sample(SCHEMAS[op["req"]]) if op["req"] else None

    def call(h=headers, raw=None, query=None, target=path):
        kwargs = {"headers": h}
        if raw is not None:
            kwargs["content"] = raw
        elif body is not None:
            kwargs["json"] = body
        if query is not None:
            kwargs["params"] = query
        return client.request(op["method"], target, **kwargs)

    if op["oid"] not in PUBLIC:
        for auth in (None, "Bearer malformed", "Basic abc"):
            with subtests.test(operation=op["oid"], auth=auth):
                h = {k: v for k, v in headers.items() if k.lower() != "authorization"}
                if auth:
                    h["Authorization"] = auth
                assert call(h=h).status_code == 401
    for p in op["params"]:
        if p["in"] == "path":
            with subtests.test(operation=op["oid"], path=p["name"]):
                assert (
                    call(
                        target=path.replace("11111111-1111-4111-8111-111111111111", "invalid-uuid", 1)
                    ).status_code
                    == 400
                )
        elif p["in"] == "header" and p["name"] == "Idempotency-Key":
            assert call(h={k: v for k, v in headers.items() if k != p["name"]}).status_code == 400
            assert call(h={**headers, p["name"]: "invalid-key"}).status_code == 400
    assert call(query={"__undeclared__": "true"}).status_code == 400
    if op["req"]:
        for raw, expected in (
            (b"{", 400),
            (b'{"x":1,"x":2}', 400),
            (b'{"x":NaN}', 400),
            (b'{"x":1e999}', 400),
            (b"\xff", 400),
            (b"null", 422),
            (b"[]", 422),
        ):
            with subtests.test(operation=op["oid"], raw=str(raw)):
                assert (
                    call(h={**headers, "Content-Type": "application/json"}, raw=raw).status_code == expected
                )
        assert call(h={**headers, "Content-Type": "text/plain"}, raw=b"{}").status_code == 415
        assert client.request(op["method"], path, headers=headers).status_code == 422
    else:
        assert call(h={**headers, "Content-Type": "application/json"}, raw=b"{}").status_code == 400


@pytest.mark.parametrize("op", [op for op in OPS if op["req"]], ids=lambda op: op["oid"])
def test_request_schema_mutations_over_http(matrix_server, op, subtests):
    app, client, user = matrix_server
    path, headers = request_parts(op, user, app.state.settings)
    for label, changed in invalid_cases(op["req"]):
        with subtests.test(operation=op["oid"], constraint=label):
            response = client.request(op["method"], path, json=changed, headers=headers)
            assert response.status_code == 422, (label, response.text)
            assert response.headers["content-type"].startswith("application/problem+json")
            assert response.headers["cache-control"] == "no-store"
            assert response.json()["requestId"] == response.headers["x-request-id"]


@pytest.mark.parametrize(
    "op", [op for op in OPS if any(p["in"] == "query" for p in op["params"])], ids=lambda op: op["oid"]
)
def test_query_constraint_matrix(matrix_server, op, subtests):
    app, client, user = matrix_server
    path, headers = request_parts(op, user, app.state.settings)
    for p in op["params"]:
        if p["in"] != "query":
            continue
        schema, key = p["schema"], p["name"]
        candidates = (
            ["__INVALID__"]
            if schema.get("type") != "string" or schema.get("format") or schema.get("enum")
            else []
        )
        for bound in ("minimum", "maximum"):
            if bound in schema:
                candidates.append(str(schema[bound] + (-1 if bound == "minimum" else 1)))
        if "maxLength" in schema:
            candidates.append("x" * (schema["maxLength"] + 1))
        if schema.get("type") == "number":
            candidates += ["NaN", "Infinity", "-Infinity"]
        for value in candidates:
            with subtests.test(operation=op["oid"], query=key, value=value[:40]):
                assert (
                    client.request(op["method"], path, params=[(key, value)], headers=headers).status_code
                    == 400
                )
        if schema.get("type") != "array":
            with subtests.test(operation=op["oid"], duplicate=key):
                assert (
                    client.request(
                        op["method"], path, params=[(key, "1"), (key, "2")], headers=headers
                    ).status_code
                    == 400
                )
