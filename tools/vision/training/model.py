"""Native portrait MobileNetV2 / U-Net architectures for Aria vision training.

Task ID: ARIA-PORTRAIT-MODEL-001
Implements:
- Model A: Upstream MobileNetV2 / U-Net architecture adapted to native upright portrait NHWC [B, 512, 288, 3].
- Model B: Model A backbone and decoder with attached multi-label hazard/risk head [B, 512, 288, 8].
- Model C: Lightweight student architecture with bilinear upsampling, projected skip connections, and separable conv decoder [128, 64, 32, 16].
- Fine-tune policies: BN freezing, block13+ selective unfreezing.
- Export wrappers: True single-normalization [0..1] input models without double-rescaling.
"""

from typing import Dict, List, Optional, Sequence, Tuple, Union

try:
    import tensorflow as tf
    from tensorflow import keras
    from tensorflow.keras import layers
    HAS_TF = True
except (ImportError, ModuleNotFoundError):
    tf = None
    keras = None
    layers = None
    HAS_TF = False

# Dimension & Tensor constants
IMAGE_HEIGHT: int = 512
IMAGE_WIDTH: int = 288
IMAGE_CHANNELS: int = 3
INPUT_SHAPE: Tuple[int, int, int] = (IMAGE_HEIGHT, IMAGE_WIDTH, IMAGE_CHANNELS)

# Semantic segmentation classes (7 classes)
SEMANTIC_CLASSES: List[str] = [
    "other",       # 0
    "road",        # 1
    "sidewalk",    # 2 - walkable
    "crosswalk",   # 3 - walkable
    "person",      # 4
    "vehicle",     # 5
    "pole_sign",   # 6
]
NUM_SEMANTIC_CLASSES: int = len(SEMANTIC_CLASSES)
WALKABLE_CLASSES: List[int] = [2, 3]

# Hazard / Risk estimation classes (8 channels)
HAZARD_CLASSES: List[str] = [
    "bike_lane",        # 0
    "damaged_surface",  # 1
    "stairs",           # 2
    "manhole",          # 3
    "tree_zone",        # 4
    "grating",          # 5
    "repair_zone",      # 6
    "speed_bump",       # 7
]
NUM_HAZARD_CLASSES: int = len(HAZARD_CLASSES)

IGNORE_LABEL: int = 255

# Upstream MobileNetV2 skip connection endpoints
ENCODER_SKIP_NAMES: List[str] = [
    "block_1_expand_relu",   # (256, 144) - 96 channels
    "block_3_expand_relu",   # (128, 72)  - 144 channels
    "block_6_expand_relu",   # (64, 36)   - 192 channels
    "block_13_expand_relu",  # (32, 18)   - 576 channels
    "block_16_project",      # (16, 9)    - 160 channels (bottleneck)
]


def _check_tf():
    if not HAS_TF:
        raise RuntimeError(
            "TensorFlow >= 2.19 is required for vision training models. "
            "Please run inside an environment with TensorFlow installed."
        )


def build_encoder(
    input_tensor,
    weights: Optional[str] = "imagenet",
    name: str = "mobilenetv2_encoder",
):
    """Builds the MobileNetV2 multi-output feature extractor with 5 skip levels.

    Args:
        input_tensor: Keras tensor of shape [B, 512, 288, 3] scaled to [-1.0, 1.0].
        weights: Pretrained weights, e.g. 'imagenet' or None.
        name: Name of the encoder sub-model.

    Returns:
        List of 5 feature tensors: 4 skip tensors and 1 bottleneck tensor.
    """
    _check_tf()
    base = keras.applications.MobileNetV2(
        input_shape=INPUT_SHAPE,
        include_top=False,
        weights=weights,
    )
    selected_outputs = [base.get_layer(layer_name).output for layer_name in ENCODER_SKIP_NAMES]
    encoder = keras.Model(inputs=base.input, outputs=selected_outputs, name=name)
    return encoder(input_tensor)


def freeze_batch_normalization(model) -> int:
    """Freezes all BatchNormalization layers in a model and its sub-models.

    When frozen, moving mean and variance statistics are not updated during training.

    Args:
        model: tf.keras.Model to inspect and modify in-place.

    Returns:
        Count of frozen BatchNormalization layers.
    """
    _check_tf()
    frozen_count = 0

    def _freeze_recursive(sub_layer):
        nonlocal frozen_count
        if isinstance(sub_layer, layers.BatchNormalization) or "batch_normalization" in sub_layer.name.lower():
            sub_layer.trainable = False
            frozen_count += 1
        elif hasattr(sub_layer, "layers"):
            for inner in sub_layer.layers:
                _freeze_recursive(inner)

    for layer in model.layers:
        _freeze_recursive(layer)

    return frozen_count


def set_encoder_trainability(model, policy: str = "block13_plus") -> Tuple[int, int]:
    """Applies fine-tuning trainability policy to the MobileNetV2 backbone.

    Args:
        model: tf.keras.Model containing an encoder sub-model or backbone layers.
        policy: One of 'freeze_all', 'block13_plus', 'unfreeze_all'.
            - 'freeze_all': Freezes all encoder layers.
            - 'block13_plus': Unfreezes layers from block_13 onwards (block 13..16, Conv_1, etc.)
              while keeping earlier blocks frozen. All BN layers remain frozen.
            - 'unfreeze_all': Unfreezes all encoder layers while keeping BN layers frozen.

    Returns:
        Tuple of (trainable_layers_count, non_trainable_layers_count).
    """
    _check_tf()
    valid_policies = {"freeze_all", "block13_plus", "unfreeze_all"}
    if policy not in valid_policies:
        raise ValueError(f"Unknown fine-tune policy '{policy}'. Must be one of {valid_policies}")

    # Locate encoder
    encoder = None
    for layer in model.layers:
        if "encoder" in layer.name.lower():
            encoder = layer
            break

    target_layers = encoder.layers if encoder is not None else model.layers

    block13_found = False
    trainable_count = 0
    non_trainable_count = 0

    for layer in target_layers:
        is_bn = isinstance(layer, layers.BatchNormalization) or "batch_normalization" in layer.name.lower()
        if is_bn:
            layer.trainable = False
            non_trainable_count += 1
            continue

        if "block_13" in layer.name:
            block13_found = True

        if policy == "freeze_all":
            layer.trainable = False
            non_trainable_count += 1
        elif policy == "unfreeze_all":
            layer.trainable = True
            trainable_count += 1
        elif policy == "block13_plus":
            if block13_found:
                layer.trainable = True
                trainable_count += 1
            else:
                layer.trainable = False
                non_trainable_count += 1

    return trainable_count, non_trainable_count


def build_model_a(
    input_shape: Tuple[int, int, int] = INPUT_SHAPE,
    batch_size: Optional[int] = None,
    weights: Optional[str] = "imagenet",
    freeze_bn: bool = True,
    input_range: str = "0_255",
    name: str = "portrait_model_a",
):
    """Builds Architecture A: Native Portrait MobileNetV2 / U-Net semantic segmentation.

    Args:
        input_shape: Input spatial dimensions (512, 288, 3).
        batch_size: Optional fixed batch dimension (e.g. 1 for export).
        weights: Pretrained backbone weights ('imagenet' or None).
        freeze_bn: Whether to freeze BatchNormalization statistics from the start.
        input_range: Input value domain. '0_255' (training default), '0_1' (export default), or 'raw'.
        name: Name of the resulting Keras model.

    Returns:
        tf.keras.Model with single output 'semantic_output' of shape [B, 512, 288, 7].
    """
    _check_tf()
    inputs = layers.Input(shape=input_shape, batch_size=batch_size, name="image_input")

    # Normalization applied exactly once
    if input_range == "0_255":
        normalized = layers.Rescaling(1.0 / 127.5, offset=-1.0, name="input_normalization")(inputs)
    elif input_range == "0_1":
        normalized = layers.Rescaling(2.0, offset=-1.0, name="input_normalization")(inputs)
    elif input_range == "raw":
        normalized = inputs
    else:
        raise ValueError(f"Unsupported input_range '{input_range}'. Use '0_255', '0_1', or 'raw'.")

    # Encoder skip levels
    *skips, x = build_encoder(normalized, weights=weights, name="mobilenetv2_encoder")

    # Decoder: 4 upsampling stages with skip concatenations
    decoder_filters = [256, 128, 64, 32]
    for stage_idx, (filters, skip) in enumerate(zip(decoder_filters, reversed(skips)), start=1):
        x = layers.Conv2DTranspose(
            filters, 3, strides=2, padding="same", activation="relu",
            name=f"decoder_stage{stage_idx}_upconv"
        )(x)
        x = layers.Concatenate(name=f"decoder_stage{stage_idx}_concat")([x, skip])
        x = layers.Conv2D(
            filters, 3, padding="same", activation="relu",
            name=f"decoder_stage{stage_idx}_conv"
        )(x)

    # Final upsampling to original spatial resolution (512, 288)
    final_features = layers.Conv2DTranspose(
        16, 3, strides=2, padding="same", activation="relu",
        name="decoder_final_features"
    )(x)

    # Semantic classification head
    semantic_output = layers.Conv2D(
        NUM_SEMANTIC_CLASSES, 1, activation="softmax",
        name="semantic_output"
    )(final_features)

    model = keras.Model(inputs=inputs, outputs=semantic_output, name=name)

    if freeze_bn:
        freeze_batch_normalization(model)

    return model


def build_model_b(
    input_shape: Tuple[int, int, int] = INPUT_SHAPE,
    batch_size: Optional[int] = None,
    weights: Optional[str] = "imagenet",
    freeze_bn: bool = True,
    input_range: str = "0_255",
    name: str = "portrait_model_b",
):
    """Builds Architecture B: Model A with shared decoder and multi-label risk/hazard head.

    Outputs:
        - semantic_output: Softmax probabilities [B, 512, 288, 7].
        - hazard_output: Independent sigmoid probabilities [B, 512, 288, 8].

    Args:
        input_shape: Input spatial dimensions (512, 288, 3).
        batch_size: Optional fixed batch dimension.
        weights: Pretrained backbone weights ('imagenet' or None).
        freeze_bn: Whether to freeze BatchNormalization statistics.
        input_range: Input value domain ('0_255', '0_1', or 'raw').
        name: Name of the resulting Keras model.

    Returns:
        tf.keras.Model with dual outputs [semantic_output, hazard_output].
    """
    _check_tf()
    inputs = layers.Input(shape=input_shape, batch_size=batch_size, name="image_input")

    if input_range == "0_255":
        normalized = layers.Rescaling(1.0 / 127.5, offset=-1.0, name="input_normalization")(inputs)
    elif input_range == "0_1":
        normalized = layers.Rescaling(2.0, offset=-1.0, name="input_normalization")(inputs)
    elif input_range == "raw":
        normalized = inputs
    else:
        raise ValueError(f"Unsupported input_range '{input_range}'.")

    *skips, x = build_encoder(normalized, weights=weights, name="mobilenetv2_encoder")

    decoder_filters = [256, 128, 64, 32]
    for stage_idx, (filters, skip) in enumerate(zip(decoder_filters, reversed(skips)), start=1):
        x = layers.Conv2DTranspose(
            filters, 3, strides=2, padding="same", activation="relu",
            name=f"decoder_stage{stage_idx}_upconv"
        )(x)
        x = layers.Concatenate(name=f"decoder_stage{stage_idx}_concat")([x, skip])
        x = layers.Conv2D(
            filters, 3, padding="same", activation="relu",
            name=f"decoder_stage{stage_idx}_conv"
        )(x)

    final_features = layers.Conv2DTranspose(
        16, 3, strides=2, padding="same", activation="relu",
        name="decoder_final_features"
    )(x)

    # 1. Semantic head
    semantic_output = layers.Conv2D(
        NUM_SEMANTIC_CLASSES, 1, activation="softmax",
        name="semantic_output"
    )(final_features)

    # 2. Risk / Hazard head (Conv3x3/16 + Conv1x1/8 sigmoid)
    risk_conv = layers.Conv2D(
        16, 3, padding="same", activation="relu",
        name="risk_conv3x3"
    )(final_features)
    hazard_output = layers.Conv2D(
        NUM_HAZARD_CLASSES, 1, activation="sigmoid",
        name="hazard_output"
    )(risk_conv)

    model = keras.Model(
        inputs=inputs,
        outputs=[semantic_output, hazard_output],
        name=name
    )

    if freeze_bn:
        freeze_batch_normalization(model)

    return model


def attach_risk_head(model_a, name: str = "portrait_model_b"):
    """Attaches a hazard head to a pre-trained Model A, preserving all existing weights.

    Args:
        model_a: Pretrained tf.keras.Model conforming to Model A architecture.
        name: Name of the resulting multi-output model.

    Returns:
        tf.keras.Model with outputs [semantic_output, hazard_output].
    """
    _check_tf()
    try:
        final_features = model_a.get_layer("decoder_final_features").output
    except ValueError as e:
        raise ValueError("Provided model does not contain layer 'decoder_final_features'") from e

    semantic_output = model_a.get_layer("semantic_output").output

    risk_conv = layers.Conv2D(
        16, 3, padding="same", activation="relu",
        name="risk_conv3x3"
    )(final_features)
    hazard_output = layers.Conv2D(
        NUM_HAZARD_CLASSES, 1, activation="sigmoid",
        name="hazard_output"
    )(risk_conv)

    model_b = keras.Model(
        inputs=model_a.input,
        outputs=[semantic_output, hazard_output],
        name=name
    )
    return model_b


def build_model_c(
    input_shape: Tuple[int, int, int] = INPUT_SHAPE,
    batch_size: Optional[int] = None,
    weights: Optional[str] = "imagenet",
    freeze_bn: bool = True,
    input_range: str = "0_255",
    include_hazard_head: bool = False,
    name: str = "portrait_model_c",
):
    """Builds Architecture C: Lightweight student decoder with bilinear upsampling and projected skips.

    Decoder channels: [128, 64, 32, 16].
    Strictly follows execution contract: bilinear upsampling (UpSampling2D with bilinear interpolation),
    projected skip connections (1x1 conv) to reduce channel depth, and depthwise-separable convs.

    Args:
        input_shape: Input spatial dimensions (512, 288, 3).
        batch_size: Optional fixed batch dimension.
        weights: Pretrained backbone weights ('imagenet' or None).
        freeze_bn: Whether to freeze BatchNormalization statistics.
        input_range: Input value domain ('0_255', '0_1', or 'raw').
        include_hazard_head: Whether to attach the 8-channel hazard sigmoid head.
        name: Name of the resulting Keras model.

    Returns:
        tf.keras.Model for lightweight student inference.
    """
    _check_tf()
    inputs = layers.Input(shape=input_shape, batch_size=batch_size, name="image_input")

    if input_range == "0_255":
        normalized = layers.Rescaling(1.0 / 127.5, offset=-1.0, name="input_normalization")(inputs)
    elif input_range == "0_1":
        normalized = layers.Rescaling(2.0, offset=-1.0, name="input_normalization")(inputs)
    elif input_range == "raw":
        normalized = inputs
    else:
        raise ValueError(f"Unsupported input_range '{input_range}'.")

    *skips, x = build_encoder(normalized, weights=weights, name="mobilenetv2_encoder")

    # Lightweight decoder channel schedule
    decoder_filters = [128, 64, 32, 16]

    for stage_idx, (filters, skip) in enumerate(zip(decoder_filters, reversed(skips)), start=1):
        # Bilinear upsampling per contract
        x = layers.UpSampling2D(
            size=(2, 2), interpolation="bilinear",
            name=f"c_decoder_stage{stage_idx}_upsample"
        )(x)

        # Projected skip connection (1x1 conv) to reduce channel depth before concatenation
        proj_skip = layers.Conv2D(
            max(filters // 2, 16), 1, padding="same", activation="relu",
            name=f"c_decoder_stage{stage_idx}_skip_proj"
        )(skip)

        x = layers.Concatenate(name=f"c_decoder_stage{stage_idx}_concat")([x, proj_skip])

        # Depthwise-separable convolution
        x = layers.SeparableConv2D(
            filters, 3, padding="same", activation="relu",
            name=f"c_decoder_stage{stage_idx}_sepconv"
        )(x)

    # Final bilinear upsampling stage to 512x288
    x = layers.UpSampling2D(
        size=(2, 2), interpolation="bilinear",
        name="c_decoder_final_upsample"
    )(x)

    final_features = layers.SeparableConv2D(
        16, 3, padding="same", activation="relu",
        name="decoder_final_features"
    )(x)

    semantic_output = layers.Conv2D(
        NUM_SEMANTIC_CLASSES, 1, activation="softmax",
        name="semantic_output"
    )(final_features)

    outputs = [semantic_output]
    if include_hazard_head:
        risk_conv = layers.Conv2D(
            16, 3, padding="same", activation="relu",
            name="risk_conv3x3"
        )(final_features)
        hazard_output = layers.Conv2D(
            NUM_HAZARD_CLASSES, 1, activation="sigmoid",
            name="hazard_output"
        )(risk_conv)
        outputs.append(hazard_output)

    model = keras.Model(
        inputs=inputs,
        outputs=outputs if len(outputs) > 1 else outputs[0],
        name=name
    )

    if freeze_bn:
        freeze_batch_normalization(model)

    return model


def build_export_model(
    model,
    batch_size: int = 1,
    name: str = "aria_export_model",
):
    """Constructs an export model for Aria Android deployment.

    Ensures:
    - Fixed static export input batch_size (enforced positive int, default 1).
    - Input shape: [batch_size, 512, 288, 3].
    - Input range: [0.0, 1.0] float32 RGB.
    - True single normalization: [0, 1] is mapped to [-1, 1] directly via Rescaling(2.0, -1.0).
    - No double-rescaling or wrapping over [0..255] normalization.
    - Preserves exact model weights.

    Args:
        model: Trained tf.keras.Model (Model A, B, or C).
        batch_size: Fixed positive export batch size (default 1 for mobile).
        name: Name of the exported model.

    Returns:
        tf.keras.Model ready for TFLite conversion.
    """
    _check_tf()
    if batch_size is None or batch_size <= 0:
        raise ValueError(f"batch_size must be a positive integer, got {batch_size}")

    has_hazard = False
    if isinstance(model.outputs, list) and len(model.outputs) > 1:
        has_hazard = True
    elif hasattr(model, "output_names") and "hazard_output" in model.output_names:
        has_hazard = True

    is_model_c = any("c_decoder" in layer.name for layer in model.layers)

    if is_model_c:
        export_model = build_model_c(
            weights=None,
            batch_size=batch_size,
            freeze_bn=False,
            input_range="0_1",
            include_hazard_head=has_hazard,
            name=name,
        )
    elif has_hazard:
        export_model = build_model_b(
            weights=None,
            batch_size=batch_size,
            freeze_bn=False,
            input_range="0_1",
            name=name,
        )
    else:
        export_model = build_model_a(
            weights=None,
            batch_size=batch_size,
            freeze_bn=False,
            input_range="0_1",
            name=name,
        )

    export_model.set_weights(model.get_weights())
    return export_model
