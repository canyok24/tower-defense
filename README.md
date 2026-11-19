# Emberwatch

A vertical tower defense game for Android, written in Kotlin. One watchtower stands at the bottom of a tall portrait screen and fires upward at endless waves of geometric shapes coming down from the top. When the wall falls you go back to the menu, spend what you earned, and run again.

Package: `com.toasteralley.emberwatch` · minSdk 24 · target/compile SDK 35 · Play Billing Library 8.3.0 · AdMob.

---

## Features

**Core loop**
- One tower anchored at the bottom centre, mobs spawn at the top and descend. Portrait only, tuned for tall phones (Galaxy S25 Ultra, about 19.5:9). The status bar, display cutout and gesture-nav insets are all handled.
- Waves never end. Each wave is timed: mobs spawn over 16 s, then the next wave starts once the field is clear or 10 s have passed.
- **Spiral Boss every 10 waves.** It's bigger and slower, has much more HP and a shield, and hits hard at the wall. Its coin reward is `50 × (wave ÷ 10)` before multipliers. Killing it also drops Diamonds and Research Cores.
- During a run you earn **Cash** and spend it on run-only upgrades (ATK / RATE / HP). Cash resets every run.
- Speed button: 1x / 2x / 3x / 5x. The simulation always uses fixed 1/60 s steps, and higher speeds just run more steps per frame.
- Difficulty **Tiers** 1–10. Each tier multiplies mob HP by 3x and mob damage by 2.5x per step, and coins by 2.2x. Reaching wave 50 on your highest tier unlocks the next one.

**Mobs: simple shapes, difficulty comes from behaviour**
- There are 20 shapes, each a cached unit-size `Path`: pentagon, hexagon, heptagon, octagon, nonagon, decagon, star, crescent, cross, arrowhead, chevron, parallelogram, trapezoid, ellipse, rounded rectangle, ring, heart, clover, yin-yang and spiral. Circle, square, triangle and diamond are never used, and a unit test enforces this.
- There are 5 outline styles (solid, dashed, dotted, double-line, glow) and 20 colours. Optional extras are size tier, rotation, pulsing opacity and a secondary inner shape.
- A mob's look is `shapeId + colorIndex + styleFlag (+ extras)`. That gives **2,000** base combinations, or **504,000** with extras. Each mob takes 2–5 `drawPath` calls with reused Paints.
- Behaviour is set per shape:
  - movement: straight, zigzag, sine weave or dash/pause
  - damage type: contact, ranged or piercing (piercing ignores half of your defense)
  - special traits: shields, splitting, ranged standoff shooting, regeneration
- Tier ranking:
  - common: ellipse, rounded rectangle, parallelogram
  - mid: pentagon, hexagon, trapezoid, ring, crescent
  - dangerous: heptagon, octagon, chevron, arrowhead, cross
  - boss-level: nonagon, decagon, star, clover, heart
  - endgame: yin-yang, a rare elite from wave 75
  - spiral: Spiral Boss only
- The tier mix shifts toward harder shapes as waves climb.

**Tower stats:** health, attack, defense (damage reduction %), attack speed, crit chance and crit factor, plus super crits from Diamond Perks.

**Progression menus (each is its own screen)**
1. **Workshop.** Permanent stats bought with Coins between runs. Costs grow exponentially (`base × growth^level`). Crit stats unlock at wave 5.
2. **Ultimate Weapons.** Unlocked and upgraded with Diamonds, opening at wave 20. They fire automatically on a cooldown during a run:
   - Ember Nova: area damage near the wall
   - Frost Lattice: slows every mob on the field
   - Chain Arc: chain lightning through several mobs
   - Gravity Well: drags mobs back toward the top and damages them
3. **Diamond Perks.** Paid with Diamonds, opening at wave 15: damage multiplier, health multiplier, crit-rate multiplier, critical factor, advanced crit rate, advanced crit factor, and super crits (a chance to crit again on a crit, with its own factor).
4. **Enhancement.** One global multiplier that applies on top of every other window. It opens at wave 30 and is very expensive (`25,000 × 1.09^level` coins). See the formula below.
5. **Research Bay.** Its own currency (Research Cores) plus Coins. Timers run on the real wall clock and keep going while the app is closed. Projects are gated by best wave, and 2 run at once (Bay Expansion research raises this to 4).
   - **Light Speed Shots** is the flagship project: a single level costing 3,000,000 coins + 50 cores and 20 hours, unlocked at best wave 10. Shots hit instantly as a hitscan beam, and Projectile Velocity research turns into pure fire rate (+3% per level).
   - The other projects are Starting Cash, Ember Rounds (damage), Wall Plating (health), Rapid Breech (fire rate), Projectile Velocity, Coin Ledger (coin bonus), Core Siphon (core yield) and Bay Expansion.
6. **Coin multipliers.** Owned packs, a 1.5x rewarded-ad boost, Coin Ledger research, the Enhancement multiplier and the Tier multiplier all multiply together and apply to every coin source: kills, wave bonuses and boss rewards. A rewarded ad adds 20 minutes of 1.5x, stacking up to 2 hours on the wall clock and saved across restarts. Starting Cash research raises your opening run cash.

**Store (Google Play Billing, one-time non-consumables)**

| Product id   | Suggested price | Effect |
|--------------|-----------------|--------|
| `no_ads`     | $4.99   | 1.5x coins forever + removes banner & interstitial ads |
| `pack_epic`  | $19.99  | 2x coins |
| `pack_tier3` | $39.99  | 3x coins |
| `pack_tier4` | $149.99 | 4x coins |
| `pack_tier5` | $499.99 | 10x coins |

All packs together give 1.5 × 2 × 3 × 4 × 10 = **360x**. Prices are set in Play Console, and the app shows `ProductDetails.formattedPrice`. A Restore Purchases button is included. Entitlements are stored separately from progress and are never wiped, not even by Settings → Reset progress.

### Enhancement formula
```
base(L)      = 1 + 0.1·L               for L ≤ 10   (1.1, 1.2, … 2.0 at L10)
base(L)      = 2 · (L/10)^P            for L > 10   (continuous at L10)
milestone(L) = 1.05 ^ floor(L / 50)    (+5% compounding bonus every 50 levels)
multiplier   = base · milestone
P = ln(50 / 1.05^20) / ln(100) ≈ 0.6376  → multiplier(1000) = 100 exactly
```
Sample values: L10 = 2.00, L20 = 3.11, L50 = 5.86, L100 = 9.57, L500 = 39.5, L1000 = 100. The multiplier applies to health, damage, the bonus part of the crit and super-crit factors, ultimate-weapon damage (which scales with attack) and all coin income. It does not apply to probability stats (defense, crit chance) or fire rate, which stay inside their caps.

---

## Project layout
```
app/src/main/java/com/toasteralley/emberwatch/
  core/      pure Kotlin (unit-tested): shapes, behaviours, wave rules, GameEngine,
             Workshop/Perks/Ultimates/Enhancement/Research formulas, coin multipliers,
             Products, SaveData, DebugGate + SecretGesture
  game/      GameView (SurfaceView loop + rendering + input), ShapeRenderer (Path cache)
  ui/        main menu, game screen, the progression screens, Store, Settings
  billing/   BillingManager (PBL 8.3.0), EntitlementStore
  ads/       AdManager (banner, interstitial, rewarded; AdMob test ids by default)
  data/      SaveStore (single JSON save in SharedPreferences, with a version field)
app/src/debug/   management override (DebugTools, DebugMenuActivity, debug manifest)
app/src/release/ no-op DebugTools stub
public/          PWA icons, manifest.webmanifest, site.json, og-meta.html
tools/render_icons.py   regenerates the legacy mipmap PNGs and the PWA icons from the icon paths
```

---

## Building

Requirements: JDK 17 and the Android SDK (platform 35). Android Studio Ladybug or newer opens the project directly.

```bash
# set sdk.dir in local.properties, or export ANDROID_HOME
./gradlew clean assembleDebug assembleRelease testDebugUnitTest
```
Outputs:
- `app/build/outputs/apk/debug/app-debug.apk` (installable, includes the management override)
- `app/build/outputs/apk/release/app-release-unsigned.apk` (until you configure signing)

### Signed release (AAB for Play, APK for sideloading)
1. Create an upload keystore once and keep it safe. Never commit it:
   ```bash
   keytool -genkeypair -v -keystore ~/keys/emberwatch-upload.jks -alias upload \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Create `keystore.properties` in the project root (it's git-ignored), or use `-P` flags or environment variables:
   ```properties
   RELEASE_STORE_FILE=/home/you/keys/emberwatch-upload.jks
   RELEASE_STORE_PASSWORD=...
   RELEASE_KEY_ALIAS=upload
   RELEASE_KEY_PASSWORD=...
   ```
3. Build:
   ```bash
   ./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab  (upload to Play)
   ./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
   ```
4. Before release, set your real AdMob ids (they default to Google's test ids):
   `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_INTERSTITIAL_ID`, `ADMOB_REWARDED_ID`, in the same places as the signing keys.
5. Raise `versionCode` / `versionName` in `app/build.gradle.kts` for every upload.

---

## Publishing on Google Play Console
1. **Create the app:** Play Console → *Create app*. Name it "Emberwatch", choose Game, set it to free, and accept the declarations. Use Play App Signing (the default), and the keystore above becomes your *upload* key.
2. **Upload a first build to Internal testing:** *Testing → Internal testing → Create release* → upload `app-release.aab`. In-app products can only be created after an APK/AAB that includes the Billing permission has been uploaded.
3. **Create the in-app products:** *Monetize → Products → In-app products*. Create five **one-time** products with exactly these ids: `no_ads`, `pack_epic`, `pack_tier3`, `pack_tier4`, `pack_tier5`. Set the prices ($4.99 / $19.99 / $39.99 / $149.99 / $499.99), titles and descriptions, then **Activate** each one. The app never consumes them, so they behave as permanent unlocks.
4. **Testers:** add testers to the internal track (an email list), and add the same accounts under *Settings → License testing* so they can buy with test cards without being charged. Install the app from the internal-testing opt-in link. Sideloaded builds signed with a different key can't buy.
5. **Store listing:** use the short and full descriptions (from `public/site.json`), the 512×512 icon (`public/icons/play-store-512.png`), a 1024×500 feature graphic and portrait phone screenshots.
6. **App content:**
   - *Ads:* declare **Yes, the app contains ads** (AdMob banner, interstitial and rewarded).
   - *Data safety:* AdMob collects the device/advertising ID and approximate location, plus app interactions and diagnostics, for advertising and analytics. Purchase history goes through Google Play. Data is encrypted in transit. The game itself has no accounts and sends nothing to its own servers.
   - *Content rating:* fill in the IARC questionnaire (simple shape violence, in-app purchases).
   - *Target audience:* 13+ is recommended. Choosing under-13 brings Families policy and ad restrictions.
   - *Privacy policy URL:* required because of ads and IAP.
7. **Consent (UMP):** before showing ads to users in the EEA, UK or Switzerland you must show a Google-certified CMP consent form. Add the User Messaging Platform SDK (`com.google.android.ump:user-messaging-platform`), call `ConsentInformation.requestConsentInfoUpdate` on launch, show the form when required, and only initialise and load ads once `canRequestAds()` is true. Set up the message in AdMob → *Privacy & messaging*. **This build does not include UMP yet.**
8. Promote Internal → Closed → Production once testing passes. New personal developer accounts need a closed test with at least 12 testers for 14 days before production access.

---

## Management override (testing tool)

A hidden developer/admin menu for testing progression without grinding.

**It exists only in debug builds.** The menu code (`DebugTools`, `DebugMenuActivity`) lives in `app/src/debug/`, and its activity is declared in the debug-only manifest. Release builds compile a no-op stub from `app/src/release/`, and every entry point is also gated on `BuildConfig.DEBUG` through `core.DebugGate`. Saved debug flags (god mode, bypass gates, start wave) are ignored in release builds, and debug store grants are never loaded there. This matters because the menu can grant the paid coin multipliers for free.

**How to open it (debug build):**
- **Main menu:** press and hold the gear icon (top right) for **at least 2 seconds** and release. Then, within 10 seconds, tap the **EMBERWATCH** title **5 times within 3 seconds**.
- **In a run:** hold the **II** (pause) button for 2 seconds and release. Then tap the **WAVE** label at top left 5 times within 3 seconds.

**Main-menu override:** bypass all progression gates (menus, research gates, tiers); unlock all tiers; max all research or unlock only Light Speed Shots; unlock all Ultimate Weapons; max all Diamond Perks; complete running research timers now; set Coins, Diamonds, Research Cores and best wave; start the next run at wave N; god mode; locally grant or revoke any store pack (not a real purchase).

**In-run override:** +1 wave, +10 waves, jump to wave N, force-spawn the Spiral Boss, toggle god mode, +1M cash, full heal.

---

## Roadmap: Enhancement cap releases
`Enhancement.ENHANCEMENT_CAP` in `core/Progression.kt` is the only thing that changes per release. The formula is defined for every level, so raising the cap needs no save migration.

| Release | Cap |
|---------|-----|
| 1.0 (this) | 100 |
| next | 200 |
| then | 300 → 400 → … |
| long-term | toward 100,000 via periodic updates |

Before each cap bump, re-check the cost curve (`Enhancement.cost`) against the coin income at that stage.

## Tests
`./gradlew testDebugUnitTest` covers:
- the Enhancement formula (2.0 at L10, 100 at L1000, monotonic, milestone jumps)
- boss reward scaling
- 20 shapes with no forbidden ones, combo counts (≥ 2,000 base, ≥ 5,000 with extras) and tier ranking
- cost curves
- coin-multiplier stacking (all packs = 360x, plus boost × research × enhancement) and rewarded-boost stacking up to 2 h
- research timers on a fake clock, plus gates, slots and bypass
- Light Speed conversion into fire rate and instant hits
- milestone Diamonds
- the debug gate: closed when `!DEBUG`, and the secret-gesture timing
- simulation smoke tests: a fresh run falls, upgraded runs go further, and god mode runs endless waves from wave 990

## License
MIT (see LICENSE).
