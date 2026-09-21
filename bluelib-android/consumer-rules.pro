# Consumer ProGuard/R8 rules shipped inside the BlueLib AAR.
# BlueLib exposes a Kotlin coroutine based API; no reflection is used, so the
# only rules needed are the standard Kotlin metadata and coroutine exemptions.
-dontwarn io.github.cybersafetyid.bluelib.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
