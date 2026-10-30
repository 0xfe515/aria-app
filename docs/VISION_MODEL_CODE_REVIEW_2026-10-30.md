# 비전 모델 코드 검토 및 재학습 판단

검토일: 2026-10-30. 검토 대상: `tulg580/vision_model`, `main` 커밋 `bfb11e64153d5131c4aaf07c27f907f65fd59461`.

## 결론

재학습은 타당한 개선 후보지만 **데이터 분할·부분 라벨 처리·실제 앱 입력에 맞춘 평가를 먼저 수정해야 한다.** 현재 검증 점수만으로 모델 개선을 판단하면 같은 촬영 묶음의 유사 장면과 불완전한 객체 정답 때문에 잘못된 모델을 선택할 수 있다. 다음으로 학습 입력을 앱의 세로형 입력에 맞추고, 그 뒤에 디코더 경량화를 별도 실험하는 순서가 적절하다.

이번 작업은 코드 검토와 읽기 전용 데이터 조사다. 모델·학습 코드·데이터를 변경하거나 재학습하지 않았다.

## 검토 범위와 증거

- 원격 저장소를 `build/model-review/upstream`에 내려받아 커밋을 고정했다. 해당 경로는 Git에서 무시된다. 별도 로컬 `C:/Users/felice/orca/vision_model`의 Python 11개 파일도 내려받은 코드와 바이트 해시가 모두 같았다.
- 업스트림 원본 `walkable_seg_aria5.tflite`와 앱에 포함된 동일 이름 파일의 SHA-256은 모두 `3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7`이다. 따라서 검토한 저장소가 앱 원본 모델과 연결됨을 확인했다. 다만 이 해시만으로 과거의 정확한 학습 실행 설정까지 증명되지는 않는다.
- 바탕화면의 Surface_1에서 코드와 같은 방식으로 이미지/마스크 9,558쌍을 확인하고, 정렬→seed 42 shuffle→2,000개 선택→검증 15% 분할을 재현했다. 과거 실행의 분할 manifest가 없어 실제 과거 분할과 동일하다는 보장은 없다.
- 현재 작업 트리의 앱은 세로 모델 `[1,512,288,3]`과 `FULL_FRAME_UPRIGHT_LETTERBOX`를 사용한다. 아직 커밋되지 않은 앱 변경은 보존했다.
- 사용 가능한 기본 Python에는 TensorFlow, NumPy, Pillow, OpenCV가 없다. 별도 로컬 모델 저장소에는 `.keras` 체크포인트가 없다. 이번에는 추론 정확도·학습 수렴·실기기 속도를 새로 측정하지 않았다. 아래 과거 성능 수치는 업스트림 README 또는 기존 앱 실험 문서의 기록이다.

## 우선순위별 발견 사항

### 1. 높음 — 촬영 묶음이 학습·검증에 섞인다

근거: [prepare_aihub.py:65–74](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/prepare_aihub.py#L65-L74).

개별 이미지를 무작위로 나누며 `Surface_###` 단위를 분리하지 않는다. 현재 데이터로 재현한 결과:

| 항목 | 결과 |
|---|---:|
| 학습 / 검증 이미지 | 1,700 / 300 |
| 학습 / 검증의 Surface 묶음 수 | 130 / 108 |
| 양쪽에 등장한 묶음 수 | 108 |
| 학습과 같은 묶음에 속한 검증 이미지 | 300 / 300 |
| 같은 묶음의 학습 이미지와 파일 번호 차이 ≤1인 검증 이미지 | 98 / 300 |
| 같은 묶음의 학습 이미지와 파일 번호 차이 ≤5인 검증 이미지 | 250 / 300 |

이는 동일 이미지 중복을 입증한 결과가 아니라, 인접 프레임 또는 유사 장면이 섞일 가능성을 보여주는 직접적인 구조적 근거다. 파일 번호를 실제 시간 간격으로 해석하지 않는다.

개선: 촬영 시퀀스·장소 단위의 group split과 독립 test를 먼저 고정하고 파일 목록·해시를 저장한다. Surface 디렉터리가 실제 촬영 단위인지 XML/메타데이터로 확인하고, 같은 장소가 여러 디렉터리에 걸치면 더 큰 단위로 묶는다. 검증에 사용한 장소를 다시 학습에 넣고 같은 점수를 비교해서는 안 된다.

### 2. 높음 — Surface의 부분 라벨이 객체 인식을 억제할 수 있다

근거: [prepare_aihub.py:16–49](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/prepare_aihub.py#L16-L49), [seg_multi.py:69–75](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/seg_multi.py#L69-L75).

Surface 변환 결과에는 클래스 0~3만 존재한다. 나머지 모든 색상은 `other=0`으로 합쳐지고, CE와 Dice는 모든 픽셀을 정답으로 사용한다. Surface 이미지에 사람·차량·기둥이 보여도 이 객체들이 별도로 라벨링되지 않은 영역이면, 올바른 객체 예측이 `other` 정답에 의해 벌점을 받는다. 해당 객체가 얼마나 등장하는지는 이번에 전수 확인하지 않았다.

인코더를 고정해도 디코더와 마지막 분류층은 바뀌므로 객체 인식의 영구 보존은 보장되지 않는다. 실제 업스트림 README도 Cityscapes person `0.617→0.612`, vehicle `0.847→0.840`, pole_sign `0.412→0.404`를 기록한다. 이 하락의 원인을 부분 라벨 하나로 확정할 수는 없다.

개선: 우선 한국 데이터 일부에 사람·차량·기둥 정답을 보강한다. 불확실한 영역은 검토된 valid/ignore mask로 CE와 Dice 모두에서 제외하고, Cityscapes 재학습 혼합과 기존 모델 distillation을 비교한다. **모든 other를 일괄 ignore하면 실제 장애물·배경의 감독도 사라지므로 금지한다.** teacher 예측도 한국 환경에서 오류가 있을 수 있으므로 신뢰도 기준과 수동 점검이 필요하다.

### 3. 높음 — 학습과 실제 배포 입력의 기하가 다르다

근거: [prepare_aihub.py:95–109](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/prepare_aihub.py#L95-L109), [seg_multi.py:9](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/seg_multi.py#L9), [export_for_aria.py:7–23](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/export_for_aria.py#L7-L23).

- 학습: `H=224,W=512`. Surface는 화면 아래의 512:224 영역을 크롭한다. 1920×1080 표본에서 남기는 높이는 840, 위쪽 240픽셀을 제거한다.
- 최초 앱 export: `H=288,W=512`로 모델을 다시 만들고 같은 가중치를 옮긴다.
- 현재 앱 작업 트리: `H=512,W=288` 세로 모델로 전체 upright 프레임을 letterbox 처리한다. 480×640 입력은 288×384 내용과 위아래 각 64픽셀 패딩이 된다.

입력 모양 변환은 합성곱에서 가능하지만, 학습하지 않은 시야·물체 크기·검은 패딩에 대한 정확도를 보장하지 않는다. 기존 CamVid 실험에서도 portrait 모델은 portrait 장면에 유리했지만 landscape 전체 영역 점수는 낮아졌다. 이는 모든 방향의 보편적 개선 근거가 아니다.

개선: 배포와 동일한 세로 입력 및 전체 시야 letterbox로 이미지와 라벨을 함께 준비한다. 패딩은 학습 손실/지표에서 명시적으로 제외한다. 실제 촬영 시야와 객체 크기를 반영한 crop/scale 증강을 추가하고, 세로·가로·한국 실촬영을 분리 평가한다. 카메라 색 변환과 이미지 resize도 동일 입력을 넣어 비교한다.

### 4. 높음 — 평균 IoU만으로 체크포인트를 고르고, 배포 파일의 실제 정확도는 평가하지 않는다

근거: [train_finetune.py:74–83](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/train_finetune.py#L74-L83), [evaluate_multi.py:9–11](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/evaluate_multi.py#L9-L11), [export_for_aria.py:42–48](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/export_for_aria.py#L42-L48).

학습 선택 기준은 두 도메인을 합친 `val_miou` 하나다. `evaluate_multi.py`는 224×512 Keras 모델을 평가하며, 배포용 세로 TFLite와 앱 전처리를 통과한 결과가 아니다. export의 검증은 무작위 입력 하나의 Keras/TFLite 차이를 출력할 뿐, 허용 오차 초과를 실패 처리하거나 실제 라벨 정확도를 검사하지 않는다.

업스트림 README 기록에서 파인튜닝 후 Cityscapes 보행가능 정밀도는 `0.762→0.720`, 막힌 곳 재현율은 `0.986→0.982`로 하락했다. AI Hub 막힌 곳 재현율은 `0.877`이다. 작은 IoU 변화만으로 보행 판단이 유지됐다고 해석하기 어렵다.

개선: 한국/Cityscapes 각각의 클래스별 IoU, 장애물→보행가능 오인율, 보행가능 정밀도, 사람·차량 재현율을 함께 모델 선택 제약으로 둔다. 평가를 최종 TFLite CPU/GPU와 실제 앱 전처리까지 확장하고, 정답이 있는 근거리 ROI와 프레임 단위 위험 누락도 측정한다. 제한된 소규모 평가 수치를 일반 환경 성능으로 확장하지 않는다.

### 5. 중간~높음 — MobileNetV2보다 무거운 디코더가 속도 개선 후보다

근거: [seg_multi.py:60–65](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/seg_multi.py#L60-L65).

스킵 연결 뒤에 큰 채널의 일반 3×3 Conv2D를 네 번 사용한다. 모델 문서의 채널과 코드의 형상으로 계산하면 288×512 입력에서 **이 네 레이어만 약 4.42G MAC/frame**이다. 계산은 `H×W×Cin×Cout×9` 합계이며 encoder, transpose convolution, softmax 등을 제외한다. 세로형은 같은 면적이므로 이론 연산량은 같다. 실측 프로파일링 결과는 아니다.

| 출력 공간 H×W | 입력/출력 채널 | MAC |
|---|---|---:|
| 18×32 | 832→256 | 1,104,150,528 |
| 36×64 | 320→128 | 849,346,560 |
| 72×128 | 208→64 | 1,104,150,528 |
| 144×256 | 128→32 | 1,358,954,496 |

개선 후보: 1×1 스킵 채널 축소, depthwise-separable convolution, 채널 수 축소, resize+convolution 디코더. 기존 모델을 teacher로 두고 별도 student를 학습하는 실험이 적절하다. 이런 구조 변경은 기존 가중치를 그대로 옮기는 변환과 다르므로 재학습이 필요하며, depthwise/resize가 실제 Pixel 3 GPU에서 빠른지도 측정해야 한다. 입력 해상도 감소는 작은 장애물 정확도와 함께 검증한다.

원본 export의 fused `TRANSPOSE_CONV` GPU 폴백 문제는 앱의 unfused 그래프 변환에서 이미 해결한 사항이다. 현재 앱에도 남아 있는 미해결 문제로 다시 세지 않는다. 다만 새 모델을 export할 때 단일 GPU partition 및 CPU/GPU parity 검사는 다시 필요하다. FP16 가중치 저장과 GPU FP16 계산은 구분하며, 후자를 검증 없이 활성화하지 않는다.

### 6. 중간 — 세부 보행 정책을 라벨 변환이 일괄 결정한다

근거: [prepare_aihub.py:16–39](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/prepare_aihub.py#L16-L39).

자전거 도로, 파손 인도, 파손 점자블록을 모두 일반 sidewalk로 합친다. 맨홀·계단 등은 other로 합친다. 이 규칙이 실제 보행 안내 요구사항과 맞는지 명시적인 검토가 필요하다. 예를 들어 파손 인도의 모든 픽셀을 정상 인도와 동일하게 취급하면 단차·통행 위험을 현재 클래스 출력만으로 구별하기 어렵다.

개선: 제품의 통행 정책과 라벨 매핑 표를 먼저 확정한다. 필요하면 별도 위험 속성/보조 head를 검토하되, 7클래스 출력 계약을 바꾸는 경우 앱 변경을 포함한 별도 통합 작업이 된다. 색상표에 없는 값을 무조건 other로 숨기지 말고 unknown 색상 비율과 허용 목록을 기록한다. 8개 Surface 디렉터리의 첫 마스크를 격자 표본 조사했을 때 알려진 색상 및 0 배경이 확인됐으나 전체 팔레트 검증은 수행하지 않았다.

### 7. 중간 — void 픽셀, 희소 클래스, 데이터 다양성 처리가 부족하다

근거: [prepare_data_multi.py:13–22](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/prepare_data_multi.py#L13-L22), [seg_multi.py:35–41](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/seg_multi.py#L35-L41), [seg_multi.py:69–75](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/seg_multi.py#L69-L75).

Cityscapes 변환 LUT는 미등록 labelId를 모두 other로 만들고 별도 ignore 값을 유지하지 않는다. 이 경우 원래 void/미평가 영역과 실제 other를 구분할 수 없다. 또한 CE는 픽셀 평균이고 Dice는 이미지별 모든 클래스를 평균하므로, 희소/미등장 클래스와 부분 라벨의 영향을 분리해 관찰할 필요가 있다. 이것만으로 Dice 구현이 틀렸다고 단정하지 않는다.

증강은 좌우 반전·밝기·대비뿐이고 Surface 9,558쌍 중 기본 설정상 2,000쌍만 선택한다. 나머지를 무조건 추가하기보다 그룹 분리 후 야간·역광·비·작은 사람·볼라드 등의 분포를 먼저 조사해야 한다.

개선: void를 배경과 분리하고 CE/Dice/IoU에 같은 valid mask를 적용한다. 클래스 빈도와 작은 객체 recall을 측정한 뒤 class weighting 또는 focal/Tversky 계열 손실을 대조 실험한다. 무작위 crop/scale·blur·노출·촬영 자세 증강을 실제 환경에 맞추며, 작은 장애물을 제거하는 증강은 피한다.

### 8. 중간 — 재현성과 배포 출처 기록이 부족하다

근거: [export_for_aria.py:10–16](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/export_for_aria.py#L10-L16), [train_finetune.py:18–40](https://github.com/tulg580/vision_model/blob/bfb11e64153d5131c4aaf07c27f907f65fd59461/train_finetune.py#L18-L40).

파인튜닝 파일이 없으면 export/evaluate가 기본 모델로 자동 전환한다. 이름은 같은 배포 파일로 저장되므로 잘못된 체크포인트를 배포할 수 있다. shuffle·증강·데이터 혼합·가중치 초기화의 통합 seed, 버전 lock, 분할 manifest와 체크포인트/데이터/산출물 해시의 연결 기록도 코드에 없다. 출력 폴더를 비우지 않고 준비 코드를 다시 실행하면 예전 실행의 이미지가 남을 수 있다.

개선: 모델 입력 경로를 필수로 지정하고 누락 시 실패 처리한다. 실행별 새 데이터 출력 디렉터리와 manifest를 사용하고 train/val 중복을 검사한다. TensorFlow/Keras/변환기 버전, seed, Git 커밋, 데이터 분할, 모델 hash, 평가 결과를 하나의 run 기록으로 남긴다. 원본 `.keras` 체크포인트를 확보하지 못하면 TFLite만으로 원래 Keras 학습 상태를 그대로 재개할 수 없으므로 기본 학습부터 다시 하는 경로도 준비해야 한다.

## 재학습 전후 권장 실험 순서

| 순서 | 실험 | 통과 기준 |
|---|---|---|
| 1 | group split·독립 test·부분 라벨·void 처리 정비 | 그룹/중복 검사 통과, 매핑 표 수동 검토, 독립 test 고정 |
| 2 | 현재 모델을 최종 앱 전처리로 재평가 | 한국/Cityscapes, 근거리 ROI, 객체별 지표와 기기 지연 baseline 확보 |
| 3 | 구조 유지, 세로형 입력으로 파인튜닝 | 같은 독립 test에서 baseline보다 위험 오인 감소, 객체 재현율 유지 |
| 4 | encoder 상위 블록 일부 해제 및 distillation 대조 | 한국 환경 개선과 기존 객체 보존을 함께 확인; BN 동작을 명시 |
| 5 | 디코더 경량 student 학습 | 동일 조건에서 정확도 허용 범위를 충족하면서 실기기 p50/p95·발열·메모리 개선 |
| 6 | 최종 FP16 export 및 GPU 그래프 검증 | tensor/정규화 계약, CPU/GPU parity, GPU partition, 앱 전체 파이프라인 회귀 통과 |

수치 합격선은 독립 baseline을 얻은 뒤 적용 환경에 맞춰 사전에 고정한다. 모델 선택용 validation과 최종 보고용 test를 분리하며, 성능 표에는 시험 장면 수·분할·입력 방식·장치·온도·백엔드·invoke와 전체 파이프라인 시간을 함께 기록한다. 이번 코드 검토 자체는 모델 정확도 향상이나 재학습 성공의 실측 근거가 아니다.

## 관련 로컬 문서

- [학습 데이터 이력](VISION_TRAINING_DATA.md)
- [기존 GPU 최적화와 검증](VISION_VALIDATION.md)
- [세로형 비교 평가 및 제한](VISION_PORTRAIT_EVALUATION.md)
- [현재 세로형 앱 통합](VISION_PORTRAIT_PRODUCTION.md)
