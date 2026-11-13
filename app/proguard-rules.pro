# Keep native methods and Whisper JNI bridge classes
-keepclassmembers class * {
    native <methods>;
}
-keep class org.futo.voiceinput.whisper.** { *; }
-keep class helium314.keyboard.latin.voice.WhisperEngine { *; }
-keep class helium314.keyboard.latin.utils.JniUtils { *; }

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# Keep custom Views inflated via LayoutInflater / XML
-keep class helium314.keyboard.** extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep kotlinx.serialization serializers & annotations used dynamically
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class helium314.keyboard.**$$serializer { *; }
-keepclassmembers class helium314.keyboard.** {
    *** Companion;
}
-keepclasseswithmembers class helium314.keyboard.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Kotpass (.kdbx) XML & crypto models
-keep class app.keemobile.kotpass.** { *; }
-dontwarn org.slf4j.**

# Keep readable stack traces for on-device LogKeeper reports
-keepattributes SourceFile,LineNumberTable
-dontobfuscate
