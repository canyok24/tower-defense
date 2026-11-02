package com.toasteralley.towerdefense.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * SurfaceView hosting the game loop on a dedicated thread. Renders the map,
 * entities, HUD, build/upgrade side panel and overlays with plain Canvas calls.
 */
class GameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback, Runnable {

    /** Callbacks to the hosting activity. Always invoked on the UI thread. */
    interface Listener {
        fun onOpenStore()
        fun onQuitToMenu()
    }

    var listener: Listener? = null
    var engineEvents: GameEngine.Events = GameEngine.Events.NONE
        set(value) { field = value; engine = GameEngine(value) }

    var engine = GameEngine(engineEvents)
        private set

    // ------------------------------------------------------------ loop state
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    @Volatile private var hostResumed = false

    // ------------------------------------------------------------ UI state
    private var buildSelection: TowerType? = null
    private var selectedTower: Tower? = null

    // ------------------------------------------------------------ layout
    private var tile = 1f
    private var mapLeft = 0f
    private var mapTop = 0f
    private val hudRect = RectF()
    private val panelRect = RectF()
    private val pauseBtn = RectF()
    private val speedBtn = RectF()
    private val storeBtn = RectF()
    private val startWaveBtn = RectF()
    private val towerBtns = TowerType.entries.associateWith { RectF() }
    private val upgradeBtn = RectF()
    private val sellBtn = RectF()
    private val closeBtn = RectF()
    private val overlayBtn1 = RectF()
    private val overlayBtn2 = RectF()
    private val overlayBtn3 = RectF()

    // ------------------------------------------------------------ paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tmpRect = RectF()
    private val tmpPath = Path()

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // ================================================================ lifecycle

    fun onHostResume() { hostResumed = true; startLoopIfReady() }

    fun onHostPause() {
        hostResumed = false
        synchronized(engine) { if (engine.isRunning) engine.paused = true }
        stopLoop()
    }

    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true; startLoopIfReady() }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(engine) { computeLayout(width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false; stopLoop() }

    private fun startLoopIfReady() {
        if (!surfaceReady || !hostResumed || running) return
        running = true
        thread = Thread(this, "GameLoop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        val t = thread ?: return
        if (t !== Thread.currentThread()) {
            try { t.join(500) } catch (_: InterruptedException) { }
        }
        thread = null
    }

    override fun run() {
        var last = System.nanoTime()
        val frameNs = 1_000_000_000L / 60
        while (running) {
            val frameStart = System.nanoTime()
            val dt = ((frameStart - last) / 1e9f).coerceAtMost(0.05f)
            last = frameStart

            synchronized(engine) {
                repeat(engine.speedMultiplier) { engine.update(dt) }
            }
            drawFrame()

            val sleepNs = frameNs - (System.nanoTime() - frameStart)
            if (sleepNs > 0) {
                try { Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt()) } catch (_: InterruptedException) { }
            }
        }
    }

    private fun drawFrame() {
        if (!surfaceReady) return
        val canvas: Canvas = try {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas())
                ?: return
        } catch (e: Exception) {
            Log.w(TAG, "lockCanvas failed", e); return
        }
        try {
            synchronized(engine) { render(canvas) }
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) { Log.w(TAG, "unlock failed", e) }
        }
    }

    // ================================================================ layout

    private fun computeLayout(w: Int, h: Int) {
        val hudH = h * 0.11f
        val panelW = w * 0.22f
        hudRect.set(0f, 0f, w.toFloat(), hudH)
        panelRect.set(w - panelW, hudH, w.toFloat(), h.toFloat())

        val areaW = w - panelW
        val areaH = h - hudH
        tile = min(areaW / GameMap.COLS, areaH / GameMap.ROWS)
        mapLeft = (areaW - tile * GameMap.COLS) / 2f
        mapTop = hudH + (areaH - tile * GameMap.ROWS) / 2f

        // HUD buttons on the right of the top bar.
        val bh = hudH * 0.72f
        val by = (hudH - bh) / 2f
        val bw = hudH * 1.5f
        var x = w - by - bw
        storeBtn.set(x, by, x + bw, by + bh); x -= bw + by
        speedBtn.set(x, by, x + bw, by + bh); x -= bw + by
        pauseBtn.set(x, by, x + bw, by + bh)

        // Side panel: tower buttons + start-wave button.
        val pad = panelW * 0.05f
        val startH = panelRect.height() * 0.15f
        startWaveBtn.set(panelRect.left + pad, panelRect.bottom - pad - startH, panelRect.right - pad, panelRect.bottom - pad)
        val listTop = panelRect.top + pad
        val listBottom = startWaveBtn.top - pad
        val n = TowerType.entries.size
        val itemH = (listBottom - listTop) / n
        TowerType.entries.forEachIndexed { i, type ->
            towerBtns.getValue(type).set(
                panelRect.left + pad, listTop + i * itemH,
                panelRect.right - pad, listTop + (i + 1) * itemH - pad * 0.5f
            )
        }
        // Upgrade panel buttons.
        val btnH = panelRect.height() * 0.13f
        closeBtn.set(panelRect.left + pad, startWaveBtn.top - pad - btnH, panelRect.right - pad, startWaveBtn.top - pad)
        sellBtn.set(closeBtn.left, closeBtn.top - pad - btnH, closeBtn.right, closeBtn.top - pad)
        upgradeBtn.set(closeBtn.left, sellBtn.top - pad - btnH, closeBtn.right, sellBtn.top - pad)

        // Overlay buttons centered.
        val ow = w * 0.3f
        val oh = h * 0.11f
        val ox = (w - ow) / 2f
        val oy = h * 0.45f
        overlayBtn1.set(ox, oy, ox + ow, oy + oh)
        overlayBtn2.set(ox, oy + oh * 1.25f, ox + ow, oy + oh * 2.25f)
        overlayBtn3.set(ox, oy + oh * 2.5f, ox + ow, oy + oh * 3.5f)
    }

    // ================================================================ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        val x = event.x
        val y = event.y
        var action: (() -> Unit)? = null
        synchronized(engine) { action = handleTap(x, y) }
        // UI callbacks run outside the engine lock, on the UI thread.
        action?.invoke()
        return true
    }

    /** Handles a tap. Returns an optional UI-thread action to run after unlocking. */
    private fun handleTap(x: Float, y: Float): (() -> Unit)? {
        val e = engine
        // Overlays first.
        if (!e.isRunning) {
            when {
                overlayBtn1.contains(x, y) -> restart()
                overlayBtn2.contains(x, y) -> return { listener?.onQuitToMenu() }
            }
            return null
        }
        if (e.paused) {
            when {
                overlayBtn1.contains(x, y) -> e.paused = false
                overlayBtn2.contains(x, y) -> restart()
                overlayBtn3.contains(x, y) -> return { listener?.onQuitToMenu() }
            }
            return null
        }

        // HUD.
        when {
            pauseBtn.contains(x, y) -> { e.paused = true; return null }
            speedBtn.contains(x, y) -> { e.speedMultiplier = if (e.speedMultiplier == 1) 2 else 1; return null }
            storeBtn.contains(x, y) -> { e.paused = true; return { listener?.onOpenStore() } }
        }

        // Side panel.
        if (panelRect.contains(x, y)) {
            if (startWaveBtn.contains(x, y)) { e.startNextWave(); return null }
            val sel = selectedTower
            if (sel != null) {
                when {
                    upgradeBtn.contains(x, y) -> e.upgradeTower(sel)
                    sellBtn.contains(x, y) -> { e.sellTower(sel); selectedTower = null }
                    closeBtn.contains(x, y) -> selectedTower = null
                }
                return null
            }
            for ((type, rect) in towerBtns) {
                if (rect.contains(x, y)) {
                    if (!e.isTypeAvailable(type)) {
                        e.showMessage("${type.displayName} is premium - opening Store")
                        e.paused = true
                        return { listener?.onOpenStore() }
                    }
                    buildSelection = if (buildSelection == type) null else type
                    return null
                }
            }
            return null
        }

        // Map.
        val col = floor((x - mapLeft) / tile).toInt()
        val row = floor((y - mapTop) / tile).toInt()
        if (!e.map.inBounds(col, row) || y < hudRect.bottom) {
            selectedTower = null
            return null
        }
        val tower = e.towerAt(col, row)
        if (tower != null) {
            selectedTower = if (selectedTower === tower) null else tower
            buildSelection = null
            return null
        }
        selectedTower = null
        val type = buildSelection
        if (type == null) {
            if (!e.map.isPath(col, row)) e.showMessage("Pick a tower on the right, then tap a tile", 1.5f)
            return null
        }
        e.placeTower(type, col, row)
        return null
    }

    private fun restart() {
        engine.reset()
        selectedTower = null
        buildSelection = null
    }

    /** Called by the activity when entitlements change. */
    fun setPremiumUnlocked(unlocked: Boolean) {
        synchronized(engine) { engine.premiumUnlocked = unlocked }
    }

    fun resumeGame() { synchronized(engine) { engine.paused = false } }

    fun pauseGame() { synchronized(engine) { if (engine.isRunning) engine.paused = true } }

    // ================================================================ rendering

    private fun sx(tx: Float) = mapLeft + tx * tile
    private fun sy(ty: Float) = mapTop + ty * tile

    private fun render(c: Canvas) {
        if (tile <= 1f) computeLayout(width, height)
        c.drawColor(0xFF1B2A1B.toInt())
        drawMap(c)
        drawTowers(c)
        drawEnemies(c)
        drawProjectiles(c)
        drawEffects(c)
        drawSelection(c)
        drawHud(c)
        drawPanel(c)
        drawMessage(c)
        drawOverlay(c)
    }

    private fun drawMap(c: Canvas) {
        val map = engine.map
        for (r in 0 until map.rows) for (col in 0 until map.cols) {
            val l = sx(col.toFloat()); val t = sy(r.toFloat())
            fill.color = if (map.isPath(col, r)) 0xFFC8A165.toInt()
            else if ((r + col) % 2 == 0) 0xFF4E8B3A.toInt() else 0xFF467F34.toInt()
            c.drawRect(l, t, l + tile + 0.5f, t + tile + 0.5f, fill)
        }
        // Path centre line.
        stroke.color = 0x33FFFFFF
        stroke.strokeWidth = tile * 0.06f
        tmpPath.reset()
        map.waypoints.forEachIndexed { i, wp ->
            if (i == 0) tmpPath.moveTo(sx(wp.x), sy(wp.y)) else tmpPath.lineTo(sx(wp.x), sy(wp.y))
        }
        c.save()
        c.clipRect(sx(0f), sy(0f), sx(map.cols.toFloat()), sy(map.rows.toFloat()))
        c.drawPath(tmpPath, stroke)
        c.restore()
        // Entry / exit markers.
        val first = map.waypoints[1]; val last = map.waypoints[map.waypoints.size - 2]
        fill.color = 0xFF2E7D32.toInt(); c.drawCircle(sx(0.5f), sy(first.y), tile * 0.18f, fill)
        fill.color = 0xFFC62828.toInt(); c.drawCircle(sx(map.cols - 0.5f), sy(last.y), tile * 0.18f, fill)
        // Placement grid hint while building.
        if (buildSelection != null) {
            stroke.color = 0x22FFFFFF; stroke.strokeWidth = 1f
            for (r in 0 until map.rows) for (col in 0 until map.cols) {
                if (engine.canBuildOn(col, r)) {
                    c.drawRect(sx(col.toFloat()) + 2, sy(r.toFloat()) + 2, sx(col + 1f) - 2, sy(r + 1f) - 2, stroke)
                }
            }
        }
    }

    private fun drawTowerShape(c: Canvas, type: TowerType, cx: Float, cy: Float, size: Float, angle: Float, level: Int) {
        // Base.
        fill.color = 0xFF37474F.toInt()
        tmpRect.set(cx - size * 0.42f, cy - size * 0.42f, cx + size * 0.42f, cy + size * 0.42f)
        c.drawRoundRect(tmpRect, size * 0.1f, size * 0.1f, fill)
        // Body.
        fill.color = type.color
        when (type) {
            TowerType.ARROW -> c.drawCircle(cx, cy, size * 0.28f, fill)
            TowerType.CANNON -> c.drawCircle(cx, cy, size * 0.33f, fill)
            TowerType.FROST -> {
                tmpPath.reset()
                for (i in 0 until 6) {
                    val a = (Math.PI / 3 * i).toFloat()
                    val px = cx + cos(a) * size * 0.32f; val py = cy + sin(a) * size * 0.32f
                    if (i == 0) tmpPath.moveTo(px, py) else tmpPath.lineTo(px, py)
                }
                tmpPath.close(); c.drawPath(tmpPath, fill)
            }
            TowerType.SNIPER -> {
                tmpRect.set(cx - size * 0.26f, cy - size * 0.26f, cx + size * 0.26f, cy + size * 0.26f)
                c.drawRect(tmpRect, fill)
            }
            TowerType.LASER -> {
                tmpPath.reset()
                tmpPath.moveTo(cx, cy - size * 0.34f); tmpPath.lineTo(cx + size * 0.34f, cy)
                tmpPath.lineTo(cx, cy + size * 0.34f); tmpPath.lineTo(cx - size * 0.34f, cy); tmpPath.close()
                c.drawPath(tmpPath, fill)
            }
        }
        // Barrel.
        val len = when (type) { TowerType.SNIPER -> 0.5f; TowerType.CANNON -> 0.38f; else -> 0.36f } * size
        stroke.color = 0xFF263238.toInt()
        stroke.strokeWidth = size * if (type == TowerType.CANNON) 0.16f else 0.09f
        stroke.strokeCap = Paint.Cap.ROUND
        c.drawLine(cx, cy, cx + cos(angle) * len, cy + sin(angle) * len, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
        // Level pips.
        fill.color = 0xFFFFD54F.toInt()
        for (i in 0 until level) {
            c.drawCircle(cx - size * 0.3f + i * size * 0.2f, cy + size * 0.36f, size * 0.05f, fill)
        }
    }

    private fun drawTowers(c: Canvas) {
        for (t in engine.towers) {
            drawTowerShape(c, t.type, sx(t.x), sy(t.y), tile, t.angle, t.level)
            if (t.beamTimer > 0f) {
                stroke.color = t.type.color; stroke.strokeWidth = tile * 0.08f
                c.drawLine(sx(t.x), sy(t.y), sx(t.beamX), sy(t.beamY), stroke)
                stroke.color = Color.WHITE; stroke.strokeWidth = tile * 0.03f
                c.drawLine(sx(t.x), sy(t.y), sx(t.beamX), sy(t.beamY), stroke)
            }
        }
    }

    private fun drawEnemies(c: Canvas) {
        for (e in engine.enemies) {
            val cx = sx(e.x); val cy = sy(e.y); val r = e.type.radius * tile
            fill.color = if (e.hitFlash > 0f) Color.WHITE else e.type.color
            when (e.type) {
                EnemyType.NORMAL -> c.drawCircle(cx, cy, r, fill)
                EnemyType.FAST -> {
                    tmpPath.reset()
                    tmpPath.moveTo(cx, cy - r); tmpPath.lineTo(cx + r, cy + r); tmpPath.lineTo(cx - r, cy + r); tmpPath.close()
                    c.drawPath(tmpPath, fill)
                }
                EnemyType.TANK -> {
                    tmpRect.set(cx - r, cy - r, cx + r, cy + r)
                    c.drawRoundRect(tmpRect, r * 0.3f, r * 0.3f, fill)
                }
                EnemyType.BOSS -> {
                    c.drawCircle(cx, cy, r, fill)
                    stroke.color = 0xFFFFD54F.toInt(); stroke.strokeWidth = tile * 0.06f
                    c.drawCircle(cx, cy, r * 1.15f, stroke)
                }
            }
            if (e.isSlowed) {
                stroke.color = 0xFF81D4FA.toInt(); stroke.strokeWidth = tile * 0.05f
                c.drawCircle(cx, cy, r * 1.25f, stroke)
            }
            // HP bar.
            val bw = tile * 0.7f; val bh = tile * 0.08f
            val top = cy - r - bh * 2.5f
            fill.color = 0xCC000000.toInt(); c.drawRect(cx - bw / 2, top, cx + bw / 2, top + bh, fill)
            val frac = (e.hp / e.maxHp).coerceIn(0f, 1f)
            fill.color = when { frac > 0.6f -> 0xFF66BB6A.toInt(); frac > 0.3f -> 0xFFFFCA28.toInt(); else -> 0xFFEF5350.toInt() }
            c.drawRect(cx - bw / 2, top, cx - bw / 2 + bw * frac, top + bh, fill)
        }
    }

    private fun drawProjectiles(c: Canvas) {
        for (p in engine.projectiles) {
            fill.color = p.color
            c.drawCircle(sx(p.x), sy(p.y), tile * if (p.splashRadius > 0f) 0.12f else 0.08f, fill)
        }
    }

    private fun drawEffects(c: Canvas) {
        for (fx in engine.effects) {
            val a = ((1f - fx.progress) * 255).toInt().coerceIn(0, 255)
            when (fx.kind) {
                Effect.Kind.RING -> {
                    stroke.color = (fx.color and 0x00FFFFFF) or (a shl 24)
                    stroke.strokeWidth = tile * 0.08f
                    c.drawCircle(sx(fx.x), sy(fx.y), fx.radius * tile * (0.4f + 0.6f * fx.progress), stroke)
                }
                Effect.Kind.TEXT -> {
                    text.color = (fx.color and 0x00FFFFFF) or (a shl 24)
                    text.textSize = tile * 0.35f
                    text.textAlign = Paint.Align.CENTER
                    c.drawText(fx.text, sx(fx.x), sy(fx.y - 0.4f - 0.5f * fx.progress), text)
                }
            }
        }
        text.color = Color.WHITE
    }

    private fun drawSelection(c: Canvas) {
        val t = selectedTower ?: return
        if (t !in engine.towers) { selectedTower = null; return }
        fill.color = 0x2290CAF9
        c.drawCircle(sx(t.x), sy(t.y), t.range * tile, fill)
        stroke.color = 0xAA90CAF9.toInt(); stroke.strokeWidth = 2f
        c.drawCircle(sx(t.x), sy(t.y), t.range * tile, stroke)
        stroke.color = Color.WHITE; stroke.strokeWidth = 3f
        c.drawRect(sx(t.col.toFloat()), sy(t.row.toFloat()), sx(t.col + 1f), sy(t.row + 1f), stroke)
    }

    private fun drawButton(c: Canvas, r: RectF, label: String, color: Int, enabled: Boolean = true, textScale: Float = 0.42f) {
        fill.color = if (enabled) color else 0xFF555555.toInt()
        c.drawRoundRect(r, r.height() * 0.2f, r.height() * 0.2f, fill)
        text.color = if (enabled) Color.WHITE else 0xFFAAAAAA.toInt()
        text.textAlign = Paint.Align.CENTER
        text.textSize = r.height() * textScale
        // Shrink to fit.
        while (text.measureText(label) > r.width() * 0.9f && text.textSize > 8f) text.textSize *= 0.9f
        c.drawText(label, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2f, text)
        text.color = Color.WHITE
    }

    private fun drawHud(c: Canvas) {
        val e = engine
        fill.color = 0xFF102027.toInt()
        c.drawRect(hudRect, fill)
        text.textAlign = Paint.Align.LEFT
        text.textSize = hudRect.height() * 0.42f
        val baseline = hudRect.centerY() - (text.ascent() + text.descent()) / 2f
        var x = hudRect.height() * 0.3f
        text.color = 0xFFFFD54F.toInt(); val g = "Gold ${e.gold}"; c.drawText(g, x, baseline, text); x += text.measureText(g) + hudRect.height() * 0.6f
        text.color = 0xFFEF5350.toInt(); val l = "Lives ${e.lives}"; c.drawText(l, x, baseline, text); x += text.measureText(l) + hudRect.height() * 0.6f
        text.color = Color.WHITE; c.drawText("Wave ${e.wave}/${e.totalWaves}", x, baseline, text)
        drawButton(c, pauseBtn, if (e.paused) "▶" else "II", 0xFF455A64.toInt())
        drawButton(c, speedBtn, "${e.speedMultiplier}x", if (e.speedMultiplier > 1) 0xFFEF6C00.toInt() else 0xFF455A64.toInt())
        drawButton(c, storeBtn, "Store", 0xFF6A1B9A.toInt())
    }

    private fun drawPanel(c: Canvas) {
        val e = engine
        fill.color = 0xFF1C313A.toInt()
        c.drawRect(panelRect, fill)
        val sel = selectedTower
        if (sel != null) drawUpgradePanel(c, sel) else drawBuildPanel(c)

        val label = when (e.state) {
            GameEngine.State.BETWEEN_WAVES -> "Start wave ${e.wave + 1} (${e.countdown.toInt()}s)"
            GameEngine.State.WAVE_ACTIVE -> "Wave ${e.wave} in progress"
            else -> "-"
        }
        drawButton(c, startWaveBtn, label, 0xFF2E7D32.toInt(), e.state == GameEngine.State.BETWEEN_WAVES, 0.3f)
    }

    private fun drawBuildPanel(c: Canvas) {
        val e = engine
        for ((type, r) in towerBtns) {
            val available = e.isTypeAvailable(type)
            val selected = buildSelection == type
            fill.color = when { selected -> 0xFF00695C.toInt(); available -> 0xFF263238.toInt(); else -> 0xFF212121.toInt() }
            c.drawRoundRect(r, r.height() * 0.15f, r.height() * 0.15f, fill)
            if (selected) {
                stroke.color = 0xFF64FFDA.toInt(); stroke.strokeWidth = 3f
                c.drawRoundRect(r, r.height() * 0.15f, r.height() * 0.15f, stroke)
            }
            val icon = r.height() * 0.85f
            drawTowerShape(c, type, r.left + r.height() * 0.55f, r.centerY(), icon, -Math.PI.toFloat() / 2f, 1)
            val tx = r.left + r.height() * 1.15f
            text.textAlign = Paint.Align.LEFT
            text.textSize = r.height() * 0.3f
            text.color = if (available) Color.WHITE else 0xFF9E9E9E.toInt()
            c.drawText(type.displayName, tx, r.top + r.height() * 0.42f, text)
            text.textSize = r.height() * 0.26f
            text.color = when {
                !available -> 0xFFCE93D8.toInt()
                e.gold >= type.cost -> 0xFFFFD54F.toInt()
                else -> 0xFFE57373.toInt()
            }
            c.drawText(if (available) "${type.cost}g" else "\uD83D\uDD12 Premium", tx, r.top + r.height() * 0.8f, text)
        }
        text.color = Color.WHITE
    }

    private fun drawUpgradePanel(c: Canvas, t: Tower) {
        val e = engine
        val pad = panelRect.width() * 0.07f
        var y = panelRect.top + pad
        text.textAlign = Paint.Align.LEFT
        val big = panelRect.height() * 0.065f
        val small = panelRect.height() * 0.045f
        text.textSize = big; text.color = t.type.color
        y += big; c.drawText("${t.type.displayName}  Lv ${t.level}", panelRect.left + pad, y, text)
        text.textSize = small; text.color = Color.WHITE
        val next = if (t.isMaxLevel) null else t.level + 1
        fun line(label: String, cur: Float, nxt: Float?, fmt: String) {
            y += small * 1.45f
            val s = if (nxt != null) "$label ${fmt.format(cur)} → ${fmt.format(nxt)}" else "$label ${fmt.format(cur)}"
            c.drawText(s, panelRect.left + pad, y, text)
        }
        line("Dmg", t.damage, next?.let { t.statsDamage(it) }, "%.0f")
        line("Rng", t.range, next?.let { t.statsRange(it) }, "%.1f")
        line("Rate", t.fireRate, next?.let { t.statsRate(it) }, "%.2f")
        y += small * 1.45f
        text.color = 0xFFB0BEC5.toInt()
        c.drawText("Invested ${t.invested}g", panelRect.left + pad, y, text)
        text.color = Color.WHITE

        if (t.isMaxLevel) drawButton(c, upgradeBtn, "MAX LEVEL", 0xFF555555.toInt(), false, 0.34f)
        else drawButton(c, upgradeBtn, "Upgrade ${t.upgradeCost}g", 0xFF1565C0.toInt(), e.gold >= t.upgradeCost, 0.34f)
        drawButton(c, sellBtn, "Sell +${t.sellValue}g", 0xFFC62828.toInt(), true, 0.34f)
        drawButton(c, closeBtn, "Close", 0xFF455A64.toInt(), true, 0.34f)
    }

    private fun drawMessage(c: Canvas) {
        val msg = engine.message ?: return
        text.textSize = hudRect.height() * 0.36f
        text.textAlign = Paint.Align.CENTER
        val w = text.measureText(msg) + hudRect.height()
        val cx = (panelRect.left) / 2f
        val top = hudRect.bottom + hudRect.height() * 0.25f
        tmpRect.set(cx - w / 2, top, cx + w / 2, top + hudRect.height() * 0.7f)
        fill.color = 0xCC000000.toInt()
        c.drawRoundRect(tmpRect, 12f, 12f, fill)
        text.color = Color.WHITE
        c.drawText(msg, cx, tmpRect.centerY() - (text.ascent() + text.descent()) / 2f, text)
    }

    private fun drawOverlay(c: Canvas) {
        val e = engine
        val title: String
        val subtitle: String
        when {
            e.state == GameEngine.State.GAME_OVER -> { title = "Game Over"; subtitle = "You survived ${e.wave - 1} of ${e.totalWaves} waves" }
            e.state == GameEngine.State.VICTORY -> { title = "Victory!"; subtitle = "All ${e.totalWaves} waves defeated with ${e.lives} lives left" }
            e.paused -> { title = "Paused"; subtitle = "Wave ${e.wave}/${e.totalWaves} · ${e.gold} gold" }
            else -> return
        }
        c.drawColor(0xB0000000.toInt())
        text.textAlign = Paint.Align.CENTER
        text.textSize = height * 0.12f
        text.color = when (e.state) {
            GameEngine.State.VICTORY -> 0xFFFFD54F.toInt()
            GameEngine.State.GAME_OVER -> 0xFFEF5350.toInt()
            else -> Color.WHITE
        }
        c.drawText(title, width / 2f, height * 0.27f, text)
        text.textSize = height * 0.045f; text.color = 0xFFCFD8DC.toInt()
        c.drawText(subtitle, width / 2f, height * 0.36f, text)
        text.color = Color.WHITE
        if (e.isRunning) {
            drawButton(c, overlayBtn1, "Resume", 0xFF2E7D32.toInt())
            drawButton(c, overlayBtn2, "Restart", 0xFF1565C0.toInt())
            drawButton(c, overlayBtn3, "Main menu", 0xFF455A64.toInt())
        } else {
            drawButton(c, overlayBtn1, "Play again", 0xFF2E7D32.toInt())
            drawButton(c, overlayBtn2, "Main menu", 0xFF455A64.toInt())
        }
    }

    companion object {
        private const val TAG = "GameView"
    }
}
