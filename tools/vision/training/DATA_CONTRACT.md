# Portrait training data contract

The preparation tools in this directory consume the read-only `Surface_1` and
`cityscapes` directories and write generated data only beneath
`build/vision-training/data/`. The source files are never modified. The model
trainer must read this contract before consuming a manifest.

## Input inventory and splits

`prepare_data.py --inventory-only` writes `manifest.json` and
`inventory_report.json` using Python's standard library. The inventory includes
all Surface JPEGs (including unpaired images, reported separately), their
`MASK/<stem>.png` pair when present, per-directory XML, and Cityscapes
`leftImg8bit` images with matching `gtFine/*_gtFine_labelIds.png` annotations.
The inventory report records source totals, XML hazard polygon frequencies,
split counts, duplicate-image SHA-256 groups, and a disjointness audit. Pixel
frequencies are calculated by `--convert`, which decodes all labels; inventory
only does not claim pixel-level frequencies.

Stable source groups are `Surface directory + XML task name` and Cityscapes
official-train city. Identical image SHA-256s union their groups before a
deterministic seed-42 allocation, so duplicate content cannot cross splits.
Surface groups target train/validation/test proportions 70/15/15. Cityscapes
official-train cities target 85/15 train/validation; official `val` images are
always test. Cityscapes official `test` images are not included because they do
not provide ground-truth labels. Every record in a merged group receives one
split. The audit must report no group or image-hash overlap. Historical
baselines may have seen examples from the newly reserved Surface test set; this
pipeline cannot establish otherwise.

## Manifest version 1

The UTF-8 `manifest.json` has top-level `schema_version`, `seed`,
`class_names`, `hazard_names`, and `records`. Class order is
`other, road, sidewalk, crosswalk, person, vehicle, pole_sign`. Hazard order is
`bike_lane, damaged_surface, stairs, manhole, tree_zone, grating, repair_zone,
speed_bump`.

Each record has a stable `id`, `source`, `group_id`, `split`, optional `city`,
`source_split`, absolute original `image_path`, original `image_sha256`,
`annotation_paths`, and `annotation_sha256`. Generated `image_tensor_path`,
`semantic_path`, `hazard_path`, `hazard_valid_path`,
`semantic_source_path`, and `semantic_weight_path` are null in inventory-only
mode and become paths relative to the manifest directory after conversion.
`annotation_paths.semantic` names the paired Surface RGB mask or Cityscapes
labelIds image. Surface records also identify the XML path and matching XML
image name, if annotated.

The converted RGB image is lossless PNG at `[512,288,3]`, uint8 RGB. The
semantic PNG is `[512,288]`, uint8 class IDs 0..6 or ignore 255. Surface black
background is uncertain and therefore ignore, not `other`; known palette
colors retain the upstream road/sidewalk/crosswalk mapping. Any unrecognized
Surface RGB or Cityscapes labelId raises an error instead of being silently
mapped to `other`. Cityscapes void IDs 0..6 and 255 remain ignore; valid
Cityscapes classes outside the seven-class taxonomy map to real `other`.
The [official Cityscapes label definitions](https://raw.githubusercontent.com/mcordts/cityscapesScripts/master/cityscapesscripts/helpers/labels.py)
define vehicle IDs 26..33; the license-plate label has ID -1 and is ignored in
ground-truth labels, not encoded as labelId 34. A raw-mask audit of all 3,475
original Cityscapes labelIds PNGs found no pixels with ID 34, so existing
converted artifacts are unchanged and do not need reconversion.
The observed Surface caution-zone colors `(0,255,0)`, `(255,0,0)`,
`(255,128,0)`, `(255,192,0)`, and `(105,105,255)` map to semantic `other`;
their hazard meaning is retained separately from matched XML annotations.

The upright full-frame image and categorical masks share one letterbox geometry
matching the app's `FULL_FRAME_UPRIGHT_LETTERBOX` strategy at model width 288
and height 512: preserve aspect ratio, compute content dimensions with positive
round-half-up, center the content with integer floor padding, use bilinear RGB
resampling and nearest-neighbor categorical resampling. RGB padding is black;
semantic padding is 255 and has zero supervision weight. No crop removes image
field-of-view. Image/mask geometry tests cover wide and tall frames.

`hazard_path` and `hazard_valid_path` refer to the same compressed NPZ file
containing separate uint8 arrays named `hazard` and `hazard_valid`, each
`[512,288,8]` in the order above. Surface XML polygons are drawn in ascending
`z_order` (stable input order breaks ties); the topmost recognized polygon
wins where polygons overlap. A recognized annotation region is valid for all
eight hazard channels: its mapped hazard channel is positive and the other
channels are negative only inside that known region. Pixels outside recognized
XML annotations, void/ambiguous regions, and letterbox padding have all-zero
valid masks, never fabricated negative labels. Hazards are independent of
semantic IDs and are retained even when a corresponding Surface mask region is
otherwise ignore.

Hazard mappings:

| XML label / attribute | Hazard |
|---|---|
| `bike_lane` | `bike_lane` |
| `sidewalk`, `braille_guide_blocks`, or `alley` with `attribute=damaged` | `damaged_surface` |
| `caution_zone` with `attribute=stairs` / `manhole` / `tree_zone` / `grating` / `repair_zone` | same-named hazard |
| `alley` with `attribute=speed_bump` | `speed_bump` |

`semantic_source_path` and `semantic_weight_path` refer to one compressed NPZ
with `semantic_source` uint8 and `semantic_weight` float32 `[512,288]` arrays.
Source IDs are 0=ignore/unlabeled, 1=original annotation, 2=automatic
pseudo-label. The corresponding weights are 0.0, 1.0, and 0.25. Only IDs 0/1
are emitted by conversion. Training losses and metrics must use the shared
weight/ignore mask; padding and ignore pixels contribute neither loss nor
metrics. Do not retain full-resolution decoded datasets in memory.

Conversion always stores the complete upright field of view. Training-time
augmentation must draw full-frame versus upright-crop examples at 50/50, use
the same crop parameters for RGB, semantic labels, hazard labels/validity, and
semantic provenance/weights, and apply bilinear resampling only to RGB and
nearest-neighbor resampling to categorical/validity arrays. Validation and
test remain uncropped and unaugmented.

`inventory_report.json` gives XML polygon counts before conversion and per
source semantic/hazard pixel frequencies after conversion, excluding ignore
and padding. Hazard positive counts alone do not describe valid-negative
coverage; use `hazard_valid` for that denominator.

## Automatic pseudo-labels

Run `pseudo_labels.py` separately and specify `--manifest`, `--model`, and
`--output`; the tool never discovers or substitutes a teacher model. The model
must be the frozen native-portrait semantic TFLite teacher with exactly one
`[1,512,288,3]` input and one `[1,512,288,7]` probability output. TensorFlow or
`tflite_runtime`, NumPy, and Pillow are required. If a dependency, model, shape,
quantization contract, or normalized probability output is unavailable or
invalid, inference stops explicitly.

Only converted Surface `train` records are eligible; validation and test
records are never inferred or emitted. Each image is inferred normally and
horizontally flipped; the flipped probabilities are inverse-flipped into
upright coordinates. A pixel is emitted only when both predictions agree on
person/vehicle/pole_sign (IDs 4/5/6), each top probability is at least 0.90,
each top-vs-second margin is at least 0.20, and the original semantic pixel is
ignore. Pseudo labels use 255 elsewhere and carry weight 0.25 only at accepted
pixels. They are an auxiliary training augmentation, never human/object ground
truth and never Korean evaluation labels.
Each pseudo weight NPZ contains `semantic_source` uint8 (2 for accepted
pseudo pixels, 0 otherwise), `semantic_weight` float32 (0.25 for accepted
pixels, 0 otherwise), and teacher `confidence`.

The separate `pseudo_manifest.json` joins to the base manifest by record ID and
contains paths relative to its own directory, teacher hash, thresholds, split,
and accepted-pixel counts. The trainer must overlay these sparse labels only
where original semantic labels are ignore and must never use them as validation
or test ground truth.

## Commands

From the repository root, an inventory-only run is:

```powershell
python -m tools.vision.training.prepare_data --inventory-only --dataset-root C:\Users\felice\Desktop\dataset --output build\vision-training\data
```

Once the isolated image dependencies are ready, convert with:

```powershell
python -m tools.vision.training.prepare_data --convert --dataset-root C:\Users\felice\Desktop\dataset --output build\vision-training\data
```

For bounded verification, `--sample-per-source N` deterministically converts
at most N paired records from each source (Surface selection prioritizes XML
frames and distinct hazard types). The inventory remains complete; unconverted
records retain null generated paths and the completion report says `partial`.
The existing inventory can be reused without rehashing the full raw dataset:

```powershell
python -m tools.vision.training.prepare_data --convert --inventory-manifest build\vision-training\data\manifest.json --sample-per-source 12 --output build\vision-training\data
```

After conversion, explicitly choose the verified native portrait teacher and a
separate pseudo-label output directory:

```powershell
python -m tools.vision.training.pseudo_labels --manifest build\vision-training\data\manifest.json --model <native-portrait-teacher.tflite> --output build\vision-training\data\pseudo
```

Full conversion is intentionally not part of inventory-only operation; its
image output is large and should be scheduled only after dependency readiness
and available disk space are confirmed.
