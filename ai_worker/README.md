# PR #2 → Spring Boot → 기존 앱

`dulleobang-ai-poc` PR #2의 `6bce45563e3f3efb47b3fa5a2b15ff7d782cc374`를 기준으로 한 **백엔드 소유** 연결 코드입니다. AI 알고리즘이나 앱의 방 렌더러는 바꾸지 않습니다.

## 처리

1. `runner`가 AI_WORKER JWT로 대기 작업을 조회하고 claim·30초 heartbeat를 수행합니다.
2. 방 복원은 원본 capture.zip을 체크섬 확인 후 풀고, 작업별로 독립된 capture ID를 부여해 PR #2 `scripts/plan/run_mesh_room.sh`를 실행합니다. 동일 작업 재시도는 완료한 파이프라인 단계를 재사용합니다.
3. `pr2.convert_export`는 `export/space.json`의 ARKit/Y-up 좌표·실제 메시 자체 검사 결과를 확인합니다.
4. 실제 텍스처 방 메시와 배치된 가구 메시 표면을 색상 점 50만 개로 샘플링합니다. 기존 앱이 지원하는 `pointsUri`에 이 PLY를 연결하고 `3dgsUri`는 null로 둡니다. 이 점군을 3DGS로 표시하거나 Gaussian 속성을 꾸며 내지 않습니다.
5. RoomPlan 가구 중심을 명세의 바닥 중심으로, 라디안 회전을 quaternion 및 모바일의 도 단위로 변환합니다. 기존 `roomGeometry`에는 원본 벽·문·창 위치를 유지합니다.
6. 원본 방 GLB·객체 GLB도 `meshSource`로 영속 저장합니다. 각 파일은 서명 PUT·길이·SHA-256·실제 GLB 파싱을 거치며 작업·attempt·lease에 귀속됩니다.
7. 개인 가구 제거는 보관한 원본 방과 남은 객체 메시만으로 새 점군과 충돌 파일을 만듭니다. 방 자체는 PR #2에서 이미 채운 메시를 재사용합니다. 원본 매물·타인의 상태는 바꾸지 않습니다.

기존 `/api/listings/{id}/space`, `/api/listings/{id}/objects`, `/api/room-states/{id}`와 관련 생성 API를 유지합니다. 기존 GS PLY 작업과 온라인 SAM3D 가져오기 API도 유지합니다. 새 wire 형식 `POINT_CLOUD_PLY_V1`, 원본 역할 `SOURCE_MESH`, 선택 `SpaceBundle.meshSource`는 `contract/extensions/pr2.json`과 `Pr2Extensions`에 정의되어 있습니다. 동결한 v1.1 명세 원본 파일은 변경하지 않습니다.

## 모듈별 역할

| 모듈 | 책임 |
|---|---|
| `runner` | 작업 큐 조회와 attempt 처리 순서 조립 |
| `execution` | 엔진 실행·제한 시간·lease 상실·프로세스 그룹 종료와 회수 |
| `publication` | 출력 파일 등록·업로드·AssetRef 연결·완료 콜백 |
| `tokens` | VM의 전용 키를 이용한 짧은 수명의 워커 토큰 발급 |
| `engine` | PR #2 파이프라인 실행과 제거 작업 연결 |
| `pr2` | 원본 검사 및 방 복원·가구 제거 변환 순서 조립 |
| `coordinates` | ID·크기·좌표·회전 변환 |
| `room_geometry` | 방 다각형과 기존 앱의 RoomPlan 표현 |
| `object_mapping` | 가구·문·창 및 canonical bundle 매핑 |
| `mesh_assets` / `point_cloud` | 메시 읽기·배치·충돌 모델 및 색상 점군 생성 |
| `artifacts` | 로컬 경로 검사·원본 보관·변환 파일 저장 |

## 실행 방법

```bash
python -m venv .venv
.venv/bin/python -m pip install -r ai_worker/requirements.txt
.venv/bin/python -m ai_worker.runner \
  --backend http://10.178.0.5:8080 \
  --secret-file /etc/dulleobang/worker-secret \
  --pr2-repo /mnt/cdata/dulleobang-worker/ai-poc-6bce455
```

워크 디렉터리는 `/data/indoor-ai-poc/backend-jobs`, 촬영 작업 캐시는 `backend-captures`입니다. 서명 키는 AI_WORKER 토큰 전용이며 GPU 서버 root만 읽습니다. 사용자 JWT·파일 서명 키·DB 비밀번호는 GPU에 전달하지 않습니다. 워커는 1시간 유효 토큰을 새로 발급해 사용합니다. 비밀값·토큰·서명 URL은 소스나 출력 로그에 포함하지 않습니다.

GPU VM에는 `dulleobang-pr2-worker.service`로 등록했습니다. `systemctl status`와 `journalctl -u dulleobang-pr2-worker`로 확인합니다. 모델·가중치·GPU 환경은 기존 설치를 사용하고 Python HTTP·메시 변환 의존성은 별도 venv에 설치합니다. 온라인 가구 생성과 방 복원은 AI 저장소의 GPU 0 잠금을 공유합니다.

## 범위

PR #2 파이프라인에 필요한 RoomPlan·실내 LiDAR 입력과 설치된 모델 환경이 있어야 방 복원이 가능합니다. 일반 RGB-only 방 복원이나 앱의 단독 가구 촬영 재구성은 이 워커의 처리 대상이 아닙니다. 온라인 사진 → SAM3D → 내 가구 가져오기는 별도 서비스로 동작합니다.

앱의 현재 표시 방식은 색상 점군이며 원본 텍스처 GLB를 직접 렌더하는 것은 아닙니다. 충돌 파일의 방은 실제 방 충돌 메시, 가구는 바운딩 박스 근사치입니다. 닫힌 바닥 윤곽을 얻지 못하면 RoomPlan 벽의 convex hull을 canonical room polygon으로 사용하고 경고를 남깁니다. 앱에 보이는 벽 선분은 원본을 유지합니다. 객체 confidence가 원본에 없어 0.5를 사용하며 보정된 확률이 아니라는 경고를 함께 기록합니다.

한 attempt의 30분 제한·최대 3회 재시도·사용자별 활성 작업 제한은 기존 서버 정책을 유지합니다. 중지/lease 분실 시 엔진 프로세스 그룹을 종료합니다. 새 촬영의 처리 시간과 품질은 GPU·사진·가구 수에 따라 달라집니다.

## 검증

```bash
python -m unittest ai_worker.test_pr2 ai_worker.test_execution ai_worker.test_publication -q
```

좌표·축·회전·바닥 정렬, 제거 가구 표면이 점군에서 실제로 빠지는지, 원본 바이트 보존, 잘못된 exporter 자체 검사와 경로 이탈 거절을 확인합니다. 실서버 검증은 기존 촬영·이미 생성된 PR #2 결과를 사용하며 새 GPU 추론 성공 기록과 구분합니다.

프로세스 종료 테스트는 lease 상실·시간 초과·SIGTERM 후 SIGKILL 전환·정상 종료·엔진 실패를 확인합니다. 게시 테스트는 attempt별 파일 ID의 안정성과 외부 경로 파일의 등록 전 차단을 확인합니다. 실제 PR #2 결과의 변경 전후 비교에서는 Trimesh가 임의로 붙이는 임시 노드 이름을 양쪽에 동일하게 고정한 뒤 JSON·점군·GLB·파일 등록 및 콜백을 대조했습니다.
