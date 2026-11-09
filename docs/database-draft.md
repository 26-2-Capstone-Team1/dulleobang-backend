# DB 초안과 적용 경계

이 문서는 P2 당시의 설계 초안을 보존한 기록입니다. 현재 P3~P7의 실제 테이블·제약은 `src/main/resources/db/migration/V1__foundation.sql`부터 `V6`까지의 Flyway 파일을 기준으로 확인합니다. 아래의 후속 단계 표는 현재 스키마 전체를 나타내지 않습니다.

## P2에서 실제 생성하는 테이블

| 테이블 | 핵심 컬럼 | 제약·용도 |
|---|---|---|
| users | user_id UUID, display_name, created_at | PK. JPA 엔티티와 스키마 검증 |
| sessions | id UUID, user_id, expires_at, revoked | 사용자 FK. 토큰의 sid·sub와 함께 검사 |
| idempotency_records | actor, method, path, request_key, request_hash, payload, expires_at | 복합 PK, 만료 인덱스. payload는 인증 암호화된 응답 |
| cursor_snapshots | id, owner_id, binding, resource_ids JSONB, expires_at | 사용자 FK, 만료 인덱스. ID 순서의 영속 스냅샷 |
| rate_limit_buckets | bucket, window_start, request_count | 복합 PK. ON CONFLICT로 원자적 증가 |

Flyway V1__foundation.sql을 실제 PostgreSQL 17.11에 적용하고 JPA validate, FK 거절, unique 경쟁, rollback을 검증합니다. 테스트에서 H2로 대체하지 않습니다.

## 후속 단계의 엔티티·관계 초안

| 단계 | 테이블 | 관계·규칙 |
|---|---|---|
| P3 | credentials, oauth_identities, refresh_tokens | credentials의 email unique, provider+subject unique, refresh digest unique 및 sessions FK |
| P4 | furniture, furniture_models, assets, upload_sessions, upload_session_files | owner FK, furniture+model_version unique, SHA-256 파일 참조와 업로드 소유권 |
| P5 | listings, listing_photos, scraps, reports | 원 정수 금액, 주소·좌표·검색 인덱스, photo order, user+listing 관심 unique |
| P5 | chats, chat_messages, chat_participants | 동일 매물·참여자 조합의 방 중복 방지, message client key unique, 읽음 포인터 |
| P5 | agent_verifications, agent_review_history | 증빙 자원 FK, 신청·심사 상태와 감사 이력 |
| P6 | captures, capture_files, ai_jobs, job_attempts, callback_receipts, outbox, job_queue | 접수 원자성, attempt·lease 조건부 갱신, callback receipt unique |
| P7 | spaces, space_objects, object_classification_versions | listing+space_version unique, 불변 공간과 객체 분류 버전 분리 |
| P7 | room_states, placement_sets, placements | 작성자 전용 상태, source 버전 FK, user+listing 배치 세트 unique |

geometry·manifest처럼 중첩 구조가 크고 버전 단위로 불변인 문서는 JSONB를 사용할 수 있습니다. 소유관계·금액·상태·버전·검색 조건·참조 제약을 범용 JSON 리소스 테이블에 숨기지 않습니다.

JPA 동시 저장용 버전과 공개 spaceVersion·objectsVersion·modelVersion을 구분합니다. Repository의 개별 저장마다 공개 버전이 자동 증가하는 구조를 피하고 업무 Service의 상태 전이와 ETag 정책에서 공개 버전을 관리합니다.

사용자 삭제 정책, JSONB의 geometry 스키마, FK 삭제 제한과 과거 버전 보존은 실제 기능 단계의 마이그레이션에서 구체화합니다. 원본 SQLite에서 실제 데이터를 이관하거나 기존 DB를 변경하는 작업은 P8입니다.
