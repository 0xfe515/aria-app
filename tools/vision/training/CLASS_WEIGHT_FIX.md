# Absent-Class Frequency Weight Normalization Fix

**Task ID**: `ARIA-ABSENT-CLASS-WEIGHT-FIX-20261031`  
**Scope**: `tools/vision/training/losses.py`, `tools/vision/training/tests/test_losses.py`

## Overview

In `tools/vision/training/losses.py`, `compute_inverse_sqrt_class_weights` calculates frequency-based class weights for semantic segmentation loss functions.

### Bug Diagnosis
When class counts contained absent classes (count = 0), the reciprocal fallback `freq = max(count / total, 1e-8)` resulted in `1.0 / sqrt(1e-8) = 10000.0`. During mean normalization (`normalize=True`), this massive artificial weight dominated the mean (~1432), causing all legitimate present classes to be crushed to the minimum weight bound (`0.5`), while the absent class received `3.0`.

On Cityscapes training counts (`[42472514, 35308559, 5835902, 0, 1206850, 7614918, 1908035]`), weights collapsed into `[0.5, 0.5, 0.5, 3.0, 0.5, 0.5, 0.5]`.

### Mathematical Correction
1. **Present-Only Normalization**:
   $$\text{total\_pos} = \sum_{c \in P} \text{count}_c$$
   $$\text{raw\_w}_c = \frac{1}{\sqrt{\max(\text{count}_c / \text{total\_pos}, 10^{-8})}} \quad \forall c \in P$$
   $$\text{mean\_pos} = \frac{1}{|P|} \sum_{c \in P} \text{raw\_w}_c$$
   $$\text{norm\_w}_c = \frac{\text{raw\_w}_c}{\text{mean\_pos}} \quad \forall c \in P$$
   $$\text{clipped\_w}_c = \text{clip}(\text{norm\_w}_c, \text{min\_weight}, \text{max\_weight}) \quad \forall c \in P$$
2. **Neutral Absent Class Weight**:
   $$\text{clipped\_w}_c = \text{clip}(1.0, \text{min\_weight}, \text{max\_weight}) \quad \forall c \notin P$$
3. **All-Zero Fallback**:
   If all counts are zero, all classes receive neutral $1.0$ (clipped to supplied bounds).

### Results on Actual Cityscapes Counts
- Input: `[42472514, 35308559, 5835902, 0, 1206850, 7614918, 1908035]`
- Output: `[0.5, 0.5, 0.9090145132587971, 1.0, 1.9989326651463217, 0.7957784916487853, 1.5897602870593375]`
- Class 3 (`crosswalk`, absent): received neutral `1.0`.
- Present classes: properly differentiated across `[0.5, 2.0]`.
