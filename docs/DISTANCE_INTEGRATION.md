# ARCore Raw Depth 보행 장애물 거리 감지 통합 문서

## 1. 개요 및 목적

본 문서는 Aria Android 앱에 실시간 **ARCore Raw Depth** 기반의 보행 전방 장애물 거리 감지 모드를 통합한 아키텍처, 사용자 진입 경로, 실험용 경고 임계값, 진동 피드백 정책, 한계 사항 및 실기기(Pixel 3) 수용 검증 절차를 기술합니다.

- **목적**: 시각 보조 사용자를 위해 전방 보행 복도(좌우 ±0.45m) 내 장애물의 거리를 실시간 추정하고, 거리별 햅틱 진동 알림 및 한국어 인터페이스를 제공.
- **의존성**: Google Play Services for AR 공식 배포 라이브러리 `com.google.ar:core:1.57.0` (Pixel 3 설치 버전 `1.57.262640093`과 호환).
- **매니페스트 정책**: AR Optional (`com.google.ar.core = optional`, `android.hardware.camera.ar required=false`) 설정으로 비 AR 기기에서도 정상 설치 및 기본 기능 보존.
- **파이프라인 범위**: 본 모드는 **순수 깊이 기하학(Depth Geometry Only)**만을 사용하며, 기존 Aria의 기존 분할 모델 및 카메라 의미론적 파이프라인과는 융합(fusion)되지 않고 독립적으로 동작합니다.

---

## 2. 시스템 아키텍처 및 생명주기 분리

```
[MainActivity] (CameraX + 세그멘테이션 / 음성 대기)
      │
      ▼  (사용자가 "보행 거리 감지 (AR)" 선택 시)
  1. `invalidatePendingAndStopCamera()` 실행 -> CameraX 및 UseCase 전면 해제
  2. `DistanceActivity` Intent 실행 -> MainActivity onPause / onStop
      │
      ▼
[DistanceActivity] (내부 비공개 Activity, android:exported="false")
  ├── GLSurfaceView + DistanceRenderer (GL 스레드에서 ARCore session.update 및 프리뷰 렌더)
  ├── ARCore Session (Config.DepthMode.RAW_DEPTH_ONLY 전용)
  ├── FloorPlaneTracker (실시간 수평 상향 바닥면 추적 및 테이블 거절 휴리스틱)
  ├── RawDepthProcessor (Raw Depth 16비트 + 신뢰도 unprojection 및 중력 정렬 좌표계 변환)
  ├── DepthDistanceEstimator (공유 인터페이스: 복도 필터링, 이상치 제거, 15백분위수 거리, 시간적 확증)
  ├── DistanceAlertVibrator (DistanceAlertPolicy: 즉각 상향 에스컬레이션 및 쿨다운 기반 햅틱 진동)
  └── DistanceScreen (Jetpack Compose UI: 온보딩, 품질 게이트, 대형 거리 표시)
      │
      ▼  (뒤로가기 또는 닫기 버튼 선택 시)
  1. GLSurfaceView 일시정지, ARCore Session 일시정지/종료 및 리소스 해제
  2. DistanceActivity 종료
      │
      ▼
[MainActivity] onResume 복귀 -> 기존 생명주기 및 권한 정책에 따라 CameraX 정상 복원
```

### 카메라 단독 소유권 및 생명주기 가드
- CameraX와 ARCore가 카메라 하드웨어에 동시 접근하는 것을 원천 차단하기 위해 전용 Activity(`DistanceActivity`)로 격리.
- 진입 직전 동기적 `invalidatePendingAndStopCamera()` 호출 및 `MainActivity`의 `ON_STOP` 생명주기 핸들러를 통해 CameraX를 완전 정지.
- `DistanceActivity`가 포그라운드에 있는 동안 `MainActivity`는 `RESUMED` 상태가 아니며, `isDistanceModeForeground` 플래그 및 `isResumed` 가드에 의해 CameraX 자동 시작이 차단됨.
- `DistanceActivity`는 `onResume`에서 명시적 `isActivityResumed = true` 플래그를 설정하고, `onPause`에서 GLSurfaceView 일시정지 -> Session 일시정지 -> ViewModel 세션 정리 순서로 안전하게 해제.

---

## 3. 핵심 모듈 및 좌표계 변환

### 3.1 중력 정렬 카메라 기준 좌표계 (Gravity-Aligned Camera-Relative Coordinates)
공유 인터페이스 계약에 따라 모든 [DepthSample]은 다음과 같은 중력 정렬 직교 좌표계로 변환됩니다:
- **$x$ (xMeters)**: 카메라 전방 보행 방향 기준 수평 오른쪽 (+, 단위: 미터)
- **$y$ (yMeters)**: 중력 반대 방향 (수직 위쪽, +, 단위: 미터)
- **$z$ (zMeters)**: 보행 진행 방향의 수평 전방 (+, 단위: 미터)
- **`groundY`**: 카메라 렌즈 기준 바닥면까지의 수직 오프셋 (음수, 예: `-1.30m`)

### 3.2 Raw Depth 비투영 및 좌표 변환 수학 (`RawDepthProcessor`)
1. ARCore의 `acquireRawDepthImage16Bits()`로부터 16비트 밀리미터 단위 깊이 $d_{mm}$ 취득.
2. `acquireRawDepthConfidenceImage()`로부터 정규화 신뢰도 $c \in [0.0, 1.0]$ 취득 ($c \ge 0.50$만 통과, 신뢰도 이미지 누락 시 합성 신뢰도를 생성하지 않고 측정 불가 반환).
3. 핀홀 카메라 모델을 통해 2D 픽셀 $(u, v)$를 카메라 3D 광학 좌표계로 비투영:
   $$X_{cam} = \frac{(u - c_x) \cdot z}{f_x}, \quad Y_{cam} = -\frac{(v - c_y) \cdot z}{f_y}, \quad Z_{cam} = -z$$
4. 카메라 자세 $T_{W \leftarrow C}$를 통해 월드 좌표 $P_{world}$ 계산.
5. 카메라의 전방 벡터를 수평면($Y=0$)에 투영 및 정규화하여 수평 전방 벡터 $\vec{F}_{horiz} = (fx, 0, fz)$ 계산.
6. 수평 우측 기저 벡터:
   $$\vec{R}_{horiz} = \vec{F}_{horiz} \times \vec{U}_{world} = (fx, 0, fz) \times (0, 1, 0) = (-fz, 0, fx)$$
   (예: 카메라가 월드 $-Z$ 방향을 바라볼 때 $fz = -1 \implies \vec{R}_{horiz} = (1, 0, 0)$ 즉 월드 $+X$ 우측 방향).
7. 카메라 상대 오프셋 $\vec{\Delta} = P_{world} - \text{CameraPosition}$을 투영하여 $x = \vec{\Delta} \cdot \vec{R}_{horiz}$, $y = \Delta_Y$, $z = \vec{\Delta} \cdot \vec{F}_{horiz}$ 계산.

### 3.3 지면 판정 및 가짜 바닥 거절 휴리스틱 (`FloorPlaneTracker`)
- ARCore `Plane.Type.HORIZONTAL_UPWARD_FACING` 중 활성 추적 상태(`TRACKING`)이며 타 평면에 병합되지 않은(`subsumedBy == null`) 평면만 대상.
- **면적 조건**: 바운딩 사각형 면적($extentX \times extentZ$) $\ge 0.20\,\text{m}^2$ 및 보행 경로 근접도($\le 4.5\,\text{m}$) 검증.
- **지면 높이 추적**: 바닥면의 월드 고도($Y_{floor}$)를 추적 및 스무딩하여, 사용자가 팔을 올리거나 내릴 때 카메라 상대 오프셋($groundY = Y_{floor} - cameraY$)이 지연 없이 즉각 반응하도록 구현.
- **테이블/가구 거절 휴리스틱 및 모호성 (Table Ambiguity)**:
  - 수직 오프셋 > -0.60m인 일반 책상, 식탁 등은 제외됩니다.
  - 그러나 30~50cm 높이의 낮은 탁자, 커피 테이블, 스툴 등은 실제 바닥과 수직 높이가 유사하여 바닥면으로 오인될 가능성이 존재합니다.
  - 최저 월드 고도 평면을 우선 선택하지만 완벽한 구분을 보장할 수 없으므로, 사용자는 항상 안전한 제자리에서 초기 스캔을 완료하고 주변을 주의 깊게 확인해야 합니다.

### 3.4 15백분위수 전방 거리 및 햅틱 진동 피드백
- **거리 추정 알고리즘**: 단일 픽셀 잡음에 의한 오경보를 방지하기 위해 보행 복도(좌우 $\pm 0.45\text{m}$, 지면 $+0.12\text{m} \sim +1.90\text{m}$) 내 유효 포인트들의 **15백분위수(15th-percentile) 전방 거리**를 장애물 추정치로 사용합니다. 최단 단일 픽셀이나 실제 신체 접촉 유클리드 거리가 아닙니다.
- **실험용 경고 임계값**:
  - **경고 (URGENT, $\le 1.5\,\text{m}$)**: 고강도 이중 펄스 진동 (160ms on, 80ms off, 160ms on), 기본 쿨다운 1,200ms.
  - **주의 (CAUTION, $\le 3.0\,\text{m}$)**: 단일 펄스 진동 (120ms on), 기본 쿨다운 2,500ms.
  - **즉각 상향 에스컬레이션 (Immediate Escalation)**: 주의 또는 안전 상태에서 급격히 경고 거리 내로 접근하는 경우 이전 쿨다운 타이머를 무시하고 즉시 진동 발생.
  - **미확인/안전 (UNKNOWN/CLEAR)**: 진동 중지. "측정 범위 내 경고 조건 미감지"로 표시하며, 보행 경로의 절대적 안전을 보장하지 않습니다.
- **워치독 (Watchdog)**: 센서 프레임 또는 카메라 콜백이 1,500ms 이상 중단되면 즉시 거리 추정치를 만료시키고 `UNAVAILABLE` 상태로 전환하여 오래된 거리 정보가 표시되는 것을 방지합니다.

---

## 4. 사용자 경험 및 온보딩 흐름

1. **진입 경로**:
   - 홈 탭(`VoiceDemoScreen`): "보행 거리 감지 (AR)" 버튼 제공.
   - 설정 탭(`SettingsScreen` -> 진단 설정): "보행 장애물 거리 감지" 메뉴 제공.
2. **초기 온보딩 안내 및 자동 준비 게이트**:
   - 수동 미터법 오차 보정 없이 각 세션마다 실제 센서 품질(ARCore 슬램 추적 상태, 바닥면 검출 여부, Raw Depth 신뢰도 프레임 도착)에 기반하여 자동으로 준비 상태를 평가합니다.
   - 자세 안내: 스마트폰을 가슴/허리 높이에서 수직에 가깝게 유지.
   - 준비 동작: 이동하기 전 안전한 제자리에서 전방과 바닥을 천천히 비춰 바닥면 인식 유도.
   - 지면 인식 전까지는 "바닥 인식 중: 안전한 제자리에서 바닥과 전방을 천천히 비춰주세요" 안내.
3. **가시적인 재보정 (Recalibrate)**:
   - 상단 헤더에 "재보정" 버튼 상시 제공 -> 로컬 추정기/지면/깊이의 시간적 상태를 리셋하고 즉시 재스캔 (ARCore 세션의 세션 전체 트래킹 맵은 유지).

---

## 5. 알려진 한계 사항 (Limitations)

1. **순수 깊이 기하 기반 (Depth Geometry Only)**: 본 프로토타입은 기존 분할 모델과 의미론적(semantic) 융합을 수행하지 않으며, ARCore Raw Depth의 기하학적 형상과 신뢰도 기반으로만 동작합니다.
2. **투명체 및 반사체**: 유리문, 거울, 깊은 검은색 흡수성 표면 등은 ARCore 센서 특성상 깊이 결손(NaN/0)이 발생할 수 있습니다.
3. **저조도 및 단색 바닥**: 시각 특징점이 없는 바닥이나 어두운 야간 환경에서는 ARCore 슬램 추적 또는 바닥 평면 검출이 지연될 수 있습니다.
4. **측정 기준점**: 모든 거리는 신체 접촉 거리가 아닌 카메라 렌즈 기준 전방 복도 15백분위수 추정 거리입니다.
5. **실제 보행 정확도 미검증**: 본 구현은 단위 테스트 및 기기 스모크 테스트로 생명주기 및 기능 동작이 확인되었으나, 실제 실외/실내 보행 시나리오에서의 장애물 검출률 및 계측 정확도는 아직 벤치마크되지 않았습니다.

---

## 6. 실기기 (Pixel 3) 수동 보행 수용 검증 절차

코디네이터가 `./gradlew.bat :app:assembleDebug` 및 `adb -s 8B7X15A0F install -r`을 통해 설치 후 수행할 점검 항목:

1. **진입 및 카메라 충돌 검증**:
   - Aria 앱 실행 후 홈 화면 우측 상단 인셋 카메라가 정상 구동 중인지 확인.
   - "보행 거리 감지 (AR)" 버튼 터치.
   - CameraX 프리뷰가 깔끔히 정지되고 `DistanceActivity`의 ARCore 카메라 화면이 크래시 없이 부드럽게 렌더링되는지 확인.
2. **권한 및 온보딩 검증**:
   - 최초 실행 시 온보딩 다이얼로그(자세 안내, 정지 상태 스캔, 인식 후 보행)가 표시되는지 확인.
   - 다이얼로그 확인 후 바닥 탐색 상태 배너 확인.
3. **바닥면 및 장애물 감지 검증**:
   - 바닥을 향해 천천히 비추었을 때 "바닥 인식 완료 (카메라 기준: 약 -1.2m~-1.4m)"로 전환되는지 확인.
   - 전방 2m 앞 박스나 의자를 향해 걸어갈 때:
     - 3.0m 진입 시: 화면 "주의: 전방 장애물 접근" 표시 및 부드러운 1회 진동 발생.
     - 1.5m 이내 근접 시: 화면 "경고: 전방 근접 장애물" 표시 및 즉각적인 강한 2회 펄스 진동 발생.
4. **재보정 및 복귀 검증**:
   - 상단 "재보정" 버튼 클릭 시 지면 추적과 필터가 즉시 초기화되고 재인식되는지 확인.
   - 좌측 상단 닫기(X) 버튼 터치 시 `DistanceActivity`가 종료되고 Aria 홈으로 복귀하며, 홈 카메라가 크래시 없이 다시 활성화되는지 확인.
