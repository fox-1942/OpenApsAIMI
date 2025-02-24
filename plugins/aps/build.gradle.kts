plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
    id("android-module-dependencies")
    id("test-module-dependencies")
    id("jacoco-module-dependencies")
}

android {
    namespace = "app.aaps.plugins.aps"
}

dependencies {
    implementation(project(":database:entities"))
    implementation(project(":database:impl"))
    implementation(project(":core:main"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:nssdk"))
    implementation(project(":core:utils"))
    implementation(project(":core:ui"))
    implementation(project(":core:validators"))
    implementation("org.tensorflow:tensorflow-lite-support:0.1.0")
    implementation ("org.tensorflow:tensorflow-lite-metadata:0.1.0")
    implementation ("org.tensorflow:tensorflow-lite-gpu:2.3.0")
    implementation("androidx.core:core-i18n:1.0.0-alpha01")

    testImplementation(project(":pump:virtual"))
    testImplementation(project(":shared:tests"))

    api(Libs.AndroidX.appCompat)
    api(Libs.AndroidX.swipeRefreshLayout)
    api(Libs.AndroidX.gridLayout)

    // APS
    api(Libs.Mozilla.rhino)

    kapt(Libs.Dagger.androidProcessor)
}