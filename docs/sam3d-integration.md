# 온라인 가구 생성 → 내 가구

2026-11-08 배포 상태: 기존 GCP GPU 서버와 Spring Boot를 연결했고 실제 SAM3D 완료 결과의 저장·앱 API 조회·GLB 다운로드를 확인했습니다. 현재 서버와 실행 안내는 `deployment-20261108.md`를 참고해 주세요. 아래 localhost 예시는 별도 로컬 개발 환경을 위한 설명입니다.

이 기능은 상품 사진으로 생성 완료한 SAM 3D 가구를 기존 둘러방 가구 목록에 저장합니다. 기존 촬영·캡처 작업과 별도 경로이며, 기존 백엔드 원본과 기존 API 계약은 유지합니다.

## 연결 구조

```text
스튜디오 웹 → 스튜디오 Python 서버 → Spring Boot :8081 → PostgreSQL
                                         ↓
                                  SAM 3D 서버 :18766
                                         ↓
                               검증된 GLB → 영구 저장소
둘러방 앱 → Spring Boot → 기존 내 가구 조회 / 서명된 GLB 다운로드
```

별도 물리 서버를 추가할 필요는 없습니다. Spring Boot와 SAM 3D를 같은 GPU 서버에서 서로 다른 프로세스·포트로 실행할 수 있습니다. SAM 3D는 Python/GPU 환경이 필요하므로 Spring Boot 프로세스와 분리합니다. SAM 3D 포트는 내부 또는 SSH 터널로 사용합니다.

## 실행 설정

로컬 PC에서 Spring Boot와 스튜디오를 실행하고 기존 GPU 서버를 SSH 터널로 연결하는 예입니다. 터널은 사용자의 실제 서버 정보로 먼저 연결해야 합니다. 아래 명령이 서버를 생성하거나 터널을 열지는 않습니다.

Spring Boot 프로젝트에서 PowerShell:

```powershell
$env:SAM3D_ENABLED='true'
$env:SAM3D_URL='http://127.0.0.1:18766'
$env:BASE_URL='http://127.0.0.1:8081'
./scripts/run.ps1
```

위 명령 전에 프로젝트 안내에 따라 PostgreSQL을 실행합니다. 기존 DB는 Flyway V7을 추가 적용합니다. `-NativePostgres`는 일회성 검증용 DB이므로 실제 계정·가구를 보관할 실행에는 사용하지 마세요. `.env.example`은 예시이며 자동 로드되지 않습니다.

연동판 스튜디오 폴더에서 CPU 의존성을 별도 가상환경에 설치하고 실행합니다. GPU 추론 환경 설치는 기존 스튜디오 안내를 따릅니다.

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements.txt
./.venv/Scripts/python.exe server.py --port 18767 --upstream http://127.0.0.1:18766 --backend http://127.0.0.1:8081
```

웹은 `http://127.0.0.1:18767`에서 엽니다. 웹의 `--upstream`과 Spring Boot의 `SAM3D_URL`은 **같은 작업이 저장된 SAM 3D 서버**를 가리켜야 합니다. 서버가 다르면 작업 ID를 찾지 못합니다. 웹에서 연결 설정을 변경해도 Spring Boot 환경변수는 바뀌지 않습니다.

Spring Boot와 SAM 3D를 같은 VM에서 실행하면 `SAM3D_URL`에 그 VM의 loopback 주소를 사용합니다. 스튜디오를 다른 PC에서 실행하면 해당 PC의 SSH 터널 주소를 `--upstream`에 사용합니다. 운영 백엔드 주소는 HTTPS로 설정할 수 있으며 경로·쿼리를 붙이지 않습니다.

## 사용하는 순서

1. 기존 둘러방 이메일 계정이 있는 Spring Boot DB로 연결합니다.
2. 웹에서 상품 사진·마스크·실제 크기를 입력해 생성을 완료합니다.
3. `생성 결과 → 내 가구에 저장`을 누릅니다.
4. 앱과 같은 이메일 계정으로 로그인하고 이름·종류를 지정합니다.
5. 저장 후 앱의 `내 가구`를 새로고침합니다.

웹 로그인은 현재 이메일 계정을 지원합니다. 앱의 소셜 로그인만 사용하는 계정에는 웹 OAuth 연결이 추가로 필요합니다. 토큰·비밀번호를 파일이나 localStorage에 저장하지 않습니다. 토큰은 페이지 메모리에만 있으며 새로고침하면 로그인해야 합니다. 마지막 생성 작업 ID는 복구를 위해 브라우저에 저장합니다.

앱이 다른 DB/서버 또는 mock 모드를 사용하면 실제 가구가 나타나지 않습니다. 앱의 mock을 끄고 API base를 같은 Spring Boot의 `/api` 주소로 설정해야 합니다. 실제 휴대폰에서 `localhost`는 휴대폰 자신이므로 PC의 LAN 주소 또는 서버 HTTPS 주소를 사용하고, `BASE_URL`도 휴대폰이 접근 가능한 주소로 설정합니다. 앱 설정은 이 변경에서 자동 수정하지 않았습니다.

## API와 검증

추가 경로 두 개는 별도 확장 계약 `contract/extensions/sam3d.json`에 있습니다. 기존 97개 업무 API와 4개 보조 경로의 동결 계약은 변경하지 않았습니다.

```http
POST /api/v1/furniture/sam3d-imports
Authorization: Bearer <둘러방 access token>
Idempotency-Key: <UUID>
Content-Type: application/json

{"studioJobId":"32자리 소문자 hex 작업 ID","name":"온라인 의자","category":"CHAIR"}
```

모바일 별칭은 `POST /api/furniture/sam3d-imports`입니다. 종류는 `BED`, `DESK`, `SOFA`, `WARDROBE`, `SHELF`, `CHAIR`, `OTHER`입니다.

Spring Boot는 완료 상태, 추론 확인 메타데이터, 미터/Y-up/바닥 좌표계, 실제 치수와 GLB 바운딩 박스를 검증합니다. 응답의 임의 모델 URL을 따라가지 않고 설정된 서버의 고정 경로로 가져옵니다. 리다이렉트는 금지하고 응답 크기·시간·동시 작업 수를 제한합니다. 다운로드·GLB 검증은 DB 트랜잭션 밖에서 수행하고 가구·모델·파일 메타데이터는 하나의 트랜잭션으로 저장합니다.

최초 저장은 201, 같은 계정의 같은 작업·같은 입력을 새 키로 다시 저장하면 기존 가구로 200을 반환합니다. 동일 키 재시도는 원 응답을 재생합니다. 이미 저장한 작업의 이름·종류를 바꿔 재수입하면 409이며 앱에서 기존 가구를 수정해야 합니다. 원본 작업을 삭제해도 저장한 가구 파일은 백엔드에 남습니다.

결과는 기존 `READY` 가구/모델로 저장하고 앱 응답에서는 `COMPLETED`로 표시합니다. 기존 가구 조회·서명 URL·배치 경로를 사용합니다. 충돌 파일은 **크기를 감싸는 상자 근사치**입니다. SAM 3D가 재구성한 정밀 충돌 메시가 아니며 응답 `source.collisionApproximation=BOUNDING_BOX`로 구분합니다. 웹 배치 화면에서 재조정한 크기가 아닌 생성 당시 크기로 저장합니다.

## 검증 범위

실제 Spring Boot/PostgreSQL 통합 테스트로 저장·앱 조회·파일 다운로드·타인 접근 거부·중복 및 동시 요청·불완전 결과 거부를 검사합니다. 테스트의 SAM 3D HTTP 응답과 GLB는 합성 경계 데이터이며 GPU 추론 성공 증거가 아닙니다. Python 프록시와 JavaScript 클라이언트는 별도 테스트합니다.

현재 GCP 서버의 실제 생성 결과를 사용한 연동 검증은 완료했습니다. 이번 작업에서 새 GPU 추론이나 iPhone 화면까지의 현장 검증을 수행한 것은 아닙니다.
