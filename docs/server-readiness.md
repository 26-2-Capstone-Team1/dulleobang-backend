# 실제 서버 준비 시 이어서 확인할 항목

2026-10-19 사용자 확인: 연결할 실제 서버는 아직 없습니다. 현재 작업은 로컬 코드·테스트·실행 JAR 검증까지입니다.

1. Java 21, PostgreSQL, HTTPS 진입점, 영속 파일 저장소를 준비합니다. 서버 주소를 BASE_URL로 지정하고 DB_URL·DB_USER·DB_PASSWORD를 설정합니다.
2. JWT·worker·storage 키를 서로 다른 난수로 생성해 안전하게 보관합니다. 개발 키와 테스트 fixture 키를 운영에 사용하지 않습니다. 키를 바꾸면 기존 세션·서명 URL·멱등 응답의 유효성에 영향을 줍니다.
3. 새 DB에서 Flyway를 실행하고 live·ready 및 가입→로그인→매물→사진→채팅 흐름을 확인합니다. 앱 서버 주소도 해당 HTTPS 주소로 설정합니다.
4. 실제 OAuth 앱 ID·클라이언트 ID로 iPhone SDK 로그인을 검증합니다. 제공자 모의 HTTP 테스트와 구분합니다.
5. 외부 AI 엔진이 Python WorkerClient로 큐→claim→입력 다운로드→heartbeat→결과 업로드→complete를 수행하도록 연결합니다. 현재 실제 엔진은 없으며 합성 결과로 계약만 검증했습니다.
6. 기존 운영 데이터가 생긴 뒤 이전하는 경우, 일관된 SQLite 복사본·객체·키를 백업하고 전체 typed PostgreSQL 변환 도구를 구현·dry-run해야 합니다. UUID·가격·삭제 표식·과거 버전·현재 포인터·세션·refresh·멱등·커서·읽음·심사 이력의 수량과 참조를 비교해야 합니다. 현재 사전 점검 도구는 importer가 아닙니다.
7. 전환 전 쓰기 중지→작업 drain→최종 백업→이관→내부 smoke test→트래픽 전환 순서를 수행하고, 실패 시 이전 서버·DB·객체·키로 복귀하는 절차를 준비합니다. 실제 전환은 현재 수행하지 않았습니다.

사전 점검은 다음 명령으로 수행합니다. 원본을 수정하지 않고 복사본과 파일 해시만 검사합니다. 보고서에는 데이터 본문·비밀번호·토큰·키를 쓰지 않습니다.

```powershell
python scripts/migration-preflight.py --source-data <기존data폴더> --report verification/migration-preflight.json
```

서버 환경의 Docker 실행·PostgreSQL 백업/복원·TLS·iPhone·실제 OAuth·실제 AI는 서버 준비 후 검증할 항목이며, 로컬 통합 테스트 통과로 대신 완료 처리하지 않습니다.
