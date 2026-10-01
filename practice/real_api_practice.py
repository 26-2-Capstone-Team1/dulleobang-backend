"""Create a disposable teaching account/draft in a running LOCAL Dulleobang server.

Never prints tokens/passwords or changes existing accounts/listings.
"""

import argparse
import copy
from urllib.parse import urlsplit
from uuid import uuid4

import httpx


def expect(response, status):
    if response.status_code != status:
        try:
            code = response.json().get("code", "NO_ERROR_CODE")
        except ValueError:
            code = "NON_JSON_RESPONSE"
        raise RuntimeError(
            f"예상 HTTP {status}, 실제 {response.status_code}, 오류 code={code}"
        )
    return response


def main():
    parser = argparse.ArgumentParser(
        description="둘러방 로컬 API 실습: 학습 계정·초안 생성"
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:8000")
    args = parser.parse_args()
    parsed = urlsplit(args.base_url)
    if (
        parsed.scheme != "http"
        or parsed.hostname not in ("127.0.0.1", "localhost", "::1")
        or parsed.username
        or parsed.password
        or parsed.path not in ("", "/")
        or parsed.query
        or parsed.fragment
    ):
        parser.error(
            "실습은 http://localhost:PORT 형태의 로컬 서버 기본 주소만 사용합니다."
        )
    base = args.base_url.rstrip("/")
    account = {
        "email": "learning-" + uuid4().hex + "@example.com",
        "password": "LearningOnly!" + uuid4().hex,
        "displayName": "API 학습 사용자",
    }
    with httpx.Client(
        base_url=base, timeout=30, follow_redirects=False, trust_env=False
    ) as client:
        expect(client.get("/health/ready"), 200)
        expect(client.post("/api/v1/auth/signup", json=account), 201)
        tokens = expect(
            client.post(
                "/api/v1/auth/login",
                json={k: account[k] for k in ("email", "password")},
            ),
            200,
        ).json()
        client.headers["Authorization"] = "Bearer " + tokens["accessToken"]
        expect(client.get("/api/v1/users/me"), 200)
        body = {
            "roomType": "ONE_ROOM",
            "price": {
                "leaseType": "MONTHLY",
                "deposit": 10_000_000,
                "monthlyRent": 650_000,
                "maintenanceFee": None,
                "currency": "KRW",
            },
            "location": {
                "roadAddress": "서울특별시 강남구 예시로 10",
                "latitude": 37.5,
                "longitude": 127.03,
            },
            "exclusiveAreaM2": 24.5,
            "rentalOptions": ["PARKING"],
        }
        headers = {"Idempotency-Key": str(uuid4())}
        first = expect(client.post("/api/v1/listings", json=body, headers=headers), 201)
        listing = first.json()
        repeated = expect(
            client.post("/api/v1/listings", json=body, headers=headers), 201
        )
        assert listing["listingId"] == repeated.json()["listingId"]
        changed = copy.deepcopy(body)
        changed["floor"] = 2
        expect(client.post("/api/v1/listings", json=changed, headers=headers), 409)
        listing_url = "/api/v1/listings/" + listing["listingId"]
        current = expect(client.get(listing_url), 200)
        etag = current.headers["ETag"]
        expect(
            client.patch(listing_url, json={"floor": 3}, headers={"If-Match": etag}),
            200,
        )
        expect(
            client.patch(listing_url, json={"floor": 4}, headers={"If-Match": etag}),
            412,
        )
        expect(client.patch(listing_url, json={"floor": 4}), 428)
        assert expect(client.get(listing_url), 200).json()["floor"] == 3
        mine = expect(client.get("/api/v1/users/me/listings"), 200).json()["items"]
        assert any(item["listingId"] == listing["listingId"] for item in mine)
        expect(
            client.post(
                "/api/v1/auth/logout", json={"refreshToken": tokens["refreshToken"]}
            ),
            204,
        )
        expect(client.get("/api/v1/users/me"), 401)
    print("가입·로그인·초안·멱등·ETag·로그아웃 검증: 통과")
    print("생성한 학습 초안 ID:", listing["listingId"])
    print("기존 데이터는 수정하지 않았으며, 이 실습의 계정·초안은 학습 DB에 남습니다.")


if __name__ == "__main__":
    main()
