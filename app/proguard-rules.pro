# CDNHunter Pro keeps minification disabled for the release build for build
# reproducibility. The rules below are kept for future enablement.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.cdhunter.pro.**$$serializer { *; }
-keepclassmembers class com.cdhunter.pro.** { *** Companion; }
-keepclasseswithmembers class com.cdhunter.pro.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
