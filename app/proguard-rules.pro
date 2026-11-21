# Termux terminal libraries (JNI + reflection-free but keep for safety)
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# commons-compress optional deps
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.**
-dontwarn org.tukaani.**
-dontwarn org.objectweb.asm.**
-dontwarn javax.annotation.**
-dontwarn aQute.bnd.annotation.**
# App classes used from JS / assets
-keep class com.kalibox.app.** { *; }
