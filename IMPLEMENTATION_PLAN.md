# v2 구현 상태

사용자 확정: 원본 dulleobang-backend를 보존하고 형제 폴더 dulleobang-backend-v2에 구현합니다.

| 단계 | 코드·로컬 검증 상태 | 주요 증거 |
|---|---|---|
| P0 | 계약·97 API inventory와 기준선 고정 | inventory-check.json, 복사본 기준 테스트 |
| P1 | 암호·JSON·이미지·ZIP·PLY·GLB 언어 차이 검증 | CompatibilityTest |
| P2 | Spring·PostgreSQL·보안·멱등·커서·ETag·공통 오류 | FoundationIntegrationTest, ArchitectureTest |
| P3 | 인증·OAuth 경계·사용자 | AuthIntegrationTest, OAuthProviderTest |
| P4 | 파일 저장·업로드·가구 | UploadFurnitureIntegrationTest |
| P5 | 매물·사진·찜·신고·중개사·채팅 | ProductIntegrationTest |
| P6 | 촬영 ZIP·작업·worker·유지보수 | CaptureJobIntegrationTest, Python WorkerClient HTTP |
| P7 | 공간·결과 검증·객체·개인 상태·배치 | SpaceResultIntegrationTest, ProductionGeometryTest, PlacementIntegrationTest |
| P8 | 읽기 전용 복사본 사전 점검만 수행 | migration-preflight.json |

P8은 완료로 표시하지 않습니다. 아직 실제 서버가 없다는 사용자 확인을 반영했습니다. 현재 원본 SQLite의 업무 데이터·객체 파일은 0건이며, 비어 있지 않은 데이터의 PostgreSQL 전체 이관 도구와 검증은 남아 있습니다. 실서버 연결·배포·전환, iPhone 실기기, 실제 OAuth·외부 AI 엔진 검증도 별도 단계입니다.

## 수정 후 재검토

- 모든 고정 경로가 실제 Controller에 연결되는지 Spring MVC에서 확인합니다.
- 삭제된 대상 작업의 노출·유지보수 중단, 동시 촬영 잠금, 활성 작업 한도를 검사합니다.
- worker 주체·lease·시도 번호·파일 checksum·실제 3D 데이터·중복 callback을 검증합니다.
- 공간·가구 버전, 개인 결과 접근, 배치 ETag·충돌 보고서·isStale을 검사합니다.
- 만료 URL 재발급, 앱 채팅 읽음, 쿼리 검증, 용도별 요청 제한을 검사합니다.
- 파일 정리는 공유 참조와 동시 업로드를 보존하는지 실제 PostgreSQL 잠금으로 확인합니다.
- 원본 소스 해시, 포맷, 전체 테스트, 실행 JAR를 마지막에 다시 확인합니다.

전체 전환의 원래 계획은 docs/full-migration-plan-reference.md에 보존합니다. 현재 구현 상태는 이 문서와 verification/final-review.json을 기준으로 봅니다.
