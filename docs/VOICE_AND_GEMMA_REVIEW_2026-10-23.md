# Aria 호출어 인식·Gemma 4 E2B 점검 보고서

작성일: 2026-10-23 (KST)

대상: 현재 작업 사본과 연결된 Pixel 3 / Android 12(API 31)
사용자 증상: 같은 발음으로 재시도해도 호출어가 간헐적으로 감지되지 않음.

## 1. 핵심 판단

- 발음 문제만으로 설명해서는 안 된다. 호출어 감지 후 쿨다운에 걸릴 때 **실제 감지기는 멈췄지만 화면은 대기 중인 상태**가 되는 로직을 단위 재현으로 확인했다.
- 정상 호출어 경로는 짧은 Android 받아쓰기 세션을 계속 재시작하는 방식이 아니라 **Sherpa KWS의 연속 오디오 처리**다. Android 인식기를 반복하는 경로는 KWS 초기화·실행 실패 후의 폴백이다.
- 실제 기기 점검 시점에는 Aria 마이크가 활성화되어 있고 OS에 의해 음소거되지 않았다. 따라서 위 로직 결함이 사용자의 모든 미인식을 설명한다고 단정할 수 없다.
- Gemma는 호출어 감지기가 아니라 **받아쓰기 완료 후의 명령 해석기**다. 현재 기기에는 모델 파일이 없으며, 이번 점검에서 Gemma 실추론 성능을 측정한 것은 아니다.

## 2. 확인 방법과 기기 증거

| 항목 | 확인 결과 | 해석 한계 |
|---|---|---|
| 마이크 권한 | `RECORD_AUDIO: granted=true` | 권한 허용이 음향 인식 성공을 보장하지 않음 |
| 서비스 | `VoiceService`, `:wakeword`, 마이크 전경 서비스 활성 | Android 서비스 상태와 KWS 내부 진행 상태는 다름 |
| 오디오 | `RecordActivityMonitor`: active=true, silenced=false, VOICE_RECOGNITION, 16kHz/모노/PCM16 | 05:13 KST 스냅샷이며 과거 미인식 순간의 상태는 아님 |
| KWS 프로세스 메모리 | PSS 190,451KB(약 186MiB), SwapPss 48,488KB | 순간값이며 최고 사용량·모델 단독 메모리가 아님 |
| KWS CPU | `top` 단일 스냅샷 78.5% | 대략 한 코어의 0.785배; 배터리·실시간 처리율을 측정한 값이 아님 |
| 기기 메모리 | MemTotal 3,665,332kB, MemAvailable 약 1,388,032kB | 시점·다른 앱에 따라 변함 |
| Gemma 파일 | 앱 외부 `files/Download` 디렉터리 비어 있음 | 현재 설치 상태만 확인 |
| 키워드 정의 | 번들 네 줄의 모든 토큰이 `tokens.txt`에 존재 | 최적 BPE 분할·실제 음향 정확도를 증명하지 않음 |

기기 원본 음성은 수집하지 않았고, 사람·거리·소음별 발화 데이터셋도 사용하지 않았다. 그러므로 호출어 성공률, 미검출률, 오탐/시간 수치와 Gemma의 Pixel 3 응답속도는 제시하지 않는다.

## 3. 호출어 로직 분석

### 3.1 정상 경로와 청취 공백

```text
연속 KWS 대기 → 호출어 감지 → KWS 마이크 해제
→ Android 명령 받아쓰기 → 확정/실패
→ 필요한 명령 처리·TTS → KWS 재시작
```

`SherpaWakeWordDetector`는 640샘플(40ms)씩 읽어 특징을 넣고 준비된 청크를 디코딩한다. 호출어 감지 시 오디오를 중지·해제한 뒤 콜백을 보낸다. 명령 입력/TTS 동안 KWS가 항상 동시에 듣는 구조는 아니다. 감지 직후 명령을 너무 붙여 말하면 Android 인식 준비 전 앞부분이 사라질 가능성도 있지만, 그 지연은 이번에 실측하지 않았다.

### 3.2 재현된 쿨다운 결함

기존 `VoiceListeningSession.onWakeWordDetected`는 마지막 감지로부터 1,500ms 미만이면 즉시 반환했다. 그런데 호출한 실제 감지기는 이미 마이크를 해제한 **1회 감지 방식**이다. 따라서 빠른 명령 종료 후 재대기 중에 다시 감지되어 쿨다운에 걸리면, 실제 청취가 중지된 채 `Waiting`이 유지될 수 있다.

기존 테스트의 가짜 감지기는 호출 후에도 리스너를 유지한다. 이후 시간을 넘겨 다시 콜백을 보내므로 실제 감지기와 달라 위 결함을 가렸다.

임시 `WakeWordDiagnosticProbeTest`는 감지 직전 리스너를 없애는 1회 감지기를 사용했다. 다음 명령이 성공했다.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.ariateam.aria.speech.WakeWordDiagnosticProbeTest
```

결과: `BUILD SUCCESSFUL`(22초). 이 테스트의 성공 조건은 **결함 상태가 실제로 재현됨**이며, 수정 후 정상 동작을 증명하는 테스트와 구분한다. 임시 테스트는 증거 확인 후 제거했다.

수정은 쿨다운 분기에 `stopWakeDetector()`와 남은 시간의 `scheduleAttempt(...)`를 추가하는 것으로 한정했다. 감지를 중복 실행하지 않으면서 `Reconnecting`으로 전환하고, 쿨다운 종료 후 새 감지기를 시작한다. 기존 세대 검사를 사용하므로 중지·TTS가 개입하면 예약을 취소한다. 영구 테스트는 1회 감지기를 반영하고 경계 시각의 재청취, 다음 호출 수락, 중지 취소, TTS 이후 복귀를 확인한다. 세부 근거는 [호출어 진단 기록](WAKEWORD_DIAGNOSTIC_FINDINGS_2026-10-23.md)에 있다.

### 3.3 추가 검토 항목

- 오디오 읽기와 추론이 같은 실행 루프에 있다. 디코딩이 실시간보다 느리면 지연이나 버퍼 초과 가능성이 있다. 현재 CPU 값만으로 버퍼 유실을 입증할 수는 없다. 처리 샘플 수, 오디오 타임스탬프, 추론 지연 계측이 필요하다.
- `read < 0`에서 단순 종료하는 분기가 있다. 실제 Android 구현의 일반적인 오류는 예외로 전달되고, 음수 종료는 정상 stop 때도 발생한다. 이것을 현장 원인으로 확정하지 않는다.
- TTS 중 호출어 억제는 자기 목소리로 오작동하지 않게 하는 의도된 정책이다. 안내가 끝나기 전의 발화는 감지 대상에서 빠질 수 있다.
- 점수 1.5와 임계값 0.25를 무작정 완화하면 오탐도 늘 수 있다. 공식 API도 임계값이 높을수록 보수적이라고 설명한다. 먼저 청취 상태 복구를 보장한 뒤 긍정/부정 발화로 튜닝해야 한다. [Sherpa 공식 API](https://k2-fsa.github.io/sherpa/onnx/c-api/html/structSherpaOnnxKeywordSpotterConfig.html)

## 4. Gemma 4 E2B 점검

### 4.1 앱 연결 상태

| 항목 | 현재 구현 |
|---|---|
| 런타임 | `litertlm-android:0.16.1` |
| 모델 | `litert-community/gemma-4-E2B-it-litert-lm` |
| 고정 revision | `6e5c4f1e395deb959c494953478fa5cec4b8008f` |
| 파일 크기 | 2,588,147,712바이트(약 2.59GB / 2.41GiB) |
| SHA-256 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| 실행 프로세스 | 독립 `:language` 프로세스 |
| 백엔드 | CPU, 가용 코어 수를 2~4스레드로 제한 |
| 컨텍스트/출력 | 컨텍스트 2,048토큰, 출력 최대 32토큰, thinking 비활성화 |
| 제한시간 | 준비 60초, 첫 해석 30초, 이후 해석 20초 |
| 요청 경로 | 규칙 기반 명령 해석 우선, 미지원 표현이면 설치·준비된 모델에 요청 |

고정 revision의 Hugging Face API에서 파일 크기와 LFS SHA-256을 조회해 앱 상수와 일치함을 확인했다. 이는 배포 메타데이터 대조이며 **기기에 모델을 내려받아 전체 바이트 해시를 확인한 결과는 아니다**. [고정 revision 메타데이터](https://huggingface.co/api/models/litert-community/gemma-4-E2B-it-litert-lm/revision/6e5c4f1e395deb959c494953478fa5cec4b8008f?blobs=true)

현재 사용하는 CPU 엔진 생성·백그라운드 초기화 방식은 해당 버전의 API와 맞는다. 모델이 없으면 규칙 기반 명령을 사용할 수 있고, 임의 표현의 모델 해석은 수행되지 않는다. [LiteRT-LM 0.16.1 Engine 소스](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.16.1/kotlin/java/com/google/ai/edge/litertlm/Engine.kt)

### 4.2 성능과 Pixel 3 판단

공식 고정 모델 카드의 S26 Ultra CPU 기준은 prefill 557토큰/초, decode 46.9토큰/초, 첫 토큰 1.8초, CPU 메모리 1,733MB다. 조건은 1,024 입력 토큰·256 출력 토큰·2,048 컨텍스트·CPU 4스레드이며 캐시가 초기화된 상태다. **첫 토큰 시간에 모델 로딩은 포함되지 않는다.** 이 수치를 Pixel 3의 성능으로 환산할 수 없다. [고정 모델 카드](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/6e5c4f1e395deb959c494953478fa5cec4b8008f/README.md)

Pixel 3는 OS에서 약 3.50GiB 메모리를 보고하고 KWS와 비전 기능도 함께 동작한다. 따라서 언어 모델 동시 상주 시 메모리 회수·스왑·CPU 경쟁을 실측해야 한다. 파일 크기 2.59GB가 그대로 상주 RAM 크기라는 뜻은 아니다. 현재 기기에 모델이 없어 준비 성공 여부·명령 정확도·cold/warm 지연은 **미측정**으로 남긴다.

기존 `ModelCommandValidationTest.realGemmaColdPrepareAndShortInferenceProbe`는 opt-in 실추론 검증을 제공한다. 검증된 모델을 준비한 뒤 `runRealGemmaProbe=true`로 실행하면 준비, “소리가 좀 거슬려”→SoundOff, “설정 화면 보여줘”→Settings의 cold/warm 결과를 확인할 수 있다. 모델이 없는 상태에서 기본 테스트 통과만으로 Gemma가 검증되었다고 판단하면 안 된다.

## 5. 추가 요청: 실시간 받아쓰기 영역

사용자 후속 피드백에 따라 표시 방식을 앱 공통 하단의 텍스트 전용 오버레이로 조정했다. 메인·설정의 플로팅 바 바로 위에 실제 인식된 텍스트만 표시하고, 바가 숨겨진 하위 화면에서는 하단 안전 영역 위에 표시한다. 카드·제목·상태 점·청취 안내·빈 입력 플레이스홀더는 사용하지 않는다. 받아쓰기 종료·취소·오류·대기 복귀 때 텍스트를 없애며 호출어 대기나 TTS 중에는 이전 문장을 표시하지 않는다. 음성 진단 화면의 중복 텍스트 카드도 제거했고 수동 받아쓰기 조작은 유지했다.

이는 사용자에게 “호출어 대기 / 명령 받아쓰기 / 받아쓰기 종료”의 구분을 보여주는 개선이며 호출어 모델 자체의 정확도 개선과는 별개다.

## 6. 최종 변경·검증 결과

| 변경/검증 | 결과 |
|---|---|
| 쿨다운 재청취 누락 | 유효한 1회 감지가 거절되면 남은 쿨다운 이후 재청취 예약. 중지·TTS 취소와 경계 시각 수락 회귀 테스트 추가 |
| 실시간 받아쓰기 UI | `Dictating`이며 초안이 비어 있지 않을 때 공통 하단에 텍스트만 표시. 긴 문장의 최신 끝으로 자동 스크롤, 종료 시 제거 |
| `:app:assembleDebug` | 통과 |
| `:app:testDebugUnitTest` | 186개, 실패 0개 |
| `:app:lintDebug` | 통과, 오류 0개·경고 75개 |
| UI 패키지 기기 테스트 | 텍스트 전용 공통 표시 변경 후 Pixel 3 / Android 12에서 37개, 실패 0개 |
| `:app:installDebug` | Pixel 3에 최신 APK 설치 완료 |
| `git diff --check` | 통과 |

빌드·단위 테스트·Lint는 다음 명령으로 독립 실행했다.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.package=com.ariateam.aria.ui.screen'
```

기기 UI 테스트는 잠금 해제와 화면 켜짐을 확인한 뒤 실행했다. USB 연결 중 화면 유지 설정은 시험 동안만 적용하고 원래 값(0)으로 복구했다. 이번 수정 검증에서는 무관한 비전 성능 벤치마크를 다시 실행하지 않았다. UI 테스트는 상태를 주입해 표시·변경·제거·레이아웃을 확인한 것이며 실제 사람이 말한 내용의 인식 정확도를 검증한 것은 아니다.

작업 추적: Orca Run `run_0b7a95f15ff6`. Gemini UI 시작 시도는 `agent_readiness` 시간 초과로 작업 주입 전에 실패하여 런타임 복구 지시에 따라 해제했다. 이후 정상적으로 허용된 Copilot 작업자로 UI·진단·최소 수정을 분리 수행했으며, 관측된 Copilot 자동 선택 모델은 `gpt-6-luna`였다. 이는 Gemini 할당량 소진에 따른 Codex/Luna 전환이 아니다. 코디네이터가 변경 검토와 위 검증을 수행했고 회수 대기 작업자는 0개다.

## 7. 후속 실측 제안

1. 동일 화자가 같은 거리에서 두 호출어를 각각 20회 말하고, 실패 시점·화면 상태·TTS 여부·재청취 상태를 함께 기록한다. 조용한 환경과 실제 사용 환경은 따로 집계한다.
2. 빠른 재호출과 충분한 간격의 재호출을 나눠 비교한다. 마이크 활성화 여부, KWS 감지 시각, 쿨다운 거절, Android `ready()` 시각을 함께 기록해야 인식 실패와 핸드오프 실패를 구분할 수 있다.
3. 일반 대화·영상 소리와 무음 구간을 포함해 오탐 횟수도 기록한다. 긍정 발화 성공률만으로 임계값을 결정하지 않는다.
4. Gemma를 실측할 때는 검증된 모델의 초기 로딩·첫 명령·후속 명령을 따로 측정한다. KWS만 실행한 경우와 KWS·비전·Gemma가 함께 실행한 경우를 비교하고, 메모리 최고값과 언어 프로세스 종료 여부도 기록한다.

위 횟수는 앞으로 수행할 시험 설계의 예이며, 이번에 수행한 측정값이 아니다.
