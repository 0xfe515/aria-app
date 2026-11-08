# OD-Associated Semantic Segmentation Ground-Contact Distance Design and Verification

**Date:** 2026-11-08
**Task ID:** ARIA-OD-SEG-DISTANCE-20261108
**Run ID:** run_9820bba118c9
**Dispatch ID:** ctx_3d2247c2cb6d
**Worker Terminal:** term_72719dce-2de7-40e3-b42f-2ad1ed4c2512
**Coordinator Terminal:** term_df6ae23a-3ba7-4c41-9000-e20f0196f620

---

## 1. Executive Overview

This change integrates accepted auxiliary object detections (OD) with monocular semantic segmentation ground-contact distance estimation. While pure semantic segmentation can suffer from false-positive ground contacts caused by road textures or shadow blobs, raw 2D object detection bounding boxes cannot measure ground contact directly or verify local walkable surface support.

By combining the two modalities:
1. **Fresh Accepted OD Confirmation:** Detections are gated by `OdEvidenceGate` and snapshotted once per analyzed camera frame, ensuring temporal freshness, geometry matching, session fencing, and agreement across `ObjectTracker`, `ObjectDetectionHazardFusion`, and `GroundContactDistanceEstimator`.
2. **Spatial and Class-Compatible Matching:** Bounding boxes are filtered by finite valid geometry and confidence (`score in 0.5f..1.0f`), with explicit MediaPipe/COCO to semantic class mapping:
   - Category 0 (`person`) -> Semantic class 4 (`person`)
   - Categories 1 (`bicycle`), 2 (`car`), 3 (`motorcycle`), 5 (`bus`), 7 (`truck`) -> Semantic class 5 (`vehicle`)
   - Category 9 (`traffic light`) and all other categories are strictly excluded from ground contact.
3. **Mask-Derived Ground Contact Point:** The metric ground contact point `(contactX, contactY)` is derived strictly from the lowest rows of the associated segmentation mask component pixels, **never from the raw bounding box bottom**.
4. **Lower-Boundary Agreement and Truncation Rejection:** Components constrained to bounding boxes must satisfy lower-boundary agreement (`|maskBottomNorm - det.bottom| <= 0.15f`). Furthermore, artificial window-truncated bottoms (where the mask component continues downward in the original frame below the search window) are rejected, and local ground support is strictly verified directly below the contact foot columns, preventing unrelated ground to the side from legitimizing a cutoff.
5. **Robust Fallback Semantics:**
   - When OD evidence is missing (`null`), empty, low-confidence (<0.5), or contains only traffic lights, the system seamlessly falls back to existing pure segmentation-only estimation across all target classes (4, 5, 6), preserving maximum availability.
   - When qualified OD detections exist, OD-gated matching is activated for persons and vehicles, while pole (class 6) is always retained as an explicit documented fallback from segmentation because OD does not detect utility poles.
   - Unmatched OD detections never fabricate distance without mask support. Unmatched person/vehicle mask components under active OD are excluded from the distance candidates. This can also suppress real objects missed by OD; an accuracy improvement has not been measured.
6. **Zero-Allocation Traversal:** `OdMaskMatcher` utilizes preallocated, reusable BFS queues and stamp-based visited tracking, preventing garbage collection churn during per-frame execution.

---

## 2. Architecture and Data Flow

```
Camera Frame Analyzer
   │
   ├──> odEvidenceSnapshot = objectDetectionPipeline.latestEvidence() (Snapshot once per frame)
   ├──> gatedOd = objectDetectionFusion.evidenceGate.accept(odEvidenceSnapshot, ...)
   │
   ├──> ObjectTracker.update(detections = gatedOd.accepted?.detections, ...)
   ├──> ObjectDetectionHazardFusion.fuse(evidence = odEvidenceSnapshot, ...)
   │
   └──> GroundContactDistanceEstimator.estimate(
           classMask = reusableClassMask,
           detections = gatedOd?.accepted?.detections,
           profile = currentProfile,
           effectiveFov / fullCameraModel = activeCameraModel,
           orientation = groundOrientation,
           ...
        )
           │
           └──> OdMaskMatcher.matchAndExtract(...)
                   ├── [qualified.isEmpty()] ──> Fallback: pure segmentation (4, 5, 6)
                   │
                   └── [qualified.isNotEmpty()]
                           ├── Explicit fallback: Pole (6) from segmentation
                           └── For each Qualified OD Detection:
                                   ├── Constrain search window (box + padding)
                                   ├── Local BFS connected component
                                   ├── Lower-boundary agreement check (|maskBottom - bboxBottom| <= 0.15)
                                   ├── Real bottom boundary check (reject window-truncated bottom)
                                   ├── Contact derivation (bottom 3 rows average X, maxY)
                                   ├── Clipping rejection (box or mask touches bottom edge)
                                   └── Local ground support check (directly under foot columns)
```

---

## 3. Fallback Policy Matrix

| Condition | OdEvidenceGate State | Qualified Detections | Estimator Behavior | Target Classes Active |
| :--- | :--- | :--- | :--- | :--- |
| **Normal / OD Active** | `accepted != null` | 1+ Person / Vehicle | OD-gated matching on detected boxes; unmatched person/vehicle mask suppressed | Matched 4 & 5 + Fallback 6 (Pole) |
| **No OD Available** | Pipeline disabled / preview | N/A (`null`) | Full segmentation-only fallback | 4 (Person), 5 (Vehicle), 6 (Pole) |
| **Gate Stale / Lag** | Rejection `STALE_EVIDENCE` | `accepted == null` | Full segmentation-only fallback | 4 (Person), 5 (Vehicle), 6 (Pole) |
| **Gate Future Skew** | Rejection `FUTURE_EVIDENCE` | `accepted == null` | Full segmentation-only fallback | 4 (Person), 5 (Vehicle), 6 (Pole) |
| **Geometry Mismatch** | Rejection `GEOMETRY_MISMATCH` | `accepted == null` | Full segmentation-only fallback | 4 (Person), 5 (Vehicle), 6 (Pole) |
| **Session Mismatch** | Rejection `SESSION_MISMATCH` | `accepted == null` | Full segmentation-only fallback | 4 (Person), 5 (Vehicle), 6 (Pole) |
| **Empty OD Detections** | `accepted != null` | 0 (`emptyList()`) | Full segmentation-only fallback | 4 (Person), 5 (Vehicle), 6 (Pole) |
| **Low-Confidence OD** | `accepted != null` | 0 (all scores < 0.5) | Full segmentation-only fallback | 4 (Person), 5 (Vehicle), 6 (Pole) |
| **Traffic Light Only** | `accepted != null` | 0 (only category 9) | Full segmentation-only fallback | 4 (Person), 5 (Vehicle), 6 (Pole) |
| **Pole in Scene** | Any OD state | Any | Pole (6) always extracted from mask | 6 (Pole) |

---

## 4. Modified and Newly Created Files

1. `app/src/main/java/com/ariateam/aria/distance/OdMaskMatcher.kt` *(New production helper)*:
   - Zero-allocation BFS traversal using reusable stamp buffers.
   - Explicit COCO-to-semantic category mapping and confidence bounds `[0.5f, 1.0f]`.
   - Real bottom boundary verification in original mask preventing window truncation artifacts.
   - Local ground support search restricted to bottom contact columns.
   - Robust missing/empty/traffic-light OD fallback.
2. `app/src/main/java/com/ariateam/aria/distance/GroundContactDistanceEstimator.kt` *(Production modification)*:
   - Added `detections: List<DetectedObject>? = null` to `estimate()` and `extractCandidates()`.
   - Bound `OdMaskMatcher` instance to reuse internal buffers across frames.
   - Preserved all camera ray projection, stature/mount profile, body mount models, orientation validity, +/-0.45m walking corridor, nearest-forward selection, and temporal smoothing/reset.
3. `app/src/main/java/com/ariateam/aria/vision/VisionPipelineController.kt` *(Production modification)*:
   - Snapshots `odEvidenceSnapshot = objectDetectionPipeline.latestEvidence()` once per analyzed frame.
   - Evaluates `OdEvidenceGate.accept()` for tracker and distance; fusion independently rechecks the same evidence snapshot with the same per-frame clock value.
4. `app/src/test/java/com/ariateam/aria/distance/OdMaskMatcherTest.kt` *(New unit tests)*:
   - Class mapping, score qualification (including >1.0 and NaN rejection).
   - Missing/empty/low-score/traffic-light-only fallback tests.
   - Mask contact point vs bounding box bottom observable coordinate difference assertion.
   - Spatial and class mismatch rejection.
   - Merged mask with multiple boxes distinct contact assignment without distant component bottom assignment.
   - Window truncation verification rejecting tall component cutoffs even with unrelated ground to side.
   - Bottom clipping and floating unsupported rejection.
   - `OdEvidenceGate` stale, future, geometry, session rejection integration with distance estimator.
5. `app/src/test/java/com/ariateam/aria/distance/GroundContactDistanceEstimatorTest.kt` *(Unit tests)*:
   - End-to-end OD-associated distance estimation with mask contact vs box bottom metric verification.
   - Nearest forward candidate selection among multiple corridor obstacles.
   - Lateral walking corridor (+/-0.45m) boundary enforcement.
   - Temporal smoothing and reset behavior.
   - Segmentation fallback on null, empty, and low-confidence OD evidence.
6. `.orca/drops/continuation/ARIA-OD-SEG-DISTANCE-20261108.md` *(Continuation checkpoint)*.

---

## 5. Worker Verification Evidence

Commands executed in repository root with Android Studio JBR (`C:\Program Files\Android\Android Studio\jbr`) and Android SDK:

### 1. Unit Tests
```powershell
.\gradlew.bat :app:testDebugUnitTest --console=plain
```
- **Exit Code:** 0
- **Outcome:** BUILD SUCCESSFUL. 98 tests in distance package passed without failure or regression. Full test suite completed successfully.

### 2. Debug Assembly
```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```
- **Exit Code:** 0
- **Outcome:** BUILD SUCCESSFUL. Debug APK generated cleanly.

### 3. Android Lint
```powershell
.\gradlew.bat :app:lintDebug --console=plain
```
- **Exit Code:** 0
- **Outcome:** BUILD SUCCESSFUL. No lint errors; HTML and SARIF reports generated in `app/build/reports/lint-results-debug.html`.

### 4. Instrumented Test Compilation
```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --console=plain
```
- **Exit Code:** 0
- **Outcome:** BUILD SUCCESSFUL. Instrumented test sources compiled cleanly against updated classes.

### 5. Git Working Tree Hygiene
```powershell
git diff --check
```
- **Exit Code:** 0 (clean, no trailing whitespace or patch formatting issues).

---

## 6. Limitations and Exclusions

- **No Hardware Performance Claims:** In accordance with repository policy, no real-device tests or hardware installation were performed. Synthetic JVM cases verify distance calculations and contact selection. Real-world distance accuracy, recovery, and frame processing performance have not been measured in this task.
- **Pure Monocular Ground-Contact Domain:** Monocular ground contact relies on planar ground geometry assumptions and user stature/mount profiles. It is valid only for grounded obstacles on level walkable surfaces.

## 7. Coordinator Independent Review and Validation

- Reviewed final production and test diffs, class/coordinate mapping, mask contact extraction, truncation rejection, evidence freshness, fallback and existing geometric guards. Requested and verified fixes for empty OD fallback, per-component large allocations, artificial window bottoms, invalid confidence scores and evidence-gate regression fixtures.
- Independently invoked `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:compileDebugAndroidTestKotlin --console=plain` with the repository wrapper and Android Studio JBR after the worker settled. Exit code: **0**, BUILD SUCCESSFUL. Gradle reused unchanged task outputs; lintDebug executed, remaining tasks were up-to-date.
- Raw coordinator log: `%TEMP%/aria-od-seg-independent-validation.log`.
- Checked XML results: **1,019 tests, 87 suites, 0 failures, 0 errors, 0 skipped**. Distance package: 98 tests (16 added).
- Lint report: **0 errors, 196 warnings, 3 hints**; successful lint does not mean warning-free.
- `git diff --check`: exit code **0**. New helper/test files were also inspected directly because untracked files are not shown by ordinary git diff.
- No installation, connected-device execution, commit or push. Worker completed Task `task_f058173136d9` / Dispatch `ctx_3d2247c2cb6d`; terminal released after review and validation.
