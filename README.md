# 둘러방 백엔드

Java 21, Spring Boot, PostgreSQL 기반 API입니다. AI 작업 처리는 [AI 워커](ai_worker/README.md)를 실행합니다.

## Windows 로컬 실행

JDK 21과 Docker Compose를 준비한 뒤 저장소 루트에서 실행합니다.

```powershell
docker compose up -d --wait postgres
powershell -ExecutionPolicy Bypass -File .\scripts\run.ps1
```

기본 주소는 `http://localhost:8081`입니다. 실행 스크립트는 개발용 서명 키를 `.runtime/dev-secrets.json`에 생성합니다. DB를 직접 준비한 경우 환경변수 `DB_URL`, `DB_USER`, `DB_PASSWORD`를 지정합니다.

## 빌드와 배포

```bash
./gradlew bootJar --no-daemon
```

Windows에서는 `gradlew.bat bootJar --no-daemon`을 실행합니다. 생성된 JAR는 `build/libs/dulleobang-backend-v2-2.0.0-p7.jar`입니다. 직접 실행하려면 환경변수를 설정하고 `java -jar`에 JAR 경로를 전달합니다. Linux VM에 systemd 서비스로 설치하려면 `sudo bash scripts/deploy-vm.sh /path/to/app.jar`를 실행합니다.

| 환경변수 | 설정 |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL 접속 정보 |
| `JWT_SECRET`, `WORKER_JWT_SECRET`, `STORAGE_SECRET` | 서로 다른 32자 이상의 서명 키 |
| `BASE_URL` | 앱과 워커가 접근하는 서버 주소 |
| `PORT`, `V2_DATA_DIR` | API 포트와 영속 파일 저장 경로 |
| `CORS_ORIGINS` | 웹 클라이언트의 허용 출처 |
| `KAKAO_APP_ID`, `GOOGLE_CLIENT_IDS` | 소셜 로그인 설정 |
| `SAM3D_ENABLED`, `SAM3D_URL` | 온라인 가구 가져오기를 사용할 때 `true`와 SAM3D 서비스 주소 |

`.env.example`의 값을 환경변수로 전달합니다. VM 서비스는 `/etc/dulleobang/backend.env`를 읽습니다. 시작 후 `/health/live`와 `/health/ready`에서 상태를 확인합니다.

## 방 메시 응답

`GET /api/listings/{id}/space`는 기존 `meshUri`로 고해상도 방 GLB를 반환합니다. 파일이 등록된 경우 폰 기본용 `meshMobileUri`와 호환용 `meshFallbackUri`를 추가로 반환합니다. 세 URL은 모두 서명된 GLB URL이며 HTTP 응답의 `Content-Type`은 `model/gltf-binary`입니다.

완료 결과의 `meshSource.roomAsset`에는 기존 방 파일을 넣고, 추가 파일은 `roomMobileAsset`과 `roomFallbackAsset`에 넣습니다. 워커 출력은 두 파일을 `SOURCE_MESH` 역할로 등록하고 각각 `room-mobile`과 `room-fallback` 키를 사용합니다.

방 GLB에만 `KHR_texture_basisu`와 `EXT_meshopt_compression`을 허용합니다. KTX2는 GLB에 내장합니다. 가구와 충돌 GLB에는 기존 압축 규칙을 적용합니다.

방의 시각화 GLB는 기존 이미지당 4천만 화소 한도에 더해 8192×8192 이하 텍스처를 허용합니다. 가구와 충돌 파일의 텍스처 한도는 기존과 같습니다.

## IKEA 가구 카탈로그

백엔드 JAR를 빌드한 뒤, 서버와 같은 DB 설정·서명 키·`V2_DATA_DIR`를 사용하는 환경에서 가져옵니다. 가져오기 명령만 Python 3.10 이상과 `app/requirements.txt`의 `google-cloud-storage`를 사용합니다. Google Cloud 기본 사용자 인증 정보(ADC)로 버킷을 읽으므로 실행 서비스 계정에 대상 버킷의 **Storage Object Viewer**(`roles/storage.objectViewer`) 권한이 필요합니다.

```bash
uv run --no-project --with-requirements app/requirements.txt \
  python -m app.cli import-catalog --bucket dulleobang-capstone-files --prefix ikea/v1
```

빌드 폴더 밖의 JAR를 사용하면 `--jar /path/to/backend.jar` 또는 `DULLEOBANG_JAR`로 지정합니다. 명령은 내려받은 파일을 임시 디렉터리에 두고 Spring Boot의 오프라인 관리 명령으로 가져옵니다. HTTP 서버를 열지 않습니다.

- `index.json`: 품번 문자열 배열(예: `["00123456"]`) 또는 `items`/`articles` 배열을 가진 객체. 배열 항목을 `{ "article": "00123456" }`로 써도 됩니다. 앞의 0이 보존되도록 품번은 문자열로 둡니다.
- `<article>/meta.json`: `name`, `category`, `dimensions`, `boundingBox`, `quality`, `sourceUrl`과 `sha256`(파일명 → 해시)을 포함합니다. `files` 안에 파일명별 `{"sha256": "…"}`을 넣은 형식도 읽습니다. 치수·경계 상자·품질은 기존 `FurnitureModel` 형식입니다.
- `<article>/model.glb`, `<article>/collision.glb`, `<article>/photo.jpg`: 원본의 SHA-256을 모두 대조한 뒤 두 GLB와 바닥 중심 경계 상자를 검증합니다. 실패한 항목은 게시하지 않고 명령이 중단됩니다. 다시 실행하면 이미 가져온 품번은 갱신되고, 같은 해시의 파일은 중복 저장되지 않습니다. 원본이 바뀌어도 이미 등록한 개인 가구의 모델은 유지됩니다.

| API (`/api/v1` 기준) | 동작 |
|---|---|
| `GET /catalog/furniture` | 이름·품번 `q`, `category`, `cursor`, `limit`으로 조회. 목록에는 썸네일만 포함합니다. |
| `GET /catalog/furniture/{catalogId}` | 해당 항목에 서명된 두 GLB URL이 든 `model`을 포함합니다. |
| `POST /furniture/from-catalog` | `Idempotency-Key`에 요청별 UUID, 본문에 `catalogId`와 선택 `name`을 보내면 모델 버전 1인 `READY` 가구를 반환합니다(201). |

모두 로그인해야 하며, 개인 가구 등록에는 기존과 같이 `TENANT` 역할이 필요합니다. 수납 가구는 현행 `SHELF` 카테고리를 유지합니다. 모바일 `/api` 경로도 제공하며 등록 결과의 상태·모델 URL은 기존 모바일 가구 응답과 같습니다.

모바일 내 가구 응답은 기존 `modelUri`, `dimensions`, `boundingBox`, `collisionMeshUri`를 유지합니다. 카탈로그에서 등록한 가구처럼 전체 모델 정보가 있으면 `model`도 반환합니다.

가구 GLB 좌표의 가로는 X, 높이는 Y, 깊이는 Z에 맞춥니다. 뷰어는 `dimensions`에 맞춰 축별로 크기를 조절하므로 원본도 이 방향으로 내보내야 합니다.

파일 URL이 없거나 만료되면 파일 참조(`model.modelAsset`, `model.collisionAsset`, `thumbnail`)의 `fileId`로 `GET /api/assets/{fileId}/download`(또는 `/api/v1/assets/{fileId}/download`)를 호출합니다. 이 요청에는 로그인 토큰을 붙이고, 응답으로 받은 15분 유효 서명 URL에는 붙이지 않습니다. `BASE_URL`은 `/api` 접미사 없이 앱에서 접근할 수 있는 서버 주소로 설정합니다.
