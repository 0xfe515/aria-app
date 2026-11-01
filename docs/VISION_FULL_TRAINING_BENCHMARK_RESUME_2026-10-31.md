# 정식 학습·벤치마크·모바일 압축 작업 재개 기록

2026-10-31 사용자 요청으로 정식 학습에 앞서 표본 추출 비교 실험을 먼저 보완했다. 이 문서는 정식 학습·기기 벤치마크·압축 작업을 재개하기 위한 기록이며 해당 작업을 완료했다는 보고서가 아니다.

## 보존된 상태

- Git 기준: `bfffad401d971d9a7390a93dc6691d0b325bf7f9`, 당시 작업 트리 clean.
- 당시 정식 학습·압축용 Orca Run `run_c136ab2a1e0b`에는 worker/Task가 시작되지 않았다. 다만 이전 `run_1de5424dff38`의 City warmup `attempt_02`는 이후 완료됐다. `tools/vision/training/FULL_TRAINING_RUN.md` §7에 종료 증거와 설정 결함을 기록했다.
- 우선 수행한 전체 train 무작위 표본 실험은 GPU 학습·양쪽 전체 검증까지 완료됐고 독립 산출물/hash 대조를 통과했다. [완료 실험 보고서](VISION_RANDOM_SAMPLING_EXPERIMENT_2026-10-31.md), 산출물 `build/vision-training/ab-random-sampling-20261031/`. 양쪽 Phase 2는 횡단보도 실제 감독 픽셀 1,304,885개를 받았지만 둘 다 Surface val 횡단보도를 전혀 예측하지 못했다. City 객체 성능은 City 선행 A군이 우세했고 Surface 보행면은 혼합 시작 B군이 우세했다. 단일 seed 파일럿이므로 학습 전략의 최종 결론으로 확대하지 않는다.
- GPU supervisor/trainer는 exit 0으로 종료했다. Gemini 개별 quota 소진과 후속 Copilot 월간 quota 오류로 두 시도는 격리됐다. 확인된 fallback Codex `gpt-6-luna`/`medium`이 읽기 전용 검토를 성공적으로 마쳐 Task를 완료했고 terminal도 release됐다. 두 quota 오류 worker의 terminal은 Orca가 `identity_unproven`으로 release를 거부해 남아 있으며, 상세 lifecycle은 [실험 보고서](VISION_RANDOM_SAMPLING_EXPERIMENT_2026-10-31.md)에 적었다. 이미 격리된 정확한 두 Dispatch 이외의 프로세스를 강제로 닫지 말고, 재개 때 Orca의 새 상태와 소유권 증거부터 확인한다.
- 이전 두 군 실험은 완료·보존돼 있다: [실험 보고서](VISION_TRAINING_STRATEGY_EXPERIMENT_2026-10-31.md).
- 이전 산출물: Windows `build/vision-training/ab-strategy-20261031/experiment/`, WSL `/home/user/.local/share/aria-training/ab-strategy-20261031/experiment/`.
- 이 실험의 가중치는 정식 stage predecessor로 승인되지 않았다. 완료된 City 5-epoch warmup도 잘못된 과거 클래스 가중치 `[0.5, 0.5, 0.5, 3.0, 0.5, 0.5, 0.5]`를 사용했으므로 정식 새 모델로 취급하지 않는다. 과거 실행은 비교 기준으로 보존하고 수정된 가중치로 fresh output에서 warmup을 다시 시작한다.
- 수정된 클래스 가중치 구현은 `4896756`, 비교 실험 도구는 `7f490e5`에 보존돼 있다.

## 실행 환경과 재사용 입력

- WSL `Ubuntu-26.04`, Python `/home/user/.venvs/aria_wsl/bin/python`, TF 2.19.1, 커널 `5.15.167.4`, GTX 1660 SUPER 6GiB.
- CUDA 부트스트랩 `tools/vision/training/environment/run_wsl_training.py`의 `--deterministic --allow-growth` 사용. 프로세스에 gdb/ptrace를 연결하지 않는다.
- 전체 staged manifest `/home/user/.local/share/aria-training/full-training-20261031/data/manifest.json`, SHA-256 `e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9`. 원본 데이터/변환/의사 라벨을 재생성하지 않는다.
- Windows 검증 Python `build/vision-training/environment/venv_win_py311/Scripts/python.exe`.
- 연결 기기 확인: Pixel 3, adb ID `8B7X15A0F`. 장치·Gradle·GPU 소유권을 한 작업자/코디네이터에만 배정한다.
- 기존 앱 모델은 `app/src/main/assets/models/`의 portrait/landscape TFLite. 실제 사용하는 baseline을 앱 설정·해시로 확정하고 변경 전 보존해야 한다.

## 나중에 이어서 할 일

1. 횡단보도 라벨 ID 3의 디코딩, 모델 출력 채널, 손실/gradient, Surface validation 예측을 확인한다. 이전 전체 표본 실험으로 실제 양성 데이터 노출은 확인됐으므로 표본 누락을 원인으로 단정하지 않는다.
2. 샘플 순서와 증강 RNG를 분리해 seed 간 비교의 교란 요인을 제거한다. 수정된 가중치로 fresh `city_warmup`을 수행하고, 이후 혼합 단계는 양쪽 domain의 클래스별 validation을 확인하면서 이어간다. 기존 `city_full` 30 epoch을 검증 없이 그대로 진행하지 않는다.
3. 기존 앱의 기본 모델은 `walkable_seg_aria5_portrait_unfused.tflite`이며 SHA-256은 `312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1`이다. 기존 모델과 후보를 같은 validation 입력·전처리·클래스 매핑으로 평가하고, Pixel 3에서 backend/thread/전체 pipeline 지연 및 메모리를 비교한다. 검증으로 후보를 고정한 뒤에만 별도 test 평가를 수행한다.
4. FP16 또는 INT8 압축은 정확도, 횡단보도·객체 지표, 크기, 메모리, median/p95 지연을 함께 확인해 모바일 이점이 있을 때만 적용한다. 앱 모델 교체는 후보가 평가 기준을 통과한 경우에만 별도 통합 작업으로 진행한다.
5. 최종 결과·실행 설정·체크포인트와 TFLite 해시·한계를 Markdown으로 기록한다. 앱 코드가 변경되면 `:app:assembleDebug`, `:app:testDebugUnitTest`, `:app:lintDebug` 및 가능한 기기 검사를 수행한다.

## 역할과 권한

사용자는 실제 학습, 기존/신규 모델 벤치마크, 모바일 적합성이 입증될 경우 압축 적용을 승인했다. 이번 재개에서는 위 진단과 수정된 warmup부터 진행한다. `AGENTS.md`, `docs/ANTIGRAVITY_DELEGATION.md`, training retry 기록을 확인하고 Orca의 실제 Run/Task/Dispatch를 사용한다. coordinator는 명세·독립 검증·문서·Git을 담당하며 프로덕션 코드 직접 구현은 하지 않는다. 이미 release된 과거 Dispatch ID를 재사용하지 않는다.
