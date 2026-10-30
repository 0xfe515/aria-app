# 비전 모델 개선 계획 — 임시 저장

저장일: 2026-10-30. 상태: **사용자 요청으로 작업 중단. 명시적인 재개 요청 전까지 실행하지 않는다.**

## 확정한 요구사항

- 모델은 **처음부터 세로형 H=512, W=288로 생성·학습**한다. 가로로 학습한 뒤 세로형으로 변환하는 방식은 사용하지 않는다.
- 정확도 우선, 로컬 GTX 1660 SUPER 6GB 사용, WSL2 기반 학습 환경을 준비한다.
- Cityscapes 최초 학습과 Surface_1 한국 보도 환경 튜닝 이력을 반영한다. 데이터 원본은 읽기 전용으로 보존한다.
- Surface 전체 9,558쌍을 대상으로 자동 보강한다. 사람 검수는 이번 계획에 포함하지 않는다. 자동 객체 라벨은 의사 라벨이며 실제 정답으로 성능을 입증하지 않는다.
- 기존 7클래스를 유지하면서 위험 속성 출력을 별도로 추가하고 앱 경고까지 통합한다.
- 현재 모델과 기존 앱 작업 트리는 보존한다. 합격 기준을 통과하지 않은 모델로 배포 에셋을 교체하지 않는다.

## 실행할 순서

### 1. 환경과 데이터 준비

1. WSL2 Ubuntu 22.04, Python 3.11, TensorFlow 2.19 계열의 독립 환경을 만들고 실제 버전을 고정한다. GPU 학습 100스텝, 체크포인트 저장·복원, TFLite 변환을 먼저 검증한다.
2. FLOAT32 배치 2·기울기 누적 4회로 시작한다. 메모리가 부족하면 배치 1·누적 8회로 바꾸며 세로 입력 해상도는 유지한다.
3. Surface를 촬영 묶음 기준 train/val/test 약 70/15/15로 분할한다. XML 촬영 식별자와 Surface 디렉터리를 사용하고, 중복 이미지로 연결된 묶음은 같은 분할에 둔다. seed 42와 파일 해시·분할 목록을 저장한다.
4. Cityscapes 공식 train은 도시 단위로 내부 train/val 약 85/15로 나누고 공식 val 500장은 최종 평가용으로 보존한다.
5. XML 폴리곤을 z_order 순으로 변환해 표면·위험 정답을 보존한다. void·패딩·알 수 없는 색상은 ignore로 구분한다.
6. 기존 세로 TFLite teacher의 원본·좌우 반전 예측이 일치하고 확률 ≥0.90, 확률 차이 ≥0.20인 사람·차량·기둥 픽셀만 의사 라벨로 사용한다. 가중치는 실제 정답의 0.25로 둔다. 불확실한 Surface 배경을 일괄 other 정답으로 만들지 않는다.

### 2. 실험 A — 기존 구조로 정확도 개선

- MobileNetV2 + 기존 U-Net 디코더를 **세로형 입력으로 처음부터 학습**한다. 원본 Keras 체크포인트를 찾지 못했으므로 ImageNet 초기화가 기본 경로다.
- upright RGB, W288×H512 letterbox를 앱과 통일한다. 패딩은 모든 손실·지표에서 제외한다. 학습 증강은 전체 시야 50%와 정방향 세로 crop 50%, 좌우 반전·노출·대비·약한 blur를 사용한다.
- 손실은 masked 가중 CE + Dice. 클래스 가중치는 실제 학습셋 빈도의 역제곱근을 0.5~3으로 제한하며, 감독되지 않은 Dice 클래스는 제외한다.
- Cityscapes: 인코더 동결 5에포크(Adam 1e-3), 전체 학습 최대 30에포크(1e-4). BatchNorm 통계는 고정한다.
- 혼합 학습: Cityscapes/Surface 50:50, 디코더 5에포크(3e-4), block_13 이후 해제 최대 20에포크(1e-5). gradient clipping 1.0, 조기 종료 patience 5.

### 3. 실험 B — 위험 속성 예측 추가

- 실험 A에 마지막 디코더 특징을 공유하는 Conv3×3/16 + Conv1×1/8 sigmoid head를 추가한다.
- 속성 순서: `bike_lane`, `damaged_surface`, `stairs`, `manhole`, `tree_zone`, `grating`, `repair_zone`, `speed_bump`.
- 파손 인도·점자블록·골목길은 damaged_surface로 합친다. XML에서 정답 여부를 판단할 수 없는 영역은 위험 손실에서 제외한다.
- 총손실은 의미 분할 손실 + 0.5×masked focal BCE(γ=2). head만 3에포크 후 상위 인코더·디코더와 최대 20에포크 공동 학습한다.

### 4. 실험 C — 합격한 모델의 경량화

- 실험 B의 정확도 합격 이후 수행한다. 인코더·해상도는 유지한다.
- 디코더를 bilinear upsample + 1×1 스킵 채널 축소 + depthwise-separable convolution으로 바꾼다. 단계 채널은 128/64/32/16.
- B를 teacher로 사용하고 정답 손실에 0.2×KL distillation(T=2)을 추가한다. 정확도·실기기 속도 기준에 미달하면 B를 유지한다.

### 5. 앱 출력·경고 통합

- 입력 `[1,512,288,3]` FLOAT32 RGB 0~1, 의미 출력 `[1,512,288,7]`, 위험 출력 `[1,512,288,8]`.
- v2 명세에 출력 텐서 식별자·속성 순서·정규화·모델 해시·속성별 임계값을 기록하고 검증한다. 기존 단일 출력 모델도 지원한다.
- 두 출력을 canonical 512×288로 역매핑한다. 활성 통로 근거리 행 230..287에서 위험 점유율 ≥1%, 연결 영역 ≥32픽셀이면 경고 후보로 삼는다.
- 확률 임계값은 validation에서 0.50~0.95, 간격 0.05로 선택한다. 연속 2프레임, 경고 간격 2.5초, 결과 만료 1.5초 및 세션 차단을 적용한다.
- 위험 우선순위는 계단→공사→파손→과속방지턱→맨홀→격자→가로수 구역→자전거 도로. 기존 진동·Toast를 사용하며 방향 신뢰도가 부족하면 경고를 억제한다.
- 평가가 부족하거나 기준에 미달한 속성은 진단 표시만 제공하고 자동 경고를 활성화하지 않는다.

## 합격 기준과 검증

| 항목 | 계획상 목표 |
|---|---|
| Surface 실제 정답 영역 mIoU | 기준 모델 대비 +3%p 이상 |
| 비보행 영역→보행가능 오인율 | 기준 대비 상대 20% 이상 감소 |
| Cityscapes person/vehicle/pole_sign IoU | 각각 하락 1%p 이내 |
| 위험 경고 precision/recall | 속성별 ≥0.90 / ≥0.70 |
| 속성 경고 평가 자료 | 양성 이미지 ≥50장, 촬영 묶음 ≥5개 |
| 경량 모델 | B 대비 mIoU 하락 ≤0.5%p, 위험 오인 증가 없음, invoke p50 20% 이상 단축 |
| 앱 전체 지연 | 동일 기기 파이프라인 p95 악화 10% 이내 |

validation으로 모델·임계값을 선택하고 test는 최종 후보에 사용한다. 최종 후보는 seed 42/43/44로 반복한다. 과거 모델이 Surface의 어느 장면을 학습했는지는 불명확하므로 기준 모델의 역사적 test 노출 가능성을 명시한다. 자동 객체 라벨을 한국 객체 정확도 정답으로 사용하지 않는다.

필수 검사: 그룹·해시 중복, 색상/ignore, XML z_order, 좌우 반전 복원, paired transform, ignore 손실/기울기 제외, 저장·복원, CPU/GPU parity, GPU 위임, v1/v2 모델 호환, 역매핑, 오래된 결과·세션 종료 후 경고 차단. 앱 변경 후 assembleDebug/testDebugUnitTest/lintDebug와 가능한 연결 기기 테스트를 수행한다.

## 중단 시점의 실제 상태

- Orca CLI/런타임 모두 1.4.244로 연결 성공을 확인했다. 이전 EPERM은 권한 수준 차이가 관련된 것으로 판단되며, 현재는 연결 가능한 상태다.
- 실행 계약은 [VISION_PORTRAIT_TRAINING_EXECUTION.md](VISION_PORTRAIT_TRAINING_EXECUTION.md)에 저장되어 있다. 상세 코드 검토는 [검토 보고서](VISION_MODEL_CODE_REVIEW_2026-10-30.md)에 있다.
- Run: `run_1de5424dff38`.
- 환경 Task `task_f3f7e5f596ce`, Dispatch `ctx_3a210715518a`: 요청/실효 모델 모두 Gemini `gemini-3.8-flash-high` 확인. 사용자 중단 요청으로 `worker-stop` 실행, `state=stopped`, `ptyKilled=true` 확인 후 release했다.
- 데이터 Task `task_3c229b8b13f4`, Dispatch `ctx_b5927ab97be3`: Copilot으로 시작했으나 관리 계정 정책 로딩 메시지 이후 프로세스 종료가 확인됐다. 실제 구현 모델은 확인되지 않았다. `failed/process_exited` 상태를 확인하고 release했다.
- 두 워커 모두 리소스 해제 완료, reclaimable 워커 0개를 확인했다. 재시도나 대체 워커를 시작하지 않았다.
- 중단 시점 `tools/vision/training` 아래 구현 파일은 확인되지 않았다. 재학습·정확도 평가·앱 신규 통합이 완료됐다는 근거는 없다. WSL 환경 구성 완료 여부도 확인되지 않았으므로 재개 시 다시 점검해야 한다.
- 중단 시점 wsl/dism/python/python3 프로세스 조회에서 해당 프로세스는 나오지 않았다. 이 조회만으로 Windows 기능 변경이 전혀 없었다고 보장하지 않는다.
- YOLO/Full Access 전환은 **완료되지 않았다**. Orca 설정 화면을 열었으나 권한 변경을 적용·검증하지 않았다.
- 기존 앱 변경과 원본 모델을 보존했으며 commit/push하지 않았다.

## 재개 절차

1. 사용자의 명시적 재개 요청을 확인한다. 자동으로 재개하지 않는다.
2. AGENTS.md와 Orca 버전 일치 스킬, 본 파일·실행 계약을 읽고 현재 연결·Git 변경·워커 상태를 재확인한다.
3. WSL/Windows 기능 상태 및 산출물을 조사한다. 이미 적용된 설치 작업을 무작정 반복하지 않는다. 호스트 재부팅이 필요하면 근거를 보고하고 사용자 판단을 기다린다.
4. 실패/중단된 Task의 재시도는 Orca 복구 지침에 따라 원래 task와 retry-of dispatch를 지정한다. 새 시도로 기존 소유권을 우회하지 않는다.
5. Gemini/Copilot의 파일 소유권을 분리해 환경·데이터 준비부터 재개한다. 코디네이터는 구현을 대신하지 않고 검토·독립 검증·통합을 담당한다.

## 2026-10-30 재개 진행 기록

사용자가 명시적으로 재개했고 효율적인 Gemini/Copilot 다중 세션 병렬 처리를 승인했다. 위 중단 기록은 과거 상태이며 현재 실행은 같은 Run `run_1de5424dff38`에서 계속한다.

- ENV 재시도 `ctx_7cd1f12705c1`: Windows 관리자 권한 부족(DISM740)으로 WSL GPU 준비는 미완료. Python3.11.9/TF2.19.1 CPU venv 및 NumPy/Pillow venv 준비; 스크립트 검토 진행 중. 재부팅하지 않음.
- DATA 재시도 `ctx_559fc95f99de`: Surface9558 + Cityscapes3475, 총13033 레코드 manifest 생성. 그룹/이미지해시 split 중복 없음. 실제24표본 변환 완료; 전체 변환/학습은 미실행. 의사 라벨 실제 추론 검증 진행 중.
- MODEL `task_00daade82803` / `ctx_ed32c47e755b`: 성공 보고 수용, TF 환경 독립 테스트24개 통과, worker-release released/captured 확인.
- EVAL `task_d7a123efe6e5` / `ctx_94b2bf8bfb19`: 독립 테스트30개 통과. release 요청은 런타임이 user_takeover 이유로 retained 처리; 강제 종료하지 않음.
- RUNNER `task_4c73907781ee` / `ctx_b2f0cbd1dc9d`: train.py/export.py/해당 테스트/문서 및 Python ignore 규칙 전용 소유. 실행 가능한 단계별 학습/복원/내보내기 구현 검토 중.
- LOADER `task_e26c5794da37` / `ctx_3d9c9cc3fb4a`: train_data.py/TRAIN_DATA_CONTRACT.md/tests/test_train_data.py 전용 소유. RUNNER와 API 공유, 파일 편집 충돌 없음.
- 코디네이터 독립 검사: manifest13033 ID고유성·그룹/해시 split분리·원본4이미지 해시 대조 통과. assembleDebug/testDebugUnitTest/lintDebug 성공(대부분 캐시). Pixel3 Android12 파이프라인4개, 방향1개, 설정13개 통과. 설정 테스트는 절전 상태에서 최초실패 후 화면깨움/키가드해제 후 재실행 성공.
- 새 모델의 정확도 향상/실기기속도/최종학습 성공은 아직 주장할 수 없음. 기존 앱 모델과 변경분 보존, commit/push 없음.

### 재개 추가 체크포인트 (2026-10-30, 진행 중)

- ENV 성공 보고 수용 후 release/captured. 코디네이터도 합성 CNN 2-step CPU 학습/냉복원/TFLite 변환·추론을 재검증. WSL host PowerShell 구문과 guest bash -n 통과. 실제 WSL 설치/재부팅은 아직 없음.
- LOADER 성공 보고 수용, 독립8tests + 실제 partialmanifest/pseudo/증강/3samplebatch 검사 통과 후 release/captured.
- EVAL-WARNING 보완 `task_3f4294b2f6c9` / `ctx_c718540bc07c`: 픽셀 점수를 실제 경고 정확도로 오인하지 않도록 automatic_warning_enablement=false, 경고단위 평가 지원없음 명시. 독립34tests 통과 후 release/captured.
- DATA 최신코드 독립16tests와24실제변환 표본 패딩/가중치/위험마스크 불변량 통과. fullconversion 승인 후 속도가 느려 프로파일: Surface1920x1080 마스크 매핑1.409s→packedRGB0.034s 정확일치. 본인 변환 subprocess를 정리한 뒤 최적화·재실행 중; 아직 전체변환완료 아님.
- RUNNER는 아직 수용 전. train.py 실제단계에 PortraitDataset 통합, 이전A단계checkpoint연결, resume_path처리, Bhead동결, C합격증거, classweights, 비어있는val오류 등 구체적인 보완을 요청. 합성smoke 통과만으로 실제단계정확성 수용하지 않음.
- 사용자에게 관리자 설치·필요시재시작 허용 여부를 비동기 질문으로 보냄. 답변 전 자동재부팅 금지 유지. 모든워커/부분작업 정리 후에만 재시작 고려.

### 교정 작업 소유권 (2026-10-30)

- 기존 RUNNER `ctx_b2f0cbd1dc9d`는 실제train_stage6개갭을 명시해 failed로 완료보고했고 release/captured 확인. 독립export6tests는 TF환경에서104.996s에 통과. train.py 전체수용은 아님.
- TRAINER-CORRECTION `task_8bf6c98a3dbf` / `ctx_054dcdff161f` (Copilot): train.py/tests/test_train.py/TRAINING.md 및 필요시tests/test_train_stage.py만 수정. 실제단계/엄격복원/로더연결 교정.
- EXPORT-CORRECTION `task_6e7c158e4217` / `ctx_23363701112a` (Copilot): export.py/tests/test_export.py/필요시test_export_contract.py/EXPORT_VALIDATION.md만 수정. 수동trueflag로경고활성화·누락hash·잘못된metadata·parity실패시기존artifact덮어쓰기 문제 교정. TRAINING.md는 trainer소유.
- DATA는4스레드출력동등성 검증후전체변환재실행. 24sample순수변환serial2.844s→4thread0.993s; 이전14.87s CLI측정에는13033manifest입출력 고정비가포함되어2시간선형추정은부정확했음. 06:15경 semantic출력2576개이상 확인. 원본·기존앱모델보존.
### 2026-10-30 추가 체크포인트 (이어받은 실행)

- 전체 Surface 학습 분할 6,647건 teacher inference 완료. completion_report는 complete=true, train record 6,647/6,647, pseudo 대상 708건, 나머지 5,939건 무라벨, 평가 GT 생성 false, 워커 4개. 교사 모델 SHA-256은 312105f5a82fc2677337ca7c15867249b3ae255a983fb5ffa4a54dae2b6864c1.
- 코디네이터 감사: 708 pseudo ID 모두 원 manifest의 Surface/train ID·그룹·이미지 해시와 일치, mask/weight 1,416파일 존재. 42개 표본에서 512x288, 허용 class, object-only pseudo ID, pseudo weight .25 및 그 외 ignore/0 불변량 통과.
- 독립 테스트: test_train_data 8개, test_data 13개, test_pseudo_labels 10개 통과.
- trainer 교정 test_train_stage.py 6개 중 2개 실패: train.py:1742에서 _iter_limited_batches generator에 yielded_samples 속성이 있다고 가정함. trainer 소유 워커에게 train_sample_count로 변경하고 regression test 후 재검증하도록 보고. 이 결함이 해소되기 전 실제 단계 학습은 승인할 수 없음.
- 4개 진행 워커 (trainer, WSL 경로, pseudo, Cityscapes map)는 Orca terminal running/live로 확인. 각 워커에 checkpoint 요청을 보냈으나 이 시점까지 worker_done/escalation 답변은 도착하지 않음. 종료 또는 release하지 않음.
- WSL 미설치/관리자 설치 및 필요한 Windows 재시작은 계속 대기. 실행 명세의 자동 재시작 금지와 별도 비동기 사용자 질문에 따라 답변 전 기능 변경·재부팅을 하지 않음. GPU full training 및 개선 정확도는 미완료.
- 이후 코디네이터 실행 test_train.py 7개도 통과 (CPU/TFLite 합성 smoke, parity 최대 절대차 2.98e-8). 실제 데이터 수렴/정확도 주장은 여전히 하지 않음. `git diff --check`는 오류 없이 통과; Git이 LF→CRLF 변환 예정이라는 기존 경고만 표시.

### 사용자 정책 변경 및 새 세션 (2026-10-30)

- 사용자가 Antigravity/Copilot의 여러 공급자 모델을 난이도에 따라 선택하고 실제 사용량 초과 시 다른 모델로 전환하도록 승인했다. 기존 고정 모델 조항보다 우선하는 정책을 AGENTS.md와 위임 문서에 기록했다. 무응답 자체를 quota 소진 근거로 간주하지 않으며 현재까지 실제 quota 오류는 미확인이다.
- 사용자가 현재 무응답 세션을 정리하고 새 세션으로 시작하도록 명시했다. 기존 네 세션이 user_owned여서 worker-stop은 stop_unknown/processAction=none을 반환했다. 각각 worker-abandon으로 권한을 차단한 뒤 사용자 지시에 따른 정확한 terminal close로 네 PTY 모두 ptyKilled=true를 확인했다. 부분 코드·산출물은 보존했다.
- 같은 Task의 retry-of 새 Dispatch: trainer ctx_9958bffe3d34 (Claude Sonnet 5.5 High), WSL paths ctx_c5710792a047 (Gemini 3.8 Flash Medium), pseudo ctx_bf0d23851d1f (Gemini 3.8 Flash Medium), City label ctx_c31744baaf09 (Gemini 3.8 Flash Low). 모두 Antigravity를 통해 실행했고 requested/effective model 일치 및 turnStart=observed를 확인했다. 실제 작업·heartbeat가 진행 중이다.
- 현재 Orca worker-start는 Antigravity model override를 지원하나 Copilot model override는 지원 목록에 없다. 전역 설정을 바꾸거나 추측 플래그를 사용하지 않고 이번 네 세션은 Antigravity의 실제 모델 catalog(agy models)에서 선택했다. 상세 이어받기·전용 소유권·재실행 금지는 VISION_TRAINING_RETRY_2026-10-30.md에 있다.
- City 새 세션 ctx_c31744baaf09: matching worker_done succeeded 확인, 독립 test_data 13개 재통과 후 release/released/captured 및 delivery ack 완료.
- Pseudo 새 세션 ctx_bf0d23851d1f: matching worker_done succeeded 확인, PSEUDO_EXECUTION.md 기록 및 독립 test_pseudo_labels 10개 재통과 후 release/released/captured 및 delivery ack 완료. 코디네이터 문서 검토에서 serial16/parallel16 보고서는 workers 필드가 다르고 두 샘플 모두 accepted0인 한계를 명시하도록 수정했다. 비어 있지 않은 배열의 병렬 동등성은 제어된 fake-interpreter 테스트로 검증한 범위이며 실데이터 비어있지 않은 마스크의 동등성까지 주장하지 않는다.
- Trainer 새 세션 ctx_9958bffe3d34: matching worker_done succeeded 확인. generator의 yielded_samples 참조를 실제 train_sample_count 검사로 교정하고 실제 CLI 진입점·단계 초기화·엄격한 복원·조기종료 복원 거절·장치 설정·OOM 복구 안내·검증용 표본 제한 등을 보완했다. 코디네이터 독립 test_train_stage 20개(65.542s), test_train 7개(28.879s) 통과, skip 없음. 단계 C의 실제 acceptance evidence wrapper 생성 도구는 아직 없어서 실제 C는 fail-closed이며, C 테스트는 validator stub/소형 모델 범위다. multi-epoch 실제 MobileNet 학습·중간 epoch 복원·OOM 자동 재시도는 미검증 또는 미지원임을 TRAINING.md에 명시했다. root 문서 검토에서 실행 예시를 TF venv와 완성된 pseudo_full manifest를 사용하도록 정정했다. release/released/captured 및 delivery ack 완료.
- WSL paths 새 세션 ctx_c5710792a047: matching worker_done succeeded 확인. 코디네이터 독립 test_train_data 21개(2.490s), skip 없이 통과. wslpath argument vector/shell=False/cache/공백 경로/도구 오류/경로 유효성/원본 geometry·metadata 보존을 mock으로 검증했다. 실제 WSL runtime은 미설치로 미검증이다. 보고서는 build/vision-training/wsl-paths/wsl_path_verification_report.json. release/released/captured 및 delivery ack 완료.
- 이번 새 세션 독립 검증 총 71개 통과(도시13 + pseudo10 + trainer20/7 + loader21). 완성된 13033-record manifest와 pseudo_full을 함께 연 실제 Windows loader에서 training_eligible=true 및 증강·균형 표본3개의 형상/가중치/split을 확인했다. tracked diff check 및 새 Python 파일 whitespace 검토에서 오류 없음(기존 LF/CRLF 경고만 있음).
- 새 네 Dispatch 모두 succeeded/released/archive captured, reclaimable=0 확인. 이전 무응답 네 세션은 user-requested cleanup으로 authority fencing + ptyKilled=true가 확인됐고 추가 편집 권한을 갖지 않는다. 실제 quota 초과 오류는 이번 실행에서도 확인되지 않았으며 난이도에 따른 모델 선택으로 모두 완료했다.
- 다음 재개 지점은 승인 대기 중인 WSL 관리자 설치/필요한 재시작과 GPU 환경 확인, 이후 실제 A/B 학습·held-out 평가다. 실행 명세의 자동 재시작 금지는 계속 유효하고 새 모델의 품질·수렴·실기기 성능 향상이나 앱 모델 교체 완료를 주장하지 않는다. 원본 데이터와 기존 앱 모델 보존, commit/push 없음.

### WSL installation confirmed and Git integration resumed (2026-10-30)

- User installed WSL: Ubuntu-26.04.1 LTS, WSL2 kernel 6.18.54.1, default user `user`; project access through `/mnt/c/Users/felice/orca/aria-app` verified. Earlier statements that WSL was absent describe historical checkpoints.
- WSL `nvidia-smi` detects GTX 1660 SUPER (6144 MiB), Windows driver 617.42. TensorFlow GPU readiness is a separate pending gate; no password sharing is required.
- App portrait/GPU pipeline, home preview, fixtures, tooling and documentation committed and pushed as `3e07a33`. Current `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` passed (mostly up-to-date). Focused Pixel 3 tests are running.
- Combined Python discovery exposed TensorFlow threading setup order errors in two `test_train_stage.py` classes after 130 tests. A dedicated worker owns only this test file; no quality or convergence claim follows from prior narrow suites.
- WSL environment Task `task_11723fcd12bf`: Copilot attempt `ctx_87bc39b6797e` showed an explicit monthly Copilot Free request exhaustion message before implementation; worker-stop confirmed stopped, closed_agent_terminal and ptyKilled=true. Retry `ctx_5b517f770642` requested/effectively selected Antigravity `gemini-3.8-flash-high` and observed turn start. Account identifiers/credentials are not recorded.
- Independent test-discovery Task `task_69ab37aeb4d4`, Dispatch `ctx_27971e27aadc`, requested/effectively selected `gemini-3.8-flash-medium`. It owns Windows test execution; the WSL worker exclusively owns GPU/guest installation, while the coordinator owns Android/device/Git.

### Independent regression verification and WSL kernel blocker

- Current Android assembleDebug/testDebugUnitTest/lintDebug succeeded; cached JVM reports contain208 tests. Pixel3 Android12 instrumented model migration7/7 and portrait production pipeline4/4 passed. A direct follow-up instrumentation attempt failed before test startup while test APK was absent; another18-test attempt was interrupted by the subsequent Gradle run and is not counted as passing. Final app installDebug succeeded after instrumentation cleanup.
- Coordinator independently reran full Python discovery:150tests in183.323s, OK, exit0, no skips (build/vision-training/coordinator-full-tests.log). Stage setup now tolerates only the specific post-initialization RuntimeError; unrelated exceptions still propagate. Matching succeeded worker_done accepted, released/captured and delivery acknowledged for ctx_27971e27aadc.
- WSL independent lscpu/proc CPU flags omit AVX/AVX2/XSAVE; root dmesg confirms `XSAVE consistency problem: size 832 != kernel_size 0` and `x86/fpu: XSAVE disabled`. GPU detection alone does not establish TensorFlow usability. TensorFlow import hits Illegal instruction; full GPU smoke and actual A/B training are blocked.
- Primary references: https://www.tensorflow.org/install/pip (AVX requirement), https://github.com/microsoft/WSL/issues/14436 (same Ryzen3600 XSAVE symptom; reported5.15.153 kernel workaround is not independently validated here). Do not treat that report as permission to replace the kernel or assert Ubuntu26.04 compatibility.

### Environment attempt closed with reproducible blocker

- WSL isolated Python3.11.18/TF2.19.1/Keras3.15.1/NumPy2.1.3/Pillow13.0.0 installation metadata independently confirmed. TF import via wsl -e independently exited4; XSAVE/AVX still disabled. No GPU training success.
- Full-manifest loader diagnostic exceeded10minutes; exact PID337 verified, SIGTERM sent and exit confirmed. Loader runtime is not accepted. The worker continued diagnostics beyond narrowed scope; coordinator deliberately cancelled ctx_5b517f770642 and confirmed stopped/closed_agent_terminal/ptyKilled=true. Task failed, no successful worker_done accepted.
- Worker invoked WSL shutdown during diagnosis; guest restarted without resolving XSAVE. The original6.18.54.1 kernel remains, no user .wslconfig present, no Windows reboot performed, and final ps contains no remaining training/loader diagnostic. Impact on pre-existing interactive WSL processes is unknown.
- Current environment verdict, historical22.04-only script limitation, primary references and next gates are documented in tools/vision/training/ENVIRONMENT.md. Core code was not modified by the coordinator. No full training, raw conversion or pseudo rerun occurred. The test worker is succeeded/released/captured; both environment launch terminals were stopped with ptyKilled=true. Reclaimable workers0.

### WSL XSAVE/AVX resolved and independently accepted (2026-10-30)

- User explicitly requested researching similar internet cases and resolving the blocker, then continuing. New Task task_c5c14c76ed97 authorized a reversible official-kernel override and scoped WSL restart, preserving original WSL app/distro, built-in kernel, data and CPU venv. No Windows reboot, firmware/security/boot-setting change, full training or pseudo rerun.
- Primary matching case: https://github.com/microsoft/WSL/issues/14436. Official Microsoft WSL2.3.26 release https://github.com/microsoft/WSL/releases/tag/2.3.26 includes kernel5.15.167.4 and an XSAVES patch. Signed official MSI extracted without installing/downgrading WSL app. Persistent kernel SHA256 A304ABE9C0A0C20EDE269A318CD3AFF5434A824A752C2F940E6E2CB33DBE2043 matches extracted source; coordinator independently confirmed Valid Microsoft signature. User .wslconfig absent state recorded; override and exact rollback documented in tools/vision/training/ENVIRONMENT.md.
- Current kernel5.15.167.4 exposes XSAVE/AVX/AVX2/FMA; TF2.19.1 imports successfully and actual matmul/conv2d/gradients execute on GTX1660SUPER GPU:0 (CUDA build12.5.1, loaded cuDNN9.3). Strict100step synthetic GPU training27.97s, cold model/optimizer checkpoint restore and dual-head TFLite export/inference pass.
- Worker12record real loader diagnostic passed47.181s. Coordinator separately reran GPU ops and loader: PASS/exit0; loader46.353s,12records,248pseudo pixels, original base and pseudo manifests hash-unchanged. Independent modified-script Windows CPU2step/checkpoint/export test also passed. No full-dataset or accuracy/convergence claim.
- First Sonnet dispatch ctx_442216a87984 hit actual Individual quota reached (36min reset); stopped/closed_agent_terminal/ptyKilled=true before retry. Requested/effective retry ctx_d75e6cf6091d used gemini-4-argon-high. Matching succeeded worker_done accepted, released/captured, ack completed, reclaimable0. Coordinator authored documentation/verification/Git integration only; worker authored code/config.
- Next training gate: pass native MobileNet bounded multi-epoch GPU training and restore using process-local CUDA library/tool environment; current diagnostic helpers bootstrap only their own process. Preserve full manifest/pseudo_full and existing app assets. GPU environment blocker is resolved; actual A/B training and held-out accuracy remain future work.
