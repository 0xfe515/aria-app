# Crosswalk Region Display Estimation (2026-11-08)

## 1. Overview and Goal

In street-level semantic segmentation for pedestrian navigation, zebra crosswalks are frequently predicted as alternating stripes: the white painted stripes are classified as class 3 (`crosswalk`), while the asphalt gaps between stripes are classified as class 1 (`road`) or class 0 (`other` / background). When rendered under a binary walkable overlay (e.g. green for walkable surfaces, red for non-walkable surfaces), this produces an unnatural, striped display that impairs user comprehension.

`CrosswalkRegionEstimator` provides a bounded, deterministic, pure display-only geometric supplement. It bridges the inter-stripe gaps between nearby aligned crosswalk stripes to render a continuous, filled green crossing region while strictly preserving obstacle holes and keeping separate crossings isolated.

> [!IMPORTANT]
> **Display Approximation Notice:**
> The estimated region is an empirical geometric presentation aid derived from visible stripe predictions on the 2D image plane. It is **not** a physical measurement of real-world crossing boundaries and must **never** be interpreted as a safety certification or traffic signal permission ("green means go"). All underlying hazard rules, spatial distance measurements, and raw segmentation class masks remain unaltered.

---

## 2. Shared Frozen Interface

The estimator is located in `com.ariateam.aria.vision` and conforms to the frozen interface:

```kotlin
package com.ariateam.aria.vision

internal class CrosswalkRegionEstimator {
    fun estimate(
        classMask: ByteArray,
        width: Int,
        height: Int,
        outRegion: BooleanArray
    )
}
```

### Input and Output Contract
- `classMask`: Input 1D byte array of size at least `width * height`, representing the canonical segmentation class IDs (0..6). **Never mutated.**
- `width`, `height`: Canonical upright image grid dimensions (e.g., 512x288 or test resolutions). Validated for positive values and no integer overflow (`width <= Int.MAX_VALUE / height`).
- `outRegion`: Output 1D boolean array of size at least `width * height`.
  - Cleared across frames and dimension changes (`Arrays.fill(outRegion, 0, width * height, false)`).
  - Set to `true` for pixels belonging to supported crosswalk stripes and bounded inter-stripe road/background gaps.
  - Set to `false` for background, road outside crossings, non-crosswalk areas, and all obstacle pixels.

---

## 3. Geometric Algorithm Details

The estimation operates in four deterministic stages:

```
[ classMask (raw) ]
        │
        ▼ (Stage 1: 8-Connected Component Analysis + 2D Central Moments)
[ Candidate Stripes: area, centroid, angle θ, elongation, major length, 8 boundary vertices ]
        │
        ▼ (Stage 2: Pairwise Alignment & Nearest Transverse Neighbor Selection)
[ Strictly Adjacent Stripe Pairs: |cos(Δθ)| ≥ 0.75, transverse gap ≤ 75px, bounded longitudinal offset ]
        │
        ▼ (Stage 3: Cluster Size Gating)
[ Accepted Clusters (≥ 2 stripes); isolated single patches & noise rejected ]
        │
        ▼ (Stage 4: 2D Convex Hull Rasterization per Pair + Obstacle Hole Preservation)
[ outRegion (filled green crossing region with obstacle holes) ]
```

### Stage 1: Component Analysis and Moment Extraction
1. Scans `classMask` for class 3 pixels using an 8-connected BFS queue.
2. Accumulates zeroth, first, and second moments ($\sum x, \sum y, \sum x^2, \sum y^2, \sum xy$) in 64-bit integers (`Long`) to prevent overflow.
3. Computes central moments $\mu_{20}, \mu_{02}, \mu_{11}$ and principal components:
   $$\theta = \frac{1}{2} \operatorname{atan2}(2 \mu_{11}, \mu_{20} - \mu_{02})$$
   $$\lambda_{1,2} = \frac{\mu_{20} + \mu_{02} \pm \sqrt{(\mu_{20} - \mu_{02})^2 + 4 \mu_{11}^2}}{2}$$
   $$\text{elongation} = \sqrt{\frac{\lambda_1}{\lambda_2}}, \quad \text{majorLength} = \sqrt{12 \lambda_1}$$
4. Rejection criteria:
   - Noise: $\text{area} < 10$ pixels.
   - Non-stripes: $\text{elongation} < 1.30$ or $\text{majorLength} < 6.0$ pixels.
5. For accepted candidates, extracts the 4 exact Oriented Bounding Box (OBB) corners in the principal orientation basis $(\vec{u}, \vec{v})$:
   $$\begin{cases}
   C_0 = (\min U \cdot u_x + \min V \cdot v_x, \; \min U \cdot u_y + \min V \cdot v_y) \\
   C_1 = (\max U \cdot u_x + \min V \cdot v_x, \; \max U \cdot u_y + \min V \cdot v_y) \\
   C_2 = (\max U \cdot u_x + \max V \cdot v_x, \; \max U \cdot u_y + \max V \cdot v_y) \\
   C_3 = (\min U \cdot u_x + \max V \cdot v_x, \; \min U \cdot u_y + \max V \cdot v_y)
   \end{cases}$$
   Using exact OBB corners avoids coordinate indexing artifacts or unassigned coordinate branches, ensuring the hull tightly bounds the stripe without extending beyond the true crossing perimeter.

### Stage 2: Pairwise Alignment & Adjacency Selection
To bridge gaps without leaping over stripes or connecting unrelated crossings:
- **Alignment:** Mutual orientation cosine $|\vec{u}_i \cdot \vec{u}_j| \ge 0.75$ (tolerance up to $\sim 41.4^\circ$ to handle camera perspective convergence).
- **Transverse Distance:** Projected gap along the mutual transverse normal $\vec{v}_{avg}$ must be within $[1.0, \min(75.0, \max(6.0, 1.5 \times \text{avgLength}))]$ pixels scaled by vertical resolution.
- **Longitudinal Alignment:** Projected center offset along the stripe axis must be $\le 0.60 \times \max(\text{length}_i, \text{length}_j) + 8.0$ px, with longitudinal overlap $\ge 0.35 \times \min(\text{length}_i, \text{length}_j)$ px.
- **Nearest Neighbor Guarantee:** Each stripe only pairs with its **immediate** nearest transverse neighbor on the positive side and negative side. This guarantees that stripes form a sequential chain $(S_1-S_2, S_2-S_3, \dots)$ and never bridge long-distance leaps across multiple stripes or avenues.

### Stage 3: Cluster Size Gating
- Candidate stripes are clustered using Disjoint Set Union (Union-Find) over the adjacent pairs.
- Clusters with fewer than 2 stripes ($M < 2$) are **rejected**.
- Single isolated patches, false positive blobs, and sparse noise produce 0 pairs and no filled region (`outRegion` stays completely `false`).

### Stage 4: Convex Hull Rasterization and Obstacle Preservation
- For each adjacent pair $(S_A, S_B)$ in an accepted cluster:
  1. Combines the 4 OBB corners of $S_A$ and 4 OBB corners of $S_B$ (8 points total).
  2. Computes the 2D convex hull via Andrew's monotone chain algorithm ($O(P \log P)$ for $P = 8$).
  3. Scanline-rasterizes the convex polygon between $\lfloor \min(Y) \rfloor$ and $\lceil \max(Y) \rceil$.
  4. For each pixel $(x, y)$ within the scanline span $[startX, endX]$:
     ```kotlin
     val classId = classMask[y * width + x].toInt() and 0xFF
     if (classId == CLASS_CROSSWALK || classId == CLASS_BACKGROUND || classId == CLASS_ROAD) {
         outRegion[y * width + x] = true
     }
     ```
- **Obstacle Preservation:** If `classId` is 4 (person), 5 (vehicle), 6 (pole/sign), 2 (sidewalk), or any unknown/invalid ID, the pixel is **not** set to `true`. Obstacles inside the crosswalk remain preserved as `false` holes.

---

## 4. Complexity and Allocation Profile

| Metric | Property |
| :--- | :--- |
| **Time Complexity** | $O(W \times H + K^2 + P \cdot A_{pair})$ where $W \times H$ is pixel count, $K$ is candidate stripe count ($K \le 64$), $P$ is pair count ($P \le 64$), and $A_{pair}$ is the bounded inter-stripe gap area. Device latency has not been measured for this change. |
| **Space Complexity** | $O(W \times H)$ primitive arrays (`IntArray`, `FloatArray`, `BooleanArray`), pre-allocated in estimator instance. |
| **Heap Allocations** | **Zero per-frame allocations.** All queue buffers, moments, boundary points, union-find structures, and convex hull vertices reside in reusable primitive arrays. Dynamic expansion occurs only on the first frame or when image dimensions change. |
| **Dependencies** | Pure standard Kotlin / Java runtime math (`kotlin.math.*`, `java.util.Arrays`). No native, NDK, or third-party graphics libraries required. |

---

## 5. Visual Fixture and Test Artifact

The test suite generates a synthetic visual evidence artifact at:
`app/build/reports/crosswalk/crosswalk_display_fixture.svg`

It validates and displays four canonical scenarios side-by-side (raw classMask vs. output crosswalk region):
1. **Horizontal Crossing with Obstacle:** Inter-stripe road gaps are filled green; a person obstacle inside the gap is preserved as a hole.
2. **Perspective Convergence:** The perspective fixture shows partial support: only stripe pairs meeting the local geometry gates are joined. Other raw crosswalk stripes remain green in the production renderer; complete perspective filling is not guaranteed.
3. **Diagonal Orientation:** 45-degree angled crosswalk stripes have their diagonal gaps bridged.
4. **Separate Crossings Isolation:** Two distinct crossings separated by a wide road gap have their internal gaps filled, while the wide intermediate road area remains strictly unfilled (`false`).

---

## 6. Validation Summary and Evidence

- **Unit Test Execution:**
  - Command: `.\gradlew.bat :app:testDebugUnitTest --tests com.ariateam.aria.vision.CrosswalkRegionEstimatorTest --console=plain`
  - Result: Exit code 0, 15 tests passed, 0 failures, 0 skipped, 0 errors.
  - Log: `app/build/test_crosswalk_estimator.log`
  - JUnit XML: `app/build/test-results/testDebugUnitTest/TEST-com.ariateam.aria.vision.CrosswalkRegionEstimatorTest.xml`
  - Generated SVG: `app/build/reports/crosswalk/crosswalk_display_fixture.svg` (619 KB)
- **Code Quality and Constraints:**
  - `git diff --check` passes cleanly with 0 whitespace or formatting issues.
  - Zero per-frame allocations in `estimate()`.
  - All temporary debug `println` calls removed.
  - Frozen contract `internal class CrosswalkRegionEstimator` strictly preserved.
