"""Masked loss functions and distillation objectives for Aria vision training.

Task ID: ARIA-PORTRAIT-MODEL-001
Implements:
- Weighted Masked Categorical Cross-Entropy (handling ignore label 255, pseudo weight 0.25, zero gradient on void).
- Masked Soft Dice Loss with absent supervised class exclusion and linear (un-squared) provenance weighting.
- Public Keras Loss __call__ override ensuring spatial pixel sample_weights are passed directly to loss logic.
- Clipped inverse square-root class frequency weighting (bounds [0.5, 3.0]).
- Masked Focal Binary Cross-Entropy for 8-channel hazard estimation (gamma=2.0, weight 0.5).
- Model B Joint Multi-Task Loss.
- Softened KL Distillation Loss for Model C student training (T=2.0, weight 0.2).
- Pure-Python numerical reference implementations for offline verification without dependencies.
"""

import math
from typing import Dict, List, Optional, Sequence, Tuple, Union

try:
    import tensorflow as tf
    from tensorflow import keras
    HAS_TF = True
except (ImportError, ModuleNotFoundError):
    tf = None
    keras = None
    HAS_TF = False

# Constants aligned with MODEL_CONTRACT.md
NUM_SEMANTIC_CLASSES: int = 7
NUM_HAZARD_CLASSES: int = 8
IGNORE_LABEL: int = 255
DEFAULT_PSEUDO_WEIGHT: float = 0.25
EPSILON: float = 1e-7


def _check_tf():
    if not HAS_TF:
        raise RuntimeError(
            "TensorFlow >= 2.19 is required for TensorFlow loss operations. "
            "Use pure-Python reference loss functions for offline verification."
        )


def compute_inverse_sqrt_class_weights(
    frequencies_or_counts: Sequence[float],
    min_weight: float = 0.5,
    max_weight: float = 3.0,
    normalize: bool = True,
) -> List[float]:
    """Computes clipped inverse square-root frequency weights for class imbalance.

    Weight for class c: w_c = 1 / sqrt(max(freq_c, eps))
    If normalize is True, weights are normalized so their mean equals 1.0.
    Finally, weights are clipped to [min_weight, max_weight].

    Args:
        frequencies_or_counts: List or sequence of class counts or frequencies.
        min_weight: Minimum weight clipping threshold (default 0.5).
        max_weight: Maximum weight clipping threshold (default 3.0).
        normalize: Whether to normalize mean weight to 1.0 before clipping.

    Returns:
        List of clipped float weights with length matching frequencies_or_counts.
    """
    total = sum(frequencies_or_counts)
    if total <= 0.0:
        return [1.0] * len(frequencies_or_counts)

    raw_weights = []
    for count in frequencies_or_counts:
        freq = max(float(count) / total, 1e-8)
        w = 1.0 / math.sqrt(freq)
        raw_weights.append(w)

    if normalize and len(raw_weights) > 0:
        mean_w = sum(raw_weights) / len(raw_weights)
        if mean_w > 0.0:
            raw_weights = [w / mean_w for w in raw_weights]

    clipped_weights = [min(max(w, min_weight), max_weight) for w in raw_weights]
    return clipped_weights


# ==============================================================================
# Pure-Python Reference Implementations (Dependency-Free Algorithm Ground Truth)
# ==============================================================================

def py_masked_ce(
    y_true: List[int],
    y_pred: List[List[float]],
    sample_weight: Optional[List[float]] = None,
    class_weights: Optional[List[float]] = None,
    ignore_label: int = IGNORE_LABEL,
) -> float:
    """Pure-Python reference computation for weighted masked cross-entropy."""
    total_loss = 0.0
    total_weight = 0.0

    for idx, (label, probs) in enumerate(zip(y_true, y_pred)):
        w = sample_weight[idx] if sample_weight is not None else 1.0
        if label == ignore_label or w <= 0.0 or label < 0 or label >= len(probs):
            continue
        cw = class_weights[label] if class_weights is not None else 1.0
        eff_w = w * cw
        prob = max(min(probs[label], 1.0 - EPSILON), EPSILON)
        total_loss += eff_w * (-math.log(prob))
        total_weight += eff_w

    return total_loss / total_weight if total_weight > 0.0 else 0.0


def py_masked_dice(
    y_true: List[int],
    y_pred: List[List[float]],
    num_classes: int = NUM_SEMANTIC_CLASSES,
    sample_weight: Optional[List[float]] = None,
    class_weights: Optional[List[float]] = None,
    ignore_label: int = IGNORE_LABEL,
    smooth: float = 1.0,
    exclude_absent: bool = True,
) -> float:
    """Pure-Python reference computation for masked Dice loss.

    Uses single linear provenance weighting:
    intersection_c = sum(w * target_c * pred_c)
    target_sum_c   = sum(w * target_c)
    pred_sum_c     = sum(w * pred_c)
    Ensures that perfect predictions with pseudo weight (e.g. 0.25) yield exactly 0.0 Dice loss.
    """
    target_sums = [0.0] * num_classes
    pred_sums = [0.0] * num_classes
    intersections = [0.0] * num_classes

    for idx, (label, probs) in enumerate(zip(y_true, y_pred)):
        w = sample_weight[idx] if sample_weight is not None else 1.0
        if label == ignore_label or w <= 0.0 or label < 0 or label >= num_classes:
            continue
        for c in range(num_classes):
            t_val = 1.0 if label == c else 0.0
            p_val = probs[c]
            # Single linear weight factor w
            intersections[c] += w * t_val * p_val
            target_sums[c] += w * t_val
            pred_sums[c] += w * p_val

    dice_losses = []
    dice_weights = []
    for c in range(num_classes):
        cw = class_weights[c] if class_weights is not None else 1.0
        if exclude_absent and target_sums[c] <= 0.0:
            continue
        num = 2.0 * intersections[c] + smooth
        den = target_sums[c] + pred_sums[c] + smooth
        d_loss = 1.0 - (num / den)
        dice_losses.append(d_loss * cw)
        dice_weights.append(cw)

    if not dice_losses:
        return 0.0
    return sum(dice_losses) / sum(dice_weights)


def py_masked_focal_bce(
    y_true: List[List[float]],
    y_pred: List[List[float]],
    valid_mask: Optional[List[List[float]]] = None,
    gamma: float = 2.0,
) -> float:
    """Pure-Python reference computation for masked focal binary cross-entropy."""
    total_loss = 0.0
    total_valid = 0.0

    for sample_idx in range(len(y_true)):
        for ch in range(len(y_true[sample_idx])):
            v = valid_mask[sample_idx][ch] if valid_mask is not None else 1.0
            if v <= 0.0:
                continue
            y = y_true[sample_idx][ch]
            p = max(min(y_pred[sample_idx][ch], 1.0 - EPSILON), EPSILON)
            p_t = p if y == 1.0 else (1.0 - p)
            focal_factor = (1.0 - p_t) ** gamma
            bce = -(y * math.log(p) + (1.0 - y) * math.log(1.0 - p))
            total_loss += focal_factor * bce * v
            total_valid += v

    return total_loss / total_valid if total_valid > 0.0 else 0.0


def py_kl_distillation(
    teacher_prob: List[List[float]],
    student_prob: List[List[float]],
    valid_mask: Optional[List[float]] = None,
    temperature: float = 2.0,
    weight: float = 0.2,
) -> float:
    """Pure-Python reference computation for softened KL distillation."""
    total_kl = 0.0
    total_valid = 0.0

    for idx, (t_p, s_p) in enumerate(zip(teacher_prob, student_prob)):
        v = valid_mask[idx] if valid_mask is not None else 1.0
        if v <= 0.0:
            continue

        exp_t = [math.exp(math.log(max(p, EPSILON)) / temperature) for p in t_p]
        exp_s = [math.exp(math.log(max(p, EPSILON)) / temperature) for p in s_p]
        sum_t, sum_s = sum(exp_t), sum(exp_s)
        q_t = [val / sum_t for val in exp_t]
        q_s = [val / sum_s for val in exp_s]

        kl = sum(
            qt * (math.log(max(qt, EPSILON)) - math.log(max(qs, EPSILON)))
            for qt, qs in zip(q_t, q_s)
        )
        total_kl += (temperature ** 2) * kl * v
        total_valid += v

    avg_kl = total_kl / total_valid if total_valid > 0.0 else 0.0
    return weight * avg_kl


# ==============================================================================
# TensorFlow Functional APIs
# ==============================================================================

def masked_sparse_categorical_crossentropy(
    y_true,
    y_pred,
    sample_weight=None,
    class_weights=None,
    ignore_label: int = IGNORE_LABEL,
    from_logits: bool = False,
):
    """Computes weighted masked sparse cross-entropy with zero loss/gradient on ignored pixels."""
    _check_tf()
    y_true = tf.cast(y_true, tf.int32)
    if len(y_true.shape) == len(y_pred.shape):
        y_true = tf.squeeze(y_true, axis=-1)

    num_classes = tf.shape(y_pred)[-1]
    valid_mask = tf.not_equal(y_true, ignore_label)
    valid_mask = tf.logical_and(valid_mask, tf.greater_equal(y_true, 0))
    valid_mask = tf.logical_and(valid_mask, tf.less(y_true, num_classes))

    if sample_weight is not None:
        sample_weight = tf.cast(sample_weight, tf.float32)
        if len(sample_weight.shape) == len(y_pred.shape):
            sample_weight = tf.squeeze(sample_weight, axis=-1)
        valid_mask = tf.logical_and(valid_mask, tf.greater(sample_weight, 0.0))
        pixel_weight = tf.where(valid_mask, sample_weight, 0.0)
    else:
        pixel_weight = tf.where(valid_mask, tf.ones_like(y_true, dtype=tf.float32), 0.0)

    safe_y_true = tf.where(valid_mask, y_true, tf.zeros_like(y_true))

    if class_weights is not None:
        if not isinstance(class_weights, tf.Tensor):
            class_weights = tf.constant(class_weights, dtype=tf.float32)
        class_w = tf.gather(class_weights, safe_y_true)
        pixel_weight = pixel_weight * class_w

    if from_logits:
        ce_loss = tf.nn.sparse_softmax_cross_entropy_with_logits(labels=safe_y_true, logits=y_pred)
    else:
        y_pred_clipped = tf.clip_by_value(y_pred, EPSILON, 1.0 - EPSILON)
        p_target = tf.gather(
            y_pred_clipped,
            safe_y_true[..., tf.newaxis],
            batch_dims=len(safe_y_true.shape),
        )
        p_target = tf.squeeze(p_target, axis=-1)
        ce_loss = -tf.math.log(p_target)

    weighted_ce = tf.where(valid_mask, ce_loss * pixel_weight, 0.0)
    total_weight = tf.reduce_sum(tf.where(valid_mask, pixel_weight, 0.0))

    return tf.math.divide_no_nan(tf.reduce_sum(weighted_ce), total_weight)


def masked_dice_loss(
    y_true,
    y_pred,
    sample_weight=None,
    num_classes: int = NUM_SEMANTIC_CLASSES,
    class_weights=None,
    ignore_label: int = IGNORE_LABEL,
    smooth: float = 1.0,
    exclude_absent: bool = True,
):
    """Computes masked Soft Dice loss with linear (un-squared) provenance weighting.

    Guarantees:
    - intersection_c = sum(w * target_c * pred_c)
    - target_sum_c   = sum(w * target_c)
    - pred_sum_c     = sum(w * pred_c)
    - Perfect predictions with pseudo weight (0.25) achieve exactly 0.0 Dice loss.
    - Ignored pixels (255 or weight 0.0) have strictly zero loss and zero gradient.
    - Absent supervised classes in partial annotations are excluded from the loss average.
    """
    _check_tf()
    y_true = tf.cast(y_true, tf.int32)
    if len(y_true.shape) == len(y_pred.shape):
        y_true = tf.squeeze(y_true, axis=-1)

    valid_mask = tf.not_equal(y_true, ignore_label)
    valid_mask = tf.logical_and(valid_mask, tf.greater_equal(y_true, 0))
    valid_mask = tf.logical_and(valid_mask, tf.less(y_true, num_classes))

    if sample_weight is not None:
        sample_weight = tf.cast(sample_weight, tf.float32)
        if len(sample_weight.shape) == len(y_pred.shape):
            sample_weight = tf.squeeze(sample_weight, axis=-1)
        valid_mask = tf.logical_and(valid_mask, tf.greater(sample_weight, 0.0))
        w = tf.where(valid_mask, sample_weight, 0.0)
    else:
        w = tf.where(valid_mask, tf.ones_like(y_true, dtype=tf.float32), 0.0)

    w_expanded = tf.expand_dims(w, axis=-1)  # [B, H, W, 1]

    safe_y_true = tf.where(valid_mask, y_true, tf.zeros_like(y_true))
    target_one_hot = tf.one_hot(safe_y_true, depth=num_classes, dtype=tf.float32)

    # Linear weighting: apply w_expanded ONCE per element so intersection is not squared
    # inter = sum(w * target * pred)
    # target_sum = sum(w * target)
    # pred_sum = sum(w * pred)
    spatial_axes = [1, 2]
    intersection = tf.reduce_sum(w_expanded * target_one_hot * y_pred, axis=spatial_axes)
    target_sum = tf.reduce_sum(w_expanded * target_one_hot, axis=spatial_axes)
    pred_sum = tf.reduce_sum(w_expanded * y_pred, axis=spatial_axes)

    dice_per_class = 1.0 - (2.0 * intersection + smooth) / (target_sum + pred_sum + smooth)

    if class_weights is not None:
        if not isinstance(class_weights, tf.Tensor):
            class_weights_t = tf.constant(class_weights, dtype=tf.float32)
        else:
            class_weights_t = tf.cast(class_weights, tf.float32)
    else:
        class_weights_t = tf.ones((num_classes,), dtype=tf.float32)

    if exclude_absent:
        class_present = tf.greater(target_sum, 0.0)
        active_weights = tf.where(
            class_present,
            tf.broadcast_to(class_weights_t, tf.shape(class_present)),
            0.0,
        )
        sample_dice_sum = tf.reduce_sum(dice_per_class * active_weights, axis=-1)
        sample_weight_sum = tf.reduce_sum(active_weights, axis=-1)
        sample_dice = tf.math.divide_no_nan(sample_dice_sum, sample_weight_sum)
        sample_has_labels = tf.greater(sample_weight_sum, 0.0)
        batch_dice = tf.math.divide_no_nan(
            tf.reduce_sum(tf.where(sample_has_labels, sample_dice, 0.0)),
            tf.reduce_sum(tf.cast(sample_has_labels, tf.float32)),
        )
        return batch_dice
    else:
        weighted_dice = dice_per_class * class_weights_t
        return tf.reduce_mean(weighted_dice)


def masked_focal_binary_crossentropy(
    y_true,
    y_pred,
    sample_weight=None,
    gamma: float = 2.0,
):
    """Computes masked Focal BCE for multi-label hazard output."""
    _check_tf()
    y_true = tf.cast(y_true, tf.float32)
    y_pred = tf.cast(y_pred, tf.float32)
    y_pred = tf.clip_by_value(y_pred, EPSILON, 1.0 - EPSILON)

    p_t = tf.where(tf.equal(y_true, 1.0), y_pred, 1.0 - y_pred)
    focal_factor = tf.pow(1.0 - p_t, gamma)
    bce = -(y_true * tf.math.log(y_pred) + (1.0 - y_true) * tf.math.log(1.0 - y_pred))
    focal_bce = focal_factor * bce

    if sample_weight is not None:
        sample_weight = tf.cast(sample_weight, tf.float32)
        valid_mask = tf.cast(tf.greater(sample_weight, 0.0), tf.float32)
        focal_bce = focal_bce * valid_mask
        total_valid = tf.reduce_sum(valid_mask)
    else:
        total_valid = tf.cast(tf.size(focal_bce), tf.float32)

    return tf.math.divide_no_nan(tf.reduce_sum(focal_bce), total_valid)


def kl_distillation_loss(
    teacher_pred,
    student_pred,
    sample_weight=None,
    temperature: float = 2.0,
    distill_weight: float = 0.2,
):
    """Computes softened KL divergence distillation loss."""
    _check_tf()
    t_prob = tf.clip_by_value(teacher_pred, EPSILON, 1.0)
    s_prob = tf.clip_by_value(student_pred, EPSILON, 1.0)

    log_t = tf.math.log(t_prob) / temperature
    log_s = tf.math.log(s_prob) / temperature
    q_t = tf.nn.softmax(log_t, axis=-1)
    q_s = tf.nn.softmax(log_s, axis=-1)

    kl_pixel = tf.reduce_sum(
        q_t * (tf.math.log(q_t + EPSILON) - tf.math.log(q_s + EPSILON)),
        axis=-1,
    )
    scaled_kl = (temperature ** 2) * kl_pixel

    if sample_weight is not None:
        sample_weight = tf.cast(sample_weight, tf.float32)
        valid_mask = tf.cast(tf.greater(sample_weight, 0.0), tf.float32)
        if len(valid_mask.shape) == len(scaled_kl.shape) + 1:
            valid_mask = tf.squeeze(valid_mask, axis=-1)
        scaled_kl = scaled_kl * valid_mask
        total_valid = tf.reduce_sum(valid_mask)
    else:
        total_valid = tf.cast(tf.size(scaled_kl), tf.float32)

    kl_loss = tf.math.divide_no_nan(tf.reduce_sum(scaled_kl), total_valid)
    return distill_weight * kl_loss


# ==============================================================================
# Public Keras Loss Classes (Overriding __call__ to preserve spatial pixel weights)
# ==============================================================================

if HAS_TF:

    class MaskedSparseCategoricalCrossentropy(keras.losses.Loss):
        """Weighted masked sparse categorical cross-entropy.

        Overrides __call__ to ensure spatial pixel sample_weight arrays are passed directly
        into loss calculations rather than being reduced post-hoc by Keras base logic.
        """

        def __init__(
            self,
            class_weights: Optional[Sequence[float]] = None,
            ignore_label: int = IGNORE_LABEL,
            from_logits: bool = False,
            name: str = "masked_sparse_ce",
            **kwargs,
        ):
            super().__init__(name=name, **kwargs)
            self.class_weights = class_weights
            self.ignore_label = int(ignore_label)
            self.from_logits = bool(from_logits)

        def __call__(self, y_true, y_pred, sample_weight=None):
            return masked_sparse_categorical_crossentropy(
                y_true=y_true,
                y_pred=y_pred,
                sample_weight=sample_weight,
                class_weights=self.class_weights,
                ignore_label=self.ignore_label,
                from_logits=self.from_logits,
            )

        def call(self, y_true, y_pred, sample_weight=None):
            return self.__call__(y_true, y_pred, sample_weight=sample_weight)


    class MaskedDiceLoss(keras.losses.Loss):
        """Soft Dice loss with linear provenance weighting and absent-class exclusion.

        Overrides __call__ to ensure spatial pixel sample_weight arrays are passed directly.
        """

        def __init__(
            self,
            num_classes: int = NUM_SEMANTIC_CLASSES,
            class_weights: Optional[Sequence[float]] = None,
            ignore_label: int = IGNORE_LABEL,
            smooth: float = 1.0,
            exclude_absent: bool = True,
            name: str = "masked_dice_loss",
            **kwargs,
        ):
            super().__init__(name=name, **kwargs)
            self.num_classes = int(num_classes)
            self.class_weights = class_weights
            self.ignore_label = int(ignore_label)
            self.smooth = float(smooth)
            self.exclude_absent = bool(exclude_absent)

        def __call__(self, y_true, y_pred, sample_weight=None):
            return masked_dice_loss(
                y_true=y_true,
                y_pred=y_pred,
                sample_weight=sample_weight,
                num_classes=self.num_classes,
                class_weights=self.class_weights,
                ignore_label=self.ignore_label,
                smooth=self.smooth,
                exclude_absent=self.exclude_absent,
            )

        def call(self, y_true, y_pred, sample_weight=None):
            return self.__call__(y_true, y_pred, sample_weight=sample_weight)


    class CombinedSemanticLoss(keras.losses.Loss):
        """Weighted combination of Masked Cross-Entropy and Masked Soft Dice."""

        def __init__(
            self,
            class_weights: Optional[Sequence[float]] = None,
            ce_weight: float = 1.0,
            dice_weight: float = 1.0,
            ignore_label: int = IGNORE_LABEL,
            exclude_absent_dice: bool = True,
            name: str = "combined_semantic_loss",
            **kwargs,
        ):
            super().__init__(name=name, **kwargs)
            self.ce_weight = float(ce_weight)
            self.dice_weight = float(dice_weight)
            self.ce_loss = MaskedSparseCategoricalCrossentropy(
                class_weights=class_weights, ignore_label=ignore_label
            )
            self.dice_loss = MaskedDiceLoss(
                class_weights=class_weights,
                ignore_label=ignore_label,
                exclude_absent=exclude_absent_dice,
            )

        def __call__(self, y_true, y_pred, sample_weight=None):
            loss_ce = self.ce_loss(y_true, y_pred, sample_weight=sample_weight)
            loss_dice = self.dice_loss(y_true, y_pred, sample_weight=sample_weight)
            return self.ce_weight * loss_ce + self.dice_weight * loss_dice

        def call(self, y_true, y_pred, sample_weight=None):
            return self.__call__(y_true, y_pred, sample_weight=sample_weight)


    class MaskedFocalBinaryCrossentropy(keras.losses.Loss):
        """Masked Focal Binary Cross-Entropy for multi-label hazard output."""

        def __init__(
            self,
            gamma: float = 2.0,
            name: str = "masked_focal_bce",
            **kwargs,
        ):
            super().__init__(name=name, **kwargs)
            self.gamma = float(gamma)

        def __call__(self, y_true, y_pred, sample_weight=None):
            return masked_focal_binary_crossentropy(
                y_true=y_true,
                y_pred=y_pred,
                sample_weight=sample_weight,
                gamma=self.gamma,
            )

        def call(self, y_true, y_pred, sample_weight=None):
            return self.__call__(y_true, y_pred, sample_weight=sample_weight)


    class ModelBJointLoss:
        """Multi-task loss for Model B: Semantic Loss + 0.5 * Hazard Focal BCE."""

        def __init__(
            self,
            semantic_loss_fn: Optional[keras.losses.Loss] = None,
            hazard_loss_weight: float = 0.5,
            gamma: float = 2.0,
            class_weights: Optional[Sequence[float]] = None,
        ):
            self.semantic_loss_fn = semantic_loss_fn or CombinedSemanticLoss(
                class_weights=class_weights
            )
            self.hazard_loss_fn = MaskedFocalBinaryCrossentropy(gamma=gamma)
            self.hazard_loss_weight = float(hazard_loss_weight)

        def __call__(
            self,
            semantic_true,
            hazard_true,
            semantic_pred,
            hazard_pred,
            semantic_weight=None,
            hazard_valid=None,
        ):
            l_sem = self.semantic_loss_fn(
                semantic_true, semantic_pred, sample_weight=semantic_weight
            )
            l_haz = self.hazard_loss_fn(
                hazard_true, hazard_pred, sample_weight=hazard_valid
            )
            return l_sem + self.hazard_loss_weight * l_haz, l_sem, l_haz


    class KLDistillationLoss(keras.losses.Loss):
        """Softened KL divergence for student distillation (Model C from Model B)."""

        def __init__(
            self,
            temperature: float = 2.0,
            distill_weight: float = 0.2,
            ignore_label: int = IGNORE_LABEL,
            name: str = "kl_distillation_loss",
            **kwargs,
        ):
            super().__init__(name=name, **kwargs)
            self.temperature = float(temperature)
            self.distill_weight = float(distill_weight)
            self.ignore_label = int(ignore_label)

        def __call__(self, teacher_pred, student_pred, sample_weight=None):
            return kl_distillation_loss(
                teacher_pred=teacher_pred,
                student_pred=student_pred,
                sample_weight=sample_weight,
                temperature=self.temperature,
                distill_weight=self.distill_weight,
            )

        def call(self, teacher_pred, student_pred, sample_weight=None):
            return self.__call__(teacher_pred, student_pred, sample_weight=sample_weight)
