# DuckFlix Lite - Android TV App

Native Android TV application built with Kotlin and Jetpack Compose.

## Project Structure

```
app/src/main/java/com/duckflix/lite/
├── DuckFlixApplication.kt    # Hilt application
├── MainActivity.kt            # Main activity
├── DuckFlixApp.kt            # Navigation setup
├── AppViewModel.kt           # App-level ViewModel
├── data/
│   ├── local/                # Room database
│   │   ├── DuckFlixDatabase.kt
│   │   ├── dao/              # Data Access Objects
│   │   └── entity/           # Room entities
│   ├── remote/               # Retrofit API
│   │   ├── DuckFlixApi.kt
│   │   └── dto/              # Data Transfer Objects
│   └── repository/           # Repository pattern
│       └── AuthRepository.kt
├── di/                       # Hilt modules
│   ├── NetworkModule.kt
│   └── DatabaseModule.kt
├── ui/
│   ├── theme/                # Material3 theme
│   ├── screens/              # Compose screens
│   │   ├── login/
│   │   └── home/
│   └── components/           # Reusable components (TODO)
└── service/                  # Android services (TODO)
```

## Tech Stack

- **Language**: Kotlin
- **UI**: Jetpack Compose + Compose for TV
- **Architecture**: MVVM + Repository pattern
- **DI**: Hilt
- **Database**: Room
- **Networking**: Retrofit + OkHttp + Moshi
- **Video**: ExoPlayer (Media3)
- **Images**: Coil
- **Async**: Kotlin Coroutines + Flow

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Android TV emulator or physical device

### Build Steps

1. Open project in Android Studio
2. Sync Gradle
3. Run on Android TV emulator or device

```bash
./gradlew assembleDebug
```

### Release Build

```bash
./gradlew assembleRelease
```

## Running

- **Emulator**: Android Studio > Device Manager > Create Android TV device
- **Physical Device**: Enable Developer Options > ADB Debugging

## Configuration

Update server URL in `NetworkModule.kt`:
```kotlin
.baseUrl("https://duckflix.tv/lite_service/api/")
```

## Testing

```bash
./gradlew test           # Unit tests
./gradlew connectedTest  # Instrumented tests
```

## Current Status

**Phase 2 - Foundation Complete**:
- ✅ Project structure
- ✅ Gradle configuration
- ✅ Hilt dependency injection
- ✅ Room database
- ✅ Retrofit API client
- ✅ Authentication repository
- ✅ Login screen (basic)
- ✅ Home screen (placeholder)

**Next Phase (Phase 3 - VOD)**:
- Search functionality
- Content detail screens
- ExoPlayer integration
- Direct RD streaming
- Playback progress tracking

## Architecture

### Data Flow

```
UI Layer (Compose)
    ↓
ViewModel (StateFlow)
    ↓
Repository
    ↓
Data Sources (Room + Retrofit)
```

### Authentication

1. User enters credentials
2. LoginViewModel calls AuthRepository
3. Repository calls API via Retrofit
4. Token stored in EncryptedSharedPreferences
5. User data cached in Room
6. App navigates to Home

## Dependencies

See `app/build.gradle.kts` for full list.

**Key Libraries**:
- Jetpack Compose: 2024.01.00
- Compose for TV: 1.0.0-alpha10
- Hilt: 2.50
- Room: 2.6.1
- Retrofit: 2.9.0
- Media3 (ExoPlayer): 1.2.1
- Coil: 2.5.0

## License

Private - Friends & Family Use Only
