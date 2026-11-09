# 2026-11-08 실제 배포 기록

AI 기준은 dulleobang-ai-poc PR #2의 `6bce45563e3f3efb47b3fa5a2b15ff7d782cc374`입니다. 프론트는 `feature/furniture-full-shot-guide`의 `704aa834e635fade74206fa9bc1ab40f896033f3`를 반영했습니다.

## 서버와 서비스

- 프로젝트: `project-70d73144-cecd-47d0-915`, 존: `asia-northeast3-a`
- 백엔드: `dulleobang-backend`, 외부 `34.47.87.3`, 내부 `10.178.0.5`, 포트 `8080`
- GPU: `capstone-20261025-071028`, 내부 `10.178.0.4`, NVIDIA L4
- JAR: `/opt/dulleobang/app.jar`
- 설정: `/etc/dulleobang/backend.env` (서버에만 보관)
- DB: PostgreSQL `dulleobang_v2`, 계정 `dulleobang_app`
- 파일: `/var/lib/dulleobang`

백엔드의 `dulleobang-backend.service`·`dulleobang-ai-tunnel.service`, GPU의 `dulleobang-sam3d.service`·`dulleobang-pr2-worker.service`를 등록하고 enable/start 했습니다. VM이 실행되면 서비스도 시작합니다. 중지 상태였던 백엔드 VM을 켰으며 원래 실행 중이던 GPU VM도 유지했습니다.

SAM3D는 GPU loopback `127.0.0.1:18766`에만 바인딩됩니다. 백엔드는 전용 SSH 계정 `dulleobang-tunnel`을 통해 내부 IP로 연결합니다. 키는 SAM3D 포트 전달만 허용하고 호스트 공개키를 고정했습니다. GCP가 갱신하는 개인 SSH 키와 분리했습니다. Java의 `SAM3D_URL`은 `http://127.0.0.1:18766`입니다.

GPU 워커는 `/mnt/cdata/dulleobang-worker/backend/ai_worker`에 있고 AI 원본은 별도 경로 `ai-poc-6bce455`에 고정했습니다. 기존 AI 작업 폴더·모델 환경은 덮어쓰지 않았습니다. 워커용 Python 의존성은 별도 venv에 설치했습니다. 워커 토큰 전용 서명 키만 GPU root에게 전달했으며 사용자 토큰 키·파일 서명 키·DB 암호는 전달하지 않았습니다.

```bash
sudo systemctl status dulleobang-backend dulleobang-ai-tunnel
sudo journalctl -u dulleobang-backend -n 100 --no-pager
curl -i http://127.0.0.1:8080/
curl -i http://127.0.0.1:8080/health/ready
```

GPU에서는 `sudo systemctl status dulleobang-sam3d dulleobang-pr2-worker`로 확인합니다.

## 기존 앱을 유지한 PR #2 연결

PR #2는 텍스처 GLB를 내보내지만 현재 앱의 방 뷰어는 3DGS 또는 색상 점군을 받습니다. 백엔드의 `ai_worker/pr2.py`가 실제 방·객체 메시 표면에서 색상 점 50만 개를 샘플링해 기존 `pointsUri`로 제공합니다. `3dgsUri`는 null이며 Gaussian 속성을 만들지 않습니다. 원본 GLB는 변형 없이 보관합니다. 앱의 방 렌더러를 새 메시 렌더러로 바꾼 것은 아닙니다.

가구 중심은 명세의 바닥 중심으로 변환하고, quaternion 및 모바일의 도 단위 회전을 맞췄습니다. 원래 벽·문·창·유리 위치도 기존 JSON 필드에 전달합니다. 가구 제거는 이미 복원된 방과 남은 객체 메시만으로 개인 상태를 다시 구성합니다. 원본 공간과 다른 사용자의 상태는 유지합니다.

고정 v1.1 원본 명세 파일은 유지했습니다. `POINT_CLOUD_PLY_V1`, `SOURCE_MESH`, 선택 `meshSource`와 제거용 `meshDownloads`는 별도 호환 확장입니다. Flyway V8은 기본 산출물 역할의 고유 제약을 유지하면서 원본 메시만 여러 개 보관하도록 합니다. 원본 GLB의 표준 `KHR_materials_unlit` 재질은 허용하고 지원하지 않는 확장은 거절합니다.

## 검증

Java 전체 114개, 스튜디오 Python 19개·JavaScript 5개, PR2 변환 테스트 2개가 통과했습니다.

실제 기존 촬영 ZIP 156,964,348바이트와 해당 촬영의 PR #2 결과로 ZIP 접수 → worker lease → 원본 메시·점군·기하·충돌 업로드 및 검증 → 기존 앱 `/api/listings/{id}/space`의 점군 50만 개 다운로드를 확인했습니다. 이어 객체를 PERSONAL로 분류하고 제거 요청을 만들었으며, GPU의 자동 워커가 처리해 입주 상태 READY, 객체 6→5, 점군 파일 해시 변경을 확인했습니다. 검사 매물·상태·세션·계정·작업은 정리했습니다.

온라인 SAM3D는 기존 실제 작업 `d04b066380af4274945ad4ea311cfd0d`를 가져와 201·멱등 재요청·내 가구 조회·GLB 5,608,544바이트 다운로드 및 SHA-256 일치를 확인했습니다. 생성 당시 크기는 0.65 × 1.235 × 0.65m입니다. 해당 검사 가구·계정도 정리했습니다.

이번 검증은 기존 실제 생성물을 사용했습니다. 새로운 촬영의 GPU 전체 추론이나 iPhone/Xcode 실행을 새로 검증한 기록은 아닙니다.

## 사용

앱 base URL은 `http://34.47.87.3:8080/api`입니다. 신규 설치의 기본값은 실제 서버이며, 기존 설치는 설정의 데모 모드를 꺼야 합니다. 실제 서버의 이메일 계정으로 로그인합니다.

온라인 가구 스튜디오는 AI 폴더의 `products/start-connected.ps1`를 실행합니다. 이 PC의 기존 GCP 로그인으로 GPU 18766·백엔드 18080 터널과 로컬 화면 18767을 연결합니다. 사진 생성 후 ‘내 가구에 저장’에서 앱과 같은 이메일로 로그인합니다.

PR #2 방 복원에는 RoomPlan·LiDAR 입력과 기존 GPU 모델 환경이 필요합니다. RGB-only 방 및 앱의 단독 가구 촬영 추론은 이 워커의 대상이 아닙니다. 온라인 사진 SAM3D 생성·가구 가져오기는 별도 서비스로 동작합니다. 실제 카카오/구글 로그인에는 운영 앱 키·클라이언트 ID 설정이 필요합니다.

기존 Python 백엔드와 서버의 기존 DB `dulleobang`는 보존했습니다. 비어 있지 않은 이전 데이터 이관은 수행하지 않았습니다. 코드 백업은 캡스톤 폴더 `.codex-backups/20261108-before-sync.zip`, 이전 AI 로컬 변경은 Git stash `before-local-sync-20261108`에 있습니다. 두 Git 저장소는 로컬 `codex/local-sync-20261108` 브랜치에 있으며 새 변경을 원격에 푸시하지 않았습니다.

## 재배포

검증한 bootJar를 VM에 복사한 뒤 `sudo bash scripts/deploy-vm.sh /path/to/app.jar`를 실행합니다. 이전 JAR는 날짜별로 보관하고 DB·서명 키·객체 저장소는 유지합니다. 현재 JAR 해시는 함께 제공하는 `빌드_검증결과.json`과 비교합니다.
