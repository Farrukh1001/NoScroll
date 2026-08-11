# Third-party notices

NoScroll is licensed under GPL-3.0-only. It bundles the following third-party components, all under
GPL-compatible licences.

## Runtime dependencies

All of the following are licensed under the **Apache License, Version 2.0**, which is compatible with
GPL-3.0.

### AndroidX (The Android Open Source Project)

| Component | Version | Used for |
|---|---|---|
| `androidx.core:core-ktx` | 1.19.0 | Platform helpers |
| `androidx.activity:activity-compose` | 1.13.0 | Compose activity host, edge-to-edge |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 | Lifecycle-aware coroutines |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | Lifecycle-aware state collection |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | View models in Compose |
| `androidx.lifecycle:lifecycle-service` | 2.11.0 | Lifecycle-aware services |
| `androidx.lifecycle:lifecycle-process` | 2.11.0 | Process lifecycle |
| `androidx.navigation:navigation-compose` | 2.9.8 | Screen navigation |
| `androidx.compose:compose-bom` | 2026.06.01 | Compose version alignment |
| `androidx.compose.ui:ui` | via BOM | UI toolkit |
| `androidx.compose.ui:ui-graphics` | via BOM | Canvas charts |
| `androidx.compose.ui:ui-tooling-preview` | via BOM | Previews |
| `androidx.compose.material3:material3` | via BOM | Material 3 components |
| `androidx.compose.material:material-icons-core` | via BOM | Icons |
| `androidx.datastore:datastore-preferences` | 1.2.1 | Transactional settings storage |
| `androidx.sqlite:sqlite-framework` | 2.7.0 | Wellbeing aggregate storage |
| `androidx.sqlite:sqlite-ktx` | 2.7.0 | SQLite helpers |
| `androidx.work:work-runtime-ktx` | 2.11.2 | Battery-friendly scheduling |

Copyright © The Android Open Source Project. Licensed under the Apache License 2.0.

### Kotlin (JetBrains)

| Component | Version | Used for |
|---|---|---|
| Kotlin standard library | 2.3.21 | Language runtime |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Concurrency |

Copyright © JetBrains s.r.o. Licensed under the Apache License 2.0.

## Test-only dependencies

Not shipped in the APK.

| Component | Version | Licence |
|---|---|---|
| `junit:junit` | 4.13.2 | Eclipse Public License 1.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.11.0 | Apache 2.0 |
| `androidx.test.ext:junit` | 1.3.0 | Apache 2.0 |
| `androidx.test.espresso:espresso-core` | 3.7.0 | Apache 2.0 |
| `androidx.compose.ui:ui-test-junit4` | via BOM | Apache 2.0 |
| `androidx.compose.ui:ui-test-manifest` | via BOM | Apache 2.0 |

## Build tooling

Not shipped in the APK.

| Component | Version | Licence |
|---|---|---|
| Android Gradle Plugin | 9.3.1 | Apache 2.0 |
| Gradle | 9.7.0 | Apache 2.0 |
| Kotlin Compose compiler plugin | 2.3.21 | Apache 2.0 |

## Bundled data

### Adult-content blocklist

`app/src/main/assets/rules/bundled-rules.txt` was compiled for this project and is distributed under
**GPL-3.0-only** with the application.

It deliberately contains adult sexual content only. It is not a general "mature content" list, and it
excludes gore, violence, horror, crime, general manga and manhwa, and medical or educational
resources — including those in the same broad category — because blocking those would be a harm
rather than a feature.

## Assets

The launcher icon, the status-bar icon and the navigation icons are original vector drawables created
for this project, licensed under GPL-3.0-only with the rest of the source.

No third-party fonts are bundled; the app uses the platform's system typefaces.

## What is deliberately absent

No advertising SDK, no analytics library, no crash reporter, no networking library, no charting
library, no dependency-injection framework, and no annotation processors.

Charts are drawn directly on a Compose Canvas, dependency injection is one hand-written container,
and the DNS filter parses packets itself. Each of those is a dependency that would otherwise need
auditing, and none of them earned their place here.

## Verifying

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```
