plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Build output goes under LOCALAPPDATA when it is set.
val localAppData = System.getenv("LOCALAPPDATA")
if (localAppData != null) {
    val localBuildRoot = File(localAppData, "GradleBuilds/${rootProject.name}")
    layout.buildDirectory.set(File(localBuildRoot, "root"))
    subprojects {
        layout.buildDirectory.set(File(localBuildRoot, project.name))
    }
}
