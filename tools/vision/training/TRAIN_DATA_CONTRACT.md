# Portrait training loader contract

`train_data.py` is the streaming training-time consumer of the version-1
manifest described in `DATA_CONTRACT.md`. It does not import the trainer,
perform inference, or cache decoded dataset tensors.

## Public API

```python
dataset = PortraitDataset(
    manifest_path,
    split,                       # "train", "val", or "test"
    sources=None,                # None/"mixed", one source, or an iterable
    pseudo_manifest_path=None,   # training-only
    seed=42,
    require_complete=True,
)

samples = dataset.iter_samples(epoch=0, augment=False, balanced=False)
batches = dataset.iter_batches(batch_size, epoch=0, augment=False, balanced=False)
```

`sources` accepts `cityscapes`, `surface`, or `mixed`. Both `sample_count()` and
`batch_count(batch_size, ...)` report the finite planned epoch length;
`iter_samples()` and `iter_batches()` return iterators with live
`yielded_count` and `yielded_samples` counters. For sample iteration both
counters count samples. For batch iteration `yielded_count` counts batches and
`yielded_samples` counts the samples actually returned, including a final
partial batch. Counters increase only as values are consumed, so a stopped
iterator reports its actual yield rather than an estimate.

Each sample contains exactly these keys:

| Key | Shape | Dtype | Values |
|---|---:|---|---|
| `image` | `[512,288,3]` | `float32` | Upright RGB, 0..255 |
| `semantic` | `[512,288]` | `int32` | Class IDs 0..6 or ignore 255 |
| `semantic_weight` | `[512,288]` | `float32` | 0.0 ignore, 1.0 original, 0.25 pseudo |
| `semantic_source` | `[512,288]` | `uint8` | 0 ignore, 1 original, 2 pseudo |
| `hazard` | `[512,288,8]` | `float32` | Binary targets |
| `hazard_valid` | `[512,288,8]` | `float32` | Binary supervision mask |
| `id`, `source`, `group_id`, `split` | scalar strings | `str` | Original record identifiers |

Batches have the same keys: arrays above gain a leading batch dimension and
the four metadata values become lists in matching record order. Batch size is a
positive integer; the last batch is not padded or dropped.

`training_eligible` is true only for a complete `train` dataset created with
`require_complete=True`. `require_complete=False` is diagnostic-only:
manifest rows without converted paths are listed in `diagnostics` and excluded,
and the dataset explicitly reports `training_eligible=False`. Any populated
path that points to a missing file is still an error. Empty selections,
malformed records, malformed tensors, unknown labels, and missing data never
silently become skipped training examples.

## Validation and streaming behavior

Construction validates schema version, exact semantic/hazard class order,
record IDs, allowed source/split values, source split metadata, SHA-256 values,
and group/hash split disjointness across the full manifest before selecting a
split or source. Selected records require converted image, semantic, hazard,
hazard-valid, provenance-source, and provenance-weight paths. Paths are
manifest-relative; original `image_path` is absolute and is read only to
derive the exact letterbox content rectangle. Files are checked at
construction, then one record's arrays are decoded and released at a time.
Only metadata and the current sample/batch are held in memory.

The loader checks the `[512,288]` geometry, semantic IDs, provenance pairing,
NPZ keys/shapes/dtypes, and binary hazard values. Original provenance must
pair source 0 with ignore/weight 0 and source 1 with a class/weight 1. Padding
is derived from original image dimensions with `letterbox_geometry`; the
loader verifies ignore/zero supervision there and never guesses padding from
black RGB. Hazard positives must be valid; uncovered pixels retain zero
validity and are not treated as negatives.

Without balancing, records are shuffled deterministically from the dataset
seed and epoch. Balanced sampling is training-only and requires at least one
selected record from each source. It shuffles each source deterministically,
cycles the smaller source, and emits one record from each source per pair.
Thus each finite epoch has `2 * max(cityscapes_count, surface_count)` samples,
uses every member of the larger source once, and does not drop its remainder.
Iteration, augmentation, and batch formation are deterministic for the same
seed and epoch.

When a pseudo manifest is supplied, its rows use the producer's `id`,
`semantic_path`, and `semantic_weight_path` keys. Every row must match a base
Surface `train` record's ID, source, split, image SHA-256, and group ID.
Pseudo PNGs carry only person/vehicle/pole-sign IDs at source-2 pixels; the
NPZ carries `semantic_source` and `semantic_weight` with exact 2/0.25 and
0/0.0 pairing. Only original ignore pixels are overlaid. Validation and test
datasets reject pseudo manifests.

## Augmentation

Training augmentation selects full-field or upright crop with equal
probability. Crops preserve the portrait field geometry and are resized back
to `[512,288]`; image uses bilinear resampling, while semantic IDs, source IDs,
weights, hazards, hazard validity, and an internally derived content mask use
nearest-neighbor resampling. Horizontal flips apply to every image/mask plane.
Brightness, contrast, and optional mild blur affect RGB only. Content outside
the original image remains black, and its semantic supervision/hazard
validity remains zero. Float weights are nearest-neighbor transformed without
quantization, preserving exact 0.25 values. Validation/test callers should use
`augment=False`.


## Cross-platform and WSL path resolution

Manifests generated on Windows contain converted paths that are relative to the
manifest directory, while the original image_path is an absolute Windows path
(e.g. C:\Users\...). To make these manifests directly consumable in planned
WSL training without mutating manifest records, IDs, groups, hashes, or
original files:

1. **POSIX / WSL resolution**: When running on POSIX systems (including WSL),
   Windows drive-absolute paths ([A-Za-z]:\...) are recognized and translated
   to their mounted POSIX mount points using WSL's wslpath -u utility executed
   with argument list shell=False (or an injected path resolver).
2. **Drive-root translation caching**: The translated drive root (e.g. C:\
   mapped to /mnt/c) is cached with @lru_cache so that subprocess execution
   occurs only once per drive, completely avoiding per-frame or per-epoch
   subprocess overhead.
3. **Per-dataset parent directory resolution caching**: During manifest record
   validation, canonical parent directory resolution is cached within the scope
   of the individual dataset construction (`parent_cache`). Because manifest
   records share a small number of distinct parent directories on the mounted
   drive, repeated ancestor filesystem traversals over 9P (`p9_client_rpc`) are
   eliminated while resolving each parent directory exactly once. The cache scope
   is strictly local to `_validate_records` and automatically discarded after
   construction, preventing any cross-dataset stale state.
4. **Leaf symlink resolution and containment**: Non-symlink leaf files avoid
   re-resolving the canonical parent chain. Explicit leaf symlinks and directory
   symlinks are resolved and strictly validated to ensure they remain inside the
   translated drive root via `.relative_to(translated_root)`. Path traversal
   attempts with `..` or symlinks that resolve outside the drive root are caught
   and rejected with an explicit `PortraitDatasetError`.
5. **Validation and rejection**:
   - Drive-relative paths (e.g. C:foo) are rejected as ambiguous.
   - UNC paths (e.g. \\server\share\..., //..., \\?\...) are rejected.
   - Arbitrary drive mount locations are never guessed. If wslpath is missing,
     fails, times out, returns an empty or malformed non-absolute path, or points
     to an unmounted/unavailable mount directory, an actionable error is raised
     immediately.
   - Native absolute paths (Windows paths on Windows, POSIX paths on POSIX) are
     validated normally.
6. **Data integrity and loader contract**: Original image_path, IDs, group
   IDs, source splits, and image_sha256 metadata are never rewritten. The
   resolved file must exist on disk and is read only to derive exact letterbox
   geometry and padding bounds.
7. **Environment note**: Path resolution safety is verified via mocked platform
   unit tests in Windows environments and real Linux symlink and bounded path
   performance verification in WSL.
