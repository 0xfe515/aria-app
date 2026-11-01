# 단안 지면 접점 기반 거리 추정 연동 명세 (Monocular Ground-Contact Distance)

상태: **구현 완료 (ARIA-MONOCULAR-CORE-001)**. 작성일: 2026-11-01.

---

## 1. 개요 및 아키텍처 역할

Aria 앱의 메인 라이브 프리뷰에서 ARCore 바닥 평면 감지 의존성 없이, 사용자 키(신장) 및 착용 위치(어깨/가슴) 모델과 기기 실시간 IMU(가속도·자이로) 센서, Camera2 내부 파라미터를 결합하여 카메라 광선과 가상 수평 지면 평면의 교차점을 계산해 전방 장애물의 **추정 거리(m)**를 실시간 제공합니다.

- **백엔드/코어 소유**: `com.ariateam.aria.distance.*`, `CameraGeometry`, `VisionPipelineController`
- **UI/내비게이션 피어 소유**: `MainVisionInset`, `MainScreen`, `SettingsScreen` 등 UI 표현 컴포넌트

---

## 2. 계산 원리 및 기하학적 모델 (Exact Computation)

### 2.1 사용자 착용 높이 추정 (Body Mount Height)
- 신장 $H$ (cm, 허용 범위: 120cm ~ 220cm)
- AIST 2003 일본 성인 인체 치수 표본 가중 평균 비율 적용:
  - 어깨(견봉) 비율 $r_{\text{shoulder}} = 0.8035428$
  - 가슴(유두/흉부) 비율 $r_{\text{chest}} = 0.7164686$
- 추정 카메라 렌즈 높이:
  $$h = \frac{H}{100} \times r_{\text{position}} \quad (\text{단위: m})$$

### 2.2 비대칭 크롭 왜곡 방지 및 풀프레임 정립 카메라 모델 (`FullUprightCameraModel`)
- `LEGACY_CROP`(하단 정렬 16:9 크롭) 및 레터박스 전처리 환경에서 대칭 FOV 가정 시 광학 중심축 편향이 발생하는 문제를 해결하기 위해, 분석 마스크 좌표 $(u, v)$를 풀프레임 정립 센서 정규화 좌표 $(u_{\text{full}}, v_{\text{full}})$로 역매핑합니다.
  $$u_{\text{full}} = \text{clamp}(u_{\text{crop\_left}} + u \cdot u_{\text{crop\_width}}, 0, 1)$$
  $$v_{\text{full}} = \text{clamp}(v_{\text{crop\_top}} + v \cdot v_{\text{crop\_height}}, 0, 1)$$
- 풀프레임 광학 중심 $(0.5, 0.5)$ 및 초점거리 $f$ 기준 카메라 공간 단위 광선 $\mathbf{r}$:
  $$x_c = (u_{\text{full}} - 0.5) \times \frac{W_{\text{sensor, upright}}}{f}$$
  $$y_c = (v_{\text{full}} - 0.5) \times \frac{H_{\text{sensor, upright}}}{f}$$
  $$z_c = 1.0$$
  $$\mathbf{r} = \frac{(x_c, y_c, z_c)}{\|(x_c, y_c, z_c)\|}$$
- 이를 통해 상·하단 비대칭 크롭에서도 카메라 렌즈의 실제 물리적 광학 각도가 정확하게 보존됩니다.

### 2.3 IMU 센서와 중력 기준 좌표계 변환 및 유효성 검증
- 안드로이드 센서 좌표계는 화면 회전에 따라 회전하지 않으므로, 현재 `displayRotation`(`ROTATION_0`, `ROTATION_90`, `ROTATION_180`, `ROTATION_270`)을 반영해 디스플레이 기준 가속도 $\mathbf{a}_{\text{disp}} = (a_x, a_y, a_z)$로 정렬합니다.
- 카메라 좌표계로의 축 사상:
  - $+X_c = a_x$ (우측)
  - $+Y_c = -a_y$ (화면 상향 가속도 반전 -> 하향)
  - $+Z_c = -a_z$ (전면 스크린 수직 반전 -> 후면 전방)
- **가속도 크기 유효성 검증**:
  - $4.5\,\text{m/s}^2 \le \|\mathbf{a}\| \le 16.5\,\text{m/s}^2$ 범위를 벗어나는 자유 낙하 및 급격한 충격/비중력 가속도는 즉시 이상으로 격하(`isFault = true`).
- **타임스탬프 정렬 링 버퍼**:
  - 카메라 프레임 시점($\pm 200\text{ms}$)에 가장 근접한 가속도 샘플을 선택하여 미래 샘플 오참조 방지.
- 저주파 필터링을 거친 가속도 반응 벡터를 정규화하여 하늘 방향 단위 벡터 $\mathbf{n}_{\text{up}}$을 도출합니다.
  - 수직 기립 상태: $\mathbf{n}_{\text{up}} = (0, -1, 0)$
  - 전방 30도 하향 틸트 상태: $\mathbf{n}_{\text{up}} = (0, -\cos 30^\circ, -\sin 30^\circ)$

### 2.4 지면 교차 및 전방 수평 거리 도출
- 카메라 렌즈 원점 $(0, 0, 0)$에서 높이 $h$ 아래의 수평 지면 평면 방정식:
  $$\mathbf{P} \cdot \mathbf{n}_{\text{up}} = -h$$
- 광선 $\mathbf{P}(t) = t \mathbf{r}$ 대입:
  $$t = \frac{-h}{\mathbf{r} \cdot \mathbf{n}_{\text{up}}}$$
- 바닥 방향 교차 조건: $\mathbf{r} \cdot \mathbf{n}_{\text{up}} < -0.0175$ (수평선 1도 이상 하향각).
- 3D 교차점 $\mathbf{P} = t \mathbf{r}$과 카메라 직하점(nadir) 기준 지면 수평 벡터 $\mathbf{V}_{\text{ground}} = \mathbf{P} + h \mathbf{n}_{\text{up}}$.
- 전방 보행 진행 방향 단위 벡터 $\mathbf{fwd}_{\text{ground}} = \text{normalize}(\mathbf{fwd}_{\text{cam}} - (\mathbf{fwd}_{\text{cam}} \cdot \mathbf{n}_{\text{up}}) \mathbf{n}_{\text{up}})$.
- 최종 전방 수평 거리:
  $$d_{\text{forward}} = \mathbf{V}_{\text{ground}} \cdot \mathbf{fwd}_{\text{ground}}$$
- 측방 이격 거리: $d_{\text{lateral}} = \mathbf{V}_{\text{ground}} \cdot (\mathbf{fwd}_{\text{ground}} \times \mathbf{n}_{\text{up}})$.

---

## 3. UI 필드 연동 계약 (Shared API Contract)

### 3.1 상태 필드: `VisionUiState.groundContactDistance`
```kotlin
package com.ariateam.aria.distance

data class GroundContactDistance(
    val distanceMeters: Float? = null,
    val message: String = "키와 착용 위치를 설정하세요",
    val timestampNanos: Long = 0L,
    val contactXNormalized: Float? = null,
    val contactYNormalized: Float? = null,
)
```

UI 피어(`MainVisionInset` 등)는 `VisionViewModel.uiState.collectAsState().value.groundContactDistance`를 구독합니다.

### 3.2 신선도 게이트 (Freshness TTL)
- `timestampNanos`: 카메라 센서 캡처 시점의 모노토닉 나노초 타임스탬프 (`SystemClock.elapsedRealtimeNanos()` 기준 도메인).
- UI 만료 시간: 카메라 센서 캡처 시점(`timestampNanos`) 기준 경과 시간(capture age)이 **1.5초(1,500ms)**를 초과하면 UI 표시 만료 처리.

### 3.3 상태 메시지 규격 (한국어)
| 상태 / 원인 | `distanceMeters` | `message` 예시 |
|---|---|---|
| 정상 추정 성공 | `2.3` 등 유효 수치 | `"추정 거리 2.3m"` |
| 프로필 미설정 | `null` | `"키와 착용 위치를 설정하세요"` |
| 카메라 클럭 비호환 | `null` | `"카메라 센서 클럭 비호환 (거리 추정 비활성)"` |
| 보정값(FOV) 미확인 | `null` | `"카메라 내부 보정값(초점거리) 미확인"` |
| 센서 미수신 / 지연 | `null` | `"센서(자세) 신호 미수신"` / `"센서 데이터 지연"` |
| 센서 수치 이상 (자유낙하/충격) | `null` | `"비정상 센서 수치(가속도 이상) 감지"` |
| 급격한 회전 / 흔들림 | `null` | `"급격한 기기 움직임 감지"` |
| 전방 시야 이탈 (상향 틸트) | `null` | `"카메라 각도 부적합 (지면 시야 이탈)"` |
| 화면 하단 접점 잘림 | `null` | `"접점 화면 경계 잘림 (거리 추정 불가)"` |
| 공중 부유 / 지면 미접지 객체 | `null` | `"지면 접점 미확인 (공중 객체)"` |
| 경로 내 장애물 없음 | `null` | `"전방 보행 경로 장애물 미감지"` |

---

## 4. 세그멘테이션 출처 및 지면 접점 추출

- **모델 출처**: Upstream `tulg580/vision_model` (MobileNetV2 인코더 + U-Net 디코더, 7클래스 세맨틱 세그멘테이션).
- **대상 장애물 클래스**: 사람(4, `person`), 차량(5, `vehicle`), 기둥·표지판(6, `pole_sign`).
- **제외 클래스**: 도로(1, `road`), 인도(2, `sidewalk`), 횡단보도(3, `crosswalk`), 기타(0, `other`).
- **접점 추출 로직**:
  1. 대상 클래스에 대해 최소 20픽셀 이상의 연결 컴포넌트(Connected Component) 클러스터링.
  2. **지면 접지 지지(Ground Support) 검증**: 상위 35% 미만 배제뿐 아니라, 컴포넌트 최하단 접점 직하단(최대 4픽셀 깊이)에 보행 가능 지면 세맨틱 클래스(도로=1, 인도=2, 횡단보도=3)가 최소 3픽셀 이상 일관되게 존재하는지 검증합니다. 직하단에 지면 세맨틱이 없는 공중 매달린 표지판(hanging pole_sign), 신호등, 허공의 물체는 '지면 접점 미확인 (공중 객체)'으로 분류되어 거리 추정에서 제외됩니다 (`isFloatingUnsupported = true`).
  3. 컴포넌트의 최하단이 화면 경계(하단 2픽셀 이내)에 닿는 경우, 실제 발/지면 접점이 아닌 프레임 클리핑으로 판정하여 거리 계산에서 제외 (`isBottomClipped = true`).
  4. 최하단 3개 행의 수평 무게중심을 안정적인 접점 프록시($u, v$)로 산출.
  5. **보행 통로 엄격 적용**: 보행 통로($\pm 0.45\text{m}$) 내 장애물만 거리 측정 대상으로 채택하며, 경로 밖 장애물을 잘못 보고하여 보행자에게 혼선을 주지 않음.

---

## 5. 프로필 설정 및 로컬 프라이버시

- **클래스**: `BodyMountProfile(statureCm: Float, position: BodyMountPosition)`
- **저장소**: `BodyMountProfileStore`
- **저장 위치**: 앱 내부 전용 `SharedPreferences` (`aria_body_mount_profile`), 외부 네트워크 전송 없음.
- **프로필 변경 감지 및 무효화 주기**: 사용자가 UI에서 설정을 저장하거나 초기화하면 `BodyMountProfileStore`가 `SharedPreferences`와 `StateFlow`를 갱신합니다. `VisionPipelineController`는 카메라 프레임 분석 시점마다 `bodyMountProfileStore.profile.value`를 확인(다음 프레임 폴링 방식)하여 변경 감지 시 히스테리시스를 초기화(`estimator.reset()`)하고 새 프로필로 거리 산출을 진행합니다 (UI 저장 이벤트 즉시 능동 인터럽트가 아닌 프레임 주기 폴링 동기화 방식).

---

## 6. 한계 및 안전 주의사항

- **평평한 수평 지면 가정**: 계단, 내리막/오르막 경사로, 싱크홀, 연석 낙차 구간에서는 실제 거리와 오차가 발생할 수 있습니다.
- **접점 가림 현상**: 전방 장애물의 발이나 지면 접지부가 다른 물체나 풀숲, 턱에 가려진 경우 오차가 발생할 수 있습니다.
- **지면 세맨틱 지지 판정의 한계**: 공중에 매달린 표지판이나 돌출 간판은 직하단 지면 세맨틱 부재 검증으로 배제하지만, 원거리 배경 지면과 시각적으로 겹쳐 보이는 경우(배경 도로/인도 위에 겹쳐 렌더링된 돌출 표지판)나 지면 클래스 오분류 시 공중 물체의 거리가 바닥 평면으로 잘못 투영될 수 있습니다.
- **보행 안전 보증 불가**: 본 기능의 출력값은 센서 및 기하학적 모델에 기반한 **'추정 거리'**이며, 안전 인증된 계측기나 절대적인 보행 안전을 보장하지 않습니다.

## Android 기준 자료와 보정 범위

- [Android 센서 좌표계 및 화면 회전](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [SensorManager 좌표계 변환 API](https://developer.android.com/reference/android/hardware/SensorManager#remapCoordinateSystem(float[],%20int,%20int,%20float[]))
- [CameraCharacteristics 메타데이터](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics)

현재 구현은 Camera2에서 읽은 초점거리(mm), 센서 물리 크기, 활성 배열 비율과 실제 분석 프레임의 회전·크롭으로 광선을 근사한다. 광학 중심은 전체 영상 중앙으로 가정한다. 개별 렌즈의 주점·왜곡 계수·프레임별 초점 및 OIS/EIS 이동까지 보정한 계측 모델은 아니다. 제조사 사양만으로 실제 거리 정확도가 보장되지는 않는다. 보행 중 가속도 필터 지연, 착용 위치 오차와 객체 분할 오차가 남으며 실제 거리 표식과 비교한 현장 검증이 필요하다.

거리 표시의 원점은 카메라 렌즈의 지면 직하점이며 전방 방향은 카메라 광축의 수평 투영이다. 사용자의 발 위치나 실제 보행 방향을 추적하는 기능은 아니다. 기존 위험 경고 로직은 유지하며 새 추정 거리 숫자를 검증된 경고 임계값으로 자동 전환하지 않는다.

## 검증 결과 (2026-11-01, Pixel 3 / Android 12 API 31)

- `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`: 성공. JVM 281개, 실패 0개. Lint 오류 0개·경고 104개(기존 경고 포함).
- 실제 Pixel 3에 앱/테스트 APK 설치 후 `adb shell am instrument -w -r -e class ... com.ariateam.aria.test/androidx.test.runner.AndroidJUnitRunner` 실행.
- `BodyMountProfileUiTest`, `MainVisionInsetUiTest`, `SettingsScreenTest`, `VoiceDemoScreenTest` 합계 35개 중 최초 33개 통과. 새 inset 테스트 2개는 비병합 Box에서 자식 Text를 찾지 못하는 테스트 선택자 문제였으며, 선택자를 수정하고 `MainVisionInsetUiTest` 5개 전체 재실행하여 모두 통과했다. 나머지 30개는 앞선 실행에서 통과했고 이후 해당 제품 코드는 변경하지 않았다.
- 입력 검증·소수점·저장/복원/취소/초기화·키보드/큰 글꼴 스크롤·확대 프리뷰 크기·미설정 안내 클릭·숫자 표시/프레임 중단 시 만료·프로필 변경 무효화·데모 진입/홈 버튼 제거를 확인했다. 기기 테스트는 전용 합성 입력을 사용하므로 실제 거리 정확도의 증거는 아니다.
- 실제 앱 메인 화면에서 확대된 프리뷰, 키·착용 위치 미설정 안내, 약 8 FPS / 119ms 인식을 확인했다. 사용자 키를 임의 저장하지 않았다.
- 현장 거리 표식과 실제 보행 중 오차 비교는 미실시. 현재 카메라 앞 장면을 측량하거나 사용자 키/착용 위치를 확인할 수 없으므로 수치 정확도를 검증했다고 주장하지 않는다.
- 구현은 Orca 관리 Antigravity 작업자(Gemini 3.8 Flash High)가 작성했고, 코디네이터가 diff 검토·Gradle/기기 검증·Git 통합을 수행했다.
