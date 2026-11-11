# kotlinx.serialization: keep generated serializers of the data model
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class it.topcash.studio.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class it.topcash.studio.**$$serializer { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit (barcode scanner) creates its components by reflection: R8 full mode removed their constructors and the
# app closed as soon as the scanner opened (2.8.12). Keep the registrars and ML Kit's own classes whole.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-dontwarn com.google.mlkit.**
