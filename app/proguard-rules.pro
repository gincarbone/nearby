# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Nearby Connections classes
-keep class com.google.android.gms.nearby.** { *; }

# Keep Tink crypto classes
-keep class com.google.crypto.tink.** { *; }

# Keep Room entities
-keep class com.nearby.data.local.entity.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
