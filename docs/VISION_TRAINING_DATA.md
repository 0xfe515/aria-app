# 비전 모델 학습 데이터 이력

확인일: 2026-10-30

후속 조사: [모델 코드 검토 및 재학습 판단](VISION_MODEL_CODE_REVIEW_2026-10-30.md)에 업스트림 코드와 실제 데이터 분할을 대조한 문제점 및 개선 순서를 기록했다.

## 학습 단계와 근거

사용자는 현재 사용하는 비전 모델의 학습 이력을 다음과 같이 확인했다. 바탕화면 `dataset/cityscapes`는 원래 모델 학습에 사용한 데이터셋이며, `dataset/Surface_1`은 이후 한국 보도 환경에 맞추어 파인튜닝할 때 사용한 데이터셋이다. 이 단계별 용도는 사용자 제공 정보이며, 로컬 폴더 구조와 파일 수는 별도로 직접 확인했다.

| 단계 | 데이터 / 산출물 | 역할 |
|---|---|---|
| 최초 학습 | `dataset/cityscapes` | 기본 장면 분할 모델 학습 |
| 후속 파인튜닝 | `dataset/Surface_1` | 한국 보도 환경에 맞춘 모델 튜닝 |
| 앱 실행 최적화 | `walkable_seg_aria5_unfused.tflite` | 학습된 가중치를 보존하는 GPU 실행 그래프 변환 |
| 세로 입력 대응 | `walkable_seg_aria5_portrait_unfused.tflite` | 가중치를 보존한 세로형 텐서·그래프 변환, 추가 학습 없음 |

앞의 두 단계가 학습 이력이다. 뒤의 두 단계는 [모델 최적화 문서](VISION_MODEL.md)와 [세로형 모델 문서](VISION_PORTRAIT_MODEL.md)에 기록된 앱 통합 작업이다. 현재 작업 트리의 세로형 기본 모델 적용은 [프로덕션 파이프라인 문서](VISION_PORTRAIT_PRODUCTION.md)에 설명되어 있다. 작업 트리에는 아직 커밋되지 않은 세로형 통합 변경이 있으므로 Git 커밋 상태와 구분한다.

## 로컬 데이터 확인

확인 위치는 Windows 바탕화면의 `dataset` 폴더다. 사용자가 말한 cityscape의 실제 디렉터리 이름은 `cityscapes`다. 데이터 자체는 저장소에 복사하지 않았다.

### Cityscapes

| 디렉터리 | train 파일 수 | val 파일 수 | test 파일 수 | 합계 |
|---|---:|---:|---:|---:|
| `leftImg8bit` | 2,975 | 500 | 1,525 | 5,000 |
| `gtFine` | 11,900 | 2,000 | 6,100 | 20,000 |

전체 파일은 PNG 20,000개와 JSON 5,000개로 총 25,000개다. `gtFine` 수치는 주석 파일 수이며 이미지 장면 수가 아니다. 이 로컬 사본에 test/val 폴더가 있다는 사실만으로 해당 분할을 학습에 사용했다고 판단하지 않는다.

### Surface_1

- `Surface_001`부터 `Surface_130`까지 130개 디렉터리.
- JPG 9,558개, PNG 9,558개, XML 130개: 총 19,246개 파일.
- JPG 예: `Surface_001/MP_SEL_SUR_000001.jpg`.
- PNG 예: `Surface_130/MASK/MP_SEL_SUR_009829.png`.
- XML 표본 `Surface_001/15_SM0915_01.xml`은 `annotations` 루트와 이미지별 주석을 포함한다. 첫 이미지 항목은 1920×1080이다.
- 해당 XML의 라벨 정의: `caution_zone`, `bike_lane`, `alley`, `roadway`, `braille_guide_blocks`, `sidewalk`. `roadway`와 `alley`의 속성에 `crosswalk`가 포함된다.

이 목록은 파일 현황과 XML 한 개의 구조 확인 결과다. 전체 이미지·마스크의 일대일 대응, PNG 색상/클래스 인코딩, 모든 XML의 일관성을 검증한 결과는 아니다. XML 라벨과 앱의 7개 출력 클래스 사이의 정확한 변환 규칙은 학습 코드를 확인해야 한다.

## 기존 저장소 내용과의 관계

[기존 통합 가이드](VISION_INTEGRATION.md)는 초기 Cityscapes 매핑에서 출력 클래스 3(`crosswalk`)이 미학습이었다고 설명한다. 이는 최초 학습 단계의 설명이며 후속 Surface_1 파인튜닝을 포함한 전체 이력으로 일반화하지 않는다. [모델 규격 문서](VISION_MODEL.md)는 이미 AI Hub 파인튜닝을 반영한 횡단보도 클래스를 기록하고 있다. 이번 확인은 한국 보도 환경 파인튜닝에 사용한 로컬 데이터가 `Surface_1`이라는 정보를 추가한다.

앱의 7개 출력 클래스는 `other`, `road`, `sidewalk`, `crosswalk`, `person`, `vehicle`, `pole_sign`이다. 데이터에 횡단보도 속성이 존재한다는 사실만으로 최종 모델의 클래스별 학습량이나 정확도를 입증할 수는 없다.

기존 [방향 평가](VISION_ORIENTATION_EVALUATION.md)와 [세로형 평가](VISION_PORTRAIT_EVALUATION.md)는 CamVid 기반의 제한된 실험이다. 해당 수치를 한국 보도 데이터의 독립 시험 성능으로 설명하지 않는다.

## 아직 확인되지 않은 학습 세부 사항

정확한 학습 투입 파일 목록, Surface_1의 train/val/test 분할, 클래스 변환 코드, 증강·전처리, 학습률·에포크, 체크포인트와 최종 TFLite의 연결 기록은 이번 확인만으로 확정하지 않았다. 위 파일 수는 로컬 보유량이며 실제 학습 투입량이 아니다. 한국 환경 성능을 추가 평가할 때는 학습에 사용한 Surface_1 장면과 분리된 평가 자료인지 먼저 확인해야 한다.
