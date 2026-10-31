# 전체 train 무작위 표본 추출 보완 실험

작성일: 2026-10-31. 상태: 무작위 표본 추출 보완 실험 완료. 정식 학습·기존/신규 모델 벤치마크·모바일 압축은 보존된 재개 작업이다.

## 목적과 보존한 작업

사용자는 정식 학습·기존/신규 모델 벤치마크·모바일 압축 작업을 나중에 재개하도록 저장하고, 기존 짧은 A/B 실험의 표본 추출을 먼저 보완하도록 요청했다. [재개 기록](VISION_FULL_TRAINING_BENCHMARK_RESUME_2026-10-31.md)에 현재 상태·권한·환경·다음 작업을 보존했다. 정식 새 모델 학습 및 압축은 아직 시작하지 않았다.

이번 작업은 [이전 실험](VISION_TRAINING_STRATEGY_EXPERIMENT_2026-10-31.md)의 데이터 순서 민감도를 확인하는 보완 실험이다. 기존 결과·체크포인트·입력 분할은 보존한다.

## 먼저 확인한 사실

기존 `PortraitDataset._ordered_records()`는 source별 전체 train 목록을 ID로 정렬한 다음 `random.Random(dataset.seed + epoch).shuffle()`로 섞는다. 기존 실험은 그 무작위 순서에서 예산만큼 소비했으며 원본 파일 순서의 앞부분을 그대로 자른 방식이 아니었다.

coordinator는 이 순서로 이전 A/B의 네 단계를 재구성해 저장된 실제 `input_digest.ids_sha256`와 대조했고 모두 일치했다. 단순한 코드 검토만으로 기존 실행 방식을 추정한 것이 아니다.

원래 manifest를 이용해 이전 표본 순서를 재구성한 결과는 다음과 같다. 아래 수치는 **증강 전 manifest 통계**이며 이전 실행 당시 증강된 tensor를 기록한 수치가 아니다.

| Surface 표본 | 표본 수 | 횡단보도 양성 이미지 | 횡단보도 픽셀 |
|---|---:|---:|---:|
| 전체 train | 6,647 | 579 | 7,350,939 |
| 이전 seed 42, 1단계 혼합 | 358 | 39 | 695,818 |
| 이전 seed 42, 공통 2단계 | 1,076 | 111 | 1,428,290 |
| 새 sampling seed 20261031, 1단계 혼합 | 358 | 31 | 349,296 |
| 새 sampling seed 20261031, 공통 2단계 | 1,076 | 94 | 1,201,430 |

따라서 이전 결과의 횡단보도 IoU 0%를 표본에 횡단보도가 전혀 없었던 결과라고 단정할 수 없다. 이번에는 실제로 소비한 ID와 증강 전후의 감독 픽셀 수를 남겨 이 부분을 직접 확인한다.

coordinator는 위 네 단계 표본의 횡단보도 양성 이미지 합집합 **237개의 원래 변환 mask**를 직접 해독했다. 모든 mask의 class 3 픽셀 수가 manifest와 같았고, 합계는 3,213,787픽셀이었다. 새 2단계 표본은 Surface train의 전체 87개 그룹에 걸치며 원래 source별 train 목록의 5~6,640번 위치에 분포했다(0-based). 이 역시 증강 전 확인이며 증강 후 실감독 노출량과 구분한다.

## 결과 확인 전 고정한 조건

- 모델 seed는 42로 유지하고 sampling seed만 **20261031**로 분리한다. 결과를 본 뒤 seed를 재선택하지 않는다.
- source별 기존 전체 train pool(City 2,550장, Surface 6,647장)에서 uniform shuffle 후 소비한다. epoch 내 비복원 무작위 순서이며 고갈되면 결정론적으로 다시 섞는다. 횡단보도 비율을 강제로 높이는 계층 추출은 이번 실험에 넣지 않는다.
- A는 City 64 optimizer updates 후 혼합 192회, B는 혼합 64회 후 혼합 192회다. 각 군 총 256회, batch 2 × 누적 4, 이미지 노출 2,048회다.
- 모델·동결 범위·손실·공통 클래스 가중치·학습률·단계별 Adam 재시작·70:30 혼합 스케줄은 이전과 같다. 의사 라벨은 사용하지 않는다.
- 이전의 **학습 전 공통 초기 가중치**를 양쪽이 로드해 실제 초기 tensor hash가 `dbca1ef6451f0078a5fa135121711067ee80f39f97822985ee415e9e44be4fd2`와 같음을 확인한다. 이전에 학습된 A/B 가중치는 초기화에 쓰지 않는다.
- 두 군의 2단계 데이터 순서·증강·실제 입력 digest가 일치해야 한다. 단계별 순서 ID, 중복 포함 노출·고유 표본 수·그룹 수·클래스별 실감독 픽셀 수를 기록한다.
- Surface val 1,457장과 City val 425장 전체를 평가한다. test·val ID는 학습 추출 대상에서 제외한다.
- 새 결과는 `build/vision-training/ab-random-sampling-20261031/` 및 WSL의 같은 이름 실행 폴더에 저장하고 기존 결과를 덮어쓰지 않는다.
- 단일 sampling seed 변경 실험이며 장기 수렴·통계적 우열·배포 적합성을 주장하지 않는다. 이전 대비 차이는 표본 순서 민감도로 해석한다.

기존 로더는 순서와 증강에 모두 `dataset.seed + epoch`를 사용한다. 이번 sampling seed 변경으로 **데이터 순서와 증강 난수가 함께 바뀐다**. 따라서 과거 대비 차이를 순서만의 인과 효과로 단정하지 않는다. 모델 초기값·학습 조건을 고정한 표본/증강 난수 민감도 실험으로 해석한다.

## 실행과 결과

Orca Run `run_4f774deea998`, Task `task_195e503092c3`, Dispatch `ctx_36542d1fa844`. 서로 의존하는 표본 추출·노출 감사·재실행의 단일 변경이므로 한 작업자에게 배타적 소유권을 배정했다. 정식 학습·압축 작업자는 실행하지 않았다. GPU 재실험은 coordinator의 독립 코드·테스트 검증 후 시작한다.

coordinator는 Windows TF 인터프리터로 `python -m unittest discover -s tools/vision/training/tests -p test_strategy_experiment.py -v`를 독립 실행해 **12개 모두 통과(54.246초, 종료 코드 0)**를 확인했다. 실행 전후 소스와 테스트의 SHA-256이 같았고 `git diff --check`도 통과했다. 실제 Model A에 이전 공통 초기 가중치를 로드하는 검사와 새 sampling seed에서의 ID 변화·재현성·epoch 경계·픽셀 노출 기록을 포함한다.

검증된 실행 코드는 `da40bfe`이며 소스 SHA-256은 `016a74dad19c40fae47417f454997f67a773f6a831c4c5e63ca68785ef556d37`, 테스트 SHA-256은 `1b0c931c059710dc31a57ec84eca272d7581a93120d0eb107b81e4f445b16ce3`이다. WSL 실행 명령에 `--seed 42 --sampling-seed 20261031 --initial-weights-path /home/user/.local/share/aria-training/ab-strategy-20261031/experiment/shared_init/shared_initial.weights.h5`가 포함됐음을 launch receipt로 확인했다. 실행은 2026-10-31 19:43경 KST에 시작했다.

### 실행 완료 및 독립 대조

실험은 WSL2 Ubuntu-26.04의 GTX 1660 SUPER에서 완료됐다. WSL supervisor와 결과 JSON의 종료 코드는 모두 0이며, 완료 시각은 2026-10-31 11:48:42 UTC(20:48:42 KST)다. 전체 실행은 3,904.38초(65.07분), 데이터셋 초기화 1,208.84초, A군 1,446.05초, B군 1,228.16초였다.

coordinator가 Windows 복사본 `build/vision-training/ab-random-sampling-20261031/experiment/`의 JSON·표본 장부·가중치 파일을 독립 대조했다. 실험 상태/종료 코드, 모든 기록 불변식, 각 단계 optimizer 업데이트/iteration, 총 노출 수, 전체 validation 이미지 수, A/B의 2단계 ID digest 일치와 1단계 digest 차이, JSONL 및 체크포인트 SHA-256이 모두 통과했다. 검증은 Surface 1,457장과 City 425장(총 1,882장)을 942회 forward batch로 완료했다. 양쪽 모두 단계별 64+192=256 optimizer updates, 1,024 microbatches, 이미지 2,048회 노출을 기록했다. 이전 학습 전 공통 초기 가중치에서 시작했고, 전체 클래스 tensor와 동결 encoder tensor hash를 확인했다.

| 실행 후 실제 입력 | A: City 선행 후 혼합 | B: 혼합 시작 |
|---|---:|---:|
| 1단계 Surface / City 노출 | 0 / 512 | 358 / 154 |
| 2단계 Surface / City 노출 | 1,076 / 460 | 1,076 / 460 |
| 전체 Surface / City 노출 | 1,076 / 972 | 1,434 / 614 |
| 실제 optimizer updates | 256 | 256 |
| 전체 validation | 1,882장 | 1,882장 |

2단계의 순서와 증강을 포함한 실제 입력 ID digest는 양쪽 모두 `6a578d906b6fa42b772491479d01472dd6a59d38377b10c12d45626dcb35f713`로 같았다. 표본 순서는 source 전체 train pool을 무작위로 섞고 epoch 안에서 중복 없이 소비했다. Phase 1 digest는 서로 달랐다.

### 횡단보도 실제 학습 노출

| 혼합 단계의 Surface 입력 | 표본 내 횡단보도 양성 이미지(증강 전) | 증강 전 횡단보도 픽셀 | 양성 이미지(증강 후, 실감독) | 실감독 횡단보도 픽셀 |
|---|---:|---:|---:|---:|
| A 1단계(City only) | 0 | 0 | 0 | 0 |
| B 1단계 | 31 | 349,296 | 31 | 383,816 |
| A 2단계 | 94 | 1,201,430 | 92 | 1,304,885 |
| B 2단계 | 94 | 1,201,430 | 92 | 1,304,885 |

증강 후 두 2단계는 92개 이미지의 횡단보도 mask가 84개 microbatch에 포함됐고, 횡단보도 픽셀에 대한 실제 감독량은 각각 1,304,885픽셀이다. 변환 mask 대조도 통과했으며, 의사 라벨 픽셀은 0이었다. 따라서 **새 표본에서 횡단보도 데이터가 빠진 문제는 아니다.** 다만 양 모델 모두 Surface 검증에서 횡단보도를 0픽셀 예측해 IoU가 0%였다. 즉 표본 포함만으로 해결되지는 않았고, 현재 짧은 학습·모델·손실·해상도 조합에서 예측을 학습시키지 못한 실패가 남아 있다.

### 전체 validation 결과

모든 값은 %이며, 차이는 B−A의 percentage points(pp)다. Surface의 주 지표인 실제 정답 4클래스 mIoU는 other, road, sidewalk, crosswalk로 계산했다. 두 군에서 같은 54,570,600개 정답 픽셀을 평가했다.

| Surface 검증 지표 | A | B | B−A (pp) |
|---|---:|---:|---:|
| sidewalk IoU | 53.9245 | 70.9031 | +16.9786 |
| crosswalk IoU | 0.0000 | 0.0000 | 0.0000 |
| sidewalk/crosswalk macro IoU | 26.9623 | 35.4515 | +8.4893 |
| road IoU | 54.0576 | 64.0953 | +10.0377 |
| 실제 정답 4클래스 mIoU | 29.5235 | 36.3954 | +6.8719 |
| nonwalkable → walkable 오탐률 ↓ | 16.7369 | 6.9460 | −9.7909 |
| walkable precision ↑ | 83.9748 | 93.5461 | +9.5712 |

Surface val에는 횡단보도 정답이 1,399,674픽셀 있다. A/B 모두 이를 예측하지 못했다. 두 군 모두 실제 정답이 없는 person/vehicle/pole_sign에 대한 한국 객체 정확도는 주장하지 않는다.

City validation은 실제 정답 6클래스로 고정해 비교했다. A의 객체 3클래스 macro IoU(person/vehicle/pole_sign)는 **14.0698%**, B는 **0.000647%**였고, 실제 정답 6클래스 mIoU는 각각 **37.5838%**, **26.1813%**였다. A는 B보다 City 객체 성능을 보존했지만, Surface 지표는 B가 더 높았다. 혼합 시작이 Surface 보행면에는 유리한 이 짧은 예산에서 City 객체를 심하게 훼손하는 trade-off를 다시 보였다.

### 기존 seed 42 파일럿과 비교

아래 기존 값은 [이전 파일럿 보고서](VISION_TRAINING_STRATEGY_EXPERIMENT_2026-10-31.md)의 전체 validation 결과다. 지표·분모·프로토콜은 같고 sampling seed만 바뀌었으나, 앞에서 설명한 대로 augmentation RNG도 함께 바뀌었다.

| 지표 | 기존 A | 새 A | 기존 B | 새 B |
|---|---:|---:|---:|---:|
| Surface sidewalk IoU | 67.7082 | 53.9245 | 74.8304 | 70.9031 |
| Surface crosswalk IoU | 0.0000 | 0.0000 | 0.0000 | 0.0000 |
| Surface sidewalk/crosswalk macro IoU | 33.8541 | 26.9623 | 37.4152 | 35.4515 |
| Surface 실제 정답 4클래스 mIoU | 31.2766 | 29.5235 | 39.3069 | 36.3954 |
| City person/vehicle/pole_sign macro IoU | 18.4986 | 14.0698 | 0.0022 | 0.000647 |

같은 256-update 파일럿이라도 무작위 sampling/증강 난수에 따라 지표가 달라졌다. 새 결과에서 횡단보도 양성 mask가 실제로 사용됐는데도 기존처럼 IoU 0%가 유지되어, 이 실패는 무작위 추출 표본에서 횡단보도를 빼먹은 것으로 설명되지 않는다. 다만 seed 하나씩의 비교이므로 이 표만으로 일반적인 sampling 효과나 전략 우위를 추정할 수 없다.

단일 sampling seed와 군별 256 update만 비교했으며, 통계적 유의성·장기 수렴·배포 적합성은 판단하지 않는다. 결과는 단일 파일럿의 방향성이다. 이 데이터에서는 표본 무작위 추출과 증강 RNG가 동시에 바뀌었으므로 과거와의 차이를 순서 효과 하나로 돌리지 않는다.

### 산출물과 lifecycle 상태

전체 JSON과 로그, 순서/노출 JSONL, 단계별 및 최종 가중치는 `build/vision-training/ab-random-sampling-20261031/`과 WSL의 `/home/user/.local/share/aria-training/ab-random-sampling-20261031/`에 보존했다. 결과 JSON SHA-256은 `48bc96cdd67bfa3f81b083d23170d213fb140a4192209d8aef4807fda15b7576`이다.

처음 시작한 Gemini worker는 **개별 사용량 한도 소진** 오류로 완료 보고를 못 했다. Orca에서 이 시도를 `abandoned`로 격리한 뒤 Copilot으로 재시도했지만, Copilot Fusion이 선택한 GPT‑6 Luna도 **월간 사용량 한도 초과**로 실행되지 않았다. 두 오류 뒤에 남은 worker 시도를 각각 격리하고, Gemini/Copilot attempts의 terminal release를 시도했으나 Orca가 두 terminal 모두 `identity_unproven`으로 거부해 유지했다(강제 terminal close는 하지 않았다).

Gemini quota 오류가 확인되어 승인된 fallback인 Codex `gpt-6-luna`/`medium`을 요청했다. Orca launch receipt의 requested/effective 값이 일치했고, 해당 Codex worker가 **읽기 전용 최종 검증**을 완료해 Dispatch `ctx_eeccf7812244`의 `worker_done: succeeded`를 보냈다. worker는 보고서와 결과 JSON·양쪽 표본 장부·검증 요약·체크포인트 해시를 대조했으며 파일을 변경하지 않았다. coordinator가 이 완료 메시지를 확인하고 worker를 release했으며, 해당 Codex terminal 종료가 확인됐다.

실험 프로그램 종료와 Orca worker 종료는 별개다. WSL의 trainer/supervisor 종료 코드와 결과 JSON은 모두 0이며 keeper 로그도 trainer PID가 사라진 뒤 종료를 기록한다. 이전 Gemini/Copilot terminal 두 개는 Orca release가 `identity_unproven`으로 거부해 여전히 보유 상태다. 이 한도 오류와 세션 정리는 실험 산출물의 실패가 아니다.

이번 GPU 실험은 파일럿 재실행만 완료했다. 정식 새 모델 학습·기존/신규 모델 기기 벤치마크·모델 압축은 시작하지 않았으며, [재개 기록](VISION_FULL_TRAINING_BENCHMARK_RESUME_2026-10-31.md)에 다음 작업을 보존했다.
