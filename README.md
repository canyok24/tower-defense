# Tower Defense (Android)

A complete, self-contained tower defense game for Android written in Kotlin.
Rendering uses a custom `SurfaceView` game loop and plain `Canvas` drawing — no
third-party game engine. Includes Google Play Billing (non-consumable in-app
products) and a banner-ad abstraction with optional AdMob (test IDs).

> **Real purchases require the app to be uploaded to Google Play Console** (any
> testing track) with in-app products whose IDs exactly match `remove_ads` and
> `unlock_premium_towers`, and the device signed in with a license-tester or
> real account. Until then the Store shows the products as *Unavailable*.
> Debug builds include local "grant" toggles in the Store so you can test the
> premium towers and ad removal without Play.

> **Billing Library 8 required:** the project uses Play Billing Library
> **8.3.0** (`billing-ktx`, latest stable 8.x). Google Play no longer accepts new
> apps or updates built with PBL 7 or earlier (deadline Aug 31, 2026; extension
> deadline Nov 1, 2026), and PBL 8 is supported until Aug 31, 2027 — plan to move
> to PBL 9 before then. See
> <https://developer.android.com/google/play/billing/deprecation-faq> and the
> [PBL 8 migration guide](https://developer.android.com/google/play/billing/migrate-gpblv8).

---

## Features

- **Grid map with a winding path** (16×9 tiles), entry/exit markers, scaled to any landscape screen.
- **Tap-to-build**: pick a tower in the side panel, tap any empty grass tile. Path/occupied tiles are rejected.
- **5 tower types** (2 premium) with cost, range, damage, fire rate, projectiles, splash and slow effects.
- **Upgrades**: tap a placed tower → panel shows level, current → next stats, *Upgrade* (levels 1–4) and *Sell* (70% refund of everything invested). Range circle shown for the selected tower.
- **4 enemy types** following waypoints, with HP bars, armor, slow indicator and hit flash.
- **Wave system**: 20 waves with increasing count, HP scaling (+14%/wave), new enemy types unlocking, boss every 5th wave. Between-wave countdown with a **Start wave** button (early-start gold bonus) and a clear bonus.
- **Gold & lives**, floating "+gold" text, explosion rings, laser beams.
- **Pause** menu (Resume / Restart / Main menu), **1x/2x speed**, auto-pause when the app is backgrounded, Back button opens pause.
- **Game over** and **Victory** screens; best wave saved.
- **Main menu**, **Game** and **Settings** activities (sound toggle, restore purchases, reset best score).
- **Google Play Billing Library 8** (`BillingManager`): connection with auto service reconnection plus exponential back-off reconnect, product-details query, purchase flow, acknowledgement, restore on startup and on resume, revoke on refund, pending-purchase handling, entitlements persisted in SharedPreferences and exposed as `StateFlow`.
- **Store dialog** (from main menu and in-game HUD) with live localized prices, Owned state, Restore button, the Play Console note, and debug-only local grants.
- **Ads**: `AdManager` facade with a `BannerAdProvider` abstraction — AdMob banner (Google's official test IDs) or a plain placeholder view. Banner is removed and hidden when `remove_ads` is owned.
- **Sound effects** via `ToneGenerator` (no asset files), honoring the settings toggle.
- Adaptive launcher icon (vector foreground + monochrome) plus PNG fallbacks for API 24–25.
- JVM unit tests for the game engine.

### Towers

| Tower  | Cost | Range | Damage | Rate (/s) | Special | Premium |
|--------|-----:|------:|-------:|----------:|---------|:-------:|
| Arrow  | 50   | 2.6   | 10     | 1.4       | fast single-target projectile | |
| Cannon | 100  | 2.3   | 26     | 0.6       | splash radius 1.1 | |
| Frost  | 80   | 2.2   | 4      | 1.0       | small splash, slows 50% for 1.6 s | |
| Sniper | 150  | 5.5   | 70     | 0.4       | very long range, fast shot | ✔ |
| Laser  | 200  | 3.0   | 7      | 6.0       | instant-hit beam | ✔ |

Each upgrade level: damage ×1.45, range +12% of base, fire rate ×1.15. Upgrade cost = `cost × 0.75 × currentLevel`. Max level 4.

### Enemies

| Enemy  | Base HP | Speed (tiles/s) | Armor | Gold | Lives lost |
|--------|--------:|----------------:|------:|-----:|-----------:|
| Grunt (normal) | 40   | 1.25 | 0 | 5   | 1  |
| Runner (fast)  | 24   | 2.5  | 0 | 6   | 1  |
| Tank           | 170  | 0.7  | 3 | 14  | 2  |
| Boss           | 1400 | 0.5  | 6 | 120 | 10 |

Armor is subtracted from each hit (minimum 1 damage).

---

## Project structure

```
tower-defense/
├── build.gradle.kts              # root: plugin declarations
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml        # version catalog (AGP, Kotlin, Billing, Ads…)
│   └── wrapper/                  # Gradle wrapper (jar + properties)
├── gradlew / gradlew.bat
├── app/
│   ├── build.gradle.kts          # android config, signing, AdMob ids, deps
│   ├── proguard-rules.pro        # keep rules for Billing / Ads
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/toasteralley/towerdefense/
│       │   │   ├── TowerDefenseApp.kt     # Application: billing + ads init
│       │   │   ├── SettingsStore.kt       # sound toggle, best wave
│       │   │   ├── SoundManager.kt        # ToneGenerator SFX
│       │   │   ├── game/
│       │   │   │   ├── GameView.kt        # SurfaceView loop, rendering, input, HUD/panels
│       │   │   │   ├── GameEngine.kt      # simulation: waves, combat, gold, lives
│       │   │   │   ├── GameMap.kt         # grid + path waypoints
│       │   │   │   ├── TowerType.kt / Tower.kt
│       │   │   │   ├── EnemyType.kt / Enemy.kt
│       │   │   │   ├── Projectile.kt      # projectiles + visual effects
│       │   │   │   └── WaveDefinitions.kt # wave composition & difficulty curve
│       │   │   ├── billing/
│       │   │   │   ├── BillingManager.kt  # Play Billing wrapper
│       │   │   │   └── EntitlementStore.kt
│       │   │   ├── ads/AdManager.kt       # banner abstraction (AdMob / placeholder)
│       │   │   └── ui/
│       │   │       ├── MainMenuActivity.kt
│       │   │       ├── GameActivity.kt
│       │   │       ├── SettingsActivity.kt
│       │   │       ├── StoreDialog.kt
│       │   │       └── Insets.kt          # immersive mode helper
│       │   └── res/ (layouts, vector drawables, adaptive icon, themes, xml backup rules)
│       └── test/…/GameEngineTest.kt
├── README.md
└── LICENSE
```

---

## Prerequisites

- **Android Studio** (latest stable; Ladybug or newer).
- **JDK 17** (Android Studio's bundled JBR 17+ works; for CLI builds set `JAVA_HOME` to a JDK 17).
- Android SDK Platform **35** and Build-Tools 35 (Android Studio installs them on first sync).
- Toolchain used: AGP 8.13.2, Gradle 8.14.6 (wrapper), Kotlin 2.3.21, Play Billing 8.3.0, compileSdk/targetSdk 35, minSdk 24.

## Open and run

1. Android Studio → **File › Open** → select the `tower-defense` folder. Let Gradle sync.
2. Create/start an emulator (API 24+, a "Google Play" system image if you want Billing to connect) or plug in a device with USB debugging.
3. Press **Run ▶** (configuration `app`). The game runs in landscape.

How to play: tap a tower on the right panel, then tap a grass tile. Tap a placed tower to upgrade/sell. Press **Start wave** (or wait for the countdown). **II** pauses, **1x/2x** toggles speed, **Store** opens the shop.

## Build from the command line

```bash
# Debug APK
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug          # install on a connected device

# Unit tests and lint
./gradlew testDebugUnitTest lintDebug
```

(Use `gradlew.bat` on Windows.) If building outside Android Studio, create `local.properties` with `sdk.dir=/path/to/Android/sdk` or set `ANDROID_HOME`.

## Signed release APK / AAB

### 1. Create an upload keystore (once)

```bash
keytool -genkeypair -v \
  -keystore ~/keys/towerdefense-upload.jks \
  -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Store it **outside the repo** and back it up. **Never commit a keystore or its passwords** — `*.jks`, `*.keystore` and `keystore.properties` are in `.gitignore`. (With Play App Signing, Google holds the app signing key; this is your *upload* key and can be reset via Play support if lost.)

### 2. Provide the signing values

`app/build.gradle.kts` reads four values, in order of precedence, from a git-ignored `keystore.properties` at the project root, Gradle properties (`-P…` or `~/.gradle/gradle.properties`), or environment variables:

| Name | Meaning |
|------|---------|
| `RELEASE_STORE_FILE` | absolute path to the `.jks` |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias (e.g. `upload`) |
| `RELEASE_KEY_PASSWORD` | key password |

Example `keystore.properties` (do not commit):

```properties
RELEASE_STORE_FILE=/home/me/keys/towerdefense-upload.jks
RELEASE_STORE_PASSWORD=********
RELEASE_KEY_ALIAS=upload
RELEASE_KEY_PASSWORD=********
```

Or in CI:

```bash
export RELEASE_STORE_FILE=$PWD/upload.jks   # decoded from a CI secret
export RELEASE_STORE_PASSWORD=... RELEASE_KEY_ALIAS=upload RELEASE_KEY_PASSWORD=...
```

### 3. Build

```bash
./gradlew bundleRelease     # AAB for Play: app/build/outputs/bundle/release/app-release.aab
./gradlew assembleRelease   # APK: app/build/outputs/apk/release/app-release.apk
```

Release builds are minified with R8 (`proguard-rules.pro` keeps Billing classes). If no signing values are found, Gradle still builds an *unsigned* artifact (`app-release-unsigned.apk`) that can't be installed or uploaded. Bump `versionCode` (and `versionName`) in `app/build.gradle.kts` for every upload.

Alternatively in Android Studio: **Build › Generate Signed App Bundle / APK…**.

## Publishing on Google Play Console

1. **Developer account** – register at <https://play.google.com/console> (one-time fee) and complete identity verification. New personal accounts must run a closed test (currently ≥12 testers for 14 days) before production access.
2. **Create app** – *All apps › Create app*: name, default language, App/Game = **Game**, Free, accept declarations.
3. **Use a unique applicationId** – change `applicationId`/`namespace` from `com.toasteralley.towerdefense` if needed; it can never change after the first upload.
4. **Billing Library ≥ 8** – already satisfied (PBL 8.3.0); Play rejects uploads using PBL 7 or older.
5. **Replace AdMob test IDs** (see *Ads* below) or set `USE_ADMOB` to `false`.
6. **Build the signed AAB** – `./gradlew bundleRelease`.
7. **Internal testing track** – *Testing › Internal testing › Create new release*. Opt in to **Play App Signing**, upload `app-release.aab`, add release notes, *Save › Review › Start rollout*. Add testers (email list) and share the opt-in link. *The app must be uploaded before in-app products can be created.*
8. **Create the in-app products** – *Monetize › Products › In-app products › Create product* (requires a payments profile):
   - Product ID **`remove_ads`** – e.g. "Remove Ads", set a price, **Activate**.
   - Product ID **`unlock_premium_towers`** – e.g. "Premium Towers", set a price, **Activate**.
   IDs must match exactly (they are constants in `BillingManager`). Both are non-consumable: the app acknowledges and never consumes them.
9. **License testers** – *Settings (All apps level) › License testing*: add the Google accounts used on your test devices; they can buy with test cards and are never charged. Install the app **from the Play opt-in link** (or a build with the same applicationId + versionCode signed by the same key) and the Store will show real prices.
10. **Store listing** – *Grow › Store presence › Main store listing*: short & full description, app icon 512×512, feature graphic 1024×500, at least 2 phone screenshots (landscape is fine), category *Strategy*, contact email.
11. **App content** – complete every section under *Policy › App content*:
    - **Privacy policy URL** (required — AdMob and Billing process data; host a page describing ads/advertising ID and purchases).
    - **Ads**: declare "contains ads".
    - **Content rating**: fill the IARC questionnaire (this game typically rates Everyone/PEGI 3–7 for mild cartoon violence).
    - **Target audience** (choose 13+ to avoid Families policy requirements unless you comply with them), **Data safety** form (advertising ID, purchase history, diagnostics from the SDKs), Government apps / Financial features / Health = No, Advertising ID declaration.
12. **Countries & pricing** – select countries; set prices for the in-app products per country (auto-converted).
13. **Closed/Open testing → Production** – promote the tested release, *Production › Create new release*, use a **staged rollout** (e.g. 10% → 50% → 100%) and monitor Android vitals and crashes. Submit for review (can take hours to several days).

## Ads

`ads/AdManager.kt` picks a provider at runtime:

- `BuildConfig.USE_ADMOB = true` (default) → `AdMobBannerProvider` with Google's **official test IDs**:
  - App ID `ca-app-pub-3940256099942544~3347511713` (manifest placeholder `admobAppId`)
  - Banner unit `ca-app-pub-3940256099942544/6300978111`
- `USE_ADMOB = false` → a grey placeholder bar (no SDK traffic).

**Before release**, create an app + banner unit in AdMob and supply your IDs via `keystore.properties`, `~/.gradle/gradle.properties` or env vars:

```properties
ADMOB_APP_ID=ca-app-pub-XXXXXXXXXXXXXXXX~YYYYYYYYYY
ADMOB_BANNER_ID=ca-app-pub-XXXXXXXXXXXXXXXX/ZZZZZZZZZZ
```

Never click your own live ads; register test devices in AdMob. For EEA/UK users you must also show a consent form (Google UMP SDK) before requesting ads — not included here. The banner is hidden whenever `remove_ads` is owned.

## How the Billing integration works

`BillingManager` (one instance in `TowerDefenseApp`):

1. Built with PBL 8 options: `enablePendingPurchases(PendingPurchasesParams…enableOneTimeProducts())` and `enableAutoServiceReconnection()`. `startConnection()` runs on app start; an extra back-off retry (1 s → 60 s) refreshes data after disconnects.
2. On connect: `queryProductDetailsAsync()` for both INAPP products — PBL 8 returns a `QueryProductDetailsResult` whose `unfetchedProductList` is logged (usually means the product isn't created/active in Play Console) — then `restorePurchases()` via `queryPurchasesAsync` (PBL 8 removed `queryPurchaseHistory`; it's not used).
3. `launchPurchase(activity, productId)` opens the Play purchase sheet.
4. `onPurchasesUpdated` / restore → `processPurchases`: grants entitlement for `PURCHASED`, **acknowledges** unacknowledged purchases (Play auto-refunds unacknowledged purchases after 3 days), reports `PENDING`. A full restore also revokes refunded items.
5. Entitlements are saved to SharedPreferences (instant offline startup) and published as `StateFlow<Entitlements>`; `GameActivity` unlocks Sniper/Laser and `AdManager` hides the banner reactively.
6. Purchases are re-checked in `MainMenuActivity.onResume()`.

For production, verify purchase tokens server-side with the Google Play Developer API before granting (marked `TODO(production)` in code). Test with license testers and Play's test cards, including the "slow test card" for pending purchases.

## Customizing towers and waves

- **Towers** – `game/TowerType.kt`: each enum entry has `cost`, `range` (tiles), `damage`, `fireRate` (shots/s), `projectileSpeed` (0 = instant beam), `splashRadius`, `slowFactor`/`slowDuration`, `premium`, `color`. Add an entry and it automatically appears in the build panel; add a `when` branch in `GameView.drawTowerShape` for a custom look. Upgrade curve/max level/refund rate: constants in `Tower.kt`'s companion object.
- **Enemies** – `game/EnemyType.kt`: `baseHp`, `speed`, `reward`, `damageToBase`, `radius`, `color`, `armor`. Render shape in `GameView.drawEnemies`.
- **Waves** – `game/WaveDefinitions.kt`: `TOTAL_WAVES`, countdowns, starting gold/lives, `hpMultiplier(wave)`, `clearBonus(wave)` and `build(wave)` which returns the ordered spawn list (`SpawnEntry(type, delayBeforeSpawn)`). Replace `build` with a hand-authored table for fully scripted waves.
- **Map** – `game/GameMap.kt`: `GameMap.default()` lists path waypoints in grid coordinates (segments must be horizontal or vertical; first/last waypoints may sit just outside the grid). `COLS`/`ROWS` set the grid size.
- **Products** – IDs are constants in `BillingManager`; store copy is in `ui/StoreDialog.kt`.

## License

MIT — see [LICENSE](LICENSE).
