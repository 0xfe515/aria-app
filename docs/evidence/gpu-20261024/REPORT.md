# GPU 부분 위임 재현 근거 — 2026-10-24

발표 문서: [개발 회고](../../PRESENTATION_DEVELOPMENT_RETROSPECTIVE.md#6-비전-gpu-최적화-파트)

## 실행 조건

- 측정 시작: 2026-10-24 21:06:52 KST. 첨부 PNG·JSON·로그는 같은 실행의 산출물이다.
- 실제 기기: Google Pixel 3 / Android 12(API 31) / Snapdragon 845 / Adreno 630.
- LiteRT 및 GPU Delegate 1.4.2.
- 동일 입력: Java `Random(42L)`로 생성한 `[1,288,512,3]` FLOAT32 RGB 합성 텐서. 실제 카메라 입력이 아니다.
- 동일 GPU 설정: `precisionLossAllowed=false`, `INFERENCE_PREFERENCE_SUSTAINED_SPEED`.
- 원본→변환 순서로 각각 워밍업 1회, 측정 5회. 표의 지연은 `Interpreter.run`만 포함한다. 로딩·커널 준비·카메라·후처리 시간은 제외한다.
- CPU 비교 기준: 각 모델의 XNNPACK 4스레드 실행. 정답 레이블이 아니다.

## 원본 증거

| Before | After |
|---|---|
| ![원본 부분 GPU 위임](before_gpu_partial_delegation.png) | ![변환 모델 단일 GPU 파티션](after_gpu_full_delegation.png) |

두 이미지는 실제 기기의 `UiAutomation.takeScreenshot()` 원본 PNG(1080×2160)다. 합성 이미지를 모델에 입력해 얻은 실행 결과를 디버그 Activity에 표시한 화면이며, 발표용 이미지 합성이나 수치 덧씌우기를 하지 않았다. `1w+5s`는 워밍업 1회와 측정 5회를 뜻한다. 위임 노드·파티션 수는 현재 실행의 `Replacing` 로그를 파싱한 값이다. 화면의 원인 설명은 기존 그래프 분석에 근거한 설명문이다.

- [측정 JSON 및 전체 해시](gpu-evidence-20261024.json)
- [Before 원시 LiteRT 로그](raw_logcat_before.txt)
- [After 원시 LiteRT 로그](raw_logcat_after.txt)

## 측정 결과

| 항목 | Before | After |
|---|---|---|
| 모델 | `walkable_seg_aria5.tflite` | `walkable_seg_aria5_unfused.tflite` |
| 크기 | 10,837,912B | 10,839,936B |
| SHA-256 | `3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7` | `84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847` |
| GPU 위임 | 64/204 | 210/210 |
| CPU 폴백 | 140 | 0 |
| 파티션 | 3 | 1 |
| GPU p50 | **859.21ms** | **91.10ms** |
| GPU 측정 5회(ms) | 859.21, 871.51, 862.46, 856.28, 858.52 | 91.10, 92.60, 90.40, 92.32, 90.45 |
| CPU 4T p50 | 893.79ms | 867.00ms |
| CPU 기준 argmax 일치율 | 100.00% | 100.00% |
| CPU 기준 maxDiff(6자리 반올림) | 0.000009 | 0.000009 |
| CPU 기준 MAE(6자리 반올림) | 0.000000 | 0.000000 |
| Android thermal status | 0→0 | 0→0 |
| 배터리 온도 | 29.8→29.8°C | 29.6→29.6°C |

이번 실행의 GPU p50 비율은 **9.43배**다. 단일 합성 입력의 짧은 반복 시험이므로 실제 장면 정확도, 모든 입력의 수학적 동일성, 장시간 고정 속도를 입증하지 않는다. MAE 표기 0은 반올림 결과이며 정확히 오차가 없다는 뜻이 아니다. Thermal status 0은 관측된 Android 상태이며 모든 종류의 클록 제한이 없었다는 증거는 아니다.

## 로그가 보여주는 차이

```text
BEFORE:
Replacing 64 out of 204 node(s) with delegate (TfLiteGpuDelegateV2) node, yielding 3 partitions
Replacing 140 out of 141 node(s) with delegate (TfLiteXNNPackDelegate) node, yielding 3 partitions

AFTER:
Replacing 210 out of 210 node(s) with delegate (TfLiteGpuDelegateV2) node, yielding 1 partitions
Created 1 GPU delegate kernels.
```

GPU와 XNNPACK 로그의 분모는 각 위임 단계의 그래프를 가리키므로 서로 직접 합산하지 않는다. 이번 변환 모델 관측은 210/210이며, 이전 문서의 209/209 기록과 구분한다.

원인 분석은 [기존 모델 분석](../../VISION_MODEL.md)과 [변환 도구](../../../tools/vision/unfuse_transpose_conv.py)에 근거한다. 원본의 fused ReLU를 가진 `TRANSPOSE_CONV` v4를 v3 + 별도 ReLU로 분리하고, 첫 디코더는 `VALID + SLICE`로 패딩을 보정했다. 이번 로그는 부분 위임과 전체 위임을 직접 재현한 증거다. 메모리 복사·동기화 비용을 별도 프로파일러로 분해 측정한 것은 아니다.

## 재현 방법

진단 Activity는 `app/src/debug/`에만 선언되어 있고 일반 화면 흐름에는 추가하지 않았다. 테스트는 번들 모델을 캐시에 복사해 실행하므로 설치된 사용자 모델을 교체하지 않는다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ariateam.aria.vision.GpuPresentationEvidenceTest#capturePresentationEvidenceBeforeAndAfter'
```

테스트는 앱 내부 `files/gpu_evidence_20261024/`와 `/sdcard/Download/gpu_evidence_20261024/`에 결과를 저장한다. 공용 폴더 쓰기에는 테스트의 shell 명령 경로를 사용한다. 연결 기기에서 다음과 같이 별도 로컬 폴더로 가져올 수 있다. 매 실행의 시간은 달라지므로 기존 발표 이미지와 새 실행의 JSON을 혼합하지 않는다.

```powershell
& "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" pull /sdcard/Download/gpu_evidence_20261024 ./gpu-evidence-local
```

워커 검증: 위 빌드·단위 테스트·Lint·테스트 APK 빌드와 선택 기기 테스트가 종료 코드 0으로 통과했다. 기존 단위 테스트 186개, Lint 오류 0이며 전체 기기 테스트를 다시 실행한 것은 아니다. 기기 화면 유지 설정은 작업 전 값 `0`으로 복구했고 진단 Activity를 종료했다.

## 코디네이터 독립 검증

2026-10-24 별도 실행에서 동일 Gradle 빌드·단위 테스트·Lint·테스트 APK 빌드를 다시 확인했다(종료 코드 0, `BUILD SUCCESSFUL in 5s`, 기존 산출물 대부분 UP-TO-DATE). 단위 테스트 XML은 186개·실패 0개, 현재 Lint는 오류 0개·경고 79개다. 디버그 진단 화면의 세로 고정 관련 경고가 포함되며, 경고가 없다는 뜻은 아니다.

같은 선택 기기 테스트를 직접 재실행했고 `BUILD SUCCESSFUL in 1m 23s`, 테스트 1개·실패/오류/미실행 0개를 확인했다. [별도 검증 JSON](coordinator-verification.json)은 원본 859.17ms, 변환 90.84ms, 비율 9.46배와 같은 위임 구조를 기록한다. 이 실행의 수치를 위 발표 PNG의 수치로 대체하지 않았다. 기기의 `stay_on_while_plugged_in=0` 복구를 직접 재확인했다.

Orca 구현·화면 수정 Task 모두 요청/실효 `antigravity / gemini-3.8-flash-high`를 확인했다. 각 `worker_done`의 Task/Dispatch를 대조하고 워커 두 개를 해제했으며, 미정리 reclaimable 워커 0개를 확인했다. 코디네이터는 소스·차이·실제 PNG·원시 로그를 검토하고 위 독립 검증을 수행했다.
