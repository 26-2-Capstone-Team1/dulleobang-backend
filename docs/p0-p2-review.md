# P0~P2 요구사항 재검토

기준일: 2026-10-16. 기존 폴더를 보존하고 별도 dulleobang-backend-v2에 구현합니다. 검토 범위는 구현 계획의 P0~P2이며 97개 업무 API 전체 이전을 뜻하지 않습니다.

| 요구사항 | 구현·증거 | 검토 기준 |
|---|---|---|
| 기존 서버 보존 | verification/original-before.json, original-after.json | 소스·문서 80개 SHA-256 일치. 기존 data·키는 복사·실행하지 않음 |
| 별도 v2 프로젝트 | build.gradle, settings.gradle, Gradle Wrapper, src | 원본과 별도 폴더·포트·개발 DB |
| P0 API 목록 | contract/api-inventory.json, docs/api-ownership.md | v1 60 + 앱 37 + health/storage 4. 복사본 라우팅과 집합 비교 |
| P0 앱 계약 | contract/mobile-openapi.json | 37개 메서드·경로, 입력 변환·응답 모델, 모든 로컬 schema 참조 검사 |
| P0 안전한 fixture | src/test/resources/fixtures, sha256.json | 공개 합성 계정·키·토큰·파일. 실제 DB·개인정보 미사용 |
| P0 기준 테스트 분류 | verification/inventory-check.json | HTTP 시나리오와 Python 내부 테스트 목록 |
| P0 실제 HTTP 비교 기반 | contract-tests/run.py, legacy-http-contracts.json | FastAPI import 없는 TCP 시나리오 16개. 서버 주소 외부화 |
| P0 전체 기준선 | verification/legacy-suite-summary.json, legacy-baseline/verification.json | 570개 통과, 1개 의도된 skip, 20,049개 하위 검증 통과 |
| P1 비밀번호 | CompatibilityTest.pythonArgonHash | Python Argon2 해시와 Java PasswordEncoder 교차 검증 |
| P1 JWT | CompatibilityTest.pythonJwtKindsAndKeySeparation | user/worker/storage/cursor 서명, audience·키 분리·만료 |
| P1 JSON·JCS | CompatibilityTest의 JSON·canonical 테스트 | 중복 키·NaN·Infinity·빈 입력·UTF-8·정수 안전 범위·Python hash 일치 |
| P1 이미지 | CompatibilityTest의 depth·confidence·metadata 테스트 | PNG16 실제 0/1200/5000/65535, confidence 0/1/2, EXIF 제거 |
| P1 ZIP·PLY·GLB | compatibility 패키지와 실제 바이너리 fixture 테스트 | 실제 바이트·길이·경계·잘못된 입력 거절. 전체 업로드 정책은 P6/P7 |
| P1 도구 호환성 고정 | Gradle 9.6.1, Boot 4.1.1, Java 21, lockfile | 실제 다운로드·컴파일·테스트. Windows 한글 경로 대응 포함 |
| P2 역할별 파일 | common 기능별 패키지, user/domain·repository | 클래스별 파일. 생성자 주입·Controller/DB·Domain/HTTP 의존 검사 |
| P2 PostgreSQL·Flyway·JPA | V1__foundation.sql, FoundationIntegrationTest.realPostgresFlywayAndJpa | PostgreSQL 17.11 실제 서버와 FK·마이그레이션 검증 |
| P2 보안·오류·CORS·요청 ID | HTTP 통합 테스트와 FailureHandlingTest | 세션 폐기·키 분리·401/503 오류·명시 Origin·requestId |
| P2 멱등·트랜잭션 | concurrentIdempotency, rollback 테스트 | 동시 6개 요청에서 부수효과 한 번, 다른 본문 409, 업무 변경과 기록 함께 rollback |
| P2 응답 보호 | cipherAuthenticatesBindingAndPayload | DB 평문 토큰 미저장, 다른 scope·변조 거절 |
| P2 버전·커서 | ETag 테스트, signedCursor 테스트 | 428/412, 최초 저장, 영속화·사용자·경로·필터·만료 결합 |
| P2 요청 제한 | rateBucketIsAtomic, httpRateLimitHasProblemAndRetryHeaders | 동시 증가·허용 개수, 429·Retry-After. 인증 전 필터 순서 검증 |
| P2 실행·CI | scripts/run.ps1, verify.ps1, Dockerfile, CI | 로컬 실행·자동 검증 스크립트 제공. Docker/원격 CI 실행 여부는 별도 표시 |

## 재검토 중 발견해 수정한 항목

1. Jackson의 빈 입력이 MissingNode로 처리되는 경우를 거절하도록 수정했습니다.
2. 요청 제한과 Bearer 필터가 같은 순서에 배치되던 문제를 고쳐 인증 전 제한을 검증했습니다.
3. Python RFC 8785의 정수 안전 범위와 맞추고, 손상된 UTF-8을 대체 문자로 통과시키지 않도록 했습니다.
4. GLB의 accessor min/max 누락·비정상 선언을 거절하고 accessor별 실제 범위를 따로 계산했습니다.
5. 세션 저장소 실패도 공통 503 응답으로 처리하도록 했습니다.
6. Windows Java 21의 임시 소켓 및 한글 worker classpath 문제에 대응하고 재현 가능한 실행 스크립트에 반영했습니다.

## 완료와 미구현의 구분

P0: 계약·baseline·fixture·비교 테스트 기반. P1: 언어·보안·파일 형식의 검증 구현. P2: 실제 실행·DB·보안·멱등·커서·구조 기반입니다.

P3~P8의 가입·로그인 발급·매물·사진·업로드·채팅·3D·AI 작업·전체 데이터 이관은 아직 구현하지 않았습니다. HTTP 97개를 등록·보호·분류한 것과 97개 업무 기능을 완성한 것을 구분합니다. fallback Controller는 501을 반환합니다.

외부 OAuth 제공자 실제 로그인, 실제 AI 엔진, iPhone 실기기, Docker 이미지 실행과 GitHub 원격 CI는 이번 환경에서 검증하지 않았습니다. 네이티브 PostgreSQL과 로컬 Spring HTTP 서버·빌드·테스트 결과를 검증 증거로 사용합니다.

검토 결과의 최종 자동 집계는 verification/final-review.json과 JUnit 보고서에 기록합니다.
