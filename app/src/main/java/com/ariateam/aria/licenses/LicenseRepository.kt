package com.ariateam.aria.licenses

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

object LicenseRepository {

    val allLicenses: List<LicenseItem> = listOf(
        // === Native & On-Device Engines ===
        LicenseItem(
            id = "sherpa-onnx",
            name = "Sherpa-ONNX (Offline Speech & Wake Word)",
            version = "1.13.8",
            projectUrl = "https://github.com/k2-fsa/sherpa-onnx",
            licenseName = "Apache-2.0",
            category = LicenseCategory.NativeBinary,
            projectNotice = "Sherpa-ONNX: Next-gen Kaldi offline speech processing library.\nCopyright by many contributors (see AUTHORS and git commits).",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "kaldifst",
            name = "kaldifst",
            version = "bundled in Sherpa-ONNX 1.13.8",
            projectUrl = "https://github.com/k2-fsa/kaldifst",
            licenseName = "Apache-2.0",
            category = LicenseCategory.NativeBinary,
            projectNotice = "kaldifst: Fast and lightweight OpenFST implementation for Kaldi speech recognition.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "sentencepiece",
            name = "Google SentencePiece",
            version = "bundled in Sherpa-ONNX 1.13.8",
            projectUrl = "https://github.com/google/sentencepiece",
            licenseName = "Apache-2.0",
            category = LicenseCategory.NativeBinary,
            projectNotice = "SentencePiece: Unsupervised text tokenizer and detokenizer for Neural Network-based text generation.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "onnxruntime",
            name = "Microsoft ONNX Runtime Mobile",
            version = "1.20.1",
            projectUrl = "https://github.com/microsoft/onnxruntime",
            licenseName = "MIT",
            category = LicenseCategory.NativeBinary,
            projectNotice = """ONNX Runtime: cross-platform, high performance scoring engine for ML models.
Copyright (c) Microsoft Corporation. All rights reserved.""",
            licenseAssetPath = "licenses/onnx-mit.txt",
        ),
        LicenseItem(
            id = "litert",
            name = "Google LiteRT (runtime, litert-gpu, litert-gpu-api)",
            version = "1.4.2",
            projectUrl = "https://ai.google.dev/edge/litert",
            licenseName = "Apache-2.0",
            category = LicenseCategory.NativeBinary,
            projectNotice = """Google LiteRT: High-performance on-device AI runtime (com.google.ai.edge.litert:litert, litert-gpu, litert-gpu-api 1.4.2).
Copyright The TensorFlow / LiteRT Authors.""",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "litert-lm",
            name = "Google LiteRT-LM (On-Device LLM Runtime)",
            version = "0.16.1",
            projectUrl = "https://ai.google.dev/edge/litert",
            licenseName = "Apache-2.0",
            category = LicenseCategory.NativeBinary,
            projectNotice = """LiteRT-LM: On-device Large Language Model inference runtime.
Copyright The LiteRT-LM Authors.
This AAR includes bundled native third-party notices (see native notices viewer).""",
            licenseAssetPath = "licenses/apache-2.0.txt",
            thirdPartyNoticeAssetPath = "licenses/litertlm_third_party_notice.txt",
        ),

        // === AndroidX & Runtime Libraries ===
        LicenseItem(
            id = "androidx-compose",
            name = "AndroidX Jetpack Compose",
            version = "BOM 2026.02.01 / UI 1.10.4",
            projectUrl = "https://developer.android.com/jetpack/compose",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright The Android Open Source Project.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "androidx-camerax",
            name = "AndroidX CameraX",
            version = "1.6.2",
            projectUrl = "https://developer.android.com/jetpack/androidx/releases/camera",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright The Android Open Source Project.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "androidx-lifecycle",
            name = "AndroidX Lifecycle & ViewModel",
            version = "2.10.0",
            projectUrl = "https://developer.android.com/jetpack/androidx/releases/lifecycle",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright The Android Open Source Project.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "androidx-core",
            name = "AndroidX Core, Activity & NavigationEvent",
            version = "1.19.1 / 1.13.0 / 1.0.0",
            projectUrl = "https://developer.android.com/jetpack",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright The Android Open Source Project.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "kotlin-stdlib",
            name = "Kotlin Standard Library & Coroutines",
            version = "2.2.21 / 1.9.0",
            projectUrl = "https://kotlinlang.org",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "kotlinx-serialization",
            name = "Kotlinx Serialization Core",
            version = "1.7.3",
            projectUrl = "https://github.com/Kotlin/kotlinx.serialization",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "kotlinx-atomicfu",
            name = "Kotlinx AtomicFU",
            version = "0.28.0",
            projectUrl = "https://github.com/Kotlin/kotlinx-atomicfu",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "google-dagger",
            name = "Google Dagger",
            version = "2.59",
            projectUrl = "https://github.com/google/dagger",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright Google LLC.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "jakarta-inject",
            name = "Jakarta Dependency Injection API",
            version = "2.0.1",
            projectUrl = "https://github.com/eclipse-ee4j/injection-api",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = """Eclipse Jakarta Dependency Injection project.
Copyright Eclipse Foundation and individual authors.""",
            licenseAssetPath = "licenses/jakarta_inject_notice_and_license.txt",
        ),
        LicenseItem(
            id = "javax-inject",
            name = "JSR-330 javax.inject",
            version = "1",
            projectUrl = "https://github.com/javax-inject/javax-inject",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright (C) 2009 The JSR-330 Expert Group.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "google-guava",
            name = "Google Guava",
            version = "33.3.1-android",
            projectUrl = "https://github.com/google/guava",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright (C) Google Inc.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "google-failureaccess",
            name = "Google Guava FailureAccess",
            version = "1.0.2",
            projectUrl = "https://github.com/google/guava",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright (C) Google Inc.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "google-gson",
            name = "Google Gson",
            version = "2.13.2",
            projectUrl = "https://github.com/google/gson",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright (C) Google Inc.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "google-jsr305",
            name = "FindBugs JSR-305 Annotations",
            version = "3.0.2",
            projectUrl = "https://code.google.com/p/jsr-305/",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "JSR305 Annotations for Findbugs.\nDeclared under The Apache Software License, Version 2.0 in published Maven POM.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "checker-qual",
            name = "Checker Framework Qualifiers",
            version = "3.43.0",
            projectUrl = "https://checkerframework.org",
            licenseName = "MIT",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Checker Framework qualifiers (org.checkerframework:checker-qual).",
            licenseAssetPath = "licenses/checker-qual-mit.txt",
        ),
        LicenseItem(
            id = "jspecify",
            name = "JSpecify Standard Annotations",
            version = "1.0.0",
            projectUrl = "https://jspecify.dev",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright (C) JSpecify authors.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "jetbrains-annotations",
            name = "JetBrains Java Annotations",
            version = "23.0.0",
            projectUrl = "https://github.com/JetBrains/java-annotations",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright JetBrains s.r.o.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "google-errorprone",
            name = "Google ErrorProne Annotations",
            version = "2.41.0",
            projectUrl = "https://github.com/google/error-prone",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright Google LLC.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "google-autovalue",
            name = "Google AutoValue Annotations",
            version = "1.6.3",
            projectUrl = "https://github.com/google/auto",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright Google LLC.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "google-j2objc",
            name = "Google J2ObjC Annotations",
            version = "3.0.0",
            projectUrl = "https://github.com/google/j2objc",
            licenseName = "Apache-2.0",
            category = LicenseCategory.AndroidJvm,
            projectNotice = "Copyright Google LLC.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),

        // === Models & Assets ===
        LicenseItem(
            id = "zipformer-model",
            name = "Sherpa Zipformer Korean ASR Model",
            version = "2024-06-16-mobile",
            projectUrl = "https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12",
            licenseName = "Apache-2.0",
            category = LicenseCategory.ModelAsset,
            projectNotice = "Pruned stateless transducer streaming model trained on KsponSpeech Korean dataset.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "gemma-model",
            name = "Gemma 4 E2B-IT On-Device LLM",
            version = "gemma-4-E2B-it-litert-lm",
            projectUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
            licenseName = "Apache-2.0",
            category = LicenseCategory.ModelAsset,
            projectNotice = "Gemma model weights published under the Apache-2.0 license per official HuggingFace model card.",
            licenseAssetPath = "licenses/apache-2.0.txt",
        ),
        LicenseItem(
            id = "team-vision-model",
            name = "ARIA Team Vision Model",
            version = "walkable_seg_aria5.tflite (commit bfb11e6)",
            projectUrl = "https://github.com/tulg580/vision_model",
            licenseName = "NOT PROVIDED",
            category = LicenseCategory.ModelAsset,
            projectNotice = "ARIA 팀 소스 비전 모델 (SHA-256: 3944dce57cc161679525c3f4feb1f6b46c8e7519f8f10ba06155b3874c2c66b7, 크기: 10,837,912 B, 참조: docs/VISION_MODEL.md). 외부 오픈소스 라이선스를 임의로 주장하지 않으며 NOT PROVIDED로 명시합니다.",
            licenseAssetPath = "",
        ),
        LicenseItem(
            id = "team-vision-model-unfused",
            name = "ARIA Team Vision Model (GPU-Optimized)",
            version = "walkable_seg_aria5_unfused.tflite",
            projectUrl = "https://github.com/tulg580/vision_model",
            licenseName = "NOT PROVIDED",
            category = LicenseCategory.ModelAsset,
            projectNotice = "ARIA 팀 비전 모델 GPU 최적화 그래프 (Op 189 VALID+SLICE 및 Op 192, 195, 198, 201 TransposeConv v3+명시적 ReLU v1로 분리하여 LiteRT GPU 위임 지원, SHA-256: 84364b9730d200b00a65ebcbae6a13444c6f8eb9bbab0676adc30d57cfa24847, 크기: 10,839,936 B, 참조: docs/VISION_MODEL.md). NOT PROVIDED로 명시합니다.",
            licenseAssetPath = "",
        ),
        LicenseItem(
            id = "user-vision-model",
            name = "Custom User Vision Model",
            version = "User-imported .tflite",
            projectUrl = "로컬 저장소 (Local Storage)",
            licenseName = "NOT PROVIDED",
            category = LicenseCategory.ModelAsset,
            projectNotice = "사용자가 로컬 파일 탐색기를 통해 임포트하는 모델 파일입니다. 앱에서 별도 오픈소스 라이선스를 주장하지 않습니다.",
            licenseAssetPath = "",
        ),
        LicenseItem(
            id = "audio-cues",
            name = "ARIA Audio Feedback Cues",
            version = "bundled in res/raw",
            projectUrl = "com.ariateam.aria",
            licenseName = "NOT PROVIDED",
            category = LicenseCategory.ModelAsset,
            projectNotice = "voice_start_clear.wav 등 앱 번들 효과음 자산입니다. 외부 오픈소스 저작권 라이선스를 주장하지 않습니다.",
            licenseAssetPath = "",
        ),
    )

    private val cachedTexts = mutableMapOf<String, String>()

    suspend fun loadFullLicenseText(context: Context, item: LicenseItem): String = withContext(Dispatchers.IO) {
        val notice = item.projectNotice.trim()
        if (item.licenseAssetPath.isBlank()) {
            return@withContext notice
        }

        val licenseText = cachedTexts.getOrPut(item.licenseAssetPath) {
            try {
                context.assets.open(item.licenseAssetPath).bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                "라이선스 파일 로드 실패: ${e.message}"
            }
        }

        if (notice.isEmpty()) {
            licenseText
        } else {
            "$notice\n\n${"-".repeat(40)}\n\n$licenseText"
        }
    }
}
