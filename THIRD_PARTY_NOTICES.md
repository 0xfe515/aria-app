# THIRD-PARTY SOFTWARE NOTICES AND LICENSES

This file contains third-party software notices and licenses for components incorporated in ARiA.

---

## 1. Apache License, Version 2.0
The following software components are provided under the Apache License, Version 2.0:
- **Sherpa-ONNX**: `1.13.8` (k2-fsa / Next-gen Kaldi, https://github.com/k2-fsa/sherpa-onnx)
- **kaldifst**: `bundled in Sherpa-ONNX 1.13.8` (k2-fsa, https://github.com/k2-fsa/kaldifst)
- **Google SentencePiece**: `bundled in Sherpa-ONNX 1.13.8` (Google LLC, https://github.com/google/sentencepiece)
- **Google LiteRT**: `1.4.2` (Google LLC, https://ai.google.dev/edge/litert) — includes `litert`, `litert-gpu`, and `litert-gpu-api`
- **Google LiteRT-LM**: `0.16.1` (Google LLC, https://ai.google.dev/edge/litert) - Also bundles native third-party notices.
- **AndroidX Jetpack Compose**: `BOM 2026.02.01 / UI 1.10.4` (The Android Open Source Project)
- **AndroidX CameraX**: `1.6.2` (The Android Open Source Project)
- **AndroidX Lifecycle & Core**: `Lifecycle 2.10.0 / Core 1.19.1 / Activity 1.13.0 / NavigationEvent 1.0.0` (The Android Open Source Project)
- **Kotlin Standard Library & Coroutines**: `2.2.21 / 1.9.0` (JetBrains s.r.o. and Kotlin contributors)
- **Kotlinx Serialization Core**: `1.7.3` (JetBrains s.r.o. and Kotlin contributors)
- **Kotlinx AtomicFU**: `0.28.0` (JetBrains s.r.o. and Kotlin contributors)
- **Google Dagger**: `2.59` (Google LLC, https://github.com/google/dagger)
- **Jakarta Dependency Injection API**: `2.0.1` (Eclipse Foundation, https://github.com/eclipse-ee4j/injection-api - see section 3 for official NOTICE.md)
- **JSR-330 javax.inject**: `1` (JSR-330 Expert Group, https://github.com/javax-inject/javax-inject)
- **Google Guava**: `33.3.1-android` (Google Inc., https://github.com/google/guava)
- **Google Guava FailureAccess**: `1.0.2` (Google Inc., https://github.com/google/guava)
- **Google Gson**: `2.13.2` (Google Inc., https://github.com/google/gson)
- **FindBugs JSR-305 Annotations**: `3.0.2` (FindBugs / JSR-305 authors, declared under Apache 2.0 in Maven POM, https://code.google.com/p/jsr-305/)
- **JSpecify Standard Annotations**: `1.0.0` (JSpecify authors, https://jspecify.dev)
- **JetBrains Java Annotations**: `23.0.0` (JetBrains s.r.o., https://github.com/JetBrains/java-annotations)
- **Google ErrorProne Annotations**: `2.41.0` (Google LLC, https://github.com/google/error-prone)
- **Google AutoValue Annotations**: `1.6.3` (Google LLC, https://github.com/google/auto)
- **Google J2ObjC Annotations**: `3.0.0` (Google LLC, https://github.com/google/j2objc)
- **Sherpa Zipformer Korean Model**: `2024-06-16-mobile` (johnBamma / icefall)
- **Gemma 4 E2B-IT On-Device LLM**: `gemma-4-E2B-it-litert-lm` (Google LLC / LiteRT community)

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to use, reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or exemplary damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS
```

---

## 2. MIT License
The following software components are provided under the MIT License:
- **Microsoft ONNX Runtime**: `1.20.1` (Bundled in Sherpa-ONNX AAR, `libonnxruntime.so`)
- **Checker Framework Qualifiers**: `3.43.0` (The Checker Framework developers, https://checkerframework.org)

```text
MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 3. Bundled Native and Project Notices
- **Google LiteRT-LM (`litertlm-android-0.16.1.aar`)**:
  Bundles an extensive native third-party notice document covering LLM runtime dependencies. This notice is included in full within `app/src/main/assets/licenses/litertlm_third_party_notice.txt` and is readable offline directly inside the application's Open Source Licenses viewer.
- **Eclipse Jakarta Dependency Injection API (`jakarta.inject-api-2.0.1.jar`)**:
  Includes official NOTICE.md from Eclipse Foundation within `app/src/main/assets/licenses/jakarta_inject_NOTICE.md`.

---

## 4. Models and Assets with Unprovided Licenses (NOT PROVIDED)
The following components are either proprietary project assets or external user models for which no third-party open-source license is claimed:
- **ARiA Team Vision Model (Original Asset)**: `walkable_seg_aria5.tflite` (commit `bfb11e6`, SHA-256 `3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7`, size: `10,837,912` bytes, Reference: https://github.com/tulg580/vision_model, see `docs/VISION_MODEL.md`). License is **NOT PROVIDED**.
- **ARiA Team Vision Model (GPU-Optimized Graph)**: `walkable_seg_aria5_unfused.tflite` (Derived via standalone flatbuffers transformation with Op 189 VALID+SLICE and Ops 192, 195, 198, 201 TransposeConv v3 + explicit ReLU v1 for full LiteRT GPU delegate compatibility, SHA-256 `84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847`, size: `10,839,936` bytes). License is **NOT PROVIDED**.
- **Custom User Vision Model**: `User-imported .tflite` files imported via the local device file picker. License is **NOT PROVIDED**.
- **Audio Feedback Cues**: `voice_start_clear.wav` and other audio cues in `app/src/main/res/raw/`. License is **NOT PROVIDED**.
