plugins {
  id("com.android.library")
}

android {
  namespace = "echo.music.usbaudio"
  compileSdk = 36
  ndkVersion = "27.1.12297006"

  defaultConfig {
    minSdk = 26

    externalNativeBuild {
      cmake {
        cppFlags("-std=c++20", "-O2", "-fvisibility=hidden")
        abiFilters("arm64-v8a", "x86_64")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

kotlin { jvmToolchain(21) }

dependencies {
  compileOnly(libs.media3)
  testImplementation(libs.media3)
  testImplementation(libs.junit)
}
