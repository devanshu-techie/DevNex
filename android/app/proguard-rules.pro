# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.devanshu.devnex.**$$serializer { *; }
-keepclassmembers class com.devanshu.devnex.** { *** Companion; }
-keepclasseswithmembers class com.devanshu.devnex.** { kotlinx.serialization.KSerializer serializer(...); }

# SQLCipher
-keep class net.zetetic.** { *; }

# Ktor / Supabase
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-keep class io.github.jan.supabase.** { *; }
