# Vision Training Model and Loss API Contract

**Task ID**: ARIA-PORTRAIT-MODEL-001
**Run ID**: `run_1de5424dff38` | **Dispatch ID**: `ctx_ed32c47e755b`
**Status**: Implemented & Verified API Contract (Wave 1)
**Author**: Implementation Worker (`gemini-3.8-flash-high`)
**Scope**: `tools/vision/training/model.py`, `losses.py`, `MODEL_CONTRACT.md`, `tests/test_model.py`, `tests/test_losses.py`

---

## 1. Overview & Architectural Principles

This contract establishes the formal specification for native portrait semantic segmentation and risk/hazard estimation architectures, masked multi-task losses, and export interfaces for Aria.

### Core Invariants
1. **Native Portrait NHWC Input**: Training and inference operate directly on upright portrait RGB tensors of shape `[B, 512, 288, 3]`. No landscape training or post-hoc spatial transpose/permute.
2. **Explicit Single Normalization**: Input normalization happens **exactly once** in the computational graph.
   - For training pipelines feeding `[0, 255]` float32 RGB: `layers.Rescaling(1.0 / 127.5, offset=-1.0)` maps `[0, 255]` to `[-1, 1]` for MobileNetV2.
   - For export wrappers feeding `[0, 1]` float32 RGB (Aria production): `build_export_model` instantiates the identical architecture with `input_range="0_1"` (`layers.Rescaling(2.0, offset=-1.0)` directly mapping `[0, 1]` to `[-1, 1]`) and copies all trained weights directly. There are no redundant wrapper layers or double-scaling operations.
   - Numerical equivalence: For $x \in [0.0, 1.0]$, $x \times 2.0 - 1.0 \equiv (x \times 255.0) \times (1 / 127.5) - 1.0$.
3. **Class Indices & Canonical Ordering**:
   - **Semantic Output (7 classes)**:
     `0: other`, `1: road`, `2: sidewalk`, `3: crosswalk`, `4: person`, `5: vehicle`, `6: pole_sign`.
     Walkable classes: `[2, 3]` (`sidewalk`, `crosswalk`).
   - **Hazard / Risk Output (8 channels)**:
     `0: bike_lane`, `1: damaged_surface`, `2: stairs`, `3: manhole`, `4: tree_zone`, `5: grating`, `6: repair_zone`, `7: speed_bump`.
4. **Masked Supervised Loss Rules**:
   - Ignore label `255` and pixel weight `0.0` produce strictly **zero loss and zero gradient**.
   - Dice loss uses linear (un-squared) provenance weighting ($w \cdot T \cdot P$, $w \cdot T$, $w \cdot P$), guaranteeing that perfect predictions with pseudo weight (e.g. 0.25) achieve exactly 0.0 Dice loss.
   - Absent supervised classes in partial annotations (e.g., Surface without person/vehicle labels) are excluded from Dice calculation.
   - Frequency class weights are inverse square-root clipped to `[0.5, 3.0]`.
   - Hazard supervision uses masked focal BCE (`gamma = 2.0`, weight `0.5`).
   - Model C student distillation uses softened KL divergence (`T = 2.0`, weight `0.2`).
   - All custom Keras Loss classes override `__call__` so that spatial pixel weights passed via `loss_fn(y_true, y_pred, sample_weight=...)` reach pixel masking logic directly without post-hoc scalar reduction corruption.
5. **Import Safety**:
   - All modules (`model.py`, `losses.py`, and test suites) are import-safe on standard Python environments where TensorFlow 2.19 / NumPy are not yet installed.
   - Pure-Python numerical reference implementations (`py_masked_ce`, `py_masked_dice`, `py_masked_focal_bce`, `py_kl_distillation`) provide verifiable algorithm ground truth for non-TF test runners.

---

## 2. Model Architectures

### Architecture A: Native Portrait MobileNetV2 / U-Net
- **Backbone**: `keras.applications.MobileNetV2(input_shape=(512, 288, 3), include_top=False, weights=weights)`.
- **Skip Connections (5 levels)**:
  - `block_1_expand_relu`: spatial shape `(256, 144)`, 96 channels.
  - `block_3_expand_relu`: spatial shape `(128, 72)`, 144 channels.
  - `block_6_expand_relu`: spatial shape `(64, 36)`, 192 channels.
  - `block_13_expand_relu`: spatial shape `(32, 18)`, 576 channels.
  - `block_16_project` (bottleneck): spatial shape `(16, 9)`, 160 channels.
- **Decoder**:
  - Step 1: Up from `(16, 9)` -> `Conv2DTranspose(256, 3, strides=2, padding="same", activation="relu")` -> `(32, 18)`, concat with `block_13_expand_relu` -> `Conv2D(256, 3, padding="same", activation="relu")`.
  - Step 2: Up from `(32, 18)` -> `Conv2DTranspose(128, 3, strides=2, padding="same", activation="relu")` -> `(64, 36)`, concat with `block_6_expand_relu` -> `Conv2D(128, 3, padding="same", activation="relu")`.
  - Step 3: Up from `(64, 36)` -> `Conv2DTranspose(64, 3, strides=2, padding="same", activation="relu")` -> `(128, 72)`, concat with `block_3_expand_relu` -> `Conv2D(64, 3, padding="same", activation="relu")`.
  - Step 4: Up from `(128, 72)` -> `Conv2DTranspose(32, 3, strides=2, padding="same", activation="relu")` -> `(256, 144)`, concat with `block_1_expand_relu` -> `Conv2D(32, 3, padding="same", activation="relu")`.
  - Final decoder features: `Conv2DTranspose(16, 3, strides=2, padding="same", activation="relu", name="decoder_final_features")` -> shape `(B, 512, 288, 16)`.
- **Semantic Head**:
  - `Conv2D(7, 1, activation="softmax", name="semantic_output")` applied to `decoder_final_features`.
- **Batch Normalization & Fine-Tuning**:
  - `freeze_batch_normalization(model)`: sets `layer.trainable = False` for all `BatchNormalization` layers, freezing moving statistics across all training stages.
  - `set_encoder_trainability(model, policy)`:
    - `"freeze_all"`: all encoder layers frozen.
    - `"block13_plus"`: unfreeze blocks `block_13` through `block_16_project` and downstream layers; keep `block_0` through `block_12` frozen (BN layers remain frozen).
    - `"unfreeze_all"`: all encoder layers trainable (BN layers remain frozen).

### Architecture B: Shared Final Decoder with Multi-Label Risk Head
- **Backbone & Decoder**: Identical to Architecture A up to `decoder_final_features` `[B, 512, 288, 16]`.
- **Semantic Head**: `Conv2D(7, 1, activation="softmax", name="semantic_output")(decoder_final_features)`.
- **Risk / Hazard Head**:
  - `Conv2D(16, 3, padding="same", activation="relu", name="risk_conv3x3")(decoder_final_features)`.
  - `Conv2D(8, 1, activation="sigmoid", name="hazard_output")(risk_conv3x3)`.
- **Outputs**:
  - Dual outputs: `[semantic_output, hazard_output]`.
- **Checkpoint Compatibility**:
  - `attach_risk_head(model_a)` transfers weights from pre-trained Model A checkpoint into Model B, initializing the new 2 risk layers while preserving semantic performance.

### Architecture C: Lightweight Bilinear-Upsample Projected Skip Decoder
- **Backbone**: MobileNetV2 with identical skip connections.
- **Lightweight Decoder**:
  - Bilinear upsampling: `UpSampling2D(size=(2, 2), interpolation="bilinear")` at all 4 stages and final stage per execution contract.
  - Skip projection: `Conv2D(f // 2, 1, padding="same", activation="relu")` projects large encoder skip channel depths down prior to concatenation.
  - Separable Convolution: `SeparableConv2D(f, 3, padding="same", activation="relu")`.
  - Channel progression: `[128, 64, 32, 16]`.
  - Final decoder features: `SeparableConv2D(16, 3, padding="same", activation="relu", name="decoder_final_features")`.
- **Heads**: `semantic_output` (7 classes), and optional `hazard_output` (8 channels).
- **Distillation**: Trained with supervised loss + `0.2 * KL_distillation(T=2.0)` from Model B.

---

## 3. Loss Functions & Formulations

### 1. Weighted Masked Categorical Cross-Entropy (`MaskedSparseCategoricalCrossentropy`)
$$\mathcal{L}_{\text{CE}} = \frac{\sum_{b, h, w} m_{b,h,w} \cdot s_{b,h,w} \cdot w_{y_{b,h,w}} \cdot \left(-\log \hat{y}_{b,h,w, y_{b,h,w}}\right)}{\sum_{b, h, w} m_{b,h,w} \cdot s_{b,h,w} \cdot w_{y_{b,h,w}} + \epsilon}$$
where:
- $m_{b,h,w} = \mathbb{I}(y_{b,h,w} \neq 255 \land s_{b,h,w} > 0.0)$ is the valid pixel mask.
- $s_{b,h,w}$ is the sample / pixel provenance weight: $1.0$ for real labels, $0.25$ for pseudo-labels, $0.0$ for ignore/void.
- $w_c \in [0.5, 3.0]$ is the clipped inverse square-root class frequency weight.
- Any pixel with $y=255$ or $s=0.0$ receives zero loss and exactly zero gradient.

### 2. Masked Dice Loss with Linear Provenance (`MaskedDiceLoss`)
For each sample $b$ and class $c \in \{0, \dots, 6\}$:
$$\text{inter}_{b, c} = \sum_{h, w} w_{b,h,w} \cdot \mathbb{I}(y_{b,h,w} = c) \cdot \hat{y}_{b,h,w, c}$$
$$\text{target\_sum}_{b, c} = \sum_{h, w} w_{b,h,w} \cdot \mathbb{I}(y_{b,h,w} = c)$$
$$\text{pred\_sum}_{b, c} = \sum_{h, w} w_{b,h,w} \cdot \hat{y}_{b,h,w, c}$$
$$\text{Dice}_{b, c} = 1.0 - \frac{2 \cdot \text{inter}_{b, c} + \text{smooth}}{\text{target\_sum}_{b, c} + \text{pred\_sum}_{b, c} + \text{smooth}}$$
- Linear weighting $w$ ensures that when $\hat{y} == y$, $2 \cdot \text{inter} == \text{target\_sum} + \text{pred\_sum}$, resulting in **strictly 0.0 loss for perfect predictions regardless of pseudo weight**.
- **Absent-Class Exclusion**: If $\text{target\_sum}_{b,c} == 0$, class $c$ is excluded from the sample Dice loss average:
$$\mathcal{L}_{\text{Dice}, b} = \frac{\sum_{c: \text{target\_sum}_{b,c} > 0} w_c \cdot \text{Dice}_{b, c}}{\sum_{c: \text{target\_sum}_{b,c} > 0} w_c + \epsilon}$$

### 3. Masked Focal Binary Cross-Entropy (`MaskedFocalBinaryCrossentropy`)
For hazard outputs $p_{b,h,w,k} \in [0, 1]$ and targets $y_{b,h,w,k} \in \{0, 1\}$, with validity mask $v_{b,h,w,k} \in \{0, 1\}$:
$$p_t = y \cdot p + (1 - y) \cdot (1 - p)$$
$$\text{FL}(p, y) = -(1 - p_t)^\gamma \cdot \left(y \log(p + \epsilon) + (1 - y)\log(1 - p + \epsilon)\right)$$
$$\mathcal{L}_{\text{Focal}} = \frac{\sum_{b,h,w,k} v_{b,h,w,k} \cdot \text{FL}(p_{b,h,w,k}, y_{b,h,w,k})}{\sum_{b,h,w,k} v_{b,h,w,k} + \epsilon}$$
- $\gamma = 2.0$. Weight in multi-task loss is $0.5$.
- Uncovered/ambiguous hazard regions have $v=0$ and produce strictly zero loss/gradient.

### 4. KL Distillation Loss (`KLDistillationLoss`)
Softened probability distributions at temperature $T=2.0$:
$$q^{\text{teacher}}_c = \frac{\exp(z^{\text{teacher}}_c / T)}{\sum_j \exp(z^{\text{teacher}}_j / T)}, \quad q^{\text{student}}_c = \frac{\exp(z^{\text{student}}_c / T)}{\sum_j \exp(z^{\text{student}}_j / T)}$$
$$\mathcal{L}_{\text{KL}} = T^2 \cdot \sum_c q^{\text{teacher}}_c \log \left(\frac{q^{\text{teacher}}_c}{q^{\text{student}}_c + \epsilon}\right)$$
- Applied over valid pixels $m_{b,h,w} == 1$, weighted by $0.2$.

---

## 4. Export Contract (Aria Production Compatibility)

### Function: `build_export_model(model, batch_size=1, name="aria_export_model")`
- **Input Spec**:
  - Name: `image_input`.
  - Shape: `[1, 512, 288, 3]`.
  - Type: `float32`.
  - Value Range: `[0.0, 1.0]` (standard normalized float from Android `Bitmap` or camera frame).
- **Internal Normalization**:
  - Maps `[0.0, 1.0]` directly to `[-1.0, 1.0]` via `layers.Rescaling(2.0, offset=-1.0)`.
  - Reconstructed directly from trained weights without wrapping over training Rescaling.
  - Normalization executed exactly once; no double-scaling or unscaled inputs.
- **Output Spec**:
  - Model A / Model C (single output):
    - Name: `semantic_output`.
    - Shape: `[1, 512, 288, 7]`.
    - Type: `float32` probabilities (softmax).
  - Model B / Multi-output C:
    - Output 1: `semantic_output`, shape `[1, 512, 288, 7]`, float32.
    - Output 2: `hazard_output`, shape `[1, 512, 288, 8]`, float32 probabilities (sigmoid).
