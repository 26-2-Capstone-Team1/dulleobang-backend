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
| `SAM3D_ENABLED`, `SAM3D_URL` | 온라인 가구 생성·가져오기를 사용할 때 `true`와 SAM3D 서비스 주소 |

`.env.example`의 값을 환경변수로 전달합니다. VM 서비스는 `/etc/dulleobang/backend.env`를 읽습니다. 시작 후 `/health/live`와 `/health/ready`에서 상태를 확인합니다.

## 방 메시 응답

`GET /api/listings/{id}/space`는 기존 `meshUri`로 고해상도 방 GLB를 반환합니다. 파일이 등록된 경우 폰 기본용 `meshMobileUri`와 호환용 `meshFallbackUri`를 추가로 반환합니다. 세 URL은 모두 서명된 GLB URL이며 HTTP 응답의 `Content-Type`은 `model/gltf-binary`입니다.

완료 결과의 `meshSource.roomAsset`에는 기존 방 파일을 넣고, 추가 파일은 `roomMobileAsset`과 `roomFallbackAsset`에 넣습니다. 워커 출력은 두 파일을 `SOURCE_MESH` 역할로 등록하고 각각 `room-mobile`과 `room-fallback` 키를 사용합니다.

방 GLB에만 `KHR_texture_basisu`와 `EXT_meshopt_compression`을 허용합니다. KTX2는 GLB에 내장합니다. 가구와 충돌 GLB에는 기존 압축 규칙을 적용합니다.

## 앱에서 온라인 가구 생성

앱은 Spring Boot를 통해 SAM 3D 생성을 요청하고 상태를 조회합니다. [온라인 가구 생성 API 변경 사항](docs/sam3d-generation-api.md)에서 요청 형식과 저장 흐름을 확인할 수 있습니다.
