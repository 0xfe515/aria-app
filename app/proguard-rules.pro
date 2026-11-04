# JNI looks up Kotlin API types and fields by name. Keep the pinned API intact.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT 2.3.2 runtime JNI bindings and native handles
-keep class com.google.ai.edge.litert.** { *; }
-keep class org.tensorflow.lite.** { *; }
