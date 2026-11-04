# Field Settings Reorganization & Fixes (ARIA-FIELD-SETTINGS-020)

## Overview & Scope
- **Task ID**: `ARIA-FIELD-SETTINGS-020` (`task_bc9fe2809ce7`)
- **Dispatch ID**: `ctx_dc8103e8fb16`
- **Goal**: Reorganize settings screen into 5 top-level categories with exact Korean labels (`일반`, `계정`, `진단`, `데모`, `앱 정보`), migrate existing settings preserving values and API keys in Android Keystore, remove local conversation LLM (Gemma) UI dependencies, and make mount choices concise without explanatory subtitles.

## Reorganized Hierarchy & Contents

### 1. 일반 (General)
- **Menu Label**: `일반` (`R.string.settings_general_title`)
- **Summary**: `음성 호출, 피드백 안내, 키·착용 위치 설정` (`R.string.settings_general_summary`)
- **Consolidated Contents**:
  - **음성 및 알림**:
    - 호출어 대기 스위치 (`wake_setting`, "헤이 아리아" 서비스 요약, 음성 오류 상태 메시지)
    - 피드백 방식 토글 (`audible_feedback`, 진동+안내 소리 vs 진동 전용)
    - TTS 지원 안내
    - 백그라운드 명령 알림 설정 바로가기 (`command_notification_settings`)
  - **신체 장착 기준**:
    - 키·착용 위치 설정 (`body_mount_profile_title`, e.g. "170cm · 어깨 (추정 렌즈 높이 약 137cm)" 또는 "미설정 · 탭하여 설정")
    - 탭 시 신체 장착 설정 다이얼로그(`BodyMountProfileDialog`) 진입

### 2. 계정 (Account)
- **Menu Label**: `계정` (`R.string.settings_account_title`)
- **Summary**: `Gemini API 키 등록됨 (***)` 또는 `Gemini API 키 미설정` (`R.string.settings_cloud_summary_registered` / `settings_cloud_summary_empty`)
- **Consolidated Contents**:
  - **Gemini 질문 설정**:
    - 기본 모델: Gemini 2.5 Flash
    - API 키 상태 표시 (마스킹된 키 또는 미등록 안내)
    - API 키 등록 / 변경 / 삭제 액션 (Android Keystore 암호화 저장소 `AriaKeyStorage` 연동, 기존 저장 키 보존)
    - 보안 및 개인정보 처리방침 안내
  - **온디바이스 AI 컴포넌트**:
    - 한국어 음성 인식: 온디바이스 (Sherpa-ONNX) · 오프라인 동작
    - 보행 안전 비전: 온디바이스 (LiteRT) · 오프라인 동작
  - **로컬 대화 모델(Gemma) 종속성 제거**:
    - 이전의 2.6GB Gemma 모델 다운로드/취소/삭제 UI 제거
    - 대화 및 복합 질문은 안전한 Gemini API 키 기반 계정 관리로 전환

### 3. 진단 (Diagnostics)
- **Menu Label**: `진단` (`R.string.settings_diagnostics_title`)
- **Summary**: `카메라·비전 및 음성 진단, 권한 관리` (`R.string.settings_diagnostics_summary`)
- **Consolidated Contents**:
  - 카메라 · 비전 진단 (`debug_open_vision`)
  - 음성 컴포넌트 진단 (`debug_open_voice`)
  - 시스템 앱 설정 (`open_app_settings`, 카메라 및 마이크 권한 관리)
  - **중복 시연 진입로 제거**: 기존 진단 화면에 포함되었던 통합 시연 데모는 데모 페이지로 일원화되어 진단에서 완전 제거됨.

### 4. 데모 (Demo)
- **Menu Label**: `데모` (`R.string.settings_demo_page_title`)
- **Summary**: `통합 시연 데모 및 AR 거리 측정` (`R.string.settings_demo_page_summary`)
- **Consolidated Contents**:
  - 통합 시연 데모 (`debug_open_demo`)
  - AR 거리 측정 (`settings_demo_ar_title`)
  - 평평한 지면 가정 및 안전 면책 조항 (`demoSafetyDisclaimer`)
  - **일반 설정 분리**: 기존 데모 페이지에 임시로 위치했던 키·착용 위치 설정은 일반(General) 페이지로 이전됨.

### 5. 앱 정보 (App info)
- **Menu Label**: `앱 정보` (`R.string.app_info`)
- **Summary**: `Aria App v1.0` (`R.string.app_version`)
- **Consolidated Contents**:
  - 앱 버전 정보 (`app_version`)
  - 오픈소스 라이선스 뷰어 (`licenses_title`)

## Body Mount Position UI Improvements
- **Concise Choices**: 착용 위치 선택 카드가 오직 **`어깨`** 와 **`가슴`** 만 명확히 표시하도록 개선.
- **Subtitles Removed**: 기존의 `body_mount_position_shoulder_desc` 및 `body_mount_position_chest_desc` 설명 부제목을 카드 UI에서 제거하여 간결하고 직관적인 라디오 선택 제공.

## Version & Build Updates
- `app/build.gradle.kts`:
  - `versionCode = 3`
  - `versionName = "1.0.0-preview.20261104.1"`

## Regression & Validation Test Suite
- **JVM Unit Test**:
  - `com.ariateam.aria.ui.screen.SettingsPolicyTest`:
    - `exactMenuOrderingAndKoreanLabels`: 5개 카테고리 순서 및 한국어 라벨 검증
    - `generalConsolidatesStatureMountAndOrdinarySettings`: 일반 설정 내 착용 위치 및 일반 옵션 통합 검증
    - `diagnosticsOnlyContainsDeveloperStateAndProbes`: 개발자 진단 전용 및 시연 중복 배제 검증
    - `demoUnifiesPresentationAndArDistanceAndHasNoOrdinarySettingsOrDiagnostics`: 데모 통합 진입로 및 일반/진단 배제 검증
    - `accountReplacesLocalLlmManagementAndScopeIsGeminiOnly`: Gemini 단일 공급자 및 로컬 LLM UI 제거/온디바이스 ASR·비전 보존 검증
    - `mountChoicesAreConciseWithoutExplanatorySubtitles`: 어깨/가슴 단독 선택 및 부제목 배제 검증
- **Instrumented Test Updates (Compile-only for AndroidTest)**:
  - `com.ariateam.aria.ui.screen.SettingsScreenTest`:
    - 신규 5개 메뉴 렌더링 및 네비게이션 검증
    - 계정 페이지 API 키 등록/삭제 및 온디바이스 모델 정보 검증
    - 진단 페이지 내 개발자 도구 한정 및 시연 배제 검증
    - 데모 페이지 통합 시연 및 착용 위치 배제 검증
    - 일반 페이지 내 신체 장착 설정 진입 검증
  - `com.ariateam.aria.distance.BodyMountProfileUiTest`:
    - `mountChoicesAreConciseWithoutExplanatorySubtitles`: 어깨/가슴 표시 및 설명 부제목 부존재 검증
