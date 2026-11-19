package com.toasteralley.emberwatch.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import com.toasteralley.emberwatch.core.FxKind
import com.toasteralley.emberwatch.core.GameEngine
import com.toasteralley.emberwatch.core.MobShape
import com.toasteralley.emberwatch.core.NumberFormat
import com.toasteralley.emberwatch.core.Palette
import com.toasteralley.emberwatch.core.RunUpgrade
import com.toasteralley.emberwatch.core.SecretGesture
import com.toasteralley.emberwatch.core.UltimateWeapon
import kotlin.math.max
import kotlin.math.sin

/**
 * Portrait SurfaceView: HUD on top (below the status bar / cutout), playfield,
 * tower at the bottom centre, run-upgrade bar above the navigation inset.
 * Rendering runs on its own thread; the engine advances in fixed 1/60 s steps.
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context, val engine: GameEngine) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    interface Listener {
        /** The tower fell (called once, automatically). Bank rewards here. */
        fun onRunEnded()
        /** Player tapped "Return to menu" on the game-over overlay. */
        fun onReturnToMenu()
        fun onQuit()
        /** Called when the hidden management-override gesture completes in game. */
        fun onDebugGesture()
        fun debugAvailable(): Boolean
    }

    var listener: Listener? = null
    private val gesture = SecretGesture()

    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    private var thread: Thread? = null
    private var gameOverReported = false

    // insets (px)
    private var insetTop = 0; private var insetBottom = 0

    // layout
    private var w = 1f; private var h = 1f
    private var fieldTop = 0f; private var fieldBottom = 0f; private var scale = 1f
    private val hud = RectF(); private val waveLabel = RectF(); private val gearBtn = RectF(); private val speedBtn = RectF()
    private val bar = RectF()
    private val upBtns = RunUpgrade.entries.map { RectF() }
    private val overlayBtn = RectF(); private val overlayBtn2 = RectF()

    private val density = resources.displayMetrics.density
    private val shapes = ShapeRenderer(2.2f * density)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textSize = 14f * density }
    private val tmp = RectF()

    // touch
    private var downAt = 0L; private var downX = 0f; private var downY = 0f

    init { holder.addCallback(this); isFocusable = true }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            insetTop = i.top; insetBottom = i.bottom
        } else {
            @Suppress("DEPRECATION")
            run { insetTop = insets.systemWindowInsetTop; insetBottom = insets.systemWindowInsetBottom }
        }
        synchronized(engine) { layout(width, height) }
        return insets
    }

    // ---------------------------------------------------------------- lifecycle
    fun resume() { if (surfaceReady && !running) start() }
    fun pause() { synchronized(engine) { if (!engine.gameOver) engine.paused = true }; stop() }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; start() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { synchronized(engine) { layout(width, height) } }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stop() }

    private fun start() { if (running) return; running = true; thread = Thread(this, "EmberLoop").also { it.start() } }
    private fun stop() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) try { it.join(500) } catch (_: InterruptedException) {} }
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        val frameNs = 1_000_000_000L / 60
        while (running) {
            val t0 = System.nanoTime()
            val dt = (t0 - last) / 1e9f; last = t0
            var over = false
            synchronized(engine) { engine.advance(dt); over = engine.gameOver }
            if (over && !gameOverReported) { gameOverReported = true; post { listener?.onRunEnded() } }
            draw()
            val sleep = frameNs - (System.nanoTime() - t0)
            if (sleep > 0) try { Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt()) } catch (_: InterruptedException) {}
        }
    }

    private fun draw() {
        if (!surfaceReady) return
        val c: Canvas = try {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()) ?: return
        } catch (e: Exception) { Log.w("GameView", "lock failed", e); return }
        try { synchronized(engine) { render(c) } } finally {
            try { holder.unlockCanvasAndPost(c) } catch (e: Exception) { Log.w("GameView", "post failed", e) }
        }
    }

    // ---------------------------------------------------------------- layout
    private fun layout(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        w = width.toFloat(); h = height.toFloat()
        val hudH = 64f * density
        hud.set(0f, insetTop.toFloat(), w, insetTop + hudH)
        val bh = hudH * 0.62f; val by = hud.top + (hudH - bh) / 2
        gearBtn.set(w - bh - 10 * density, by, w - 10 * density, by + bh)
        speedBtn.set(gearBtn.left - bh * 1.4f - 8 * density, by, gearBtn.left - 8 * density, by + bh)
        waveLabel.set(10 * density, hud.top, w * 0.42f, hud.bottom)
        val barH = 78f * density
        bar.set(0f, h - insetBottom - barH, w, h - insetBottom.toFloat())
        val pad = 8 * density; val bw = (w - pad * (upBtns.size + 1)) / upBtns.size
        upBtns.forEachIndexed { i, r -> val x = pad + i * (bw + pad); r.set(x, bar.top + pad, x + bw, bar.bottom - pad) }
        fieldTop = hud.bottom; fieldBottom = bar.top
        scale = w
        engine.worldH = (fieldBottom - fieldTop) / scale
        val ow = w * 0.6f; val oh = 56 * density
        overlayBtn.set((w - ow) / 2, h * 0.58f, (w + ow) / 2, h * 0.58f + oh)
        overlayBtn2.set((w - ow) / 2, overlayBtn.bottom + 14 * density, (w + ow) / 2, overlayBtn.bottom + 14 * density + oh)
    }

    private fun sx(x: Float) = x * scale
    private fun sy(y: Float) = fieldTop + y * scale

    // ---------------------------------------------------------------- render
    private fun render(c: Canvas) {
        c.drawColor(Palette.BG)
        val e = engine
        c.save(); c.clipRect(0f, fieldTop, w, fieldBottom)
        drawGrid(c)
        if (e.frostActive) { fill.color = Palette.STEEL_LIGHT; fill.alpha = 22; c.drawRect(0f, fieldTop, w, fieldBottom, fill) }
        // wall line
        stroke.color = Palette.STEEL; stroke.alpha = 120
        c.drawLine(0f, sy(e.wallY), w, sy(e.wallY), stroke)
        // mobs
        for (m in e.mobs) {
            if (!m.alive) continue
            val frac = (m.hp / m.maxHp).toFloat().coerceIn(0f, 1f)
            shapes.draw(c, m.look, sx(m.x), sy(m.y), m.radius * scale, m.age, m.hitFlash > 0, frac)
            if (m.isBoss || frac < 1f) {
                val bw = m.radius * scale * 1.6f; val top = sy(m.y) - m.radius * scale - 8 * density
                fill.color = Palette.BG_PANEL; fill.alpha = 255; c.drawRect(sx(m.x) - bw / 2, top, sx(m.x) + bw / 2, top + 3 * density, fill)
                fill.color = if (m.isBoss) Palette.CORAL else Palette.STEEL_LIGHT
                c.drawRect(sx(m.x) - bw / 2, top, sx(m.x) - bw / 2 + bw * frac, top + 3 * density, fill)
                if (m.shield > 0 && m.maxShield > 0) {
                    stroke.color = Palette.STEEL_LIGHT; stroke.alpha = (60 + 140 * (m.shield / m.maxShield)).toInt()
                    c.drawCircle(sx(m.x), sy(m.y), m.radius * scale * 1.35f, stroke)
                }
            }
        }
        // shots
        fill.alpha = 255
        for (s in e.shots) {
            fill.color = when (s.crit) { 2 -> Palette.TEXT; 1 -> Palette.CORAL; else -> Palette.STEEL_LIGHT }
            c.drawCircle(sx(s.x), sy(s.y), 3.2f * density, fill)
        }
        fill.color = Palette.CORAL_DIM
        for (s in e.enemyShots) c.drawCircle(sx(s.x), sy(s.y), 4f * density, fill)
        drawFx(c)
        drawTower(c)
        c.restore()
        drawHud(c)
        drawBar(c)
        if (e.paused && !e.gameOver) drawOverlay(c, "PAUSED", "Resume", "Abandon run")
        if (e.gameOver) drawOverlay(c, "THE WALL FELL", "Return to menu", null)
    }

    private fun drawGrid(c: Canvas) {
        stroke.color = Palette.STEEL; stroke.alpha = 18
        val step = w / 8
        var y = fieldTop; while (y < fieldBottom) { c.drawLine(0f, y, w, y, stroke); y += step }
        var x = 0f; while (x <= w) { c.drawLine(x, fieldTop, x, fieldBottom, stroke); x += step }
    }

    private fun drawTower(c: Canvas) {
        val e = engine
        val cx = sx(e.towerX); val cy = sy(e.towerY); val r = GameEngine.TOWER_RADIUS * scale
        // decagon body + hexagon core: on-brand, avoids circle/square/triangle/diamond
        shapes.outline(c, MobShape.DECAGON, cx, cy, r * 1.5f, Palette.STEEL)
        fill.color = Palette.BG_PANEL; fill.alpha = 255; c.drawCircle(cx, cy, r, fill)
        shapes.outline(c, MobShape.HEXAGON, cx, cy, r, Palette.STEEL_LIGHT)
        val pulse = 0.6f + 0.4f * sin(e.time * 3f)
        fill.color = Palette.CORAL; fill.alpha = (255 * pulse).toInt(); c.drawCircle(cx, cy, r * 0.35f, fill)
        fill.alpha = 255
    }

    private fun drawFx(c: Canvas) {
        for (f in engine.fx) {
            val a = (f.life / f.maxLife).coerceIn(0f, 1f)
            when (f.kind) {
                FxKind.BEAM -> {
                    stroke.color = if (f.color == 0) Palette.STEEL_LIGHT else Palette.CORAL; stroke.alpha = (255 * a).toInt()
                    stroke.strokeWidth = 3f * density; c.drawLine(sx(f.x), sy(f.y), sx(f.x2), sy(f.y2), stroke)
                    stroke.strokeWidth = 2f * density
                }
                FxKind.CHAIN -> { stroke.color = Palette.STEEL_LIGHT; stroke.alpha = (255 * a).toInt(); c.drawLine(sx(f.x), sy(f.y), sx(f.x2), sy(f.y2), stroke) }
                FxKind.NOVA -> { stroke.color = Palette.CORAL; stroke.alpha = (255 * a).toInt(); c.drawCircle(sx(f.x), sy(f.y), f.x2 * scale * (1 - a * 0.7f), stroke) }
                FxKind.POP -> { stroke.color = f.color; stroke.alpha = (200 * a).toInt(); c.drawCircle(sx(f.x), sy(f.y), f.x2 * scale * (2 - a), stroke) }
                FxKind.FROST -> { fill.color = Palette.STEEL_LIGHT; fill.alpha = (70 * a).toInt(); c.drawRect(0f, fieldTop, w, fieldBottom, fill) }
                FxKind.GRAVITY -> { fill.color = Palette.STEEL; fill.alpha = (80 * a).toInt(); c.drawRect(0f, fieldTop, w, fieldBottom, fill) }
                FxKind.BOSS_WARN -> {
                    text.color = Palette.CORAL; text.alpha = (255 * a).toInt(); text.textSize = 22 * density
                    text.textAlign = Paint.Align.CENTER; c.drawText("SPIRAL BOSS", w / 2, fieldTop + 60 * density, text)
                    text.textAlign = Paint.Align.LEFT; text.alpha = 255
                }
            }
        }
        fill.alpha = 255; stroke.alpha = 255
    }

    private fun drawHud(c: Canvas) {
        val e = engine
        fill.color = Palette.BG_PANEL; fill.alpha = 255
        c.drawRect(0f, 0f, w, hud.bottom, fill)
        text.color = Palette.TEXT; text.textSize = 18 * density; text.isFakeBoldText = true
        c.drawText("WAVE ${e.wave}  S${e.stage}", waveLabel.left, hud.top + 24 * density, text)
        text.isFakeBoldText = false; text.textSize = 12 * density; text.color = Palette.TEXT_DIM
        c.drawText("Coins ${NumberFormat.compact(e.runCoins)}  Cash ${NumberFormat.compact(e.cash)}", waveLabel.left, hud.top + 44 * density, text)
        // health bar
        val hbL = waveLabel.left; val hbR = speedBtn.left - 10 * density; val hbT = hud.bottom - 10 * density
        fill.color = Palette.BG; c.drawRect(hbL, hbT, hbR, hbT + 5 * density, fill)
        fill.color = if (e.godMode) Palette.STEEL_LIGHT else Palette.CORAL
        c.drawRect(hbL, hbT, hbL + (hbR - hbL) * (e.health / e.maxHealth).toFloat().coerceIn(0f, 1f), hbT + 5 * density, fill)
        text.color = Palette.TEXT; text.textAlign = Paint.Align.RIGHT
        c.drawText("${NumberFormat.compact(max(0.0, e.health))} / ${NumberFormat.compact(e.maxHealth)}", hbR, hbT - 4 * density, text)
        text.textAlign = Paint.Align.LEFT
        button(c, speedBtn, "${e.speed}x", true)
        button(c, gearBtn, "II", true)
    }

    private fun drawBar(c: Canvas) {
        val e = engine
        fill.color = Palette.BG_PANEL; c.drawRect(0f, bar.top, w, h, fill)
        RunUpgrade.entries.forEachIndexed { i, u ->
            val r = upBtns[i]; val cost = e.runUpgradeCost(u)
            button(c, r, "", e.cash >= cost)
            text.textAlign = Paint.Align.CENTER; text.color = Palette.TEXT; text.textSize = 14 * density
            c.drawText("${u.label} Lv${e.runLevels[i]}", r.centerX(), r.top + r.height() * 0.42f, text)
            text.color = Palette.TEXT_DIM; text.textSize = 12 * density
            c.drawText("$" + NumberFormat.compact(cost), r.centerX(), r.top + r.height() * 0.75f, text)
            text.textAlign = Paint.Align.LEFT
        }
        // ultimate cooldown pips just above the bar
        var x = 12 * density
        for ((uw, lvl) in e.ultimates) {
            if (lvl <= 0) continue
            val cd = (e.uwCooldown[uw] ?: 0f).coerceAtLeast(0f) / uw.cooldown(lvl)
            val cy = bar.top - 16 * density
            stroke.color = Palette.STEEL; c.drawCircle(x + 8 * density, cy, 8 * density, stroke)
            fill.color = Palette.CORAL; fill.alpha = if (cd <= 0f) 255 else 90
            tmp.set(x, cy - 8 * density, x + 16 * density, cy + 8 * density)
            c.drawArc(tmp, -90f, 360f * (1 - cd), true, fill); fill.alpha = 255
            text.textSize = 10 * density; text.color = Palette.TEXT_DIM
            c.drawText(uwShort(uw), x + 20 * density, cy + 4 * density, text)
            x += 70 * density
        }
    }

    private fun uwShort(u: UltimateWeapon) = when (u) {
        UltimateWeapon.EMBER_NOVA -> "Nova"; UltimateWeapon.FROST_LATTICE -> "Frost"
        UltimateWeapon.CHAIN_ARC -> "Arc"; UltimateWeapon.GRAVITY_WELL -> "Well"
    }

    private fun button(c: Canvas, r: RectF, label: String, enabled: Boolean) {
        fill.color = if (enabled) Palette.STEEL else Palette.BG; fill.alpha = if (enabled) 110 else 255
        c.drawRoundRect(r, 10 * density, 10 * density, fill); fill.alpha = 255
        stroke.color = if (enabled) Palette.STEEL_LIGHT else Palette.STEEL; c.drawRoundRect(r, 10 * density, 10 * density, stroke)
        if (label.isNotEmpty()) {
            text.textAlign = Paint.Align.CENTER; text.color = Palette.TEXT; text.textSize = 15 * density
            c.drawText(label, r.centerX(), r.centerY() + 5 * density, text); text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawOverlay(c: Canvas, title: String, b1: String, b2: String?) {
        val e = engine
        fill.color = Palette.BG; fill.alpha = 215; c.drawRect(0f, 0f, w, h, fill); fill.alpha = 255
        text.textAlign = Paint.Align.CENTER; text.color = Palette.CORAL; text.textSize = 30 * density; text.isFakeBoldText = true
        c.drawText(title, w / 2, h * 0.32f, text); text.isFakeBoldText = false
        text.color = Palette.TEXT; text.textSize = 16 * density
        val lines = listOf("Wave ${e.wave}   Kills ${e.kills}", "Coins +${NumberFormat.compact(e.runCoins)}",
            "Diamonds +${e.runDiamonds}   Cores +${e.runCores}")
        lines.forEachIndexed { i, s -> c.drawText(s, w / 2, h * 0.40f + i * 26 * density, text) }
        text.textAlign = Paint.Align.LEFT
        button(c, overlayBtn, b1, true)
        if (b2 != null) button(c, overlayBtn2, b2, true)
    }

    // ---------------------------------------------------------------- input
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downAt = System.currentTimeMillis(); downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP -> {
                val held = System.currentTimeMillis() - downAt
                synchronized(engine) { tap(ev.x, ev.y, held) }
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float, heldMs: Long) {
        val e = engine
        if (e.gameOver) { if (overlayBtn.contains(x, y)) post { listener?.onReturnToMenu() }; return }
        if (e.paused) {
            when {
                overlayBtn.contains(x, y) -> e.paused = false
                overlayBtn2.contains(x, y) -> post { listener?.onQuit() }
            }
            return
        }
        when {
            gearBtn.contains(x, y) -> {
                // Long-press (>= 2 s) arms the management-override gesture (debug builds only).
                if (heldMs >= SecretGesture.LONG_PRESS_MS && listener?.debugAvailable() == true) gesture.onSettingsHeld(heldMs)
                else e.paused = true
            }
            speedBtn.contains(x, y) -> e.speedIndex = (e.speedIndex + 1) % GameEngine.SPEEDS.size
            waveLabel.contains(x, y) -> if (listener?.debugAvailable() == true && gesture.onTitleTap()) {
                e.paused = true; post { listener?.onDebugGesture() }
            }
            else -> upBtns.forEachIndexed { i, r -> if (r.contains(x, y)) e.buyRunUpgrade(RunUpgrade.entries[i]) }
        }
    }

}
