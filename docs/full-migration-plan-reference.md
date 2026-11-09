> 이전 논의의 전체 전환 계획 참고본입니다. 현재 작업 위치·범위는 루트 IMPLEMENTATION_PLAN.md를 우선합니다.

# 둘러방 Spring Boot 전환 및 파일 분리 구현 계획

작성일: 2026-10-16 · 상태: 구현 전 계획 · 기준: 현재 로컬 백엔드와 Swift 프론트엔드

## 1. 목표와 구현 범위

Spring Boot 기반의 단일 백엔드 애플리케이션으로 이전합니다. 기능별 패키지 안에서 Controller, Service, Repository, Domain, DTO, Mapper를 파일로 분리합니다. 현재 Swift 앱과 Python 워커 클라이언트가 사용하는 HTTP 계약을 보존합니다.

현재 등록된 HTTP 메서드·경로 조합은 `/api/v1` 60개, 앱 전용 `/api` 37개로 총 97개입니다. 원본 명세의 45개 경로는 `/api/v1` 기준입니다. 별도 파일 전송 경로, 상태 확인 API, 운영 CLI도 이전 대상에 포함합니다. 전체 경로 목록은 함께 제공하는 `dulleobang-api-inventory.json`에 기록합니다.

| 현재 구현 | 현재 규모 | 이전할 책임 |
|---|---:|---|
| `app/main.py` | 640줄 | 초기화, Controller, Filter, 예외 처리, 상태 확인 |
| `app/service.py` | 1,537줄 | 인증, 업로드, 촬영, 작업, 공간, 가구, 배치별 서비스 |
| `app/features.py` | 422줄 | 매물, 관심매물, 신고, 중개사 인증, 채팅 |
| `app/core.py` | 352줄 | 설정, DB, 계약 검증, 보안, ETag |
| `app/mobile.py` | 748줄 | 앱 Controller·DTO·Mapper 및 입력 변환 |
| `app/capture_zip.py`, `capture_inputs.py`, `geometry.py` | 별도 파일 | ZIP·이미지·좌표·PLY·GLB 검증기 |
| `app/cli.py`, `admin.py` | 별도 파일 | 운영 명령, 승인·반려, 워커 토큰, 백업 |

새 프로젝트는 기존 저장소의 `spring/` 디렉터리에 구성합니다. 기존 Python 서버를 비교 실행할 수 있도록 유지하며, 개발 중 기존 DB에 새 서버가 쓰지 않도록 테스트 저장소를 분리합니다. Python 코드를 먼저 전면 재구성하는 작업은 계획에 넣지 않고, 책임을 추출해 Java 구현으로 옮깁니다.

이 계획은 파일·클래스 생성과 기능 구현의 작업 기준입니다. 현재 Java 프로젝트, DB 스키마, 아래에 기재한 예정 파일은 아직 생성하지 않았습니다.

## 2. 기술 구성과 기본 결정

| 항목 | 구현안 |
|---|---|
| 언어·서버 | Java 21, Spring Boot 4.1.1, Spring MVC REST API |
| 빌드 | Gradle Wrapper. Boot가 지원하는 Gradle 버전을 P1에서 고정 |
| 인증 | Spring Security, 기존 JWT·refresh 회전·세션 폐기 동작 유지 |
| 입력 검증 | Bean Validation + JSON Schema 계약 검증 + 도메인 검증기 |
| DB | PostgreSQL, Spring Data JPA, Flyway 마이그레이션 |
| 파일 | 로컬 파일 저장소로 시작하고 `AssetStorage` 인터페이스 뒤에 구현 |
| 작업 큐 | DB 기반 작업·outbox 유지. 스케줄러로 전달·만료·재시도 처리 |
| 테스트 | JUnit, HTTP 계약 테스트, 실제 PostgreSQL 통합 테스트 |
| 실행 | 개발용 Compose, Windows PowerShell 실행 스크립트 |

Spring Boot 4.1.1은 Java 17~26 및 Gradle 8.14 이상 8.x·9.x를 지원한다고 공식 문서에 명시되어 있습니다. 선택한 JSON Schema·Argon2·이미지·3D 파일 검증 라이브러리의 실제 호환성은 P1에서 확인한 뒤 의존성을 고정합니다. [Spring Boot 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html)

단일 배포 애플리케이션에서 패키지와 책임을 분리합니다. 초기 이전 범위에 서비스별 별도 배포, Redis, Kafka, S3 도입은 포함하지 않습니다. 실제 AI 모델 구현은 기존 백엔드에도 없으므로, 이번 범위는 작업 접수·워커 계약·결과 검증까지입니다.

## 3. 디렉터리와 파일 분리 규칙

```text
dulleobang-backend/
├── app/                         # 비교 기준 Python 구현
├── tests/                       # 기존 테스트 및 fixture
├── spring/
│   ├── build.gradle
│   ├── settings.gradle
│   ├── gradlew / gradlew.bat
│   └── src/
│       ├── main/
│       │   ├── java/com/dulleobang/
│       │   │   ├── DulleobangApplication.java
│       │   │   ├── auth/ user/ listing/ scrap/ report/ verification/
│       │   │   ├── chat/ furniture/ upload/ capture/ job/
│       │   │   ├── space/ roomstate/ placement/
│       │   │   ├── infrastructure/storage/ infrastructure/oauth/
│       │   │   ├── infrastructure/scheduling/
│       │   │   ├── common/security/ common/config/ common/exception/
│       │   │   ├── common/idempotency/ common/contract/ common/pagination/
│       │   │   └── admin/
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-local.yml
│       │       ├── application-test.yml
│       │       ├── db/migration/
│       │       └── contract/
│       └── test/
│           ├── java/com/dulleobang/
│           └── resources/fixtures/
├── contract-tests/              # Python/Java 공통 HTTP 시나리오
├── scripts/run-spring.ps1
├── scripts/export-legacy.py
└── docs/migration/
```

각 기능 안에 필요한 `controller/`, `service/`, `repository/`, `domain/`, `dto/`, `mapper/`, `validation/`을 둡니다. 사용하지 않는 빈 폴더와 불필요한 인터페이스는 만들지 않습니다. 기능별 패키지는 Spring Boot 공식 문서의 구조 예시와도 맞습니다. [구조 문서](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)

| 계층 | 책임 | 의존 규칙 |
|---|---|---|
| Controller | 경로·헤더·본문 수신, 인증 주체 전달, 상태 코드·응답 생성 | Service와 DTO·Mapper 사용 |
| Service | 업무 흐름, 권한 판단 조율, 트랜잭션 경계 | Repository·도메인 정책·외부 연동 인터페이스 사용 |
| Domain / Policy | 가격, 게시 조건, 제거 가능 여부, 상태 전이 등 업무 규칙 | HTTP 요청 객체에 의존하지 않음 |
| Repository | 엔티티 조회·저장·검색·잠금 | HTTP 응답 생성이나 외부 API 호출을 넣지 않음 |
| DTO / Mapper | 앱·v1 요청 및 응답 형식, 단위·좌표·enum 변환 | JPA 엔티티를 API 응답으로 직접 노출하지 않음 |
| Infrastructure | 파일 I/O, OAuth 제공자 통신, 스케줄러 실행 | 애플리케이션이 정의한 인터페이스 구현 |

주요 클래스는 각각 파일로 분리합니다. Service는 조회·변경·게시 등 책임이 갈라질 때 나눕니다. `Utils`, `CommonService`, `BaseService`에 기능을 다시 모으지 않습니다. DB 접근이 필요한 도우미를 정적 유틸리티로 만들지 않습니다. 함수·클래스의 줄 수는 검토 신호로 사용하고, 책임과 변경 이유를 기준으로 분리합니다.

SOLID 적용은 다음 코드 규칙으로 확인합니다. 의존성은 생성자로 주입하고, 파일 저장소·OAuth 제공자·시간처럼 바뀌거나 테스트에서 대체할 경계에 인터페이스를 둡니다. Service마다 형식적인 인터페이스를 만들지는 않습니다. Controller의 Repository 직접 호출, Domain의 HTTP 의존, 기능 간 순환 의존은 구조 테스트로 차단합니다. 조회·파일 저장·토큰 발급처럼 서로 다른 책임을 하나의 상속 기반 서비스로 합치지 않습니다.

## 4. 기능별 예정 파일

아래는 핵심 파일 목록입니다. 요청·응답 DTO, Repository, 엔티티는 각 기능 패키지에서 별도 파일로 추가합니다.

| 기능 | Controller·Service·정책 파일 |
|---|---|
| 인증·사용자 | `MobileAuthController`, `V1AuthController`, `AuthService`, `SessionService`, `UserService`, `JwtTokenProvider`, `RefreshTokenRotator` |
| 매물 | `MobileListingController`, `V1ListingController`, `ListingQueryService`, `ListingCommandService`, `ListingPublicationService`, `ListingAccessPolicy`, `ListingMapper` |
| 관심매물·신고 | `ScrapController`, `ScrapService`, `ReportController`, `ReportService`, `ReportDuplicatePolicy` |
| 중개사 인증 | `MobileAgentProfileController`, `V1AgentVerificationController`, `AgentVerificationService`, `AgentReviewService` |
| 채팅 | `MobileChatController`, `V1ChatController`, `ChatRoomService`, `ChatMessageService`, `ChatReadService`, `ChatAccessPolicy`, `ChatMapper` |
| 가구 | `MobileFurnitureController`, `V1FurnitureController`, `FurnitureQueryService`, `FurnitureCommandService`, `FurnitureCategoryMapper` |
| 파일·업로드 | `UploadSessionController`, `UploadSessionService`, `UploadVerificationService`, `AssetController`, `StorageObjectController`, `AssetAccessPolicy`, `SignedUrlService` |
| 사진 | `ListingImageController`, `ListingPhotoService`, `PhotoSanitizer` |
| 촬영 | `CaptureController`, `CaptureSubmissionService`, `CaptureZipValidator`, `CaptureManifestParser`, `DepthImageValidator`, `RoomPlanValidator`, `CaptureCoordinateNormalizer` |
| 작업·워커 | `AiJobController`, `WorkerJobController`, `AiJobService`, `JobLeaseService`, `JobResultService`, `JobRetryService`, `OutboxPublisher`, `JobMaintenanceService` |
| 3D 공간 | `SpaceController`, `SpaceQueryService`, `ObjectClassificationService`, `SpaceBundleValidator`, `GlbValidator`, `PlyValidator`, `MobileSpaceMapper` |
| 개인 입주 상태 | `RoomStateController`, `RoomStateService`, `RemovalPolicy`, `RoomStateAccessPolicy`, `RoomStateMapper` |
| 배치 | `PlacementController`, `PlacementService`, `PlacementVersionPolicy`, `PlacementMapper` |
| 공통 | `GlobalExceptionHandler`, `SecurityConfig`, `IdempotencyService`, `ETagParser`, `CursorService`, `ChecksumCalculator`, `ContractValidator` |
| 운영 | `AdminCommandRunner`, `AgentReviewCommand`, `ReportReviewCommand`, `WorkerTokenCommand`, `QueueCommand`, `BackupCommand` |

같은 업무를 처리하는 앱·v1 Controller는 동일한 Service를 호출합니다. 두 API의 차이는 DTO·Mapper와 명시적인 정책으로 표현합니다. 앱 사진 업로드 후 게시와 v1의 사진+3D 게시 조건도 각각 유지합니다.

## 5. 보존할 계약과 회귀 기준

| 항목 | 보존할 동작 |
|---|---|
| API 범위 | `/api/v1` 60개와 `/api` 37개, 상태·파일 경로, 운영 명령 |
| JSON·오류 | 필수·선택·null·enum·숫자/문자열 구분, 상태 코드, 오류 code·body·헤더 |
| 인증 | 기존 Argon2 해시 검증, JWT claim, access/refresh 수명, 회전·재사용 탐지·로그아웃 폐기 |
| 소셜 로그인 | 카카오 앱 ID, 구글 서명·issuer·audience·만료 검증, 동일 이메일 자동 계정 연결 금지 |
| 가격·주소 | 앱 만원 ↔ 내부 원 정수, 문자열 주소 ↔ 내부 주소 객체, 좌표 필수 조건 |
| 가구 종류 | `bed/desk/sofa/closet/storage/chair/other`와 내부 enum 양방향 변환 |
| 3D 객체 | 내부 바닥 중심 → 앱 상자 중심. 회전된 로컬 반높이를 월드 좌표에 적용 |
| 천장 | 앱 `ceilingHeight = max(ceilingY) - min(floorY)`, boundingBox는 절대 좌표 |
| 업로드 | 사진·증빙은 multipart, 촬영은 ZIP 원문. Content-Type·파일 순서·한도 유지 |
| 작업 | 접수·outbox 원자 저장, claim·lease·heartbeat·retry·cancel·중복 callback 동작 |
| 버전 | spaceVersion·objectsVersion·modelVersion, 과거 공간, 배치 isStale, ETag 충돌 보호 |
| 권한 | 타인 자원의 404/403 구분, 미게시 매물 보호, 개인 입주 상태 작성자만 조회 |
| 페이지 | 서명 커서, 사용자·경로·필터 결합, 영속 ID 스냅샷, 앱 자동 전체 페이지 취합 |
| 파일 URL | 다운로드 가능한 서명 URL, 만료·소유권·메서드 검사, 재생 응답에서도 URL 재발급 |

Spring의 기본 검증 오류가 기존 API의 상태 코드와 같다고 가정하지 않습니다. 예외 변환을 명시하여 기존 400/422 등의 구분을 재현합니다. multipart와 일반 요청 본문의 처리 경로도 구분합니다. [Spring multipart 문서](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/multipart-forms.html)

## 6. 단계별 구현 작업과 완료 조건

### P0. 계약·fixture·비교 테스트 기반 고정

- [ ] 97개 API와 파일·상태 경로를 inventory에 고정하고 담당 기능·테스트를 연결합니다.
- [ ] 앱 37개 API를 별도 계약으로 문서화합니다. 현재 `/api` 라우트는 OpenAPI 노출에서 제외되어 있으므로 v1 명세 복사만으로 끝내지 않습니다.
- [ ] 현재 DB·파일·설정은 복사본으로 fixture를 만들고 민감정보 없는 테스트 데이터를 준비합니다.
- [ ] 기존 테스트를 HTTP 시나리오와 Python 내부 구현 테스트로 구분합니다.
- [ ] 이전 가능한 HTTP 시나리오는 `contract-tests/`에서 서버 주소를 바꾸어 실행하도록 만듭니다.

완료 조건: API 누락을 자동 검사할 수 있고, 현재 Python 서버에서 공통 계약 테스트가 통과합니다. 앞서 통과한 70개는 `test_mobile.py`, `test_capture_zip.py`, `test_backend.py`의 결과이며 전체 테스트 결과로 확대 해석하지 않습니다. P0에서 나머지 기존 테스트도 실행해 기준선을 기록합니다.

### P1. 전환 위험 요소의 작은 검증 구현

- [ ] Java에서 기존 Argon2 해시·JWT·저장소 서명 토큰을 검증하는 fixture를 통과시킵니다.
- [ ] JSON 중복 키, 잘못된 타입, NaN·Infinity, RFC 8785 정규화·멱등 해시의 호환성을 검증합니다.
- [ ] PNG uint16 mm·confidence 값, JPEG/PNG 디코드와 메타데이터 제거를 확인합니다.
- [ ] 실제 ZIP 및 PLY·GLB fixture로 검증기 구현 가능성을 확인합니다. 검증을 생략하거나 워커를 신뢰하는 방식으로 대체하지 않습니다.
- [ ] 실제 JDK·Docker 이용 가능 여부와 필요한 의존성의 Boot 4.1.1 호환성을 확인하고 Wrapper·BOM 버전을 고정합니다.

완료 조건: 인증·해시·바이너리 파일의 언어 간 차이를 해결한 작은 실행 테스트가 존재합니다. 해결되지 않은 호환성은 광범위한 포팅 전에 기록하고 구현 방식을 확정합니다. Spring Security는 `PasswordEncoder` Bean으로 구현을 구성할 수 있습니다. [공식 문서](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/password-encoder.html)

### P2. 프로젝트·DB·공통 실행 기반

- [ ] `spring/` 프로젝트와 기능 패키지를 생성합니다.
- [ ] PostgreSQL 개발·테스트 설정, Flyway, 보안 필터, 예외 응답, 요청 ID, CORS, health를 구성합니다.
- [ ] 멱등 키 저장소, ETag 파서, 버전 충돌 변환, 커서 기반을 구현합니다.
- [ ] 엔티티와 DB 스키마의 초안을 만들고 이후 기능 단계에서 마이그레이션을 순차 추가합니다.
- [ ] 기존 Python CI와 별도로 Spring 빌드·테스트 작업을 추가합니다.

완료 조건: `gradlew.bat test`가 실행되고 실제 PostgreSQL에서 마이그레이션·기본 트랜잭션·공통 오류·중복 키 경쟁 테스트가 통과합니다. 테스트 환경은 Testcontainers를 우선 사용하고, Docker 실행 제약이 있으면 별도 PostgreSQL 접속 설정을 사용합니다.

### P3. 인증·사용자

- [ ] 이메일 가입·로그인·refresh·로그아웃·내 정보, 앱·v1 Controller를 구현합니다.
- [ ] 카카오·구글 제공자 클라이언트를 분리하고 테스트에서는 제공자 HTTP 경계를 대체합니다.
- [ ] 회원가입으로 권한을 추가할 수 없도록 서버 역할 검증을 유지합니다.
- [ ] 토큰 재사용·동시 refresh·로그아웃 후 access 차단을 검증합니다.

완료 조건: 기존 해시의 계정 로그인 및 세션·refresh 정책이 공통 계약 테스트에서 일치합니다. 외부 OAuth 실제 로그인은 앱 SDK·키가 준비된 실환경 테스트 항목으로 따로 기록합니다.

### P4. 파일 저장·업로드·가구 기본 기능

- [ ] `AssetStorage`와 `LocalAssetStorage`, 서명 URL, 파일 스트리밍 GET/PUT을 구현합니다.
- [ ] upload-session 생성·갱신·완료, SHA-256·실제 바이트 검증, 업로드 후 불변 조건을 구현합니다.
- [ ] 가구 메타데이터 CRUD·목록과 앱 종류 변환을 구현합니다.
- [ ] 사진 정제와 증빙 파일의 비공개 접근 규칙을 구현합니다.

완료 조건: 파일 소유권·메서드·만료·checksum 실패가 기존 응답과 일치하고 임시 파일이 정리됩니다. 앱 7종 가구 등록·조회·멱등 재생과 v1 enum의 구분이 통과합니다.

### P5. 매물·사진·관심·신고·인증·채팅

- [ ] 매물 등록·수정·삭제·검색·내 매물·게시 서비스를 구현합니다.
- [ ] 사진 순서·전체 교체·앱 자동 게시, v1 게시 조건을 유지합니다.
- [ ] 관심매물, 신고 중복, 중개사 증빙 신청·승인·반려를 구현합니다.
- [ ] 채팅방 재사용·메시지 멱등·읽음·미읽음 수와 앱 상세 메시지 취합을 구현합니다.
- [ ] 승인·신고 검토 CLI를 구현하고 공개 사용자 API로 관리자 권한을 추가하지 않습니다.

완료 조건: 앱에서 로그인→매물 등록→사진 업로드→검색·상세→찜·신고→채팅 흐름이 정상이며 가격·주소·개인정보 노출 규칙이 기존과 일치합니다. 소유권·중복 생성·페이지 순회 경쟁을 포함합니다.

### P6. 촬영·AI 작업·워커

- [ ] 방·가구 ZIP 원문 스트리밍 수신, ZIP 엔트리·실제 입력 검증을 구현합니다.
- [ ] 최대 압축 2GiB·해제 5GiB·7000항목, 방 30~1000장·가구 12~300장 조건을 유지합니다.
- [ ] ARKit pose, RoomPlan, 단위·행렬 배치·시간·바닥·roomPlanToWorld 정규화를 구현합니다.
- [ ] Capture·Job·outbox의 DB 변경을 하나의 업무 트랜잭션으로 저장합니다.
- [ ] claim·heartbeat·artifacts·complete·fail, lease 만료·재시도·취소를 구현합니다.
- [ ] `JobMaintenanceService`에서 만료·outbox·정리 작업을 분리합니다.
- [ ] 기존 Python `WorkerClient`로 실제 Spring HTTP 서버를 호출하는 호환 테스트를 만듭니다.

완료 조건: 동일 ZIP 재시도는 같은 작업을 반환하고, 실패 시 부분 Capture·Job이 남지 않습니다. 다른 worker·lease의 완료 제출은 거절하며 중복 callback은 안전하게 재생합니다. 아직 외부 AI 엔진이 없을 때 작업 완료를 자동 생성하지 않습니다.

### P7. 공간·객체·개인 입주 상태·배치

- [ ] PLY·GLB·geometry·객체 참조·실제 AABB 결과 검증 후 공간·가구 버전을 확정합니다.
- [ ] 객체 분류, 개인 가구 제거 규칙, 작성자 전용 입주 상태를 구현합니다.
- [ ] 배치 전체 교체, 현재 공간·가구 버전, isStale, 참조 중 가구 삭제 제한을 구현합니다.
- [ ] 공통 `MobileSpaceMapper`에 객체 중심·천장 상대 높이 변환을 적용합니다.

완료 조건: 원본 공간·객체·가구 버전이 보존되고 타인 입주 상태는 조회할 수 없습니다. 바닥 높이 -1.42m/1m, 회전 객체 중심, 여러 방 높이, 배치 충돌·버전 경쟁 회귀 테스트가 통과합니다.

### P8. 전체 데이터 이관·실기기 검증·전환

- [ ] 복사한 SQLite에서 PostgreSQL로 dry-run 이관을 수행합니다.
- [ ] 97개 API와 파일·상태·운영 명령의 완료 표를 확인합니다.
- [ ] 앱 데모 모드를 끄고 가입·로그인·검색·등록·채팅·촬영·3D·배치의 실기기 흐름을 확인합니다.
- [ ] 실제 OAuth·실제 AI 엔진 테스트의 수행 여부와 결과를 따로 기록합니다.
- [ ] 최종 백업, 쓰기 중지, 작업 drain, 최종 이관, 내부 smoke test, 트래픽 전환을 순서대로 실행합니다.

완료 조건: 아래 데이터 이관·전환 기준과 최종 완료 기준이 모두 충족됩니다. 코드 이전 완료와 실제 외부 시스템 연동 검증 완료를 구분해 보고합니다.

작업 의존 순서: P0 → P1 → P2 → P3 → P4 → P5 → P6 → P7 → P8. 단계별로 검토 가능한 커밋 단위로 나누며, 통과하지 못한 선행 계약을 다음 단계에서 임시로 우회하지 않습니다.

## 7. 트랜잭션·동시성 설계

- 업무를 조율하는 Service에 트랜잭션 경계를 둡니다. 예를 들어 촬영 접수의 Capture·Job·outbox 생성은 함께 commit됩니다. [Spring Data 트랜잭션 문서](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html)
- 기존 SQLite의 `BEGIN IMMEDIATE`에 의한 직렬화를 JPA 기본 동작이 대신한다고 가정하지 않습니다. 멱등 키 unique 제약, refresh·claim 행 잠금/조건부 갱신, 상태 전이 조건을 명시합니다. [잠금 문서](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- JPA 저장 충돌용 버전과 공개 `spaceVersion/objectsVersion/modelVersion`의 의미를 구분합니다. JPA 기본 버전 값이 기존 ETag의 시작값·증가 규칙을 바꾸지 않도록 설계합니다.
- 긴 파일 수신·외부 OAuth HTTP 통신 동안 DB 트랜잭션을 계속 열어두지 않습니다. 권한·상태를 확인하고 임시 저장·검증 후, 최종 commit 직전에 소유권·작업 활성 상태를 재검사합니다.
- DB 트랜잭션은 파일 시스템까지 rollback하지 못합니다. 검증된 파일은 불변 저장하고 DB와 연결하며, 실패·중단 시 고아 파일을 정리하는 회수 절차를 둡니다. commit된 참조 파일을 정리 작업이 삭제하지 않도록 테스트합니다.
- 멱등 재생에서 서명 URL은 다시 발급하고, 같은 키에 다른 본문은 기존 충돌 응답을 유지합니다.

## 8. DB 모델과 데이터 이관

| 영역 | 예정 테이블 |
|---|---|
| 인증 | users, credentials, oauth_identities, sessions, refresh_tokens |
| 매물 | listings, listing_photos, scraps, reports |
| 인증·채팅 | agent_verifications, agent_review_history, chats, chat_messages, chat_participants |
| 파일·촬영 | assets, upload_sessions, upload_session_files, captures, capture_files |
| 공간·가구 | spaces, space_objects, object_classification_versions, furniture, furniture_models |
| 개인 결과·배치 | room_states, placement_sets, placements |
| 실행·공통 | ai_jobs, job_attempts, callback_receipts, outbox, job_queue, idempotency_records, cursor_snapshots, rate_limit_buckets |

사용자·소유관계·검색·참조·버전·상태 등 조회와 제약에 쓰는 값은 명시적인 컬럼·키·인덱스로 관리합니다. 불변 geometry/manifest처럼 중첩 구조가 큰 문서는 명시적인 저장 모델과 JSONB를 사용할 수 있습니다. 모든 리소스를 다시 하나의 범용 JSON 테이블에 넣는 구조는 만들지 않습니다.

이관 도구는 다음 작업을 수행합니다.

1. 읽기 전용 SQLite 복사본에서 종류별 데이터를 추출하고 FK 참조·삭제 표식·과거 버전을 포함한 중간 manifest를 만듭니다.
2. 기존 UUID, 원 금액, 소유자, 상태, 시각, space/objects/model 버전, 현재 버전 포인터를 보존합니다.
3. 객체 파일은 SHA-256·크기·참조 수를 비교하고 원본 바이트를 보존합니다. photo derivative, ZIP archive, 증빙도 포함합니다.
4. 기존 Argon2 해시, OAuth 제공자 회원번호, session·refresh의 사용/폐기 상태를 보존하고 실제 검증 fixture를 통과시킵니다.
5. JWT·저장소 서명·커서에 쓰는 기존 키는 안전하게 전달합니다. 만료 전 URL·토큰·cursor가 유지되는지 검증하며 키를 소스·보고서에 기록하지 않습니다.
6. 멱등 기록의 TTL·actor·method·path·본문 hash를 보존합니다. 기존 암호화 응답은 Python export 단계에서 로컬로 해석해 대상 형식으로 안전하게 재암호화하며, 평문 중간 파일을 남기지 않도록 구현합니다.
7. 영속 cursor snapshot, chat read pointer, 운영 심사 이력, 삭제 리소스, 미참조 파일까지 수량·참조·보관 정책 검증 보고서를 만듭니다.
8. target의 별도 import 영역에서 반복 실행하고, 데이터 검증 후 최종 반영합니다. 원본 DB에는 쓰지 않습니다.

`V1__auth.sql`, `V2__assets_furniture.sql`, `V3__listings_chat_verification.sql`, `V4__capture_jobs.sql`, `V5__space_roomstate_placements.sql`, `V6__constraints_indexes.sql` 순으로 Flyway 파일을 기능 단계와 함께 작성합니다. 실제 파일 번호는 P2에서 고정하고 적용한 마이그레이션은 수정하지 않습니다.

## 9. 테스트 구성과 합격 기준

| 종류 | 핵심 항목 | 실행 대상 |
|---|---|---|
| 계약 | 97개 API, 오류·헤더·응답 schema·숫자·null·순서 | 실제 Python/Java HTTP 서버 |
| 서비스 | 게시·가격·권한·제거·상태 전이 | Java Service·Policy |
| 저장소 | FK, unique, 검색, 삭제, 잠금·동시성 | 실제 PostgreSQL |
| 파일 | ZIP 공격·용량·깊이·confidence·manifest·PLY·GLB·AABB | 정상/비정상 바이너리 fixture |
| 재시도 | 멱등·callback 재생, refresh 재사용, lease 상실, 취소·완료 경쟁 | HTTP + DB + worker client |
| 복구 | 재시작, outbox 중단, 임시 파일 정리, 부분 실패 | 실제 프로세스·저장소 |
| 앱 | 로그인→등록→사진→검색→채팅→촬영→3D→입주 상태→배치 | 데모 모드 OFF의 iPhone |
| 이관 | 수량·ID·참조·금액·해시·버전·세션 일치 | SQLite 복사본→PostgreSQL |

Python 내부 모듈을 직접 호출하는 테스트는 Java 단위·통합 테스트로 다시 구현합니다. `FastAPI TestClient`를 그대로 Java에 연결할 수 있다고 가정하지 않습니다. 공통 HTTP 시나리오는 서버 주소를 외부화하고, 랜덤 ID·시각·서명 URL은 값 자체보다 관계·형식·유효성으로 비교합니다.

최종 합격 기준은 다음과 같습니다.

- [ ] 앱 37개와 v1 60개 API의 누락이 없습니다. 각 메서드·경로에 담당 코드와 성공·오류 테스트가 연결됩니다.
- [ ] 현재 실패·거절 동작, 권한, 멱등·버전·lease 조건이 재현됩니다.
- [ ] 새로 수정한 가구 종류·객체 중심·천장 높이 회귀 테스트가 통과합니다.
- [ ] 앱·v1 Controller가 공유 업무 로직을 사용하고 거대 `Service`·`Utils`로 다시 합쳐지지 않습니다.
- [ ] 이관 검증 보고서에서 미해결 데이터 손실·참조 오류가 없습니다.
- [ ] 앱 실기기 결과와 실제 OAuth·AI 엔진 검증 여부가 명확히 기록됩니다.
- [ ] 실행·운영 명령·백업·전환·복구 문서가 새 서버와 일치합니다.

## 10. 전환과 복구 절차

1. 개발·검증 중 Python과 Spring은 서로 다른 DB·파일 저장소 복사본을 사용합니다. 실제 사용자 요청을 두 서버에 동시에 쓰지 않습니다.
2. 전환 전 신규 촬영·작업 접수를 멈추고 기존 queued/running/retry 작업이 끝나도록 처리합니다. 활성 worker lease·미처리 outbox가 남아 있으면 전환을 진행하지 않습니다. 작업 강제 취소는 자동으로 수행하지 않습니다.
3. 모든 쓰기와 토큰 refresh·기존 스케줄러를 중지하고, 일관된 최종 SQLite·objects·키·설정 백업을 생성합니다.
4. 최종 import·검증을 수행하고, 외부 쓰기 트래픽을 차단한 상태에서 새 서버의 smoke test를 실행합니다. smoke test 데이터는 식별·정리합니다.
5. 원래의 외부 주소를 Spring 서버로 전환하고, worker와 앱이 같은 주소의 계약을 사용하는지 확인합니다.
6. 새 서버에서 사용자 쓰기가 시작되기 전 실패는 기존 snapshot으로 복구합니다. 새 서버에서 쓰기가 시작된 뒤에는 변경분 역이관·재적용을 검증한 절차가 있어야 구 서버로 돌아갈 수 있습니다. 예전 SQLite만 복구해 새 데이터를 잃는 방식을 복구 성공으로 처리하지 않습니다.

## 11. 첫 구현 묶음

가장 먼저 P0~P2를 구현합니다. 결과물은 다음 다섯 묶음으로 검토합니다.

1. `docs/migration/api-contracts.md`, 97개 API 담당표, 기준 테스트 실행 결과.
2. `contract-tests/`의 서버 독립 HTTP 시나리오와 fixture.
3. 인증 해시·JWT·JCS·PNG16·ZIP·GLB·PLY 호환성 검증 테스트.
4. `spring/` 실행 프로젝트, PostgreSQL·Flyway·보안·예외 처리 기반.
5. 구조 규칙·CI·개발 실행 문서 및 첫 DB/동시성 테스트.

이 기반이 통과하면 P3의 인증·사용자부터 기능을 순서대로 이전합니다. 일정은 P1에서 파일·보안 호환성의 실제 구현 난이도를 확인한 뒤 산정하며, 현재 단계에서는 전체 전환의 완료 날짜를 고정하지 않습니다.
