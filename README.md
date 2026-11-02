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

- **Grid map with a winding path** (16×9 tiles), entry/exit markers, scaled to any landscape screen. Enemies follow waypoints; lives are lost when they reach the exit.
- **Tap-to-build**: pick a tower in the scrollable side panel (drag to scroll through all 11), tap any empty grass tile. Path/occupied tiles are rejected.
- **11 tower types** (4 premium) with unique drawn sprites and colors: projectiles, splash, slow-on-hit, instant hits, a branching continuous laser beam, stun pulses and a screen-shaking nuke.
- **20 in-run upgrade levels per tower** with data-driven stat curves, milestone bonuses at levels 10 and 20, steep late-game costs, bulk buy (**x5** and **Max affordable**) and **Sell** (70% of everything invested).
- **4 enemy types** with HP bars, armor, slow and **stun** indicators, hit flash. Bosses resist stuns.
- **Endless waves**: 20 handcrafted waves, then procedurally generated waves forever (no cap). Clearing wave 20 is the **Victory milestone** (banner + Tokens bonus once per run) and the run continues. HP, count, mix and speed scale smoothly; bosses every 5 waves (more on every 10th). Between-wave countdown with a **Start wave** button (early-start gold bonus) and end-of-wave gold.
- **Designed gold economy**: income vs. a measurable "cost to kill" each wave — very generous early (5× at wave 1, >2× through wave 100), parity around wave 1000 (see *Economy*).
- **Game speed 1x–10x, 12x, 14x**: tap the speed button to cycle, or ▾ for a popup list. Fixed 1/60 s simulation steps (see *Game speed*).
- **Tokens (meta progression)**: earned at the end of every run, spent on permanent global upgrades on the **Upgrades** screen.
- **Super Tokens (prestige)**: accrue automatically from lifetime gold; at a reset, split them across three permanent upgrades (tower damage, gold per kill, gold per wave). Not a purchase.
- **Pause** menu (Resume / Restart / Main menu), auto-pause when the app is backgrounded, Back button opens pause.
- **Game over** screen (gold "Victory! Run over" if the wave-20 milestone was reached) showing Tokens earned; best wave saved (unbounded).
- **Main menu**, **Game**, **Upgrades** and **Settings** activities (sound toggle, restore purchases, reset best score).
- **Google Play Billing Library 8** (`BillingManager`): connection with auto service reconnection plus exponential back-off reconnect, product-details query, purchase flow, acknowledgement, restore on startup and on resume, revoke on refund, pending-purchase handling, entitlements persisted in SharedPreferences and exposed as `StateFlow`.
- **Store dialog** (from main menu and in-game HUD) with live localized prices, Owned state, Restore button, the Play Console note, and debug-only local grants.
- **Ads**: `AdManager` facade with a `BannerAdProvider` abstraction — AdMob banner (Google's official test IDs) or a plain placeholder view. Banner is removed and hidden when `remove_ads` is owned.
- **Sound effects** via `ToneGenerator` (no asset files), honoring the settings toggle.
- Adaptive launcher icon (vector foreground + monochrome) plus PNG fallbacks for API 24–25.
- JVM unit tests for the engine, tower mechanics, costs, speed determinism and progression.

### Towers

Level-1 stats (Laser damage is damage per second). Premium towers need the `unlock_premium_towers` purchase.

| Tower | Cost | Role | Range | Damage | Rate (/s) | Special | Premium |
|-------|-----:|------|------:|-------:|----------:|---------|:-------:|
| Arrow | 50 | Starter | 2.6 | 10 | 1.4 | fast single-target projectile | |
| Cannon | 100 | Splash | 2.3 | 26 | 0.6 | splash radius 1.1 | |
| Frost | 80 | Area slow | 2.2 | 4 | 1.0 | small splash, slows 50% for 1.6 s | |
| Slow | 90 | Slow on hit | 2.5 | 14 | 0.8 | slow-moving goo shot (2.6 tiles/s), single target, slows 35% for 2.5 s | |
| Rapid | 120 | Swarm | 2.2 | 3 | 8.0 | very high fire rate, tiny bullets | |
| Heavy | 175 | Boss killer | 2.4 | 85 | 0.35 | ×1.5 damage vs Boss and Tank | |
| Disruptor | 140 | Stun | 2.0 | 0 | 1 pulse / 4 s | stuns every enemy in range for 0.5 s (Boss ×0.35, Tank ×0.75 duration) | |
| Sniper | 150 | Long range | 5.5 | 70 | 0.4 | very long range, fast shot | ✔ |
| Railgun | 200 | Instant hit | 3.0 | 7 | 6.0 | instant-hit rail slugs (formerly "Laser") | ✔ |
| Laser | 250 | Chain beam | 2.8 | 22 DPS | continuous | beam hits the lead enemy; branches (30% damage) jump to up to 2 more nearby enemies; up to 10 targets with upgrades | ✔ |
| Annihilator | 400 | Nuke | 3.2 | 600 | 0.28 (3.6 s) | splash radius 1.6, big blast + screen shake | ✔ |

Notes:

- **Stun**: a stunned enemy stops moving (orbiting yellow sparks). After a stun ends the enemy is immune for 0.5 s so stacked Disruptors can't lock it forever. Disruptors only pulse when an enemy is in range.
- **Laser**: no projectiles; the beam is drawn every frame. The main beam (thick, bright) deals full DPS to the lead enemy; thinner, dimmer branch beams jump from it to the nearest enemies within 2.2 tiles (and within tower range). Beams ignore armor. Hard cap: 10 targets.
- **Heavy** bonus multiplies damage before armor.

### In-run upgrades (levels 1–20)

All curves live in `game/TowerStats.kt` (`TowerStats.of(type, level, modifiers)`).

| Stat | Per level | Level 10 milestone | Level 20 milestone | Clamp |
|------|-----------|--------------------|--------------------|-------|
| Damage | ×1.12 (Annihilator ×1.10) | ×1.25 | ×1.40 | — |
| Fire rate | +3% of base | ×1.10 | ×1.15 | ≤ 2.5× base, ≤ 16/s |
| Range | +2.5% of base | +8% | +10% | ≤ 1.7× base, ≤ 8 tiles |
| Splash radius | +2% of base | +10% | +15% | ≤ 1.7× base |
| Slow factor | −0.01 | −0.05 | −0.05 | ≥ 0.30 (max 70% slow) |
| Slow duration | +3% | ×1.1 | ×1.1 | ≤ 2× base |
| Stun duration | +0.03 s | +0.1 s | +0.2 s | ≤ 1.5 s |
| Stun cooldown | −0.08 s | −0.3 s | −0.4 s | ≥ 1.8 s |
| Laser targets | +1 every 3 levels | +1 | +2 | ≤ 10 |
| Laser branch damage | +0.5% | +2% | +3% | ≤ 40% |
| Heavy boss bonus | +0.025 | +0.15 | +0.25 | ≤ ×2.5 |

**Annihilator fire rate**: levels 1–13 stay slow (3.57 s → 3.03 s per shot), then ramp geometrically (~×1.29 per level) over 14–20 to **0.50 s per shot** at level 20 while damage keeps rising (600 → 2354 at L13 → 6422 at L20).

**Upgrade cost** to reach level N (`game/TowerStats.kt`, `UpgradeCosts`): level 2 costs the tower's `upgradeBase`; levels 3–12 are ×1.13 per level; then versus the previous level: L13 ×3, L14 ×3, L15 ×1.2, L16 ×1.2, L17 ×1.2, L18 ×3, L19 ×1.2, L20 ×1.2. The exact value is rounded to a nice number (<100: nearest 5, <1000: nearest 10, <10000: nearest 50, else nearest 100).

Example — Arrow (`upgradeBase` 30):

| Level | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 | 18 | 19 | 20 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Cost | 30 | 35 | 40 | 45 | 50 | 55 | 60 | 70 | 80 | 90 | 100 | 310 | 920 | 1100 | 1300 | 1600 | 4750 | 5700 | 6850 |

`upgradeBase` per tower: Arrow 30, Cannon 60, Frost 50, Slow 55, Rapid 70, Heavy 100, Disruptor 80, Sniper 90, Railgun 120, Laser 150, Annihilator 240.

The upgrade panel shows **Level X/20**, current → next stats, the next cost, **x5** (sum of the next five costs, fewer near the cap) and **Max +N** (the most levels current gold can buy in a row, with their exact summed cost). **Sell** refunds 70% of the total invested (build + all upgrades).

### Game speed

Speeds: **1x, 2x … 10x, 12x, 14x** (tap the speed button to cycle, ▾ to pick from a list). The simulation always advances in fixed **1/60 s** steps: each frame adds `realDt × speed` to an accumulator and runs that many whole steps, so N× speed = N normal steps per frame, never a bigger dt (a unit test checks N steps at 1x equal one frame at Nx bit-for-bit). At most **16 steps per frame** run; extra time is dropped, so a slow device simply runs below the selected speed instead of losing accuracy. At **≥10x** visual-only effects are reduced: at most 24 particles/explosions, no floating gold text, no hit flash, no screen flash or shake.

### Tokens (meta progression)

Earned at the end of **every run** (defeat, restart or quitting to the menu), shown on the game-over screen and persisted in SharedPreferences (`meta_progress`). Because runs are endless, every term is soft-capped (linear up to a knee, then logarithmic) so Tokens don't explode:

```
soft(x, k)  = x                      if x <= k
            = k · (1 + ln(x / k))    otherwise   (continuous, sublinear)
Tokens      = 2 · soft(wavesCleared, 20) + floor(soft(kills / 20, 50)) + 5 · soft(bosses, 8)
              + 20 if the wave-20 victory milestone was reached (once per run)
```

A normal 20-wave victory pays exactly the old amount (147 Tokens: 40 + 47 + 40 + 20). Examples: a run to wave 100 (~12k kills, 30 bosses) ≈ 390 Tokens, wave 1000 ≈ 720; even `Int.MAX_VALUE` waves/kills/bosses stays under 2,400.

Spend them on the **Upgrades** screen (main menu) — permanent, global, applied to all towers in future runs, multiplied on top of tower level stats:

| Upgrade | Levels | Per level | Max | Cost per level (Tokens) |
|---------|------:|-----------|-----|-------------------------|
| Damage | 14 | +2.5% | +35% | 20, 25, 35, 45, 55, 75, 95, 130, 160, 210, 280, 360, 470, 610 |
| Attack Speed | 10 | +2.5% | +25% | 25, 35, 45, 55, 75, 100, 130, 170, 230, 300 |
| Range | 10 | +2% | +20% | 15, 20, 25, 35, 45, 55, 70, 95, 120, 160 |
| Starting Gold | 12 | +15 gold | +180 | 10, 15, 15, 20, 25, 35, 45, 55, 70, 90, 120, 150 |
| Health (starting lives) | 10 | +1 | +10 | 15, 20, 25, 35, 45, 55, 70, 95, 120, 160 |
| Effect Power (slow/stun duration, splash radius) | 10 | +3% | +30% | 15, 20, 25, 35, 45, 55, 70, 95, 120, 160 |
| Gold per Kill (kills only; wave rewards unchanged) | 10 | +2% | +20% | 25, 35, 45, 55, 75, 100, 130, 170, 230, 300 |

Cost of level L+1 = `niceRound(baseCost × growth^L)` (growth 1.28–1.32).

### Super Tokens (prestige)

Super Tokens are **never sold** (no billing products) and have no milestone rewards — they accrue automatically from **lifetime gold earned across all runs combined** (kills, wave gold and early-start bonuses; not sell refunds). `K` was raised from 4,000 to **100,000** together with the new economy (gold income is roughly 25× higher than before), keeping a similar pace:

```
superTokensEarned    = floor( sqrt( lifetimeGold / 100000 ) )   # n-th token needs 100000·n² gold
superTokensAvailable = superTokensEarned − (spent on damage + kill gold + wave gold)
```

At a reset the player **allocates** Super Tokens across three permanent upgrades; each category's total spent `n` is tracked separately (`super_spent_damage`, `super_spent_kill_gold`, `super_spent_wave_gold`):

| Prestige upgrade | Multiplier after n Super Tokens | n = 1 | 5 | 10 | 50 | Cap |
|---|---|---|---|---|---|---|
| Tower damage (baseline) | `1 + 0.1 · n^0.8` | ×1.10 | ×1.36 | ×1.63 | ×3.29 | none |
| Gold per Kill | `min(2.5, 1 + 0.05 · n^0.7)` | ×1.05 | ×1.15 | ×1.25 | ×1.77 | ×2.5 (n ≈ 129) |
| Gold per Wave (end-of-wave gold) | `min(2.0, 1 + 0.05 · n^0.7)` | ×1.05 | ×1.15 | ×1.25 | ×1.77 | ×2.0 (n ≈ 72) |

Kill gold paid = base bounty × meta Gold per Kill × prestige Gold per Kill (multiplicative); wave gold = base wave gold × prestige Gold per Wave. Damage = level stats × meta Damage × prestige damage. Older saves with a single `super_tokens_spent` counter are migrated once into the damage category.

The Upgrades screen shows available/earned/spent Super Tokens, a progress bar to the next one, and all three multipliers (also in the in-game HUD and on the main menu). **Reset (Prestige)** opens a confirmation dialog with **− / +** steppers per category (default: all into damage; "+" pulls from another category when everything is allocated) and shows each resulting multiplier (before → after) before confirming. Unallocated Super Tokens are kept.

- **Wiped**: Tokens, every meta upgrade level, best wave and run stats.
- **Kept**: purchases/entitlements (`remove_ads`, `unlock_premium_towers`), settings, all prestige multipliers, Super Tokens spent, unspent Super Tokens.
- **Lifetime gold**: the counter is not zeroed (that would let the cheap first tokens be earned again); spent tokens are subtracted instead, so each Super Token can only be earned once and gold keeps counting toward the next one.

### Endless waves

- **Waves 1–20** keep their handcrafted composition and the `1.14^(w−1)` HP curve. Clearing **wave 20** shows a VICTORY banner, awards the +20 victory Tokens (once per run) and the run **continues**; a smaller banner appears every 50 waves. The run ends only when lives reach 0 (or you quit).
- **Wave 21+** are generated forever from the same base formulas (grunts `5+2w`, runners `2+2(w−3)`, tanks `1+⌊(w−5)/2⌋`) with a deterministic mix rotation: every 11th wave is *armored* (×2.5 tanks), every 7th a *rush* (×1.5 runners), every 3rd a *swarm* (×1.2 grunts). Bosses every 5 waves (`⌊w/20⌋+1`), more on every 10th (`⌊w/10⌋+1`).
- **HP**: `hp(w) = hp(20) · (1 + (w−20)/61.5)^2` after wave 20 (smooth, < 4% per wave).
- **Clamps (no overflow/NaN at wave 10,000+)**: at most 140 non-boss enemies and 6 bosses per wave — excess count is converted into HP so total wave HP is preserved; spawn interval floor 0.25 s; enemy speed `min(1.4, 1 + 0.08·ln(w/20))`; per-enemy HP ≤ 1e30. Enemy HP is `Double`, gold/income are `Long` (saturating at 4e15), all curve math is `Double`. Tests cover waves 10,000, 100,000, 1,000,000 and `Int.MAX_VALUE`.
- Best wave is stored as an unbounded counter.

### Economy

Gold per kill and per wave are derived from a measurable cost of killing each wave (`game/WaveDefinitions.kt`, `WaveDefinitions.plan(w)`):

```
EHP(w)          = sum of the HP of every enemy in wave w
duration(w)     = spawn time of the wave + engage time (2 · Arrow range / grunt speed = 4.16 s, ÷ speed multiplier)
refDpsPerGold   = max over L of Arrow dps(L) / (build + upgrade gold to L)   = 0.28 dps/gold (Arrow L1)
costToKill(w)   = EHP(w) / duration(w) / refDpsPerGold      # gold of reference-tower investment that delivers the wave's HP in time
targetRatio(w)  = 1 + 4 · (1 − ln w / ln 1000)               for w ≤ 1000   (5.0 at w=1, 2.33 at w=100, 1.0 at w=1000)
                = 1 / (1 + 0.1 · ln(w / 1000))               for w > 1000   (0.86 at 5000, 0.81 at 10,000, 0.69 at 100,000)
income(w)       = targetRatio(w) · costToKill(w)
kill bounty     = floor(0.75 · income · enemyHP / EHP)       # bounty proportional to HP (≥ 1)
wave gold       = income − Σ bounties                         # ≈ 25%
```

`ratio(w) = income(w) / costToKill(w)` is measured from the rounded gold actually paid; unit tests check ratio ≥ 2 for every wave 1–100, 0.85–1.15 at wave 1000, ≤ 1 afterwards, non-increasing over waves 1–3000, and no overflow at 100,000. Meta/prestige gold multipliers apply on top and are not counted in the ratio.

| Wave | Grunt HP | Enemies | Gold/kill (grunt) | Wave gold | Income | costToKill | Ratio |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 40 | 7 | 52 | 122 | 486 | 97 | 5.00 |
| 10 | 130 | 46 | 35 | 1,389 | 5,511 | 1,503 | 3.67 |
| 20 | 482 | 92 | 93 | 6,416 | 25,512 | 7,813 | 3.27 |
| 50 | 1,698 | 146 | 194 | 19,242 | 76,708 | 28,050 | 2.73 |
| 100 | 8,177 | 146 | 816 | 77,487 | 309,770 | 132,800 | 2.33 |
| 500 | 596,400 | 146 | 43,079 | 3,998,326 | 15,992,898 | 11.41M | 1.40 |
| 1000 | 4.47M | 145 | 234,581 | 21,408,981 | 85,635,641 | 85.64M | 1.00 |
| 5000 | 523M | 146 | 23,509,326 | 2.14B | 8.58B | 9.96B | 0.86 |

(Grunt HP includes count compression after the 140-enemy clamp; bosses carry a larger share of wave HP and gold, e.g. wave 10 boss 4,553 HP / 1,232 gold.) Note the practical ceiling of a run is DPS, not gold: towers cap at level 20 and the map has a fixed number of tiles, so late waves are limited by meta/prestige multipliers rather than income.

### Enemies

| Enemy  | Base HP | Speed (tiles/s) | Armor | Lives lost | Stun taken |
|--------|--------:|----------------:|------:|-----------:|-----------:|
| Grunt (normal) | 40   | 1.25 | 0 | 1  | 100% |
| Runner (fast)  | 24   | 2.5  | 0 | 1  | 100% |
| Tank           | 170  | 0.7  | 3 | 2  | 75% |
| Boss           | 1400 | 0.5  | 6 | 10 | 35% |

HP scales per wave (see *Endless waves*); kill gold is set per wave by the economy (proportional to HP). Armor is subtracted from each hit (minimum 1 damage); laser beams ignore armor.

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
│       │   │   ├── SettingsStore.kt       # sound toggle
│       │   │   ├── SoundManager.kt        # ToneGenerator SFX
│       │   │   ├── game/
│       │   │   │   ├── GameView.kt        # SurfaceView loop, rendering, input, HUD/panels
│       │   │   │   ├── GameEngine.kt      # simulation: waves, combat, gold, lives
│       │   │   │   ├── GameMap.kt         # grid + path waypoints
│       │   │   │   ├── TowerType.kt / Tower.kt
│       │   │   │   ├── TowerStats.kt      # level curves (1-20), clamps, UpgradeCosts, RunModifiers
│       │   │   │   ├── GoldFormat.kt      # compact big-number formatting
│       │   │   │   ├── EnemyType.kt / Enemy.kt
│       │   │   │   ├── Projectile.kt      # projectiles + visual effects
│       │   │   │   └── WaveDefinitions.kt # endless waves + economy (WavePlan, costToKill, ratio)
│       │   │   ├── meta/
│       │   │   │   ├── Progression.kt     # Tokens, MetaUpgrade, Super Tokens, prestige
│       │   │   │   └── KeyValueStore.kt   # SharedPreferences / in-memory store
│       │   │   ├── billing/
│       │   │   │   ├── BillingManager.kt  # Play Billing wrapper
│       │   │   │   └── EntitlementStore.kt
│       │   │   ├── ads/AdManager.kt       # banner abstraction (AdMob / placeholder)
│       │   │   └── ui/
│       │   │       ├── MainMenuActivity.kt
│       │   │       ├── GameActivity.kt
│       │   │       ├── UpgradesActivity.kt  # Tokens shop + prestige
│       │   │       ├── SettingsActivity.kt
│       │   │       ├── StoreDialog.kt
│       │   │       └── Insets.kt          # immersive mode helper
│       │   └── res/ (layouts, vector drawables, adaptive icon, themes, xml backup rules)
│       └── test/…/ (GameEngineTest, TowerMechanicsTest, UpgradeCostTest,
│                     SimulationSpeedTest, EconomyTest, meta/ProgressionTest)
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

How to play: tap a tower on the right panel (drag to scroll), then tap a grass tile. Tap a placed tower to upgrade (+1 / x5 / Max) or sell. Press **Start wave** (or wait for the countdown). **II** pauses, the speed button cycles 1x…14x (▾ opens the list), **Store** opens the shop. After a run, spend Tokens in **Upgrades** on the main menu.

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
5. Entitlements are saved to SharedPreferences (instant offline startup) and published as `StateFlow<Entitlements>`; `GameActivity` unlocks Sniper/Railgun/Laser/Annihilator and `AdManager` hides the banner reactively.
6. Purchases are re-checked in `MainMenuActivity.onResume()`.

For production, verify purchase tokens server-side with the Google Play Developer API before granting (marked `TODO(production)` in code). Test with license testers and Play's test cards, including the "slow test card" for pending purchases.

## Customizing towers and waves

- **Towers** – `game/TowerType.kt`: each enum entry has `cost`, `range` (tiles), `damage`, `fireRate` (shots/s), `projectileSpeed`, `attack` (PROJECTILE / INSTANT / BEAM / PULSE), `splashRadius`, `slowFactor`/`slowDuration`, `stunDuration`/`stunCooldown`, `beamTargets`/`beamSecondary`, `bossBonus`, `upgradeBase`, `premium`, `color`, `role`. Add an entry and it automatically appears in the build panel; add a `when` branch in `GameView.drawTowerShape` for a custom look. Level curves, milestones, clamps and max level: `game/TowerStats.kt`; upgrade cost multipliers: `UpgradeCosts`; refund rate: `Tower.SELL_REFUND`.
- **Meta / prestige** – `meta/Progression.kt`: Token award constants and soft-cap knees, `MetaUpgrade` levels/costs, `SUPER_TOKEN_GOLD_K`, `PrestigeUpgrade` formulas and caps.
- **Enemies** – `game/EnemyType.kt`: `baseHp`, `speed`, `damageToBase`, `radius`, `color`, `armor`, stun resistance. Render shape in `GameView.drawEnemies`.
- **Waves / economy** – `game/WaveDefinitions.kt`: `MILESTONE_WAVE`, countdowns, starting gold/lives, `hpMultiplier`, `speedMultiplier`, `spawnInterval`, clamps (`MAX_NON_BOSS`, `MAX_BOSSES`, `SPAWN_INTERVAL_FLOOR`, `SPEED_CAP`, `MAX_GOLD`), `targetRatio` and `KILL_SHARE`. `plan(wave)` returns a `WavePlan` (spawns with HP/bounty/speed, wave gold, EHP, duration, costToKill, ratio).
- **Map** – `game/GameMap.kt`: `GameMap.default()` lists path waypoints in grid coordinates (segments must be horizontal or vertical; first/last waypoints may sit just outside the grid). `COLS`/`ROWS` set the grid size.
- **Products** – IDs are constants in `BillingManager`; store copy is in `ui/StoreDialog.kt`.

## License

MIT — see [LICENSE](LICENSE).
