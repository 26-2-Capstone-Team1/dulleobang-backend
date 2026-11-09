# 5가지 요구사항 구조 재검토

검토 기준일: 2026-10-27. 수정 범위는 `dulleobang-backend-v2`입니다. 기존 Python 백엔드와 프론트엔드의 API 계약은 보존합니다. 검증 결과는 `verification/final-review.json`, `verification/structure-review.json`, `verification/junit`에서 확인합니다.

| 요청 | 적용 구조·재검토 내용 | 자동 검사 |
|---|---|---|
| Spring Boot | Java 21, `@SpringBootApplication`, Spring MVC·Security·DI·트랜잭션, PostgreSQL·Flyway | 실제 HTTP·PostgreSQL 통합 테스트와 `bootJar` |
| MVC | Controller는 HTTP 바인딩·상태 코드, Service는 업무 처리, Repository는 DB, Domain은 모델, DTO·Mapper는 JSON View | `ArchitectureTest.checksControllers`, `checksServices`, `checksDomain` |
| 기능·역할별 서비스·파일 분리 | 매물·채팅 조회/표현, 업로드 세션·조회·URL·검증·사진 생성, 작업 결과 파일 정책·생성을 분리 | `checksServices`, `checksMappers`, 기존 업무 회귀 테스트 |
| 유틸·DB·서비스 구분 | 공통 Repository·Service도 각 하위 패키지로 이동. JSON/해시, 이미지 검증/가공, ZIP I/O/정규화, SQL/행 변환/대상 상태 갱신 분리 | `checksRepositories`, `checksUtilities`, `checksMappers` |
| 짧은 함수명 | 클래스의 문맥과 중복되는 표현을 줄이고 테스트 함수명도 간결하게 수정. 고정된 Spring 콜백 이름 유지 | `MethodStructureTest.keepsNamesConcise`, `keepsMethodsFocused` |

## 요청과 응답 흐름

`Controller → Service → Repository → PostgreSQL`로 처리합니다. Service가 조회한 값을 Mapper·DTO로 변환하면 Controller가 JSON으로 응답합니다. 기존 프론트엔드가 화면을 렌더링하는 REST MVC 구조입니다. Mapper는 DB·Service·스토리지·HTTP에 의존하지 않습니다.

## 역할을 나눈 파일

- `listing/service/ListingViewService`: 매물·사진·공개 사용자 정보 조회와 응답 조립. `listing/mapper/ListingViewMapper`, `MobileListingMapper`, `MobileSearchMapper`: 정식/앱 응답과 검색 입력 변환.
- `chat/service/ChatViewService`, `ChatListingService`: 채팅 응답에 필요한 조회와 매물의 보존 스냅샷 처리. `chat/mapper/ChatViewMapper`, `chat/dto/ChatView`: 값 변환과 응답 모델.
- `upload/service/UploadSessionService`, `UploadQueryService`, `UploadGrantService`, `UploadVerifyService`, `PhotoAssetService`: 세션 관리·조회/접근·서명 URL·파일 검사·사진 생성.
- `upload/validation/ImageValidator`, `upload/image/ImageSanitizer`, `ImageTransforms`, `ExifOrientation`: 이미지 형식 검사·인코딩·회전/축소·EXIF 해석.
- `capture/mapper/RoomPlanMapper`, `RoomStructureMapper`, `RawRoomPlanMapper`: RoomPlan 입력 형식별 변환. `capture/validation`: 좌표·카메라·프레임·JSON 규칙. `infrastructure/capture`: ZIP 읽기·디렉터리 검사·파일 추출.
- `space/validation/BundleGeometryValidator`, `RoomGeometryValidator`, `OpeningValidator`, `BoundsValidator`, `GlbTextureValidator`: 자원 참조·방/다각형·문/창·경계·GLB 텍스처 검사. DB 조회를 포함하는 검증 조립은 `space/service/ArtifactCheckService`가 담당합니다.
- `job/repository/JobRepository`, `JobTargetRepository`, `JobRowMapper`: 작업 SQL·대상 상태/잠금·DB 행 변환. `job/validation/ArtifactPolicy`, `job/mapper/ArtifactMapper`: 업로드 정책과 파일 모델 변환.
- `common/*/repository`, `common/*/service`: 공통 관심사별 DB·서비스. `common/json/StrictJson`, `common/crypto/Hashes`, `common/contract/HttpResponses`: JSON·해시·HTTP 응답 유틸.

경로는 `src/main/java/com/dulleobang` 기준입니다. 테스트에서만 사용하던 호환성 Probe 4개는 `src/test/java/com/dulleobang/compatibility`로 옮겨 실행 JAR에서 제외했습니다. 하나의 역할에 속한 작은 함수들은 같은 파일에 유지하고, 조회·검증·저장·변환 등 책임이 달라지는 곳을 기준으로 나눴습니다.

## 이름과 함수 크기

운영 코드의 직접 선언 메서드 기준으로 검사합니다. 생성자와 컴파일러가 만드는 메서드는 집계에서 제외합니다. 이름을 줄이기 위해 의미 없는 한 글자 이름으로 바꾸지 않으며, `get`, `list`, `create`, `renew`, `validate`, `safeIntegers`, `publicAsset`처럼 클래스의 역할 안에서 의미가 드러나도록 정리했습니다.

재검토 전 최대 이름 길이는 22자, 최대 메서드 선언 길이는 107줄이었습니다. 변경 후 최대 이름 길이는 16자, 최대 선언 길이는 60줄이며 60줄 초과 함수는 0개입니다. 상세 수치는 `verification/structure-review.json`에 기록합니다. 이름 20자·선언 60줄 상한은 실제 소스 구문 트리 검사로 유지합니다. 길이 검사만으로 책임 분리를 판단하지 않고, 계층 의존성 검사와 코드 검토를 함께 적용했습니다.

테스트 이름을 줄인 뒤 실행 순서가 바뀌면서 발견된 심사 이력 공유 문제는 테스트 DB 초기화에서 해결했습니다. 운영 심사 이력 보관 정책은 변경하지 않았습니다. 구조 검사는 운영 클래스 출력 디렉터리를 명시하여 테스트용 JDBC 사용을 운영 구조 위반으로 오인하지 않게 했습니다.

실제 서버·iPhone·실제 OAuth·외부 AI 연결이나 전체 데이터 이관 완료를 의미하지는 않습니다. 해당 운영 연동 상태는 기존 `server-readiness.md`를 따릅니다.

## 2차 재검토

내부 업로드의 공개 응답·URL 역파싱 의존을 제거하고 타입이 명확한 공통 배치 처리로 통합했습니다. 쓰기 서비스의 트랜잭션 경계, HTTP 406·405 처리, Controller 애너테이션·JPA 경계 검사도 보완했습니다. 상세 발견 사항과 남은 개선 후보는 `rereview.md`, 최신 검증은 `verification/rereview.json`에 기록합니다.

## 2026-11-09 역할 분리

새로 추가한 AI 연결 코드를 포함해 역할 분리를 보완했습니다. `QueryInput.read()`는 51→17줄, `GlbSceneValidator.validate()`는 60→7줄, `ZipDirectoryValidator.validate()`는 55→12줄로 줄였습니다. 각각 파싱·검증·I/O 또는 장면 순회·좌표 변환·공간 경계 등의 책임을 별도 파일에 배치했습니다. AI 변환의 좌표·방 기하·객체 매핑·메시·점군·파일 저장과 워커의 실행·게시·토큰 발급도 분리했습니다. 상세 변경과 기존 API 및 AI 결과 보존 검증은 `refactor-20261109.md`를 참고해 주세요. 함수 줄 수는 선언부를 포함한 구문 트리 기준입니다.
