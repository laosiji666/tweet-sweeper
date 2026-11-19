# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.yg.tweetsweeper.data.** { *; }
-keepclassmembers class com.yg.tweetsweeper.net.** { *; }
-keep,includedescriptorclasses class com.yg.tweetsweeper.**$$serializer { *; }
-keepclasseswithmembers class com.yg.tweetsweeper.** {
    kotlinx.serialization.KSerializer serializer(...);
}
