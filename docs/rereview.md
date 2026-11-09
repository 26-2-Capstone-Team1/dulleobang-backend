# 2차 재검토

검토 기준일: 2026-10-27. 앞선 5가지 구조 요구사항을 기준으로 실제 호출 경로·실패 처리·검사 범위를 다시 살펴보았습니다. 기존 Python 백엔드, API 주소·요청 스키마, DB 마이그레이션은 변경하지 않습니다.

| 발견한 부족한 부분 | 보완 |
|---|---|
| 내부 촬영·멀티파트 처리가 `StoredResponse` JSON을 파싱하고 공개 URL의 쿼리에서 토큰을 잘라 사용 | `UploadSource`·`UploadBatch` DTO와 `UploadBatchService`·`UploadIngestService`로 통합. 내부에서는 파일 ID와 토큰을 직접 전달하고, 공개 응답·URL은 외부 API용으로만 생성 |
| 보조 쓰기 서비스가 호출자의 트랜잭션에 의존 | 배치 생성·전체 파일 저장/검증·사진 생성에 명시적인 트랜잭션 경계 적용. 파일 읽기나 이미지 검증 실패 시 배치의 DB 메타데이터를 롤백 |
| `/health/live`에서 지원하지 않는 `Accept` 요청을 500으로 반환 | 재현 테스트로 확인한 뒤 406 `NOT_ACCEPTABLE`로 수정. Spring의 미지원 메서드 예외는 405 `METHOD_NOT_ALLOWED`와 `Allow` 헤더로 변환 |
| Controller 검사가 클래스명에만 의존하고, 영속성 검사에서 JPA 직접 접근을 놓칠 여지 | Controller 애너테이션도 검사하고, Repository 외부의 JPA Repository·EntityManager 의존을 금지. 내부 업로드의 URL/공개 응답 의존도 검사 |

## 코드 역할

- `upload/dto/UploadSource`, `UploadBatch`: 입력 파일과 생성/검증 결과를 타입으로 전달합니다. 결과는 검증 후 DB 상태를 다시 조회하여 반환합니다.
- `upload/mapper/UploadSourceMapper`: 촬영·멀티파트 입력을 공통 파일 정보로 변환합니다. `CaptureInputFile` 도메인에서 요청 JSON 필드를 조립하던 기능은 Mapper로 옮겼습니다.
- `upload/service/UploadBatchService`: 소유권·업로드 계약·파일 분류 검사와 세션/파일 메타데이터 생성을 담당합니다.
- `upload/service/UploadIngestService`: 저장→검증을 하나의 트랜잭션에서 수행합니다. 파일 ID는 클라이언트 파일 UUID로 연결하므로 응답 배열이나 URL 형식에 의존하지 않습니다.
- `MultipartStoreService`, `CaptureIngestionService`: 입력 형식만 변환한 뒤 공통 처리에 위임합니다. 공개 HTTP 응답을 파싱하지 않습니다.
- `GlobalExceptionHandler`: 지원하지 않는 응답 형식과 메서드를 명시적인 클라이언트 오류로 처리합니다.

물리 객체 저장소는 DB 트랜잭션과 별개입니다. 실패 후 참조되지 않는 객체는 기존 보관·정리 정책으로 회수하며, 같은 해시를 다른 자원이 사용하면 보존합니다. DB 메타데이터 롤백을 파일 시스템 전체의 즉시 롤백으로 표현하지 않습니다.

## 추가한 검증

`UploadIngestIntegrationTest`는 호출자 트랜잭션 없이 실제 PostgreSQL에서 검증합니다.

1. 내부 업로드가 검증 완료 상태를 반환하고 외부 세션 API에서도 조회되는지 확인합니다.
2. 두 번째 파일 읽기 실패 후 세션·파일 메타데이터가 남지 않고 정상 재시도가 가능한지 확인합니다.
3. 실제 바이트가 손상된 이미지의 검증 실패도 메타데이터 전체를 롤백하는지 확인합니다.
4. 다른 사용자 소유 대상으로 요청하면 파일을 읽기 전에 거절하는지 확인합니다.

`FoundationIntegrationTest.handlesHttpNegotiation`은 실제 HTTP의 406·405·문제 응답·Allow 헤더를 확인합니다. 수정 전 재현 로그는 `verification/rereview-reproduction.log`이며 예상한 실패를 기록한 자료입니다. 수정 후 최종 통과 여부는 `verification/final-java-test.log`, `final-review.json`, `rereview.json`을 기준으로 확인합니다.

## 개선 후보와 검증 한계

일부 기존 매물·공간 모델과 응답은 `JsonNode`·`Map` 기반입니다. 현재 계약 스키마로 실행 시 검증하지만, 일반적인 응답부터 구체적인 DTO로 확대하면 컴파일 시 필드·타입 오류를 더 잡을 수 있습니다. 또한 목록의 전체 후보 조회·메모리 필터링은 데이터가 많아질 때 SQL 필터링과 실행 계획을 점검할 후보입니다. 이번 재검토에서 재현한 장애와 구분하며, 구조 검사 통과만으로 타입 안정성이나 대규모 성능까지 증명하지는 않습니다.

실제 서버·iPhone·실제 OAuth/AI 연결 및 운영 부하 검증은 이번 로컬 재검토의 검증 범위에 포함하지 않습니다.
