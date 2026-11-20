# 온라인 가구 생성 API 변경 사항

앱이 상품 사진으로 가구 모델을 생성할 수 있도록 Spring Boot에 생성 요청과 상태 조회 API를 추가했습니다. Spring Boot는 `SAM3D_URL`에 설정된 서비스로 요청을 전달하고, 완료된 작업은 기존 가져오기 API를 통해 ‘내 가구’에 저장합니다.

## 생성 요청

`POST /api/furniture/sam3d-jobs`에 `Authorization: Bearer <accessToken>`, `Idempotency-Key: <UUID>`, `Content-Type: application/json`을 보냅니다.

```json
{
  "name": "온라인 의자",
  "image": "data:image/png;base64,<마스크가 적용된 PNG의 Base64>",
  "dimensions": {"width": 0.6, "height": 0.8, "depth": 0.6}
}
```

가구 영역이 불투명하고 배경이 투명한 PNG를 사용합니다. 이미지 가로·세로는 각각 32~2,048 px, JSON 본문은 최대 10 MiB입니다. 치수는 미터 단위이며 각 변의 허용 범위는 0.02~20 m입니다.

성공 시 `202 Accepted`와 `studioJobId`, `status`를 반환합니다. `Location` 헤더에는 상태 조회 경로가 들어갑니다. 재시도 시 같은 본문과 `Idempotency-Key`를 보내면 이미 접수된 작업 ID를 받습니다.

## 상태 조회와 저장

1. 같은 계정으로 `GET /api/furniture/sam3d-jobs/{studioJobId}`를 약 5초 간격으로 호출합니다. 상태는 `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`입니다.
2. `COMPLETED`가 되면 기존 `POST /api/furniture/sam3d-imports`에 `studioJobId`, `name`, `category`를 보냅니다. 생성 요청과 별도의 저장용 `Idempotency-Key`를 사용합니다.
3. 저장 후 `GET /api/users/me/furniture`를 호출해 모델 주소와 미터 단위 치수가 포함된 목록을 갱신합니다.

| 상황 | 서버 응답 |
|---|---|
| 로그인 토큰 없음 또는 유효하지 않음 | `401 UNAUTHORIZED` |
| 본인 소유가 아닌 작업을 조회하거나 가져오기 | `404 SAM3D_JOB_NOT_FOUND` |
| 같은 생성 요청 키에 다른 본문 사용 | `409 IDEMPOTENCY_KEY_REUSED` |
| 작업이 끝나기 전에 가져오기 | `409 SAM3D_JOB_NOT_READY` |
| 요청 본문 크기 초과 | `413 BODY_TOO_LARGE` |
| SAM 3D 서비스가 생성 요청을 거절 | `422 SAM3D_GENERATION_REJECTED` |

## 저장과 검증

- Flyway `V10`이 생성 작업 ID와 요청한 계정의 연결을 저장합니다. 앱에서 만든 작업은 해당 계정으로 조회하고 가져올 수 있습니다.
- 기존 웹 스튜디오에서 생성한 작업은 기존 가져오기 경로로 처리합니다.
- Spring Boot JAR 빌드와 SAM 3D 통합 테스트 8개를 통과했습니다. 임시 PostgreSQL과 모의 HTTP 서버로 인증, 본문 검증, 접수 재시도, 계정별 접근 제한, 작업 조회와 기존 가져오기를 확인했습니다.
