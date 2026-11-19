# Emberwatch

A vertical tower defense game for Android, written in Kotlin. One watchtower stands at the bottom of a tall portrait screen and fires upward at endless waves of geometric shapes coming down from the top. When the wall falls you go back to the menu, spend what you earned, and run again.

Package: `com.toasteralley.emberwatch` · minSdk 24 · target/compile SDK 35 · Play Billing Library 8.3.0 · AdMob.

---

## Features

**Core loop**
- One tower anchored at the bottom centre, mobs spawn at the top and descend. Portrait only, tuned for tall phones (Galaxy S25 Ultra, about 19.5:9). The status bar, display cutout and gesture-nav insets are all handled.
- Waves never end. Each wave is timed: mobs spawn over 16 s, then the next wave starts once the field is clear or 10 s have passed.
- **Spiral Boss every 10 waves.** It's the biggest mob in the game: radius 0.11 of the screen width, against 0.0625 for the largest normal mob (a LARGE yin-yang). It's also the slowest, has 60x base HP (growing a further +1% per wave) and a shield, and hits hard at the wall. Its Bounty reward is `50 × (wave ÷ 10)`, times the full Bounty multiplier. That Bounty is banked into the same balance that pays for Workshop upgrades and Research Bay projects. Killing it also drops Diamonds and Research Cores. The Spiral is never part of the normal-mob pool.
- During a run you earn **Cash** and spend it on run-only upgrades (ATK / RATE / HP). Cash resets every run.
- Speed button: 1x / 2x / 3x / 5x. The simulation always uses fixed 1/60 s steps, and higher speeds just run more steps per frame.
- **5 Stages** (24 planned for a later update), chosen on the main menu. Each stage raises mob HP, damage and speed, pays a bigger Bounty multiplier, keeps its own best wave, and has its own milestone reward table. See [Stages](#stages).

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
- **Which tiers can spawn depends on your Enhancement level** (`core/MobBehavior.kt → EnemyPool`). The thresholds are constants, so they keep working when the cap rises:

  | Enhancement level | Normal-mob pool |
  |---|---|
  | 0 – 1,000 | common + mid |
  | 1,001 – 3,000 and 3,001 – 5,000 | + dangerous |
  | 5,001+ | everything, including boss-level and yin-yang |

  The Spiral is always the 10-wave boss and never a normal mob. With today's cap of 1,000, dangerous and higher shapes only show up after a future cap release, or when the debug override sets Enhancement above the cap.
- Within the allowed pool, the wave still shifts the mix toward harder tiers (mid from wave 3, dangerous from 12, boss-level from 30, endgame from 75).
- **Veteran traits keep late waves hard without the locked shapes.** Common and mid-tier mobs pick up behaviours as waves climb:
  - a shield from wave 40, growing to +100% HP by wave 440
  - zigzag or sine movement from wave 120
  - regeneration from wave 200
  - piercing contact damage from wave 300
  - splitting on death from wave 500
  - up to +80% speed

  On top of that, HP and damage keep scaling: exponential up to wave 1,000, then polynomial (HP ×(w/1000)^4, damage ×(w/1000)^3), so even stage 24's wave 67,500 stays a finite number.

**Tower stats:** health, attack, defense (damage reduction %), attack speed, crit chance and crit factor, plus super crits from Diamond Perks.

**Progression menus (each is its own screen)**
1. **Workshop.** Three tabs, ASSAULT (10 upgrades), SHIELD (13) and LEDGER (9). Bounty buys permanent levels between runs; Cash buys temporary levels of the same upgrades during a run. See [Workshop](#workshop-assault--shield--ledger).
2. **Ultimates.** The fourth Workshop tab (next to ASSAULT / SHIELD / LEDGER, also on the main menu), opening at wave 20. Nine Ultimate Weapons unlocked and upgraded with **Stones**; they fire automatically on their cooldown during a run. See [Ultimates and Stones](#ultimates-and-stones). (The old Diamond UWs were removed in 1.3.0 and their Diamonds refunded.)
3. **Diamond Perks.** Paid with Diamonds, opening at wave 15: damage multiplier, health multiplier, crit-rate multiplier, critical factor, advanced crit rate, advanced crit factor, and super crits (a chance to crit again on a crit, with its own factor).
4. **Enhancement.** One global multiplier that applies on top of every other window. It opens at wave 30, is capped at **level 1,000**, and is very expensive. See the formula and cost curve below.
5. **Research Bay.** (Separate from the endgame [Research tab](#research-tab-mastery).) Its own currency (Research Cores) plus Bounty. Timers run on the real wall clock and keep going while the app is closed. Projects are gated by best wave, and 2 run at once (Bay Expansion research raises this to 4).
   - **Light Speed Shots** is the flagship project: a single level costing 3,000,000 Bounty + 50 cores and 20 hours, unlocked at best wave 10. Shots hit instantly as a hitscan beam, and Projectile Velocity research turns into pure fire rate (+3% per level).
   - The other projects are Starting Cash, Ember Rounds (damage), Hull Integrity Research (health), Rapid Breech (fire rate), Projectile Velocity, Bounty Ledger (Bounty bonus), Core Siphon (core yield) and Bay Expansion.
6. **Bounty multipliers.** Owned packs, a 1.5x rewarded-ad boost, Bounty Ledger research, the Enhancement multiplier, the selected Stage's Bounty multiplier and your permanent Stage milestone Bounty bonuses all multiply together and apply to every Bounty source: kills, wave bonuses and boss rewards. A rewarded ad adds 20 minutes of 1.5x, stacking up to 2 hours on the wall clock and saved across restarts. Starting Cash research raises your opening run cash.

**Store (Google Play Billing: five one-time non-consumables + three consumable Stone packs)**

| Product id   | Suggested price | Effect |
|--------------|-----------------|--------|
| `no_ads`     | $4.99   | 1.5x Bounty forever + removes banner & interstitial ads |
| `pack_epic`  | $19.99  | 2x Bounty |
| `pack_tier3` | $39.99  | 3x Bounty |
| `pack_tier4` | $149.99 | 4x Bounty |
| `pack_tier5` | $499.99 | 10x Bounty |

The top pack is shown as $499.99 here and as the fallback label in the app. If you'd rather charge a flat $500, set it to **$500.00** in Play Console. The app always shows Play's localized price, so no code change is needed.

Consumable Stone packs (see [Stones](#stones)):

| Product id | Suggested price | Effect |
|---|---|---|
| `stones_small`  | $0.99  | +50 Stones |
| `stones_medium` | $4.99  | +300 Stones |
| `stones_large`  | $19.99 | +1500 Stones |

All packs together give 1.5 × 2 × 3 × 4 × 10 = **360x**. Prices are set in Play Console, and the app shows `ProductDetails.formattedPrice`. A Restore Purchases button is included. Entitlements are stored separately from progress and are never wiped, not even by Settings → Reset progress.

### Enhancement formula
```
base(L)      = 1 + 0.1·L               for L ≤ 10   (1.1, 1.2, … 2.0 at L10)
base(L)      = 2 · (L/10)^P            for L > 10   (continuous at L10)
milestone(L) = 1.05 ^ floor(L / 50)    (+5% compounding bonus every 50 levels)
multiplier   = base · milestone
P = ln(50 / 1.05^20) / ln(100) ≈ 0.6376  → multiplier(1000) = 100 exactly
```
Sample values: L10 = 2.00, L20 = 3.11, L50 = 5.86, L100 = 9.57, L500 = 39.5, L1000 = 100. The formula is defined past the cap (L6000 ≈ 41,000x) for future releases.

**Cost curve** (Bounty to buy the next level, `Enhancement.cost`). Steep but finite:
```
L < 100 : 25,000 × 1.09^L
L ≥ 100 : cost(100) × 1.035^(L − 100)     (≈ 31x more per 100 levels)
```
| Level | Cost of next level |
|---|---|
| 0 | 25.0K |
| 99 | 127M |
| 100 | 138M |
| 500 | 131T |
| 999 | 3.73ac (3.7e21) |

The total cost from 0 to 1,000 is about 1.1e23. Even L5000 (about 4e81) is still a finite Double.

**Number formatting** (`NumberFormat.compact`): K, M, B, T, then two-letter suffixes aa (1e15), ab (1e18), ac (1e21) … az, ba … zz (up to 1e2043). Every finite Double formats, for example "3.73ac". The multiplier applies to health, damage, the bonus part of the crit and super-crit factors, ultimate-weapon damage (which scales with attack) and all Bounty income. It does not apply to probability stats (defense, crit chance) or fire rate, which stay inside their caps.

---

## Project layout
```
app/src/main/java/com/toasteralley/emberwatch/
  core/      pure Kotlin (unit-tested): shapes, behaviours, wave rules, GameEngine,
             WorkshopDefinitions (all 3 tabs), StageDefinitions, Perks/Ultimates/
             Enhancement/Research formulas, Bounty multipliers, save migrations,
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
3. **Create the in-app products:** *Monetize → Products → In-app products*. Create five **one-time** products with exactly these ids: `no_ads`, `pack_epic`, `pack_tier3`, `pack_tier4`, `pack_tier5`. Set the prices ($4.99 / $19.99 / $39.99 / $149.99 / $499.99), titles and descriptions, then **Activate** each one. The app never consumes them, so they behave as permanent unlocks. Also create the three Stone packs `stones_small`, `stones_medium`, `stones_large` ($0.99 / $4.99 / $19.99) as one-time products; the app **consumes** these after granting, so they can be bought again.
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

**It exists only in debug builds.** The menu code (`DebugTools`, `DebugMenuActivity`) lives in `app/src/debug/`, and its activity is declared in the debug-only manifest. Release builds compile a no-op stub from `app/src/release/`, and every entry point is also gated on `BuildConfig.DEBUG` through `core.DebugGate`. Saved debug flags (god mode, bypass gates, start wave) are ignored in release builds, and debug store grants are never loaded there. This matters because the menu can grant the paid Bounty multipliers for free.

**How to open it (debug build):**
- **Main menu:** press and hold the gear icon (top right) for **at least 2 seconds** and release. Then, within 10 seconds, tap the **EMBERWATCH** title **5 times within 3 seconds**.
- **In a run:** hold the **II** (pause) button for 2 seconds and release. Then tap the **WAVE** label at top left 5 times within 3 seconds.

**Main-menu override:** bypass all progression gates (menus, research gates, stages); unlock all 5 stages (access only, no rewards); **set the Enhancement level to anything from 0 to 100,000, even above the cap**, to test the enemy pools (for example 1500 adds dangerous shapes, 6000 adds everything); set the selected stage's best cleared wave (this pays its milestones); max all research or unlock only Light Speed Shots; max all Diamond Perks; complete running research timers now; set Bounty, Diamonds, Research Cores and best wave; start the next run at wave N; god mode; locally grant or revoke any store pack (not a real purchase).

**Ultimates override (main menu):** set Stones; unlock / lock each of the nine UWs (free, no Stones spent); unlock all nine; set any UW stat level; max all UW stats; unlock all nine Plus abilities; reset Ultimates.

**Workshop override (main menu):** set any upgrade in any tab to any level (0..max), max one tab, max all three tabs, or reset the workshop.

**In-run override:** +1 wave, +10 waves, jump to wave N, force-spawn the Spiral Boss, toggle god mode, +1M cash, full heal, **trigger any Ultimate now** (fires it immediately and restarts its cooldown; a locked UW is enabled for that run only).

---

## Workshop: ASSAULT / SHIELD / LEDGER

The Workshop is split into three tabs, the same way in the main menu and in the run.

- **Between runs, Bounty buyss permanent levels.**
- **During a run, Cash buys temporary in-run levels of the same upgrades.** The in-run panel (bottom of the screen) has the same three tabs, pages through each tab 6 at a time, and shows `Lv permanent+run`, the current effect and the Cash price. In-run levels reset when the run ends and stack on top of permanent levels; the effect is evaluated at the **total** level, and permanent + in-run can never exceed the upgrade's max level.
- Every upgrade's Bounty cost curve, cash cost curve, max level, gate and effect formula live in one data file: `core/WorkshopDefinitions.kt`. Costs are `floor(base × growth^level)` — the Bounty curve uses the permanent level, the cash curve uses the in-run level. All curves are monotonic and finite up to max level. Where `growth^L` would overflow a double before max level, the curve keeps the original exponential exactly up to a threshold and continues with a softer per-level growth (see the cost columns): Damage Bounty exact to L5000 (~1e286, ~1e303 at L10,000), Damage cash exact to L2500; Hull Integrity Bounty exact to L5000, cash to L2500; Core Repair Bounty exact to L4500, cash to L2500; Plating Bounty exact to L4500, cash to L2500; Attack Speed softens after L300.
- Gates use your best wave reached (bypassed by the debug override).
- Enhancement still multiplies damage, health and Bounty income on top. **Bounty Gain** is a separate multiplicative factor in the Bounty pipeline (`bounty = base × packs × boost × research × enhancement × stage × Bounty Gain`), **Tribute** a separate factor in the cash pipeline (`cash = base × Tribute`). Menus show the Bounty multiplier including your permanent Bounty Gain.
- **Super Crit** combines multiplicatively with the Diamond Perk super crit: chance `1 − (1 − perk)(1 − workshop)`, factor `perkFactor × workshopFactor`.
- **Enemy Degrade Chance** works like the classic "skip a level" upgrade in reverse: each spawning enemy has the shown chance to be built with the stats (HP, damage, speed, veteran traits) of a wave **1..N waves lower**, N = `1 + l/10` (6 at max). Bounty and cash rewards still use the current wave. Bosses are never degraded.
- **Damage order on the tower:** Barrier → Plating (at least 10% of the hit always gets through) → Counterfire reflect (% of the raw hit) → Fortification absorbs → Last Stand (lethal hit: survive at 1% HP, "LAST STAND" popup) → Hull Integrity.
- **Fortification:** with Fortification > 0, a steel bar sits just above the tower. Contact enemies stop at it and it absorbs all damage (contact and ranged) until it falls; it regenerates slowly and rebuilds after Reconstruction seconds.
- **Recovery:** after each wave the tower heals `min(Recovery Amount, Max Recovery) × max HP`.
- **Interest:** after each wave you get `min(cash × Interest, 40 × that wave's base cash)`.
- Rendering stays cheap: Satellites are small circles, Charges are small crosses, the Fortification is a bar and the Pulse is an expanding ring (only tower-side effects are round; no mob is a circle).

- **Attack Speed** is `1 + 0.039 × l`, reaching **40 shots/s at L1000** (the old 12/s cap is gone). Research can push the tower to at most 60/s. The fixed 1/60 s simulation fires several shots per step when needed (up to 4 per step = 240/s with Rapid Fire's ×3 burst), so Light Speed, Rapid Fire and Multishot keep working at 40/s+.
- **Hull Integrity** (max health) is shaped like The Tower's Health curve: `12 × (1 + l/10)^3 × exp(1.13 × max(0, (l − 7000)/1000)^2)`. Relative per-level gains shrink slowly through the middle and steepen again past L7000. Values: L0 12 · L1000 ~1.24e7 · L3000 ~3.27e8 · L5000 ~1.51e9 · L7000 ~4.13e9 · L8000 ~1.91e10 · L10000 ~3.1e14. Hull Integrity Research, the Diamond Perk and Enhancement still multiply it.
- **Core Repair** is a flat HP/s: `0.01 × HullIntegrity(l)` (0 at L0), i.e. 1% of the Hull Integrity value at the same level, multiplied by the same research / perk / Enhancement multipliers as max health, with a **ceiling of 2.8% of current max health per second** (effective regen = `min(flat, 0.028 × maxHP)`; the UI shows `X/s (max 2.8%/s)`). Max level 10,000; old Health Regen levels (≤ 50) carry over 1:1.
- **Plating** (flat block) is shaped like The Tower's Defense Absolute: `0.6 × l × (1 + l/40)^1.5 × exp(K × max(0, (l − 5000)/1000)^2)`, K ≈ 1.169. Values: L1 0.62 · L1000 ~7.9e4 · L3000 ~1.2e6 · L5000 ~4.2e6 · **L6000 ~2.15e7** · L8000 ~5.1e11 · L10000 ~1.2e20. At least 10% of every hit still gets through.
- **Early balance:** Hull Integrity starts at 12 instead of 100, so all enemy damage is scaled by 0.12 (`GameEngine.ENEMY_DAMAGE_SCALE`). Counterfire reflects in the old units and Drain heals in the new ones, so both keep their old strength. A fresh tower still reaches wave 11 in the simulation test.

### ASSAULT (10)

| Upgrade | Effect | Formula at total level `l` | Max | Bounty cost (perm L) | Cash cost (run L) | Gate |
|---|---|---|---|---|---|---|
| **Damage** | Damage per shot | `10.0 * (1 + 0.25 * l) * 1.012.pow(l)` | 10,000 | 25 × 1.14^L, ×1.008/level after L5000 | 15 × 1.16^L, ×1.03/level after L2500 | — |
| **Attack Speed** | Shots per second (cap 40/s at L1000) | `(1.0 + 0.039 * l).coerceAtMost(MAX_WORKSHOP_FIRE_RATE)` | 1000 | 30 × 1.15^L, ×1.05/level after L300 | 20 × 1.18^L, ×1.06/level after L300 | — |
| **Crit Chance** | Chance for a critical hit (cap 90%) | `(0.01 * l).coerceAtMost(0.90)` | 90 | 60 × 1.18^L | 40 × 1.20^L | wave 5 |
| **Crit Factor** | Critical damage multiplier | `1.5 + 0.08 * l` | 175 | 50 × 1.16^L | 30 × 1.18^L | wave 5 |
| **Range** | Targeting distance from the tower (cap 2.4 at L90) | `(1.2 + 1.2 * l / 90.0).coerceAtMost(2.4)` | 90 | 40 × 1.17^L | 25 × 1.20^L | — |
| **Multishot** | Chance to fire at extra targets (cap 50%, up to +3 targets) | `(0.5 * l / 110.0).coerceAtMost(0.5)` · secondary `(1 + 2 * l / 110).coerceAtMost(3).toDouble()` | 110 | 120 × 1.20^L | 60 × 1.22^L | wave 8 |
| **Rapid Fire** | Chance per shot to start a 2 s burst of boosted fire rate (cap 25%, x3) | `(0.25 * l / 60.0).coerceAtMost(0.25)` · secondary `(2.0 + l / 60.0).coerceAtMost(3.0)` | 60 | 150 × 1.20^L | 70 × 1.22^L | wave 10 |
| **Bounce Shot** | Chance a hit bounces to nearby enemies (cap 50%, up to 3 bounces) | `(0.5 * l / 60.0).coerceAtMost(0.5)` · secondary `(1 + 2 * l / 60).coerceAtMost(3).toDouble()` | 60 | 180 × 1.20^L | 80 × 1.22^L | wave 12 |
| **Super Crit** | Chance on a crit to multiply again (cap 40%); stacks with the Diamond Perk | `(0.4 * l / 95.0).coerceAtMost(0.4)` · secondary `1.5 + 0.05 * l` | 95 | 400 × 1.22^L | 150 × 1.24^L | wave 25 |
| **Rend Armor** | Each hit shreds armor: that enemy takes more damage (cap +20%/hit, +200% total) | `(0.2 * l / 60.0).coerceAtMost(0.2)` · secondary `(2.0 * l / 60.0).coerceAtMost(2.0)` | 60 | 250 × 1.21^L | 100 × 1.23^L | wave 20 |

### SHIELD (13)

| Upgrade | Effect | Formula at total level `l` | Max | Bounty cost (perm L) | Cash cost (run L) | Gate |
|---|---|---|---|---|---|---|
| **Hull Integrity** | Tower max health | `hullIntegrity(l)` | 10,000 | 20 × 1.13^L, ×1.008/level after L5000 | 15 × 1.15^L, ×1.03/level after L2500 | — |
| **Core Repair** | Restores hull per second: 1% of the Hull Integrity value at the same level (ceiling 2.8% of max health/s) | `0.01 × hullIntegrity(l)` HP/s (0 at L0), effective `min(that, 0.028 × maxHP)` | 10,000 | 30 × 1.15^L, ×1.008/level after L4500 | 20 × 1.18^L, ×1.03/level after L2500 | — |
| **Barrier** | Damage reduction (cap 75%) | `0.75 * (1 - 0.985.pow(l))` | 300 | 40 × 1.16^L | 30 × 1.19^L | — |
| **Plating** | Flat damage removed from every hit (after Barrier, min 10% of the hit gets through) | `plating(l)` | 10,000 | 35 × 1.15^L, ×1.008/level after L4500 | 25 × 1.18^L, ×1.03/level after L2500 | wave 3 |
| **Counterfire** | Reflects % of damage taken back at the attacker (cap 100%) | `(0.02 * l).coerceAtMost(1.0)` | 50 | 80 × 1.18^L | 40 × 1.20^L | wave 6 |
| **Drain** | % of damage dealt heals the tower (cap 5%) | `(0.001 * l).coerceAtMost(0.05)` | 50 | 150 × 1.20^L | 70 × 1.22^L | wave 10 |
| **Repulse** | Chance a hit pushes the enemy back up (cap 50%; bosses resist 80%) | `(0.01 * l).coerceAtMost(0.5)` · secondary `(0.02 + 0.0012 * l).coerceAtMost(0.08)` | 50 | 60 × 1.18^L | 35 × 1.20^L | wave 4 |
| **Satellites** | Orbiting projectiles that damage enemies they touch (up to 4) | `orbCount(l).toDouble()` · secondary `0.5 + 0.05 * l` | 60 | 300 × 1.22^L | 120 × 1.24^L | wave 15 |
| **Pulse** | Periodic pulse that damages and pushes back nearby enemies | `if (l == 0) 0.0 else (12.0 - 0.1 * l).coerceAtLeast(4.0)` · secondary `1.0 + 0.2 * l` | 80 | 300 × 1.21^L | 120 × 1.23^L | wave 18 |
| **Last Stand** | Chance to survive a lethal hit at 1% health (cap 30%) | `(0.005 * l).coerceAtMost(0.3)` | 60 | 500 × 1.24^L | 200 × 1.26^L | wave 25 |
| **Fortification** | A fortification above the tower that blocks contact and absorbs damage first (up to 200% of max health) | `(0.05 * l).coerceAtMost(2.0)` | 40 | 150 × 1.19^L | 60 × 1.21^L | wave 12 |
| **Reconstruction** | Fortification regenerates, and rebuilds faster after it falls (min 8 s) | `(30.0 - 0.4 * l).coerceAtLeast(8.0)` · secondary `(0.001 * l).coerceAtMost(0.05)` | 55 | 150 × 1.19^L | 60 × 1.21^L | wave 12 |
| **Charges** | Periodically lays charges in the lane that explode on contact | `if (l == 0) 0.0 else (10.0 - 0.1 * l).coerceAtLeast(2.0)` · secondary `2.0 + 0.3 * l` | 80 | 250 × 1.21^L | 100 × 1.23^L | wave 20 |

### LEDGER (8)

| Upgrade | Effect | Formula at total level `l` | Max | Bounty cost (perm L) | Cash cost (run L) | Gate |
|---|---|---|---|---|---|---|
| **Bounty Gain** | Multiplies ALL Bounty income: kills, wave rewards, bosses | `1.0 + 0.03 * l` | 100 | 100 × 1.16^L | 50 × 1.20^L | — |
| **Tribute** | Multiplies ALL cash income: kills and wave rewards | `1.0 + 0.03 * l` | 100 | 60 × 1.15^L | 30 × 1.18^L | — |
| **Interest Per Wave** | Cash interest on held cash after each wave (cap 10%, max 40x wave cash) | `(0.002 * l).coerceAtMost(0.10)` | 50 | 200 × 1.20^L | 80 × 1.22^L | wave 5 |
| **Free Assault** | Chance an in-run ASSAULT purchase is free (cap 25%) | `(0.005 * l).coerceAtMost(0.25)` | 50 | 250 × 1.21^L | 100 × 1.23^L | wave 8 |
| **Free Shield** | Chance an in-run SHIELD purchase is free (cap 25%) | `(0.005 * l).coerceAtMost(0.25)` | 50 | 250 × 1.21^L | 100 × 1.23^L | wave 8 |
| **Free Ledger** | Chance an in-run LEDGER purchase is free (cap 25%) | `(0.005 * l).coerceAtMost(0.25)` | 50 | 250 × 1.21^L | 100 × 1.23^L | wave 8 |
| **Enemy Degrade Chance** | Chance a spawning enemy uses the stats of an earlier wave (cap 50%, 1..6 waves lower) | `(0.01 * l).coerceAtMost(0.5)` · secondary `(1 + l / 10).toDouble()` | 50 | 1000 × 1.30^L | 400 × 1.32^L | wave 15 |
| **Recovery Amount** | Heals % of max health at the end of each wave (cap 50%) | `(0.01 * l).coerceAtMost(0.5)` | 50 | 80 × 1.17^L | 40 × 1.20^L | wave 3 |
| **Max Recovery** | Cap on the per-wave heal, % of max health (10% -> 100%) | `(0.10 + 0.01 * l).coerceAtMost(1.0)` | 90 | 80 × 1.17^L | 40 × 1.20^L | wave 3 |

Saves from 1.1.0 (save v2) are migrated to save v3: Attack → Damage, Attack Speed, Crit Chance, Crit Factor keep their names, Health → Hull Integrity, Defense → Barrier.
Save v4 renames the Shield keys (HEALTH → HULL_INTEGRITY, HEALTH_REGEN → CORE_REPAIR, DEFENSE_PCT → BARRIER, DEFENSE_ABS → PLATING, THORNS → COUNTERFIRE, LIFESTEAL → DRAIN, KNOCKBACK → REPULSE, ORBS → SATELLITES, SHOCKWAVE → PULSE, DEATH_DEFY → LAST_STAND, WALL_HEALTH → FORTIFICATION, WALL_REBUILD → RECONSTRUCTION, LAND_MINES → CHARGES). On every load, workshop levels are clamped to the current max levels (e.g. Crit Factor 500 → 175) and unknown keys are dropped.

## Research tab (Mastery)

A separate **Research** screen on the main menu, independent of the **Research Bay** (Research Bay, including Light Speed Shots, is unchanged). Data: `core/ResearchTabDefinitions.kt`.

- **Unlock:** only when **every** Assault, Shield and Ledger workshop upgrade is at its max permanent level. Until then the screen and menu button show progress, e.g. `Max all Workshop upgrades: 27/32`.
- **Cost:** Bounty, paid up front when a level starts (no cores). Costs escalate per level and stay finite at every level.
- **Time:** real wall-clock durations, from hours up to days (capped at 14 days per level), persisted in the save, and they keep running while the app is closed. The Research tab has its **own 2 slots**.
- **Naming:** every item is a "Mastery" so it can't be confused with Research Bay's "Hull Integrity Research".
- **Pipelines:** Damage / Hull Integrity Mastery raise how much each level adds: `value(0) + (value(L) − value(0)) × (1 + mastery)`. Bounty Gain Mastery multiplies Bounty Gain (so all Bounty income), Tribute Mastery multiplies Tribute (all cash income). Ceilings still hold: fire rate 60/s, Core Repair 2.8% of max health/s, Barrier 90%.
- **Debug override:** unlock the Research tab, complete running Mastery research, set any Mastery level, max / reset all.

| Category | Item | Effect | Max | Bounty cost (level L) | Duration (level L) |
|---|---|---|---|---|---|
| Assault | **Damage Mastery** | +2% to how much each Damage level adds (per mastery level) | 50 | 1e+15 × 1.45^L | 4 h × 1.10^L (max 14 days) |
| Assault | **Attack Speed Mastery** | +1% fire rate per level (tower ceiling 60/s still applies) | 30 | 8e+14 × 1.45^L | 4 h × 1.12^L (max 14 days) |
| Assault | **Crit Chance Mastery** | +0.5% crit chance per level | 20 | 8e+14 × 1.50^L | 6 h × 1.12^L (max 14 days) |
| Assault | **Crit Factor Mastery** | +2% critical bonus per level | 50 | 8e+14 × 1.45^L | 4 h × 1.10^L (max 14 days) |
| Assault | **Super Crit Mastery** | +2% super crit bonus per level (deeper super crits) | 50 | 2e+15 × 1.45^L | 6 h × 1.10^L (max 14 days) |
| Shield | **Hull Integrity Mastery** | +2% to how much each Hull Integrity level adds (per mastery level) | 50 | 1e+15 × 1.45^L | 4 h × 1.10^L (max 14 days) |
| Shield | **Core Repair Mastery** | +2% Core Repair per level (2.8% max health/s ceiling still applies) | 50 | 8e+14 × 1.45^L | 4 h × 1.10^L (max 14 days) |
| Shield | **Barrier Mastery** | +0.2% Barrier per level, past the 75% workshop cap (hard cap 90%) | 25 | 1e+15 × 1.50^L | 6 h × 1.12^L (max 14 days) |
| Shield | **Plating Mastery** | +2% Plating per level | 50 | 8e+14 × 1.45^L | 4 h × 1.10^L (max 14 days) |
| Ledger | **Bounty Gain Mastery** | +3% ALL Bounty income per level (multiplies Bounty Gain) | 50 | 1.5e+15 × 1.45^L | 6 h × 1.10^L (max 14 days) |
| Ledger | **Tribute Mastery** | +3% ALL cash income per level (multiplies Tribute) | 50 | 1e+15 × 1.45^L | 4 h × 1.10^L (max 14 days) |
| Ledger | **Interest Per Wave Mastery** | +0.1% interest per level past the 10% cap, and +5% interest ceiling per level | 20 | 1e+15 × 1.50^L | 6 h × 1.12^L (max 14 days) |
| Ledger | **Enemy Degrade Mastery** | Degraded enemies can drop +1 more wave per level | 10 | 3e+15 × 1.70^L | 12 h × 1.15^L (max 14 days) |

## Ultimates and Stones

### Stones
A premium currency used only for Ultimate Weapons (shown in every screen header). Sources:
- **In-app purchase** (consumable): `stones_small` 50, `stones_medium` 300, `stones_large` 1500. The Store shows Play's formatted price (`ProductDetails.oneTimePurchaseOfferDetails.formattedPrice`, fallback to the suggested price). On a PURCHASED update the Stones are granted **first** (idempotent by purchase token, `StonePacks.grant`, the last 500 tokens are kept in the save), the save is persisted, then the purchase is consumed with `consumePurchase` (consumeAsync). Unconsumed packs found by `queryPurchasesAsync` on start/restore are finished the same way and can never grant twice. The five multiplier packs stay non-consumable and are only acknowledged.
- **Game events** (`core/GameEvents.kt`, data-driven). Once-only awards are keyed and persisted in `SaveData.awardedEvents`; a toast "Stones +N" shows when Stones are earned.

| Event | Stones | Once? |
|---|---|---|
| Best wave crosses each multiple of 100 (100, 200, …) | 10 each | once per multiple (`wave:N`) |
| Spiral Boss kill | 1 × stage | every kill (repeatable) |
| Stage milestone reached | 5 × stage | once per milestone (`stage:S:mI`) |
| Stage cleared (all 11 milestones) | 100 × stage | once per stage (`stageclear:S`) |
| Achievement: First run | 5 | once |
| Achievement: First Spiral Boss | 10 | once |
| Achievement: Reach wave 50 | 15 | once |
| Achievement: Unlock Stage 2 | 25 | once |
| Achievement: Unlock an Ultimate Weapon | 10 | once |
| Achievement: Max every Workshop upgrade | 250 | once |

Stones, unlocked UWs, UW levels and awarded events survive Settings → Reset progress (Stones can be bought with real money).

### Ultimate Weapons
All tuning lives in **`core/Ultimates.kt`**. The Ultimates tab opens at best wave 20.

**Unlock cost by slot order** (whichever UW you unlock first costs 5, the second 50, …): 5, 50, 150, 300, 600, 1,000, 1,700, 2,900, 3,000 = **9,705 Stones** for all nine. A UW must be unlocked before any of its stats can be upgraded. Each stat costs `round(base × growth^level)` Stones per level and stops at its cap. Damage values are multiples of the tower's current damage, and the Enhancement multiplier applies to UW damage because it scales tower damage.

| Ultimate | What it does | Stat 1 (L0 → cap; per-level cost; total) | Stat 2 | Stat 3 | Plus |
|---|---|---|---|---|---|
| **Golden Tower** | Temporary Bounty and cash multiplier | Multiplier: x2 → x15 (30 lv; 10 → 740 Stones/lv, 5304 total) | Duration: 10.0s → 45.0s (35 lv; 8 → 1244 Stones/lv, 8964 total) | Cooldown: 300.0s → 100.0s (20 lv; 12 → 383 Stones/lv, 2239 total) | Golden Tower+: Every kill during the effect also pays +100% of its base Bounty |
| **Black Hole** | Pulls enemies into a point and crushes them (x5 tower damage per second) | Size: 0.12 → 0.35 (23 lv; 10 → 262 Stones/lv, 1837 total) | Duration: 6.0s → 20.0s (14 lv; 10 → 107 Stones/lv, 592 total) | Cooldown: 200.0s → 50.0s (30 lv; 12 → 888 Stones/lv, 6365 total) | Black Hole+: Crushing damage x5 and bosses are pulled in too |
| **Death Wave** | A wave sweeps up the lane; each kill stores 25% of the victim's max HP into the wave | Damage: x3 → x12.0K (40 lv; 10 → 1657 Stones/lv, 13423 total) | Quantity: 1 → 5 (4 lv; 60 → 480 Stones/lv, 900 total) | Cooldown: 300.0s → 120.0s (18 lv; 12 → 353 Stones/lv, 1902 total) | Death Wave+: Stored damage per kill x3 |
| **Lighthouse** | Rotating beams of light: tower hits on lit enemies are multiplied (continuous, per hit) | Damage: x1.5 → x100 (30 lv; 10 → 949 Stones/lv, 6473 total) | Angle: 30° → 360° (33 lv; 8 → 924 Stones/lv, 6648 total) | Quantity: 1 → 4 (3 lv; 100 → 625 Stones/lv, 975 total) | Lighthouse+: Beams rotate twice as fast and each beam's multiplier is +25% |
| **Chrono Field** | Slow field around the tower (radius 0.6) | Duration: 5.0s → 20.0s (15 lv; 8 → 103 Stones/lv, 578 total) | Slow: 20% → 70% (25 lv; 10 → 352 Stones/lv, 2493 total) | Cooldown: 180.0s → 60.0s (24 lv; 10 → 304 Stones/lv, 2141 total) | Chrono Field+: Enemies inside the field take +50% damage |
| **Chain Lightning** | Tower hits have a chance to arc lightning through nearby enemies | Damage: x4 → x6.00K (40 lv; 10 → 1657 Stones/lv, 13423 total) | Quantity: 2 → 8 (6 lv; 40 → 419 Stones/lv, 1051 total) | Chance: 5% → 30% (25 lv; 12 → 423 Stones/lv, 2992 total) | Chain Lightning+: +10% chance and chain damage x2 |
| **Smart Missiles** | Volley of homing missiles | Damage: x8 → x8.00K (40 lv; 10 → 1657 Stones/lv, 13423 total) | Quantity: 2 → 12 (10 lv; 20 → 298 Stones/lv, 1091 total) | Cooldown: 30.0s → 8.0s (22 lv; 10 → 226 Stones/lv, 1575 total) | Smart Missiles+: Missiles explode, splashing full damage in a small radius |
| **Inner Land Mines** | Ring of AoE mines close around the tower | Damage: x8 → x8.00K (40 lv; 10 → 1657 Stones/lv, 13423 total) | Quantity: 3 → 9 (6 lv; 30 → 228 Stones/lv, 624 total) | Cooldown: 40.0s → 10.0s (30 lv; 10 → 740 Stones/lv, 5304 total) | Inner Land Mines+: Each mine re-arms once after exploding |
| **Poison Swamp** | Damage-over-time zone on the front line (ticks every 0.5 s, radius 0.18) | Damage: x8 → x8.00K (40 lv; 10 → 1657 Stones/lv, 13423 total) | Duration: 5.0s → 20.0s (15 lv; 10 → 128 Stones/lv, 720 total) | Cooldown: 120.0s → 40.0s (20 lv; 10 → 319 Stones/lv, 1866 total) | Poison Swamp+: Enemies in the swamp are also slowed by 30% |

Damage stats of Death Wave, Chain Lightning, Smart Missiles, Inner Land Mines and Poison Swamp grow geometrically (each level ×constant), so they span 3x → 12,000x, 4x → 6,000x and 8x → 8,000x (Poison Swamp per 0.5 s tick). Seven UWs fire on their Cooldown stat (shown as pips above the in-run bar); Chain Lightning has no cooldown and triggers on tower hits (Chance stat), and Lighthouse is always on.

**Lighthouse** (named in one constant, `UltimateNames.LIGHTHOUSE`). 1 → 4 beams rotate around the tower; each covers an Angle of 30° → 360° (360° = the whole map). Every 0.5 s each lit enemy takes one hit of tower damage × the beam multiplier (Damage 1.5x → ~100x per beam).
- **Stacking rule:** normally beams are independent and, where they overlap, their multipliers **add** (two 100x beams = 200x, four = 400x).
- When **all four beams exist AND the angle is maxed at 360°**, every beam covers the whole map and the four multipliers stack **multiplicatively**: 100^4 = **1e8** (1.56e8 with Lighthouse+). The result is clamped to a finite range (never NaN or infinite).
- **Lighthouse's ~100x is a per-hit, continuous beam multiplier.** It is not comparable to Death Wave's 12,000x, which is a one-off damage pool per wave (plus the HP it stores from kills).

**Ultimate Weapon Plus.** After **all nine** UWs are unlocked, each one gets a Plus ability (listed in the Plus column above), purchasable in **any order**. The price depends only on how many Plus abilities you already own:

| Plus # (any UW) | Stones |
|---|---|
| 1 | 500 |
| 2 | 750 |
| 3 | 1,000 |
| 4 | 1,500 |
| 5 | 2,000 |
| 6 | 2,500 |
| 7 | 3,000 |
| 8 | 4,000 |
| 9 | 5,000 |

All nine cost 22,250 Stones.

**Save migration (v5 → v6).** The old Diamond UWs (Ember Nova, Frost Lattice, Chain Arc, Gravity Well) are removed. Every Diamond spent on them is refunded: unlock 20, then 8 + 6k for each level k → k+1 (e.g. level 3 = 20 + 14 + 20 = 54 Diamonds).

## Stages

All stage tuning lives in **`app/src/main/java/com/toasteralley/emberwatch/core/StageDefinitions.kt`**. These are the formulas behind the table:

The initial build ships **5 stages** (`StageDefinitions.STAGE_COUNT = 5`). **24 stages are planned for a later update**; the formulas below already cover them, so the update only raises the constant.

| | Formula | Stage 1 | Stage 2 | Stage 3 | Stage 4 | Stage 5 |
|---|---|---|---|---|---|---|
| Unlock (waves cleared in previous stage) | 500 × 1.15^(n−2), rounded to 50 | — | 500 | 600 | 650 | 750 |
| Full rewards at wave | 10,000 + 2,500 × (n−1) | 10,000 | 12,500 | 15,000 | 17,500 | 20,000 |
| Mob HP | 4^(n−1) | ×1 | ×4 | ×16 | ×64 | ×256 |
| Mob damage | 3^(n−1) | ×1 | ×3 | ×9 | ×27 | ×81 |
| Mob speed | 1 + 0.03 × (n−1) | ×1.00 | ×1.03 | ×1.06 | ×1.09 | ×1.12 |
| Bounty multiplier | 2.2^(n−1) | ×1 | ×2.2 | ×4.84 | ×10.6 | ×23.4 |

Saves that selected or unlocked a stage above 5 (from the 24-stage 1.2.0 build) are clamped to Stage 5 on load; per-stage progress above 5 is kept for the later update.

**Milestone rewards (per stage).** There are 11 milestones per stage, at these fractions of the stage's full-reward wave: 0.1%, 0.25%, 0.5%, 1%, 2.5%, 5%, 10%, 25%, 50%, 75% and 100%. For Stage 1 that's waves 10, 25, 50, 100, 250, 500, 1,000, 2,500, 5,000, 7,500 and **10,000**. The 10,000 milestone completes every Stage 1 reward.

| Milestone # | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 (full) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Diamonds (Stage 1) | 10 | 15 | 25 | 40 | 60 | 100 | 150 | 250 | 400 | 500 | 1000 |
| Research Cores (Stage 1) | 2 | 4 | 6 | 10 | 20 | 35 | 50 | 80 | 120 | 160 | 300 |
| Permanent Bounty bonus | – | – | – | +1% | +1% | +1% | +2% | +2% | +2% | +2% | +3% |

- Diamond and core rewards scale by ×(1 + 0.5 × (n−1)) per stage, so Stage 5 pays 3x.
- Bounty bonuses are permanent, multiply together and apply on every stage. That's ×1.144 per completed stage and about ×1.96 if you complete all 5.
- Rewards are paid in order, once each, when a run ends: the stage records waves *cleared* (the wave you were on when the wall fell doesn't count).
- The menu gates (Workshop crit at wave 5, Perks 15, UW 20, Enhancement 30, research gates) still use your best wave reached on any stage.

**Save migration (v1 → v2).**
- Old difficulty tier *N* becomes Stage *N*, and every tier you had unlocked stays unlocked as a stage.
- Your old best wave becomes your Stage 1 progress, and any Stage 1 milestones you've already passed are paid out on migration.
- The selected tier carries over as the selected stage.
- Diamonds already earned are kept.

## Roadmap: Enhancement cap releases
`Enhancement.ENHANCEMENT_CAP` in `core/Progression.kt` is the only thing that changes per release. The formula and the `EnemyPool` thresholds are defined for every level, so raising the cap needs no save migration.

| Release | Cap | Effect |
|---------|-----|--------|
| 1.1 (this) | 1,000 | common + mid mobs only |
| next | 2,000 | dangerous shapes start appearing at L1,001 |
| then | 3,000 → 4,000 → 5,000 | |
| after that | 6,000+ | boss-level and yin-yang join at L5,001 |
| long-term | toward 100,000 via periodic updates | |

Before each cap bump, re-check the cost curve (`Enhancement.cost`) against the Bounty income at that stage.

## Tests
`./gradlew testDebugUnitTest` covers:
- the Enhancement formula (2.0 at L10, 100 at L1000, monotonic, milestone jumps), the cap of 1,000, and a cost curve that stays finite and increasing through L6000
- the enemy pool per Enhancement level at the boundaries 0/1/1000/1001/3000/3001/5000/5001, Spiral never in the pool, the engine honouring the pool, veteran traits, and finite HP at stage 24's last wave
- the Spiral Boss being bigger than the largest normal mob (any size tier), slower and tankier, and its reward going into the banked Bounty
- Research tab (Mastery): locked until all 32 workshop upgrades are maxed (with progress text), fake-clock timers and its own 2 slots, Mastery effects applied in the damage / health / crit / Barrier / Plating / Bounty / cash / interest / degrade pipelines with ceilings kept, costs monotonic and finite
- stages: exactly 5 stages with the formula values, saves above Stage 5 clamping to 5, Stage 2 unlocking after clearing 500, Stage 1 completing at 10,000, milestones paid once along the way, stage multipliers
- save migration v1 → v2, v2 → v3 (old six workshop stats into the new tabs), v3 → v4 (Shield renames, clamping to new max levels), clamp-on-load and v1 → current
- Workshop tabs: exactly the listed upgrades per tab (LEDGER exactly 9), no retired labels, cost curves monotonic and finite to max level, caps
- permanent + in-run levels stacking, gates and max level in-run, permanent Bounty purchases
- Free Assault/Shield/Ledger chance applied (and only to its own tab)
- Enemy Degrade lowering the stat wave (and HP) while rewards stay at the current wave
- Bounty Gain in the Bounty pipeline and Tribute in the cash pipeline
- Last Stand (0 at L0, ~30% at max, leaves 1% HP), the Fortification absorbing damage before the tower, Plating floor and Counterfire, Max Recovery capping Recovery
- Assault max levels and caps (Damage 10,000, Attack Speed 1000 = 40/s, Crit Chance 90 = 90%, Crit Factor 175, Range 90 = 2.4, Multishot 110, Rapid Fire / Bounce / Rend 60, Super Crit 95), exact cost curves below the softening thresholds, several shots per step at 40/s
- Hull Integrity (12 at L0, slow middle, steeper past L7000), Core Repair (= 1% of Hull Integrity at the same level, scaled by research, capped at 2.8% of max HP/s in stats and in the engine) and Plating (~2.15e7 at L6000) curves: monotonic, finite to L10,000, finite costs
- no retired Shield label in any upgrade label, description, value text or research name
- number formatting (K/M/B/T/aa…/zz, rounding edges, ∞)
- boss reward scaling
- 20 shapes with no forbidden ones, combo counts (≥ 2,000 base, ≥ 5,000 with extras) and tier ranking
- cost curves
- Bounty-multiplier stacking (all packs = 360x, plus boost × research × enhancement) and rewarded-boost stacking up to 2 h
- research timers on a fake clock, plus gates, slots and bypass
- Light Speed conversion into fire rate and instant hits
- the debug gate: closed when `!DEBUG`, and the secret-gesture timing
- Ultimates: nine UWs with three stats, unlock costs 5 … 3000 summing to 9,705 and priced by slot order, no upgrade before unlock, stat costs escalating and capped, damage ranges (Death Wave 3 → 12,000x, Chain Lightning 4 → 6,000x, Missiles / Mines / Swamp 8 → 8,000x, Lighthouse 1.5 → 100x, angle 30 → 360°, 1 → 4 beams), Plus locked until all nine then any order with escalating prices
- Lighthouse stacking: additive when not maxed (4 × 100 = 400), multiplicative with four beams at 360° (1e8), in the pure function and in the engine, with no overflow / NaN
- every UW firing at cap in the engine without NaN, and the debug trigger
- Stones: IAP ids, consumable grant once per purchase token, event awards once (best-wave milestones, stage milestones, stage clear, achievements) and boss kills repeatable
- save migration v5 → v6 refunding the Diamonds spent on the removed UWs (and not refunding twice)
- simulation smoke tests: a fresh run falls, upgraded runs go further, and god mode runs endless waves from wave 990

## License
MIT (see LICENSE).
