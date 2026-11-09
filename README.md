# 둘러방 백엔드 v2

Java 21 · Spring Boot 4.1.1 · Spring MVC · Spring Security · PostgreSQL 17.11 · Flyway · Gradle 9.6.1 기반입니다. 기존 `dulleobang-backend`를 보존하고 별도 프로젝트에 구현했습니다.

현재 P0~P7의 업무 코드를 구현했습니다. v1 60개, 앱 37개 API와 상태·스토리지 4개 경로를 실제 Controller에 연결하고, DB·실제 HTTP·바이너리 파일로 검증합니다. 경로 등록 검사는 각 업무 흐름의 성공 테스트와 별도로 기록합니다. 전체 운영 검증을 완료했다는 뜻은 아닙니다.

2026-11-08 GCP의 `dulleobang-backend` VM에 JAR와 systemd 서비스를 배포했습니다. `http://34.47.87.3:8080/`와 `/health/ready`는 200입니다. AI PR #2(`6bce455`)의 실제 방 결과를 기존 앱의 `pointsUri`로 연결했고, 개인 가구 제거 작업도 자동 워커로 완료해 입주 상태 READY·객체 6→5를 확인했습니다. 온라인 SAM3D 실제 결과의 내 가구 가져오기·GLB 다운로드·SHA-256 일치도 확인했습니다. 운영 DB는 기존 DB를 보존한 별도 PostgreSQL `dulleobang_v2`입니다. 전체 Java 테스트 114개가 통과했습니다. 자세한 내용은 `docs/deployment-20261108.md`, 백엔드의 AI 연결 코드는 `ai_worker/README.md`를 참고해 주세요.

## 구조

기능별 `controller / service / repository / domain / mapper / validation`에 클래스를 각각 분리했습니다. Controller는 Repository나 JDBC에 직접 접근하지 않고, Service는 HTTP 요청 객체나 JDBC에 의존하지 않습니다. 사용자 엔티티는 JPA로 검증하며 나머지 Repository는 typed PostgreSQL 컬럼과 JDBC를 사용합니다. 멱등·refresh·lease·버전 갱신은 트랜잭션과 행 잠금으로 처리합니다.

| 영역 | 구현 |
|---|---|
| auth / user | Argon2, access·refresh 회전, 재사용 탐지, OAuth 검증 경계, 사용자 ETag |
| listing / verification / scrap / report / chat | 매물·사진·검색·게시, 중개사 심사, 찜·신고, 채팅·읽음 |
| upload / furniture | 서명 GET·PUT, checksum·이미지 검증, 사진 정제, 가구 버전 |
| capture / job | ZIP·ARKit·RoomPlan 정규화, 촬영 접수, outbox, worker lease·재시도·취소 |
| space / roomstate / placement | 실제 PLY·GLB·geometry 검증, 객체 분류, 개인 제거 결과, ETag 배치 |
| infrastructure | 로컬 객체 저장소, OAuth HTTP, 스케줄러, 파일 보관·정리 |
| admin | 로컬 worker 토큰, 작업 큐, 유지보수, 심사·신고 검토 |

ZIP I/O, 좌표 정렬, 프레임 생성, manifest 생성과 결과 게시를 별도 파일로 나눴습니다. 방·가구·가구 제거 결과도 각각 별도 `JobResultHandler`가 담당합니다. 구조 규칙은 `ArchitectureTest`에서 확인합니다.

2026-10-27 구조 재검토에서 공통 영역도 `controller / service / repository`로 정리했습니다. 매물·채팅의 조회와 응답 조립은 Service, Mapper는 전달받은 값의 변환만 담당합니다. 업로드는 세션 관리·조회·서명 URL·파일 검증·사진 생성으로 분리했고, 파일 I/O는 `infrastructure`에 모았습니다. JSON과 SHA-256 유틸, 이미지 검증과 변환도 구분했습니다.

직접 선언한 운영 메서드는 생성자를 제외하고 이름 20자·본문을 포함한 선언 60줄 이내로 관리합니다. Spring의 콜백 이름은 유지합니다. `MethodStructureTest`가 실제 Java 구문 트리를 읽어 검사하며, REST MVC의 View는 JSON 응답 DTO·Mapper와 기존 프론트엔드가 담당합니다. 재검토 결과와 파일별 역할은 `docs/structure-review.md`에 정리했습니다.

2차 재검토에서는 ZIP·멀티파트의 내부 업로드를 `UploadSource / UploadBatch` 기반 공통 처리로 통합했습니다. 공개 URL·HTTP 응답을 다시 해석하지 않으며, 저장·검증 실패 시 배치 DB 메타데이터를 롤백합니다. 실제로 재현한 HTTP 500 오분류와 구조 검사 범위도 보완했습니다. 자세한 내용은 `docs/rereview.md`에 있습니다.

2026-11-09에는 쿼리 입력을 선언 로딩·타입 파싱·스키마 검증으로, GLB 검증을 장면 순회·변환 행렬·공간 경계 검사로, ZIP 검증을 디렉터리·종료 레코드·엔트리 검사로 분리했습니다. AI 연결 코드도 좌표·방 기하·객체 매핑·메시·점군·파일 저장·게시·프로세스 실행·토큰 발급으로 구분했습니다. Java 114개와 Python 워커 8개 테스트, 실제 PR #2 결과의 변경 전후 비교를 통과했습니다. 변경 범위와 검증 조건은 `docs/refactor-20261109.md`를 참고해 주세요.

## 로컬 실행

JDK 21을 설치한 뒤 이 폴더에서 실행합니다. 기존 Python 서버의 DB·파일·8080 포트를 사용하지 않습니다.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run.ps1 -NativePostgres
```

이 명령은 8081 포트와 **종료 시 사라지는 개발 PostgreSQL**을 사용합니다. 데이터를 유지하려면 Docker 또는 별도 PostgreSQL을 사용합니다.

```powershell
docker compose up -d postgres
powershell -ExecutionPolicy Bypass -File .\scripts\run.ps1
```

`/health/live`, `/health/ready`로 상태를 확인합니다. `.runtime/dev-secrets.json`에는 개발용 난수가 생성되며 소스·배포 패키지에서 제외됩니다. 첫 의존성 다운로드에는 인터넷이 필요합니다.

## 서버가 준비되면 설정할 값

| 환경변수 | 의미 |
|---|---|
| DB_URL | `jdbc:postgresql://호스트:5432/데이터베이스` |
| DB_USER / DB_PASSWORD | PostgreSQL 접속 계정 |
| JWT_SECRET / WORKER_JWT_SECRET / STORAGE_SECRET | 서로 다른 32자 이상 난수. 재시작 때 유지 |
| BASE_URL | 앱·워커에서 접근 가능한 HTTPS 서버 주소. 서명 파일 URL에 사용 |
| V2_DATA_DIR | 영속 객체·임시 파일 저장 디렉터리 |
| PORT / CORS_ORIGINS | 서버 포트, 필요한 브라우저 Origin 목록 |
| KAKAO_APP_ID / GOOGLE_CLIENT_IDS | 실제 소셜 로그인 제공자 설정 |

`.env`는 자동으로 로드하지 않습니다. 환경변수를 설정하거나 Spring 설정으로 전달합니다. 배포된 VM은 `/etc/dulleobang/backend.env`를 systemd로 읽습니다. 로컬 실행이 운영 서버를 자동 변경하지는 않습니다. 서버 준비 절차는 `docs/server-readiness.md`, 실제 배포 기록은 `docs/deployment-20261108.md`에 있습니다.

## 검증

Java 테스트는 Docker 없이 테스트 전용 네이티브 PostgreSQL을 실행합니다. `TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD`를 지정하면 해당 테스트 DB를 사용하고 초기화하므로 운영 DB를 지정하지 마십시오.

```powershell
. .\scripts\java-env.ps1
.\gradlew.bat checkFormat test bootJar --no-daemon
```

Python 계약 검사에는 `legacy-baseline/requirements.lock`의 의존성이 필요합니다. `V2_PYTHON`으로 사용할 Python을 지정할 수 있습니다.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify.ps1 -Python python
```

결과는 `verification/final-review.json`, `verification/junit`, `verification/spring-route-coverage.json`에 기록합니다. 기존 Python 전체 기준 테스트 결과는 복사본 기준의 과거 기록이며 Java 테스트 수에 합산하지 않습니다. 원격 CI와 Docker 이미지 실행은 여기서 수행하지 않았습니다.

Windows 한글 경로의 Java worker 인코딩·소켓 경로 문제를 피하기 위해 빌드 산출물은 ASCII 임시 경로에 생길 수 있습니다. `V2_JAVA_TMP / V2_BUILD_DIR`로 경로를 지정할 수 있습니다.

## 운영 CLI

DB·키 환경변수를 설정한 뒤 생성된 JAR에 옵션을 전달합니다. HTTP 서버를 열지 않고 종료합니다.

```powershell
java -jar <v2.jar> --dulleobang.admin.command=queue
java -jar <v2.jar> --dulleobang.admin.command=maintenance
java -jar <v2.jar> --dulleobang.admin.command=pending-reviews
java -jar <v2.jar> --dulleobang.admin.command=worker-token --dulleobang.admin.worker-id=worker-1 --dulleobang.admin.seconds=3600
java -jar <v2.jar> --dulleobang.admin.command=review-agent --dulleobang.admin.id=<UUID> --dulleobang.admin.decision=approve
```

토큰 발급 명령의 출력은 인증정보입니다. 심사 거절은 `--dulleobang.admin.reason`이 필요하고 신고 검토는 `review-report`와 `resolve / dismiss`를 사용합니다. 공개 가입 API에서 관리자·중개사 권한을 추가하지 않습니다.

## 파일 보관·작업 정책

서명 URL은 15분 동안 유효하며 캐시 응답 재생에서도 다시 발급합니다. worker lease는 120초, 한 시도는 최대 30분, 재시도는 최대 3회입니다. 재시도 여부는 서버가 오류 종류로 결정합니다. 소유자별 활성 작업은 최대 3개입니다.

만료 업로드와 실패한 미검증 결과에는 1일 유예를 두며, 종료한 촬영의 입력은 30일 보관합니다. 객체 해시를 다른 자원이 참조하면 보존합니다. 파일 게시와 정리는 같은 해시 잠금을 사용합니다. 실제 AI 모델을 내장하거나 작업 완료 결과를 자동 생성하지 않습니다.

## 온라인 SAM 3D 가구 가져오기

웹에서 생성 완료한 가구를 기존 내 가구로 저장하는 확장 기능은 [연동 안내](docs/sam3d-integration.md)를 참고하세요. 기존 97개 업무 API·4개 보조 경로에 확장 POST 경로 2개와 Flyway V7을 추가했습니다. 실제 GPU 서버 추론은 아직 미검증입니다.
