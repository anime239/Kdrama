dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
version = 13

cloudstream {
    description = "OnlyKdrama"
    authors = listOf("Kdrama")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = true
    language = "hi"
    iconUrl = "https://raw.githubusercontent.com/anime239/Kdrama/refs/heads/main/icons/OnlyKdrama.png"
}

android {
    namespace = "com.onlykdrama"

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    defaultConfig {
        val tmdbToken = System.getenv("TMDB_TOKEN")?.trim() ?: ""
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
    }
}
