# Official ChatGPT Login & Responses Integration (2026-11-08)

## 1. 개요 및 변경 목적

본 문서는 Aria 음성 안내 시스템에서 기존 레거시 API 키 및 사설 백엔드 경로를 완전히 배제하고, 공식 ChatGPT OAuth 인증(`cloud.auth.ChatGptAuthRepository`)과 공식 Responses 스트리밍 추론(`cloud.chatgpt.ChatGptClient`)을 기기 내부의 로컬 음성 명령 우선 파이프라인 및 TTS 음성 피드백 흐름에 완전히 통합한 내용을 정리합니다.

## 2. 통합 아키텍처 및 핵심 변경 사항

### 2.1 MainScreen 통합
- **경로**: `app/src/main/java/com/ariateam/aria/ui/screen/MainScreen.kt`
- 레거시 `AriaKeyStorage` 생성 및 의존성을 완전히 제거하였습니다.
- `DefaultChatGptAuthRepository.getInstance(context)` 싱글톤 저장소와 `ChatGptClient`를 기반으로 `CloudQuestionCoordinator`를 초기화합니다.
- `onAuthInvalidated = { qId -> model.cancelCloudQuestion(qId) }` 콜백을 등록하여, 인증 무효화 또는 로그아웃 시 진행 중이던 질문에 대해 보이스 뷰모델/명령 코디네이터가 불필요하게 60초 타임아웃까지 대기하지 않고 즉시 정리되도록 연결하였습니다.
- `SettingsScreen`에 활성 인증 리포지토리(`authRepository`)를 주입하여 UI 계정 관리와 질의 파이프라인이 단일 세션 상태를 공유하도록 구성하였습니다.

### 2.2 CloudQuestionCoordinator 인증, 예외 격리 및 세션 펜스
- **경로**: `app/src/main/java/com/ariateam/aria/cloud/CloudQuestionCoordinator.kt`
- **사전 인증 검증**: 텍스트 질의 디스패치 및 사진 촬영 전에 IO 코루틴에서 `authRepository.validAccessToken()`을 먼저 획득합니다. 미인증 시 카메라 프레임 캡처는 일절 수행되지 않습니다.
- **예외 안전성 및 시크릿 토큰 누출 차단**:
  - 텍스트 및 사진 질문의 토큰 획득 코루틴 내에서 `authRepository.validAccessToken()` 호출을 try/catch로 보호합니다.
  - 로그 출력 시 예외 메시지(`t.message`) 대신 클래스 명칭(`t.javaClass.simpleName`)만 기록하여, 예외 문자열에 포함될 수 있는 Bearer 토큰 및 Authorization 헤더 누출을 원천 방지하였습니다.
  - `CancellationException`은 정상 취소 흐름을 위해 즉시 rethrow하고, 네트워크/인증 실패 등 비취소 예외는 정제된 한국어 오류(`"로그인 상태를 확인할 수 없어요. 네트워크 연결을 확인해 주세요."`)로 보고하며 요청 및 프레임 상태를 안전하게 정리합니다.
- **초기 미인증 수집기 레이스 방어 (Unauth Collector Race Fence)**:
  - `ActiveRequest`에 `startedWithAuth` 플래그를 추가하여, 미인증 상태에서 시작된 질의가 초기 `sessionState` Flow 수집에 의해 캔슬되는 경쟁 상태를 차단하였습니다.
  - 미인증 시 질문을 요청한 경우 침묵이나 타임아웃 없이 결정론적으로 로그인 안내 메시지(`"ChatGPT 로그인이 필요해요. 설정의 계정 메뉴에서 로그인해 주세요."`)를 전달합니다.
- **세션 세대(Session Generation) 실시간 감시 및 무효화 콜백**:
  - `authRepository.sessionState` Flow를 수집하여 로그아웃(`signOut`) 또는 계정 전환(`switchAccount`) 발생 시 즉시 활성 질의를 취소(`cancelActiveRequestLocked`)하고 연결을 강제 단절합니다.
  - 취소 직후 락 외부에서 등록된 `onAuthInvalidated(qId)` 콜백을 호출하여 상위 음성 코디네이터에 질의 취소를 통지합니다.
  - 토큰 획득 후, 추론 직전, 콜백 전달 직전의 모든 단계에서 요청 발급 시점의 `sessionGeneration` 일치 여부를 검증하여 이전 세션의 응답이나 오류가 현재 TTS로 누출되는 것을 방지합니다.
- **취소 및 리소스 해제**: 취소 시 `GeminiCancellationToken`을 통해 활성 `HttpURLConnection.disconnect()`가 즉시 실행되며, 비트맵 프레임은 try-finally에서 즉시 `recycle()`됩니다.

### 2.3 로컬 명령 우선 해석 및 CloudCommandParser 개선
- **경로**: `app/src/main/java/com/ariateam/aria/cloud/CloudCommandParser.kt`
- **ChatGPT 별칭 지원**: `"챗지피티"`, `"ChatGPT"`, `"챗gpt"` 등 명시적 별칭 및 `"질문 ..."` 패턴을 완벽히 인식합니다.
- **한국어 어휘 보존 (Anchored Prefix Matching)**:
  - 전역 문자열 치환 대신 앵커 기반 정규식 매칭을 적용하여 `"해양 생태계"`, `"해외 여행"` 등 "해"를 포함하는 정상 한국어 어휘와 질의 본문이 훼손되지 않고 보존됩니다.
- **비지원 제공자(Gemini, Claude) 명시적 요청 차단**:
  - `"제미나이로 질문해줘"`, `"제미나이에게 사진 보내줘"`, `"클로드한테 물어봐줘"` 등 비지원 모델을 지정하는 텍스트/사진 명령은 ChatGPT로 임의 라우팅하지 않고 `"현재는 공식 ChatGPT 로그인만 지원해요. ChatGPT로 질문해 주세요."`로 명확히 안내합니다.
  - 단, `"제미나이가 뭐야?"`, `"ChatGPT의 특징은 뭐야?"` 등 제공자 명칭이 질문의 주제로 단순히 언급된 일반 대화 질문은 정상적인 질의응답으로 보존됩니다.
- **로컬 명령 및 안전 명령 우선권**:
  - 취소, 중지, 정면 보정, 소리 제어 등 기기 제어 명령이 최우선 처리되며, 부정어(`"하지마"`, `"찍지마"`, `"보지마"`) 및 복합 명령(`"그리고"`)은 클라우드 전송 전에 안전하게 재확인(Clarify)됩니다.
- **TTS 및 음성 안전**:
  - 어시스턴트의 답변 텍스트는 순수 음성 발화(`SpeechPriority.CloudResponse`)로만 처리되며, 앱 제어 명령으로 재실행되지 않습니다. 보행 위험 경고(`HazardWarning`) 발생 시 어시스턴트 발화는 즉시 선점 및 중단됩니다.

### 2.4 ChatGptAuthRepository 루프백 리스너 세대 불일치 해결
- **경로**: `app/src/main/java/com/ariateam/aria/cloud/auth/ChatGptAuthRepository.kt`
- `cancelLogin()`, `signOut()`, `switchAccount()` 호출 시 새 세대 번호(`newGen`) 대신 상태 락 하에서 캡처한 이전 활성 세대 번호(`oldGen`)와 자격 없는 무조건 중지(`loopbackServer.stop()`)를 함께 호출하여, 실제 로컬 HTTP 서버 소켓이 즉각 해제되고 리스너 누수가 발생하지 않도록 조치하였습니다.

### 2.5 CredentialStorage 커밋 실패 방어 & 인메모리 거부 집합
- **경로**: `app/src/main/java/com/ariateam/aria/cloud/auth/ChatGptCredentialStorage.kt`
- `markRevoked` 시 디스크 `commit()` 실패 시를 대비하여 즉시 인메모리 거부 집합(`inMemoryRevokedClients`)에 추가하고 마스터 KeyStore 엔트리를 삭제(`invalidateKeyStoreAlias`)하여 페일클로즈를 달성하였습니다.
- `getExtAgentHostId` 시 SharedPreferences 디스크 커밋 실패 시 `IllegalStateException`을 발생시켜 초기화되지 않은 호스트 ID로 로그인이 진행되는 것을 차단하였습니다.

### 2.6 ChatGptClient 모델 카탈로그 계정 격리 & SSE 파서 엄격화
- **경로**: `app/src/main/java/com/ariateam/aria/cloud/chatgpt/ChatGptClient.kt`, `ChatGptSseParser.kt`
- 인스턴스 전역 캐시를 제거하고, 비로그성 SHA-256 토큰 핑거프린트 기반 계정별 격리 캐시(`catalogCache: Map<Fingerprint, List<ChatGptModelInfo>>`)를 구축하였습니다.
- 서로 다른 토큰(Token A, Token B) 간 모델 카탈로그 재사용을 원천 방지하고, 명시적 모델 요청 시 해당 계정 카탈로그에 실제 존재하는 모델인지 엄격히 검증합니다.
- `ChatGptSseParser`에 strict JSON 검증기를 적용하여 따옴표가 누락된 비표준 키 등 손상된 이벤트 페이로드가 즉시 `Interrupted`로 안전하게 닫히도록 하였습니다.

### 2.7 설정 UI 및 리소스 문구 정리
- **경로**: `app/src/main/res/values/settings_strings.xml`, `app/src/main/java/com/ariateam/aria/ui/screen/ChatGptAccountSettings.kt`
- `settings_account_group` 및 하위 요약 문구를 기존 "Gemini 계정"에서 "ChatGPT 계정"으로 공식 갱신하였습니다.
- `ChatGptAccountSettings.kt`에서 `context.getString(...)` 호출을 `stringResource` 사전 할당 값(`defaultActionErrorMessage`)으로 전환하여 Android Lint Compose UI 검사를 무결하게 통과시켰습니다.

## 3. 검증 및 테스트 결과

| 검증 항목 | 실행 명령 | 결과 | 상세 내용 |
|---|---|---|---|
| JVM 단위 테스트 | `./gradlew.bat :app:testDebugUnitTest` | **SUCCESS** (14s) | 907개 단위 테스트 전체 통과 (0 failed), throwing-auth, secret-redaction, auth-invalidation, real server socket close, commit-failure 테스트 포함 |
| Debug APK 빌드 | `./gradlew.bat :app:assembleDebug` | **SUCCESS** (1m 1s) | 디버그 패키징 및 덱싱 정상 완료 |
| Android Lint 검증 | `./gradlew.bat :app:lintDebug` | **SUCCESS** (2m 14s) | 0 errors, 모든 Compose UI 및 리소스 무결성 통과 |
| 기기 테스트 컴파일 | `./gradlew.bat :app:assembleDebugAndroidTest` | **SUCCESS** (28s) | 계정 선택기 재인증 및 CloudQuestionDeviceTest 컴파일 무결성 검증 |
| 코드 포맷/마커 | `git diff --check` | **SUCCESS** (exit 0) | 공백 오류 및 병합 충돌 마커 없음 |

## Coordinator independent acceptance
Final four targets assembleDebug/testDebugUnitTest/lintDebug/assembleDebugAndroidTest: exit 0. Log C:/Users/felice/AppData/Local/Temp/aria-official-independent-final.log (up-to-date outputs reused). Independently forced full JVM rerun: gradlew.bat :app:testDebugUnitTest --rerun-tasks exit 0, 906 tests, 0 failures; log C:/Users/felice/AppData/Local/Temp/aria-official-independent-tests.log. git diff --check exit0. Active UI/coordinator contains no legacy getApiKey/GeminiClient path. All workers settled/released, reclaimable list empty. Real Android browser login, encrypted Keystore persistence and live authenticated inference/TTS have not been device-verified; no installation, commit or push performed. Public GitHub sources were evaluated as protocol references; official noncommercial DevKit source/assets were not imported.
