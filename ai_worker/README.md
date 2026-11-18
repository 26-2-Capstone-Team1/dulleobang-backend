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

백엔드는 최초 작업 대기를 최대 3시간, 각 실행 시도를 결과 등록과 업로드까지 최대 2시간 허용합니다. 워커는 결과를 전송할 시간을 확보하기 위해 엔진 실행을 최대 1시간 58분 20초로 제한하며, 워커 인증 토큰은 3시간 동안 유효합니다. 배포할 때는 백엔드 JAR를 먼저 갱신한 뒤 GPU VM의 `ai_worker/execution.py`와 `ai_worker/tokens.py`를 적용하고 `dulleobang-pr2-worker`를 재시작합니다.
