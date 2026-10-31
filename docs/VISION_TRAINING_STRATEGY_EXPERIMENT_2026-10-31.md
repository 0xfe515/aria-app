# City 사전학습과 혼합 시작 학습 비교 실험

작성일: 2026-10-31. 상태: 두 군 GPU 학습 및 전체 검증 완료, 종료 코드 0.

**결과 요약:** 같은 256-update 예산에서 혼합 시작(B)은 Surface 보도 IoU가 높았지만, City 객체 학습은 사실상 실패했다. City 선행(A)은 객체 성능이 상대적으로 높았으나 Surface 오탐이 컸다. 횡단보도 IoU는 양쪽 모두 0%다. 따라서 무조건 우월한 전략은 없으며, 이번 결과로 전체 학습 설정을 확정하지 않는다.

## 목적과 범위

한국 보행 환경을 목표로, 동일한 연산 업데이트 예산에서 City 사전학습이 혼합 시작보다 유리한지 비교한다. 장기 수렴이나 배포 성능을 확정하는 실험은 아니다. Model A의 7개 의미 분할 클래스만 비교하며 위험요소 헤드, distillation, Android 모델 교체는 포함하지 않는다.

기존 City 5-epoch 워밍업은 미등장 클래스 가중치 결함이 있는 실행이므로 비교군 초기화에 사용하지 않는다. 기존 산출물은 보존한다. 두 비교군은 동일한 ImageNet 인코더와 동일한 난수 초기화 디코더에서 새로 시작한다.

## 사전 고정한 실험 조건

| 항목 | 조건 |
|---|---|
| 초기화 | ImageNet MobileNetV2 + 동일한 decoder 초기 가중치; 초기 가중치 해시 일치 확인 |
| 모델 | 기존 실제 Model A, FLOAT32, NHWC `[B,512,288,3]`, semantic 7채널 |
| 학습 범위 | 인코더와 BN 동결, 기존 decoder/head만 학습 |
| 난수 | seed 42, deterministic GPU 연산; 1개 paired seed의 선행 실험 |
| 비교군 A | 1단계 City 64 optimizer updates → 2단계 혼합 192 updates |
| 비교군 B | 1단계 혼합 64 optimizer updates → 2단계 혼합 192 updates |
| 총 예산 | 각 군 정확히 256 optimizer updates, microbatch 2 × 누적 4 = update당 8장, 총 2,048 sample presentations |
| 단계별 학습률 | 양쪽 모두 1단계 `1e-3`, 2단계 `3e-4`; 동일한 단계 경계에서 Adam을 새로 시작 |
| 혼합 비율 | Surface 70%, City 30%를 목표로 결정론적 microbatch 스케줄 사용; 실제 횟수 기록 |
| 2단계 통제 | 양쪽의 데이터 순서·증강 난수·학습률·업데이트 수를 동일하게 재설정; 입력 순서 digest 일치 확인 |
| 클래스 가중치 | 결함을 수정한 함수로 양쪽의 전체 real train pixel counts 합계에서 한 번 계산; 두 단계·두 군 모두 같은 가중치를 사용해 curriculum 효과를 분리 |
| 손실·최적화 | 기존 CombinedSemanticLoss, Adam, gradient accumulation, clipnorm 1.0 재사용 |
| 의사 라벨 | 양쪽 모두 사용하지 않음. 교사 모델 효과를 제외하고 데이터 순서만 비교 |
| 검증 | Surface val 1,457장 + City val 425장 전체, 증강 없음, 실제 정답만 평가 |
| 테스트 | Surface test와 City official-val 기반 test는 평가·튜닝에 사용하지 않음 |

혼합 스케줄은 10개 microbatch마다 Surface 7개, City 3개를 배치한다. 단계가 10의 배수에서 끝나지 않으면 목표 비율과 작은 차이가 생기므로 실제 sample counts를 보고한다. 두 군의 총 이미지 노출 수는 같지만, A가 City 사전학습에 예산을 쓰는 만큼 전체 Surface 노출은 B보다 적다. 이것은 고정 연산 예산에서의 학습 전략 비교에 포함되는 차이다.

256 updates는 전체 데이터 수 회 반복 학습보다 작은 예산이다. 결과를 장기 학습의 최종 우열로 일반화하지 않는다. 다른 seed의 재현 실험과 장기 확인 실험은 후속 단계이다.

## 데이터·감독 범위

기존 group-disjoint 분할과 byte-identical full manifest를 유지한다. 학습에는 City 2,550장과 Surface 6,647장만 사용한다. City에는 별도 crosswalk 정답이 없고, 현재 Surface 실제 의미 정답에는 person/vehicle/pole_sign 픽셀이 없다. 한국 객체 정확도는 이 실험으로 입증할 수 없다. Surface 미주석 영역은 ignore를 유지하며 객체의 가짜 음성 정답으로 만들지 않는다.

클래스 가중치 수정은 미등장 클래스를 평균 정규화에서 제외하고 해당 클래스에는 중립 가중치를 부여한다. 양수 클래스 가중치가 0인 클래스의 추가·삭제에 영향을 받지 않는 회귀 검사를 포함한다. 기존 체크포인트와 수정된 실험 상태를 섞지 않는다.

## 평가와 판단 원칙

- 주 지표: Surface sidewalk/crosswalk IoU 각각과 두 클래스의 macro IoU.
- 함께 보고: Surface real-label mIoU, road/other IoU, nonwalkable→walkable false-positive rate, walkable precision.
- 객체 보존 지표: City person/vehicle/pole_sign IoU 각각과 macro IoU, City mIoU.
- 같은 검증 이미지와 같은 지표 구현으로 양쪽을 평가한다. undefined 클래스는 `null`로 유지하고 평균의 분모를 명시한다.
- 단일 전체 mIoU로 서로 다른 도메인을 합쳐 승자를 정하지 않는다. 한국 보행면 개선과 위험 오탐, City 객체 성능의 trade-off를 함께 판단한다.
- 실용적 차이의 참고선은 보행면 macro IoU 1 percentage point이며, 객체 클래스의 1pp 초과 저하나 위험 오탐 악화가 있으면 무조건 우월하다고 결론내리지 않는다. 이것은 비교 해석 기준이며 배포 승인 기준을 대체하지 않는다.
- 1개 paired seed에서 근소한 차이는 미결정으로 보고한다. 통계적 유의성이나 한국 객체 성능을 주장하지 않는다.

## 실행·검증 기록

실제 명령, 코드/입력/초기화/최종 가중치 해시, 단계별 업데이트 수·노출 수·순서 digest, GPU·메모리·시간, 테스트 결과, 종료 코드와 산출물 경로를 아래에 기록했다.

학습 전 coordinator가 Windows TensorFlow 인터프리터 `build/vision-training/environment/venv_win_py311/Scripts/python.exe`로 다음 검사를 직접 실행했다. 모두 종료 코드 0이었다.

| 명령 (`python -m unittest discover -s tools/vision/training/tests -p … -v`) | 결과 | 확인 범위 |
|---|---|---|
| `test_losses.py` | 17개 통과 | 실제 City 가중치, 미등장 클래스 불변성, 양수 클래스 기존 동작, ignore 기울기 |
| `test_train.py` | 7개 통과 | 실제 CPU 학습, 누적 업데이트, 체크포인트 복원, TFLite 출력 일치 |
| `test_strategy_experiment.py` | 7개 통과 | 실제 Model A 초기화 해시, 두 군 실행·예산·후반부 입력 일치, 지표 구현 일치, test 제외 |

가중치 수정 커밋: `4896756`. 수정 후 City 가중치는 클래스 순서대로 `[0.5, 0.5, 0.9090145, 1.0, 1.9989327, 0.7957785, 1.5897603]`이다. 이번 비교에는 이 City 단독 가중치가 아니라 전체 real train 통계에서 계산한 공통 가중치를 사용한다.

Orca Run: `run_ec235582baa6`. 가중치 수정과 실험 harness 준비는 배타적 파일 소유권으로 병행한다. 가중치 수정의 독립 검증 전에는 실험을 시작하지 않는다. GPU는 한 worker만 소유하며 두 군은 순서대로 실행한다. 학습 프로세스에 gdb/ptrace를 연결하지 않는다. 물리 호스트 여유 공간을 확인하며 원본·의사 라벨 생성·이전 실험은 재실행하거나 덮어쓰지 않는다.

가중치 수정 Task `task_374cfdbe092b`와 실험 Task `task_09c59c499a2e`는 모두 성공으로 완료됐고 독립 검증 후 작업자를 release했다. 학습·supervisor 및 Windows WSL keeper의 종료를 확인했으며, 이 Run의 정리 대상 작업자(`reclaimable`)는 0개다.

## 결과

### 실행 완료와 통제 검증

실제 실행은 2026-10-31 16:17경 시작해 17:18:24 KST에 완료됐다. supervisor 종료 코드와 실험 결과의 `exit_code`는 모두 0이다. 총 실행 시간은 **3,662.31초(61.04분)**이며, 이 중 네 데이터 로더 초기화가 965.19초(16.09분)였다. A군 학습·평가는 1,333.18초, B군은 1,349.57초였다. 시간에는 데이터 I/O가 포함되므로 순수 GPU 연산량이나 추론 벤치마크로 해석하지 않는다.

| 실제 집행 | A: City 선행 → 혼합 | B: 혼합 시작 → 혼합 |
|---|---:|---:|
| 1단계 실제 optimizer iterations | 64 | 64 |
| 2단계 실제 optimizer iterations | 192 | 192 |
| 총 optimizer updates | 256 | 256 |
| 총 microbatches | 1,024 | 1,024 |
| 총 이미지 노출 수(중복 포함) | 2,048 | 2,048 |
| Surface 노출 | 1,076 (52.54%) | 1,434 (70.02%) |
| City 노출 | 972 (47.46%) | 614 (29.98%) |
| Surface 검증 이미지 | 1,457 | 1,457 |
| City 검증 이미지 | 425 | 425 |

양쪽의 2단계는 각각 Surface 1,076회, City 460회 노출이었다. 공통 초기화의 실제 tensor hash, 학습 가능 변수 경로, 후반부 입력 digest, 동결 가중치 보존, 업데이트 카운터, 노출 수, 검증 이미지 수의 **9개 실행 불변식이 모두 통과**했다. 학습 변수는 decoder/head의 20개, 비학습 변수는 251개였다. 모든 단계의 손실·가중치·optimizer 상태는 유한했다.

공통 클래스 가중치는 `[0.5, 0.5, 0.5, 0.9633858990, 2.3776342455, 0.9465402345, 1.8909433852]`였다. 원본/manifest/의사 라벨을 재생성하지 않았고, 의사 라벨과 test 이미지는 학습·평가에 쓰지 않았다.

### Surface 검증: 한국 보행면

모든 값은 %이며, 차이는 B−A의 percentage points(pp)다. 정답이 있는 54,570,600픽셀만 평가했다.

| 지표 | A | B | B−A (pp) |
|---|---:|---:|---:|
| sidewalk IoU | 67.7082 | 74.8304 | +7.1221 |
| crosswalk IoU | 0.0000 | 0.0000 | 0.0000 |
| sidewalk/crosswalk macro IoU (2클래스) | 33.8541 | 37.4152 | +3.5611 |
| road IoU | 47.6743 | 66.7477 | +19.0734 |
| other IoU | 9.7238 | 15.6494 | +5.9255 |
| 실제 정답이 있는 4클래스 mIoU | 31.2766 | 39.3069 | +8.0302 |
| nonwalkable → walkable 오탐률 ↓ | 50.5898 | 15.5566 | −35.0332 |
| walkable precision ↑ | 72.7348 | 87.9613 | +15.2266 |

오탐률의 분모는 실제 주석된 nonwalkable 22,634,727픽셀이며, 보도/횡단보도로 잘못 예측한 픽셀은 A 11,450,861개, B 3,521,194개였다. 이 수치는 **실제 주석 영역에서의 픽셀 지표**다. ignore 영역, 미주석 한국 객체, 실제 주행/보행 위험 사건 전체를 평가한 값은 아니다.

Surface의 person/vehicle/pole_sign 정답 픽셀은 0이다. 해당 클래스 예측이 주석된 다른 클래스에 나타나면 IoU의 union이 양수여서 0이 기록되지만, 이를 한국 객체 정확도로 해석할 수 없다. 따라서 주 비교 mIoU는 고정된 실제 정답 4클래스를 사용했다. 기존 지표 구현의 union-defined 7클래스 평균은 A 17.8723%, B 22.4611%로 별도 진단 값이다.

### City 검증: 객체 학습

실제 정답이 있는 16,023,070픽셀을 평가했다. 이 표도 %이며, mIoU는 양쪽 모두 동일한 실제 정답 6클래스 분모로 계산했다.

| 지표 | A | B | B−A (pp) |
|---|---:|---:|---:|
| person IoU | 8.717694 | 0.001477 | −8.716217 |
| vehicle IoU | 45.849264 | 0.004715 | −45.844549 |
| pole_sign IoU | 0.928983 | 0.000375 | −0.928608 |
| 객체 3클래스 macro IoU | 18.498647 | 0.002189 | −18.496458 |
| 실제 정답이 있는 6클래스 mIoU | 39.2488 | 26.6669 | −12.5819 |

B군은 정답 person 270,270픽셀 중 4개, vehicle 1,442,298픽셀 중 68개, pole_sign 266,610픽셀 중 1개만 맞혔다. 따라서 **이 제한된 예산의 혼합 시작은 객체 학습이 사실상 이뤄지지 않은 결과**다. A군도 사람·기둥/표지 성능이 낮으므로, 상대 우위를 학습 충분성으로 해석하면 안 된다.

City에는 crosswalk 정답이 없다. A는 crosswalk 예측도 없어 IoU가 `null`, B는 1픽셀을 잘못 예측해 IoU가 0이다. 이 때문에 union-defined mIoU는 A가 6클래스(39.2488%), B가 7클래스(22.8574%)여서 평균 분모가 달라진다. 위 표에서는 실제 정답 6클래스를 고정해 비교했다.

### 횡단보도 실패의 관측 범위

Surface 검증에는 횡단보도 정답 **1,399,674픽셀**이 실제로 있다. A는 횡단보도를 0픽셀 예측했고, B는 3픽셀 예측했지만 모두 오답이었다. 즉 정답 부재가 아닌 **검출 실패**다. 학습 데이터 전체에는 횡단보도 7,350,939픽셀이 있으며, 실행 전 실제 mask를 해독한 spot check에서도 일치했다.

이 실험은 예산·샘플링·손실·인코더 동결 여부를 각각 분리해 원인을 규명한 실험은 아니다. 따라서 0%의 원인을 특정 하나로 단정하지 않는다. 가중치 정규화 결함을 고쳐도 짧은 현 설정만으로 횡단보도 문제가 해결되지 않았다는 사실은 확인했다.

## 재현 명령과 산출물

실행 코드 커밋은 `7f490e5`이며 가중치 수정 `4896756`을 포함한다. Windows coordinator의 독립 검사 31개(17+7+7)가 통과했다. worker는 추가로 `test_model.py` 13개와 `test_train_stage.py` 20개 통과를 보고했다. 후자는 worker 검사 증거이며 coordinator 재실행으로 표기하지 않는다. Android 소스·모델 자산은 변경하지 않아 이번 Python 실험에서 Gradle은 실행하지 않았다.

완료 후 coordinator는 공통 초기 파일과 네 단계 체크포인트의 실제 파일 SHA-256을 결과 JSON과 대조했고, 각 군의 카운터·노출 수·검증 1,882장·single-forward 942 batches를 독립 확인했다. 혼동행렬로부터 모든 클래스 IoU와 실제 정답 클래스 mIoU를 다시 계산해 저장된 수치와 일치함을 확인했다.

```bash
/home/user/.venvs/aria_wsl/bin/python \
  /mnt/c/Users/felice/orca/aria-app/tools/vision/training/environment/run_wsl_training.py \
  --deterministic --allow-growth \
  --module tools.vision.training.strategy_experiment -- \
  --manifest /home/user/.local/share/aria-training/full-training-20261031/data/manifest.json \
  --output-dir /home/user/.local/share/aria-training/ab-strategy-20261031/experiment \
  --device gpu --weights imagenet --seed 42
```

실행 환경은 Ubuntu-26.04/WSL2 커널 `5.15.167.4`, Python 3.11.18, TensorFlow 2.19.1, NumPy 2.1.3, Pillow 13.0.0, GTX 1660 SUPER 6GiB였다. TF allocator의 프로세스 누적 peak는 972.31MiB였고, 각 군 평가 종료 시점 `nvidia-smi` 전체 GPU 메모리 snapshot은 1,789MiB였다. 후자는 전용 프로세스 peak가 아니며 두 값의 측정 범위가 다르다.

WSL의 원본 실험 산출물은 `/home/user/.local/share/aria-training/ab-strategy-20261031/experiment/`, 실행 로그·supervisor 종료 코드는 같은 상위 폴더의 `runner/`에 있다. Windows 복사본은 `build/vision-training/ab-strategy-20261031/experiment/`에 보존한다. 주요 파일은 `experiment_results.json`, 각 군의 `arm_summary.json`, `validation_summary.json`, 단계별 `phase_summary.json`과 `*.weights.h5`, `shared_init/shared_initial.weights.h5`, `training.log`, `exit_code.txt`다. 대용량 산출물은 Git에 넣지 않는다.

### SHA-256 기록

| 대상 | SHA-256 |
|---|---|
| manifest | `e83b495d9ca0220115f8a59735765ed1a4683452f249c1e7254502d8ff0e66b9` |
| 완료 결과 JSON | `6616c4c1446ac0a2d5d46f96aeec12a5a1734be7c1ea77d6b30ba775f1b3da0a` |
| 실험 도구 소스 | `2407b5c10a96f8945b3b5ef0d256b47cdda2f95048cb3349d566e36484857761` |
| 손실 함수 소스 | `93043c9ffd83d86ce0d9608eac380764d78375809d2c1a2f98e5a79d63f05402` |
| 공통 초기 가중치 파일 | `f47c4e3c3e25697595aec2781711ba9d2255f96fe7bac7e0d0999ecdfd716487` |
| 공통 초기 실제 tensor | `dbca1ef6451f0078a5fa135121711067ee80f39f97822985ee415e9e44be4fd2` |
| 양쪽 2단계 실제 입력 digest | `a6335c3bf35a10be963862b97163d36af601b9d9f6b3efc8654a33dd3d824ec0` |
| 초기/최종 공통 동결 tensor | `882c42262ec798ba6fac2ea5f14e2737fa7856bd68ba754eadb51b4da9bf3b39` |
| A 최종 가중치 파일 | `0990f21a8f23017928d2a74500cf8f13fbb7bb2c1be1450256de4c75bdce9c3e` |
| B 최종 가중치 파일 | `2a3a4f5830290821fdedf59932af6549944478d5e2a791f78d756c9f84b3483d` |
| A 최종 실제 tensor | `6f7dbce46c987daeb991f8e82f376f8b08145f99d19055cd398fed06c89cc81f` |
| B 최종 실제 tensor | `6529a348ceab072875f9eeff8d9cf6de4dcb9f48613f10c59e6ef7f606adc8a1` |

실험 체크포인트는 `experimental_budgeted_pilot`이며 production stage predecessor로 승인되지 않았다. 기존 City 5-epoch 결과와 섞거나 앱 모델로 교체하지 않았다.

## 결론

1. **한국 보행면만 보면 이 예산에서는 혼합 시작이 유리했다.** 보도 IoU +7.12pp, 보행면 macro +3.56pp였고 주석 영역의 보행 가능 오탐도 줄었다. 그러나 횡단보도는 두 군 모두 실패했다.
2. **객체까지 필요한 현재 7클래스 목표에서는 혼합 시작을 바로 채택할 근거가 부족하다.** City 객체 macro IoU가 18.50%에서 0.0022%로 낮아지는 trade-off가 관측됐다. 두 군 모두 절대 성능이 충분하다는 증거는 없다.
3. **당장의 보수적 후속 기준선은 City 객체 학습을 먼저 확보한 뒤 Surface와 City를 함께 유지하는 적응 방식이다.** 이는 이번 짧은 실험을 근거로 정한 후속 실험 방향이며 최종 최적 전략의 증명은 아니다. City를 버리고 Surface만으로 현재 7클래스 목표가 충분하다고 판단하지 않는다. Surface 단독군 및 City→Surface 단독 튜닝군은 이번에 실행하지 않았다.
4. **전체 학습 설정 확정 전에 횡단보도 학습 실패를 우선 확인해야 한다.** 다음 실험에서는 train-only 횡단보도 포함 표본의 노출과 클래스별 gradient/손실을 확인하고, 적정 City warmup 길이·희소 클래스 샘플링·혼합 비율을 검증 자료로 비교한다. 더 긴 수렴 실험과 추가 seed가 필요하다. 현재 보고서는 이 후속 실험을 수행했다고 주장하지 않는다.

한 seed, 256 updates, 인코더 동결, 의사 라벨 미사용이라는 범위를 넘는 통계적 우열·한국 객체 정확도·배포 적합성을 주장하지 않는다. 테스트 자료는 최종 설정 확정 후의 독립 평가를 위해 남겨 둔다.
