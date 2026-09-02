plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
    id("com.google.devtools.ksp") version "2.1.0-1.0.29" apply false
}

// One version for both form factors. Play requires every bundle uploaded to the same
// app to carry a distinct versionCode, so the phone and watch modules derive theirs
// from this base with a different last digit (see each module's build file).
extra["verMajor"] = 1
extra["verMinor"] = 0
extra["verPatch"] = 0
