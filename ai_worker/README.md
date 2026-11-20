# AI 워커 실행

GPU 서버에 [PR #2의 AI 코드](https://github.com/26-2-Capstone-Team1/dulleobang-ai-poc/pull/2)와 모델 환경을 준비합니다. Spring Boot의 `WORKER_JWT_SECRET`과 같은 값을 권한을 제한한 키 파일에 저장합니다. 저장소 루트에서 실행합니다.

```bash
python -m venv .venv
.venv/bin/python -m pip install -r ai_worker/requirements.txt
.venv/bin/python -m ai_worker.runner \
  --backend http://10.178.0.5:8080 \
  --secret-file /etc/dulleobang/worker-secret \
  --pr2-repo /mnt/cdata/dulleobang-worker/ai-poc-6bce455
```

`--backend`와 `--pr2-repo`는 실제 환경에 맞춰 지정합니다. 작업 파일은 기본값 `/data/indoor-ai-poc/backend-jobs`에 저장되며 `--work-root`로 바꿀 수 있습니다. 방 복원에는 RoomPlan과 LiDAR가 포함된 촬영 입력이 필요합니다. 온라인 SAM3D 가구 가져오기는 백엔드의 `SAM3D_URL`에 지정한 서비스에 연결됩니다.

백엔드는 최초 작업 대기를 최대 3시간, 각 실행 시도를 결과 등록과 업로드까지 최대 2시간 허용합니다. 워커는 결과를 전송할 시간을 확보하기 위해 엔진 실행을 최대 1시간 58분 20초로 제한하며, 워커 인증 토큰은 3시간 동안 유효합니다. 백엔드 계약도 바뀌었다면 JAR를 먼저 갱신합니다. 워커는 GPU VM의 `ai_worker/` 전체를 같은 버전으로 갱신한 뒤 진행 중인 작업이 없을 때 `dulleobang-pr2-worker`를 재시작합니다.

결과 등록 또는 완료 요청에서 검증이 거절되면 `INVALID_ARTIFACT`로 작업을 종료합니다. 일시적인 네트워크 오류, HTTP 408·429·5xx 또는 실행 시간 초과는 재시도 대상입니다. 작업 권한을 잃은 워커는 추가로 완료나 실패를 전송하지 않습니다.

가구 복원은 GPU VM에서 `--furniture-engine /path/to/executable-adapter`를 지정하면 큐에서 가져옵니다. 워커는 촬영본 파일의 크기와 SHA-256을 확인해 내려받은 뒤 실행 파일에 인자 두 개(`input.json` 경로, 결과 디렉터리 경로)를 전달합니다. 실행 파일은 AI 팀의 복원 스크립트를 호출하고 그 결과를 다음 형식에 맞춰 써야 합니다.

- `input.json`에는 `captureId`, `category`(등록된 가구의 v1 카테고리, 예: `TV`, `DESK`), `measuredDimensions`(미터 또는 null), 선택적 `archive`, `manifest`, `roomplan`, `roomplanStructure`, 그리고 `images`, `depths`, `confidences` 배열이 있습니다. `category`는 복원할 물체를 고르는 데 사용하며 누락되면 워커가 실행을 중단합니다. 파일 항목에는 로컬 `path`와 `asset` 메타데이터가 들어갑니다. 원본 ZIP을 제공하는 캡처는 중복 다운로드를 피하기 위해 세 배열을 비워 둡니다.
- 결과 디렉터리의 `result.json`에는 `result`와 `files`가 필요합니다. `result`에는 `type: "FURNITURE_RECONSTRUCTION"`, `dimensions`, `boundingBox`, `quality`를 넣습니다. `files`에는 `key`와 `role`이 각각 `MODEL`, `COLLISION`이고 `format`이 `GLB_V2`인 두 항목을 넣습니다. `path`는 결과 디렉터리 기준 GLB 경로입니다. 가로는 X, 높이는 Y, 깊이는 Z를 사용합니다.
- 입력을 다시 처리해도 실패하는 경우 실행 파일은 종료 코드 65를, 일시적인 의존성 장애에는 75를 반환합니다. 성공하면 워커가 결과 파일을 올리고 두 파일의 참조를 붙여 `complete`를 호출합니다.

GPU 없이 워커 테스트를 실행하려면 저장소 루트에서 `uv run --no-project --with-requirements ai_worker/requirements.txt python -m unittest discover -s tests -p test_ai_worker.py`를 실행합니다.
