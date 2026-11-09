# Swift iOS 앱 연결

`dulleobang-frontend/Dulleobang/Core/Network/Endpoints.swift`와 `Core/Models/Models.swift`의 실제 요청·응답을 기준으로 구현했습니다. 앱 API는 `/api`, 기존 상세 명세 및 AI 워커 API는 `/api/v1`입니다. 인증·저장소·작업 큐는 같은 백엔드를 사용합니다.

## 실행 및 앱 설정

```powershell
.\scripts\run.ps1 -Reload
```

기본 포트는 8080입니다. 앱 설정에서 **데모 데이터 사용을 끄고** 서버 주소를 지정하십시오.

- iOS 시뮬레이터와 서버가 같은 Mac: `http://localhost:8080/api`
- 실제 iPhone과 Windows 서버: `http://PC의-LAN-IP:8080/api`

실제 기기를 연결할 때는 서버도 LAN에서 접근할 수 있게 실행해야 합니다. `PUBLIC_BASE_URL`은 앱이 다운로드할 사진·PLY·GLB URL의 주소이므로 같은 PC 주소를 사용합니다.

```powershell
$env:PUBLIC_BASE_URL="http://192.168.0.10:8080"
.venv\Scripts\python.exe -m uvicorn app.main:app_factory --factory --host 0.0.0.0 --port 8080
```

IP는 실제 서버 주소로 바꾸십시오. 소셜 로그인은 기존 `KAKAO_APP_ID`, `GOOGLE_CLIENT_IDS`와 앱 SDK 설정이 필요합니다. 이메일 회원 가입·로그인으로도 연결을 확인할 수 있습니다. `X-User-Id`로 로그인 상태를 대신하지 않으며 Bearer 토큰을 검증합니다.

## 앱 계약

| 기능 | 앱 요청 | 처리 |
|---|---|---|
| 인증 | `/api/auth/signup`, `login`, `oauth/kakao`, `oauth/google`, `refresh`, `logout` | 기존 TokenPair, refresh 회전·폐기 재사용 |
| 매물 등록 | `POST /api/listings` | 문자열 주소, `price{deposit,monthly}`, 옵션 소문자, 면적 필드를 변환 |
| 검색 | `GET /api/listings?location=&dealType=&roomType=&maxDeposit=&maxPrice=&minLat=&maxLat=&minLng=&maxLng=` | 주소 AND 검색, 가격·방 종류·지도 범위 필터 |
| 내 매물·찜 | `/api/users/me/listings`, `/api/users/me/scraps`, `POST/DELETE /api/listings/{id}/scrap` | 사용자별 조회·저장 |
| 사진 | `POST /api/listings/{id}/images` | multipart `images` 1~30개, JPEG/PNG 각 20MiB 이하, 선택 순서 보존 |
| 인증 신청 | `GET/POST /api/users/me/agent-profile` | multipart `name,licenseNumber,officeName,documentName,document`; 신청 전 404, 신청 후 PENDING |
| 채팅 | `GET/POST /api/chat`, `GET/POST /api/chat/{id}` | `message` 입력, 상세에 시간순 메시지 포함, 상세 조회 시 읽음 처리 |
| 신고 | `POST /api/listings/{id}/reports` | 앱의 한국어 신고 사유를 서버 enum으로 변환 |
| 방·가구 촬영 | `POST /api/listings/{id}/captures`, `/api/furniture/{id}/captures` | ZIP 원문 스트리밍, 202 작업 ID; multipart 아님 |
| 3D·객체 | `/api/listings/{id}/space`, `/objects` | 서명된 PLY/GLB URL, 벽·문·창, 객체 position/yaw 제공 |
| 객체 분류 | `PATCH /api/listings/{id}/objects` | 소유자와 If-Match 검증, 현재 spaceVersion 보충 |
| 개인 입주 상태 | `/api/listings/{id}/room-states`, `/api/room-states/{id}` | 버전 생략 시 현재 버전, 타인 결과 404 |
| 가구 | `/api/furniture`, `/api/furniture/{id}`, `/api/users/me/furniture` | 완료 상태를 COMPLETED, modelUri·dimensions·collisionMeshUri 제공 |
| 배치 | `GET/PUT /api/listings/{id}/placements` | position/yaw를 quaternion으로 변환, 현재 원본 공간·가구 모델 버전 사용, ETag 충돌 보호 |
| 작업 | `GET /api/ai-jobs/{id}` | progress 0~100 제공 |

앱 가격은 **만원**, DB와 `/api/v1` 가격은 **원 정수**입니다. 앱에서 보증금 500·월세 31·관리비 5를 보내면 DB에는 5,000,000·310,000·50,000원으로 저장합니다. 앱 응답은 다시 만원으로 변환합니다. 위경도는 실제 값을 보내야 하며 주소 검색 실패 시 422 `LOCATION_COORDINATES_REQUIRED`로 반환합니다.

앱 목록은 `{items:[...]}`입니다. 앱에 다음 페이지 조회 코드가 없어, limit/cursor를 명시하지 않은 요청은 서버가 페이지를 모두 모아 반환합니다. 명시한 요청은 기존 페이지 동작을 유지합니다.

가구 종류는 앱의 `bed/desk/sofa/closet/storage/chair/other`를 받아 내부의 `BED/DESK/SOFA/WARDROBE/SHELF/CHAIR/OTHER`로 저장합니다. 앱 응답은 다시 소문자 앱 종류로 변환합니다. 기존 대문자 요청도 계속 받으며 `/api/v1`의 enum은 그대로 유지합니다.

앱 3D 객체의 `position`은 상자 중심입니다. 내부의 바닥 중심 pivot에서 로컬 높이의 절반을 회전시켜 월드 좌표에 더합니다. 공간·객체 목록·분류 응답·개인 입주 상태에 같은 변환을 적용합니다. `roomGeometry.floorHeight`와 boundingBox는 절대 Y좌표이며, `ceilingHeight`는 최저 바닥에서 최고 천장까지의 높이(`max(ceilingY) - min(floorY)`)입니다. 원본 공간과 워커 좌표는 변경하지 않습니다.

## 게시 및 인증 정책

누구나 본인 매물을 등록할 수 있습니다. 기본 정보만 저장하면 DRAFT입니다. 앱에는 별도 게시 요청이 없으므로 **앱 사진 업로드가 검증을 통과하면 PUBLISHED로 전환**합니다. 사진만으로도 목록에 표시되며, 3D 공간은 복원 작업 완료 후 제공됩니다. 업로드 재시도는 사진을 동일 순서로 전체 교체합니다. `/api/v1`의 명시적 게시 요청은 기존 사진+3D 조건을 유지합니다.

중개사 인증은 자동 승인하지 않습니다. 증빙은 해당 사용자와 검토자만 접근하며, 기존 `review-agent` CLI로 승인해야 합니다. 현재 앱 신청 폼에는 전화번호가 없어 기존 사용자 프로필의 전화번호를 사용하고, 없으면 null로 보관합니다. 임의 전화번호를 만들지 않습니다.

## 촬영 및 검증 범위

기존 방 ZIP 검증·좌표 정규화를 보존하고 같은 경로로 가구 ZIP도 처리합니다. 방은 RGB 30~1000장, 가구는 12~300장입니다. 가구의 깊이·RoomPlan 부가 파일은 원본 ZIP에 보존하고 기존 가구 RGB+카메라 입력으로 전달합니다. LiDAR가 없어 바닥 높이가 없으면 ARKit 월드의 Y=0을 변환 기준으로 유지합니다. 실제 바닥 높이를 측정했다고 간주하지 않습니다.

`tests/test_mobile.py`는 앱 JSON·multipart·ZIP과 기존 워커 결과를 사용해 HTTP 통합 흐름을 검증합니다. 실제 iPhone 빌드·카메라 촬영·OAuth 제공자 로그인과 외부 AI 엔진 실행은 별도 환경에서 확인해야 합니다. Windows에서는 iOS 빌드를 실행하지 않았습니다.
