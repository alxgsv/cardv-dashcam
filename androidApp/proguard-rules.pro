# App-specific R8 rules. AndroidX, Compose, Media3, OkHttp, Ktor and WorkManager ship their
# own consumer rules; these cover the parts that are instantiated by name or use annotations.

# WorkManager creates the worker from its class name.
-keep class com.cardv.dashcam.download.DownloadWorker { <init>(...); }

# Kotlin metadata and annotations are read by libraries at runtime.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleAnnotations,RuntimeInvisibleParameterAnnotations,AnnotationDefault

# Optional OkHttp security providers (not bundled).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
