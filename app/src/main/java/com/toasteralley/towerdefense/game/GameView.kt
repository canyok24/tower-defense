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
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
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
        /** Asked before a restart so fresh meta bonuses apply to the new run. */
        fun currentModifiers(): RunModifiers = RunModifiers.NONE
    }

    var listener: Listener? = null
    var engineEvents: GameEngine.Events = GameEngine.Events.NONE
        set(value) { field = value; engine = GameEngine(value, engine.modifiers) }

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
    private var speedMenuOpen = false
    private var panelScroll = 0f

    // touch tracking (tap on release, drag to scroll the build list)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false
    private var canScroll = false

    // ------------------------------------------------------------ layout
    private var tile = 1f
    private var mapLeft = 0f
    private var mapTop = 0f
    private val hudRect = RectF()
    private val panelRect = RectF()
    private val listRect = RectF()
    private var itemH = 1f
    private val pauseBtn = RectF()
    private val speedBtn = RectF()
    private val speedMenuBtn = RectF()
    private val storeBtn = RectF()
    private val startWaveBtn = RectF()
    private val upgradeBtn = RectF()
    private val upgrade5Btn = RectF()
    private val upgrade10Btn = RectF()
    private val upgradeMaxBtn = RectF()
    private val sellBtn = RectF()
    private val closeBtn = RectF()
    private val overlayBtn1 = RectF()
    private val overlayBtn2 = RectF()
    private val overlayBtn3 = RectF()
    private val speedMenuRect = RectF()
    private val speedOptionRects = GameEngine.SPEED_OPTIONS.map { RectF() }

    // ------------------------------------------------------------ paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val tmpRect = RectF()
    private val itemRect = RectF()
    private val tmpPath = Path()
    private var animTime = 0f

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
            val dt = (frameStart - last) / 1e9f
            last = frameStart

            // Fixed 1/60 s steps; N substeps per frame at Nx speed (capped).
            synchronized(engine) { engine.advance(dt) }
            animTime += dt
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
        storeBtn.set(x, by, x + bw, by + bh); x -= hudH * 0.7f + by
        speedMenuBtn.set(x, by, x + hudH * 0.7f, by + bh); x -= bw + by * 0.4f
        speedBtn.set(x, by, x + bw, by + bh); x -= bw + by
        pauseBtn.set(x, by, x + bw, by + bh)

        // Side panel: scrollable tower list + start-wave button.
        val pad = panelW * 0.05f
        val startH = panelRect.height() * 0.13f
        startWaveBtn.set(panelRect.left + pad, panelRect.bottom - pad - startH, panelRect.right - pad, panelRect.bottom - pad)
        listRect.set(panelRect.left + pad, panelRect.top + pad, panelRect.right - pad, startWaveBtn.top - pad)
        itemH = max(listRect.height() / 6.3f, 1f)
        clampScroll()

        // Upgrade panel buttons: [+1] / [x5][x10][Max] / [Sell][Close]
        val btnH = panelRect.height() * 0.095f
        val gap = pad * 0.6f
        val half = (listRect.width() - gap) / 2f
        val row3 = startWaveBtn.top - pad - btnH
        sellBtn.set(listRect.left, row3, listRect.left + half, row3 + btnH)
        closeBtn.set(listRect.right - half, row3, listRect.right, row3 + btnH)
        val row2 = row3 - gap - btnH
        val third = (listRect.width() - gap * 2) / 3f
        upgrade5Btn.set(listRect.left, row2, listRect.left + third, row2 + btnH)
        upgrade10Btn.set(listRect.left + third + gap, row2, listRect.left + third * 2 + gap, row2 + btnH)
        upgradeMaxBtn.set(listRect.right - third, row2, listRect.right, row2 + btnH)
        val row1 = row2 - gap - btnH
        upgradeBtn.set(listRect.left, row1, listRect.right, row1 + btnH)

        // Overlay buttons centered.
        val ow = w * 0.3f
        val oh = h * 0.1f
        val ox = (w - ow) / 2f
        val oy = h * 0.5f
        overlayBtn1.set(ox, oy, ox + ow, oy + oh)
        overlayBtn2.set(ox, oy + oh * 1.25f, ox + ow, oy + oh * 2.25f)
        overlayBtn3.set(ox, oy + oh * 2.5f, ox + ow, oy + oh * 3.5f)

        // Speed popup: 4 x 3 grid under the speed button.
        val cols = 4
        val rows = (GameEngine.SPEED_OPTIONS.size + cols - 1) / cols
        val cell = hudH * 0.95f
        val mw = cols * cell + (cols + 1) * by
        val mh = rows * cell + (rows + 1) * by
        val ml = (speedMenuBtn.right - mw).coerceAtLeast(0f)
        speedMenuRect.set(ml, hudRect.bottom, ml + mw, hudRect.bottom + mh)
        speedOptionRects.forEachIndexed { i, r ->
            val cx = i % cols; val cy = i / cols
            val l = ml + by + cx * (cell + by)
            val t = hudRect.bottom + by + cy * (cell + by)
            r.set(l, t, l + cell, t + cell)
        }
    }

    private fun maxScroll() = max(0f, TowerType.entries.size * itemH - listRect.height())
    private fun clampScroll() { panelScroll = panelScroll.coerceIn(0f, maxScroll()) }

    private fun towerItemRect(index: Int, out: RectF): RectF {
        val top = listRect.top + index * itemH - panelScroll
        out.set(listRect.left, top, listRect.right, top + itemH - listRect.width() * 0.03f)
        return out
    }

    // ================================================================ input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; lastY = y; dragging = false
                canScroll = synchronized(engine) {
                    selectedTower == null && !speedMenuOpen && listRect.contains(x, y) && maxScroll() > 0f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (canScroll && (dragging || abs(y - downY) > touchSlop)) {
                    dragging = true
                    synchronized(engine) { panelScroll -= y - lastY; clampScroll() }
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    var action: (() -> Unit)? = null
                    synchronized(engine) { action = handleTap(downX, downY) }
                    // UI callbacks run outside the engine lock, on the UI thread.
                    action?.invoke()
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
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
                overlayBtn3.contains(x, y) -> { e.finishRun(); return { listener?.onQuitToMenu() } }
            }
            return null
        }
        if (speedMenuOpen) {
            speedOptionRects.forEachIndexed { i, r ->
                if (r.contains(x, y)) e.speedMultiplier = GameEngine.SPEED_OPTIONS[i]
            }
            speedMenuOpen = false
            return null
        }

        // HUD.
        when {
            pauseBtn.contains(x, y) -> { e.paused = true; return null }
            speedBtn.contains(x, y) -> { e.cycleSpeed(); return null }
            speedMenuBtn.contains(x, y) -> { speedMenuOpen = true; return null }
            storeBtn.contains(x, y) -> { e.paused = true; return { listener?.onOpenStore() } }
        }

        // Side panel.
        if (panelRect.contains(x, y)) {
            if (startWaveBtn.contains(x, y)) { e.startNextWave(); return null }
            val sel = selectedTower
            if (sel != null) {
                when {
                    upgradeBtn.contains(x, y) -> e.upgradeTower(sel, 1)
                    upgrade5Btn.contains(x, y) -> e.upgradeTower(sel, 5)
                    upgrade10Btn.contains(x, y) -> e.upgradeTower(sel, 10)
                    upgradeMaxBtn.contains(x, y) -> e.upgradeTowerMax(sel)
                    sellBtn.contains(x, y) -> { e.sellTower(sel); selectedTower = null }
                    closeBtn.contains(x, y) -> selectedTower = null
                }
                return null
            }
            if (!listRect.contains(x, y)) return null
            TowerType.entries.forEachIndexed { i, type ->
                if (towerItemRect(i, tmpRect).contains(x, y)) {
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
        engine.finishRun()
        listener?.currentModifiers()?.let { engine.modifiers = it }
        engine.reset()
        selectedTower = null
        buildSelection = null
        speedMenuOpen = false
    }

    /** Called by the activity when entitlements change. */
    fun setPremiumUnlocked(unlocked: Boolean) {
        synchronized(engine) { engine.premiumUnlocked = unlocked }
    }

    /** Applies meta/prestige bonuses and starts a fresh run. */
    fun startRun(mods: RunModifiers) {
        synchronized(engine) {
            engine.modifiers = mods
            engine.reset()
        }
    }

    /** Ends the current run (if anything happened) so Tokens are awarded. */
    fun finishRun() { synchronized(engine) { engine.finishRun() } }

    fun resumeGame() { synchronized(engine) { engine.paused = false } }

    fun pauseGame() { synchronized(engine) { if (engine.isRunning) engine.paused = true } }

    // ================================================================ rendering

    private fun sx(tx: Float) = mapLeft + tx * tile
    private fun sy(ty: Float) = mapTop + ty * tile

    private fun render(c: Canvas) {
        if (tile <= 1f) computeLayout(width, height)
        c.drawColor(0xFF1B2A1B.toInt())
        val e = engine
        val shaking = e.shakeTimer > 0f && !e.lowFx
        if (shaking) {
            val amp = tile * 0.15f * (e.shakeTimer / 0.35f)
            c.save()
            c.translate(sin(animTime * 97f) * amp, cos(animTime * 83f) * amp)
        }
        drawMap(c)
        drawTowers(c)
        drawEnemies(c)
        drawBeams(c)
        drawProjectiles(c)
        drawEffects(c)
        drawSelection(c)
        if (shaking) c.restore()
        if (e.flashTimer > 0f && !e.lowFx) {
            val a = ((e.flashTimer / 0.12f) * 110).toInt().coerceIn(0, 110)
            fill.color = (a shl 24) or 0xFFFFFF
            c.drawRect(0f, hudRect.bottom, panelRect.left, height.toFloat(), fill)
        }
        drawHud(c)
        drawPanel(c)
        drawMessage(c)
        drawBanner(c)
        if (speedMenuOpen) drawSpeedMenu(c)
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

    private fun polygon(cx: Float, cy: Float, radius: Float, sides: Int, rotation: Float = 0f) {
        tmpPath.reset()
        for (i in 0 until sides) {
            val a = rotation + (2 * Math.PI / sides * i).toFloat()
            val px = cx + cos(a) * radius; val py = cy + sin(a) * radius
            if (i == 0) tmpPath.moveTo(px, py) else tmpPath.lineTo(px, py)
        }
        tmpPath.close()
    }

    private fun star(cx: Float, cy: Float, outer: Float, inner: Float, points: Int) {
        tmpPath.reset()
        for (i in 0 until points * 2) {
            val a = (-Math.PI / 2 + Math.PI / points * i).toFloat()
            val r = if (i % 2 == 0) outer else inner
            val px = cx + cos(a) * r; val py = cy + sin(a) * r
            if (i == 0) tmpPath.moveTo(px, py) else tmpPath.lineTo(px, py)
        }
        tmpPath.close()
    }

    private fun barrel(c: Canvas, cx: Float, cy: Float, angle: Float, len: Float, width: Float, color: Int = 0xFF263238.toInt(), offset: Float = 0f) {
        stroke.color = color
        stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND
        val ox = -sin(angle) * offset; val oy = cos(angle) * offset
        c.drawLine(cx + ox, cy + oy, cx + ox + cos(angle) * len, cy + oy + sin(angle) * len, stroke)
        stroke.strokeCap = Paint.Cap.BUTT
    }

    private fun drawTowerShape(c: Canvas, type: TowerType, cx: Float, cy: Float, size: Float, angle: Float, level: Int, active: Boolean = false) {
        // Base.
        fill.color = 0xFF37474F.toInt()
        tmpRect.set(cx - size * 0.42f, cy - size * 0.42f, cx + size * 0.42f, cy + size * 0.42f)
        c.drawRoundRect(tmpRect, size * 0.1f, size * 0.1f, fill)
        if (level >= 10) {
            stroke.color = when {
                level >= 100 -> 0xFF00E5FF.toInt()
                level >= 50 -> 0xFFFF8A65.toInt()
                level >= 20 -> 0xFFFFD54F.toInt()
                else -> 0xFFB0BEC5.toInt()
            }
            stroke.strokeWidth = size * 0.04f
            c.drawRoundRect(tmpRect, size * 0.1f, size * 0.1f, stroke)
        }
        fill.color = type.color
        when (type) {
            TowerType.ARROW -> {
                c.drawCircle(cx, cy, size * 0.28f, fill)
                barrel(c, cx, cy, angle, size * 0.36f, size * 0.09f)
            }
            TowerType.CANNON -> {
                c.drawCircle(cx, cy, size * 0.33f, fill)
                barrel(c, cx, cy, angle, size * 0.38f, size * 0.16f)
            }
            TowerType.FROST -> {
                polygon(cx, cy, size * 0.32f, 6); c.drawPath(tmpPath, fill)
                barrel(c, cx, cy, angle, size * 0.34f, size * 0.08f, 0xFFE1F5FE.toInt())
            }
            TowerType.SLOW -> {
                // Goo blob with drips.
                c.drawCircle(cx, cy, size * 0.26f, fill)
                for (i in 0 until 3) {
                    val a = (Math.PI / 2 + i * 2 * Math.PI / 3).toFloat()
                    c.drawCircle(cx + cos(a) * size * 0.24f, cy + sin(a) * size * 0.24f, size * 0.09f, fill)
                }
                barrel(c, cx, cy, angle, size * 0.3f, size * 0.14f, 0xFF004D40.toInt())
            }
            TowerType.RAPID -> {
                polygon(cx, cy, size * 0.3f, 3, angle); c.drawPath(tmpPath, fill)
                for (o in listOf(-0.1f, 0f, 0.1f)) barrel(c, cx, cy, angle, size * 0.4f, size * 0.05f, offset = o * size)
            }
            TowerType.HEAVY -> {
                polygon(cx, cy, size * 0.36f, 8, (Math.PI / 8).toFloat()); c.drawPath(tmpPath, fill)
                barrel(c, cx, cy, angle, size * 0.42f, size * 0.2f, 0xFF3E2723.toInt())
            }
            TowerType.DISRUPTOR -> {
                c.drawCircle(cx, cy, size * 0.18f, fill)
                stroke.color = type.color; stroke.strokeWidth = size * 0.05f
                val pulse = if (active) 1.15f else 1f
                c.drawCircle(cx, cy, size * 0.27f * pulse, stroke)
                c.drawCircle(cx, cy, size * 0.36f * pulse, stroke)
                fill.color = 0xFFFFEB3B.toInt()
                c.drawCircle(cx, cy, size * 0.07f, fill)
            }
            TowerType.SNIPER -> {
                tmpRect.set(cx - size * 0.26f, cy - size * 0.26f, cx + size * 0.26f, cy + size * 0.26f)
                c.drawRect(tmpRect, fill)
                barrel(c, cx, cy, angle, size * 0.5f, size * 0.09f)
            }
            TowerType.RAILGUN -> {
                // Dark housing with two parallel cyan rails.
                fill.color = 0xFF263238.toInt(); c.drawCircle(cx, cy, size * 0.3f, fill)
                barrel(c, cx, cy, angle, size * 0.46f, size * 0.07f, type.color, size * 0.09f)
                barrel(c, cx, cy, angle, size * 0.46f, size * 0.07f, type.color, -size * 0.09f)
                fill.color = type.color; c.drawCircle(cx, cy, size * 0.11f, fill)
            }
            TowerType.LASER -> {
                tmpPath.reset()
                tmpPath.moveTo(cx, cy - size * 0.34f); tmpPath.lineTo(cx + size * 0.34f, cy)
                tmpPath.lineTo(cx, cy + size * 0.34f); tmpPath.lineTo(cx - size * 0.34f, cy); tmpPath.close()
                c.drawPath(tmpPath, fill)
                barrel(c, cx, cy, angle, size * 0.3f, size * 0.1f, 0xFF4A0000.toInt())
                fill.color = if (active) Color.WHITE else 0xFFFFCDD2.toInt()
                c.drawCircle(cx, cy, size * 0.1f, fill)
            }
            TowerType.ANNIHILATOR -> {
                star(cx, cy, size * 0.4f, size * 0.2f, 6); c.drawPath(tmpPath, fill)
                barrel(c, cx, cy, angle, size * 0.46f, size * 0.24f, 0xFF1A1A1A.toInt())
                fill.color = 0xFFFF6E40.toInt(); c.drawCircle(cx, cy, size * 0.1f, fill)
            }
        }
        // Level badge.
        if (level > 1) {
            text.textAlign = Paint.Align.CENTER
            text.textSize = size * (if (level >= 100) 0.17f else 0.24f)
            fill.color = 0xCC000000.toInt()
            val bx = cx + size * 0.3f; val by = cy + size * 0.3f
            c.drawCircle(bx, by, size * 0.15f, fill)
            text.color = when {
                level >= 100 -> 0xFF00E5FF.toInt()
                level >= 50 -> 0xFFFF8A65.toInt()
                level >= 20 -> 0xFFFFD54F.toInt()
                else -> Color.WHITE
            }
            c.drawText(level.toString(), bx, by - (text.ascent() + text.descent()) / 2f, text)
            text.color = Color.WHITE
        }
    }

    private fun drawTowers(c: Canvas) {
        for (t in engine.towers) {
            val active = t.beamTargets.isNotEmpty() || t.pulseTimer > 0f
            drawTowerShape(c, t.type, sx(t.x), sy(t.y), tile, t.angle, t.level, active)
            if (t.beamTimer > 0f) {
                // Railgun slug trail.
                stroke.color = t.type.color; stroke.strokeWidth = tile * 0.1f
                c.drawLine(sx(t.x), sy(t.y), sx(t.beamX), sy(t.beamY), stroke)
                stroke.color = Color.WHITE; stroke.strokeWidth = tile * 0.035f
                c.drawLine(sx(t.x), sy(t.y), sx(t.beamX), sy(t.beamY), stroke)
            }
        }
    }

    /** Laser beams: drawn every frame from the tower's current beam targets. */
    private fun drawBeams(c: Canvas) {
        stroke.strokeCap = Paint.Cap.ROUND
        for (t in engine.towers) {
            val targets = t.beamTargets
            if (targets.isEmpty()) continue
            val main = targets[0]
            val flicker = 0.85f + 0.15f * sin(animTime * 40f + t.col)
            // Branches first (thinner, dimmer), from the main target.
            for (i in 1 until targets.size) {
                val b = targets[i]
                stroke.color = (0x88 shl 24) or (t.type.color and 0xFFFFFF)
                stroke.strokeWidth = tile * 0.05f
                c.drawLine(sx(main.x), sy(main.y), sx(b.x), sy(b.y), stroke)
                stroke.color = 0x66FFFFFF; stroke.strokeWidth = tile * 0.015f
                c.drawLine(sx(main.x), sy(main.y), sx(b.x), sy(b.y), stroke)
            }
            stroke.color = t.type.color; stroke.strokeWidth = tile * 0.12f * flicker
            c.drawLine(sx(t.x), sy(t.y), sx(main.x), sy(main.y), stroke)
            stroke.color = Color.WHITE; stroke.strokeWidth = tile * 0.04f
            c.drawLine(sx(t.x), sy(t.y), sx(main.x), sy(main.y), stroke)
        }
        stroke.strokeCap = Paint.Cap.BUTT
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
            if (e.isStunned) {
                // Orbiting yellow sparks = stunned.
                fill.color = 0xFFFFEB3B.toInt()
                for (i in 0 until 3) {
                    val a = animTime * 6f + i * 2.094f
                    c.drawCircle(cx + cos(a) * r * 0.9f, cy - r * 1.1f + sin(a) * r * 0.3f, tile * 0.05f, fill)
                }
                stroke.color = 0xAAFFEB3B.toInt(); stroke.strokeWidth = tile * 0.03f
                c.drawCircle(cx, cy, r * 1.4f, stroke)
            }
            // HP bar.
            val bw = tile * 0.7f; val bh = tile * 0.08f
            val top = cy - r - bh * 2.5f
            fill.color = 0xCC000000.toInt(); c.drawRect(cx - bw / 2, top, cx + bw / 2, top + bh, fill)
            val frac = (e.hp / e.maxHp).toFloat().coerceIn(0f, 1f)
            fill.color = when { frac > 0.6f -> 0xFF66BB6A.toInt(); frac > 0.3f -> 0xFFFFCA28.toInt(); else -> 0xFFEF5350.toInt() }
            c.drawRect(cx - bw / 2, top, cx - bw / 2 + bw * frac, top + bh, fill)
        }
    }

    private fun drawProjectiles(c: Canvas) {
        for (p in engine.projectiles) {
            fill.color = p.color
            val r = when (p.source) {
                TowerType.ANNIHILATOR -> 0.2f
                TowerType.HEAVY -> 0.14f
                TowerType.SLOW -> 0.12f
                TowerType.RAPID -> 0.05f
                else -> if (p.splashRadius > 0f) 0.12f else 0.08f
            }
            c.drawCircle(sx(p.x), sy(p.y), tile * r, fill)
            if (p.source == TowerType.ANNIHILATOR) {
                fill.color = 0xFFFFE0B2.toInt(); c.drawCircle(sx(p.x), sy(p.y), tile * 0.09f, fill)
            }
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
                Effect.Kind.PULSE -> {
                    fill.color = (fx.color and 0x00FFFFFF) or ((a / 5) shl 24)
                    c.drawCircle(sx(fx.x), sy(fx.y), fx.radius * tile * fx.progress, fill)
                    stroke.color = (fx.color and 0x00FFFFFF) or (a shl 24)
                    stroke.strokeWidth = tile * 0.06f
                    c.drawCircle(sx(fx.x), sy(fx.y), fx.radius * tile * fx.progress, stroke)
                }
                Effect.Kind.BLAST -> {
                    val rr = fx.radius * tile * (0.3f + 0.9f * fx.progress)
                    fill.color = (0xFFFF8A65.toInt() and 0x00FFFFFF) or ((a * 2 / 3) shl 24)
                    c.drawCircle(sx(fx.x), sy(fx.y), rr, fill)
                    fill.color = (0xFFFFF3E0.toInt() and 0x00FFFFFF) or (a shl 24)
                    c.drawCircle(sx(fx.x), sy(fx.y), rr * 0.45f * (1f - fx.progress), fill)
                    stroke.color = (fx.color and 0x00FFFFFF) or (a shl 24)
                    stroke.strokeWidth = tile * 0.12f
                    c.drawCircle(sx(fx.x), sy(fx.y), rr * 1.1f, stroke)
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
        val parts = listOf(
            "Gold ${GoldFormat.compact(e.gold)}" to 0xFFFFD54F.toInt(),
            "Lives ${e.lives}" to 0xFFEF5350.toInt(),
            (if (e.milestoneReached) "Wave ${e.wave} ∞" else "Wave ${e.wave}") to Color.WHITE,
            "Prestige dmg ×%.2f · kill ×%.2f · wave ×%.2f".format(
                e.modifiers.prestigeMult, e.modifiers.prestigeKillGoldMult, e.modifiers.prestigeWaveGoldMult
            ) to 0xFFFFAB40.toInt(),
        )
        val avail = pauseBtn.left - hudRect.height() * 0.5f
        val gap = hudRect.height() * 0.45f
        text.textSize = hudRect.height() * 0.4f
        while (parts.sumOf { text.measureText(it.first).toDouble() } + gap * parts.size > avail && text.textSize > 8f) {
            text.textSize *= 0.92f
        }
        val baseline = hudRect.centerY() - (text.ascent() + text.descent()) / 2f
        var x = hudRect.height() * 0.3f
        for ((s, col) in parts) {
            text.color = col
            c.drawText(s, x, baseline, text)
            x += text.measureText(s) + gap
        }
        text.color = Color.WHITE
        drawButton(c, pauseBtn, if (e.paused) "▶" else "II", 0xFF455A64.toInt())
        val spd = e.speedMultiplier
        drawButton(c, speedBtn, "${spd}x", if (spd > 1) 0xFFEF6C00.toInt() else 0xFF455A64.toInt())
        drawButton(c, speedMenuBtn, "▾", 0xFF37474F.toInt())
        drawButton(c, storeBtn, "Store", 0xFF6A1B9A.toInt())
    }

    private fun drawSpeedMenu(c: Canvas) {
        fill.color = 0xF0102027.toInt()
        c.drawRoundRect(speedMenuRect, 16f, 16f, fill)
        stroke.color = 0xFFEF6C00.toInt(); stroke.strokeWidth = 3f
        c.drawRoundRect(speedMenuRect, 16f, 16f, stroke)
        speedOptionRects.forEachIndexed { i, r ->
            val s = GameEngine.SPEED_OPTIONS[i]
            val sel = s == engine.speedMultiplier
            drawButton(c, r, "${s}x", if (sel) 0xFFEF6C00.toInt() else 0xFF455A64.toInt())
        }
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
        c.save()
        c.clipRect(listRect)
        val r = itemRect
        TowerType.entries.forEachIndexed { i, type ->
            towerItemRect(i, r)
            if (r.bottom < listRect.top || r.top > listRect.bottom) return@forEachIndexed
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
            c.drawText(type.displayName, tx, r.top + r.height() * 0.38f, text)
            text.textSize = r.height() * 0.22f
            text.color = when {
                !available -> 0xFFCE93D8.toInt()
                e.gold >= type.cost -> 0xFFFFD54F.toInt()
                else -> 0xFFE57373.toInt()
            }
            val sub = if (available) "${GoldFormat.compact(type.cost)}g · ${type.role}" else "\uD83D\uDD12 Premium · ${type.role}"
            c.drawText(sub, tx, r.top + r.height() * 0.75f, text)
        }
        c.restore()
        // Scrollbar.
        val ms = maxScroll()
        if (ms > 0f) {
            val trackH = listRect.height()
            val thumbH = trackH * listRect.height() / (listRect.height() + ms)
            val thumbTop = listRect.top + (trackH - thumbH) * (panelScroll / ms)
            fill.color = 0x88B0BEC5.toInt()
            val bx = panelRect.right - listRect.width() * 0.025f
            tmpRect.set(bx - 3f, thumbTop, bx + 3f, thumbTop + thumbH)
            c.drawRoundRect(tmpRect, 3f, 3f, fill)
        }
        text.color = Color.WHITE
    }

    private fun fmtRate(s: TowerStats, type: TowerType): String = when (type.attack) {
        Attack.BEAM -> "continuous"
        Attack.PULSE -> "every %.1fs".format(s.stunCooldown)
        else -> if (s.fireRate < 1f) "%.2f/s (%.1fs)".format(s.fireRate, 1f / s.fireRate) else "%.2f/s".format(s.fireRate)
    }

    /** Stat rows (label, value) for a tower at given stats. */
    private fun statRows(type: TowerType, s: TowerStats): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        when (type.attack) {
            Attack.PULSE -> {}
            Attack.BEAM -> rows += "DPS" to GoldFormat.compact(s.damage)
            else -> rows += "Dmg" to GoldFormat.compact(s.damage)
        }
        rows += "Rng" to "%.2f".format(s.range)
        rows += "Rate" to fmtRate(s, type)
        if (s.splashRadius > 0f) rows += "Splash" to "%.2f".format(s.splashRadius)
        if (s.slowFactor < 1f) rows += "Slow" to "%.0f%% %.1fs".format((1f - s.slowFactor) * 100f, s.slowDuration)
        if (type.attack == Attack.PULSE) rows += "Stun" to "%.2fs".format(s.stunDuration)
        if (type.attack == Attack.BEAM) rows += "Beams" to "%d @ %.0f%%".format(s.beamTargets, s.beamSecondary * 100f)
        if (s.bossBonus > 1f) rows += "Boss" to "x%.2f".format(s.bossBonus)
        return rows
    }

    private fun drawUpgradePanel(c: Canvas, t: Tower) {
        val e = engine
        val pad = panelRect.width() * 0.06f
        val left = panelRect.left + pad
        var y = panelRect.top + pad
        text.textAlign = Paint.Align.LEFT
        val big = panelRect.height() * 0.055f
        text.textSize = big; text.color = t.type.color
        y += big; c.drawText(t.type.displayName, left, y, text)
        val small = panelRect.height() * 0.037f
        text.textSize = small; text.color = 0xFFFFD54F.toInt()
        y += small * 1.4f; c.drawText("Level ${t.level}/${Tower.MAX_LEVEL}", left, y, text)

        val cur = statRows(t.type, t.stats)
        val nxt = if (t.isMaxLevel) null else statRows(t.type, t.statsAt(t.level + 1))
        text.color = Color.WHITE
        val maxW = panelRect.width() - pad * 2
        val lineH = small * 1.32f
        val bottomLimit = upgradeBtn.top - small * 1.6f
        for ((i, row) in cur.withIndex()) {
            if (y + lineH > bottomLimit) break
            y += lineH
            val n = nxt?.getOrNull(i)?.second
            val s = if (n != null && n != row.second) "${row.first} ${row.second} → $n" else "${row.first} ${row.second}"
            text.textSize = small
            while (text.measureText(s) > maxW && text.textSize > 8f) text.textSize *= 0.92f
            c.drawText(s, left, y, text)
        }
        text.textSize = small
        text.color = 0xFFB0BEC5.toInt()
        c.drawText("Invested ${GoldFormat.compact(t.invested)}g", left, upgradeBtn.top - small * 0.6f, text)
        text.color = Color.WHITE

        if (t.isMaxLevel) {
            drawButton(c, upgradeBtn, "MAX LEVEL", 0xFF555555.toInt(), false, 0.34f)
            drawButton(c, upgrade5Btn, "x5", 0xFF555555.toInt(), false, 0.34f)
            drawButton(c, upgrade10Btn, "x10", 0xFF555555.toInt(), false, 0.34f)
            drawButton(c, upgradeMaxBtn, "Max", 0xFF555555.toInt(), false, 0.34f)
        } else {
            val c1 = t.upgradeCost
            drawButton(c, upgradeBtn, "Upgrade → L${t.level + 1}  ${GoldFormat.compact(c1)}g", 0xFF1565C0.toInt(), e.gold >= c1, 0.34f)
            val n5 = e.bulkLevels(t, 5)
            val c5 = t.bulkCost(n5)
            drawButton(c, upgrade5Btn, "x$n5 ${GoldFormat.compact(c5)}g", 0xFF0D47A1.toInt(), e.gold >= c5, 0.30f)
            val n10 = e.bulkLevels(t, 10)
            val c10 = t.bulkCost(n10)
            drawButton(c, upgrade10Btn, "x$n10 ${GoldFormat.compact(c10)}g", 0xFF0D47A1.toInt(), e.gold >= c10, 0.30f)
            val nMax = t.maxAffordable(e.gold)
            val cMax = t.bulkCost(nMax)
            drawButton(c, upgradeMaxBtn, if (nMax > 0) "Max +$nMax ${GoldFormat.compact(cMax)}g" else "Max", 0xFF0D47A1.toInt(), nMax > 0, 0.30f)
        }
        drawButton(c, sellBtn, "Sell +${GoldFormat.compact(t.sellValue)}g", 0xFFC62828.toInt(), true, 0.34f)
        drawButton(c, closeBtn, "Close", 0xFF455A64.toInt(), true, 0.34f)
    }

    /** Victory-milestone / wave-milestone banner; the game keeps running. */
    private fun drawBanner(c: Canvas) {
        val msg = engine.bannerText ?: return
        val alpha = (engine.bannerTimer / 0.5f).coerceIn(0f, 1f)
        text.textAlign = Paint.Align.CENTER
        text.textSize = height * 0.075f
        while (text.measureText(msg) > panelRect.left * 0.85f && text.textSize > 10f) text.textSize *= 0.92f
        val cx = panelRect.left / 2f
        val cy = (hudRect.bottom + height) / 2f
        val h = text.textSize * 1.8f
        val half = min(panelRect.left * 0.48f, text.measureText(msg) / 2f + h)
        tmpRect.set(cx - half, cy - h / 2, cx + half, cy + h / 2)
        fill.color = Color.argb((200 * alpha).toInt(), 40, 20, 0)
        c.drawRoundRect(tmpRect, 18f, 18f, fill)
        text.color = Color.argb((255 * alpha).toInt(), 255, 213, 79)
        c.drawText(msg, cx, cy - (text.ascent() + text.descent()) / 2f, text)
        text.color = Color.WHITE
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
            e.state == GameEngine.State.GAME_OVER && e.milestoneReached -> {
                title = "Victory! Run over"
                subtitle = "Wave ${WaveDefinitions.MILESTONE_WAVE} milestone reached · endless run ended after ${e.wavesCleared} waves"
            }
            e.state == GameEngine.State.GAME_OVER -> { title = "Game Over"; subtitle = "You cleared ${e.wavesCleared} waves" }
            e.paused -> { title = "Paused"; subtitle = "Wave ${e.wave} · ${GoldFormat.compact(e.gold)} gold · ${e.speedMultiplier}x" }
            else -> return
        }
        c.drawColor(0xB0000000.toInt())
        text.textAlign = Paint.Align.CENTER
        text.textSize = height * 0.12f
        text.color = when {
            e.state == GameEngine.State.GAME_OVER && e.milestoneReached -> 0xFFFFD54F.toInt()
            e.state == GameEngine.State.GAME_OVER -> 0xFFEF5350.toInt()
            else -> Color.WHITE
        }
        c.drawText(title, width / 2f, height * 0.25f, text)
        text.textSize = height * 0.045f; text.color = 0xFFCFD8DC.toInt()
        c.drawText(subtitle, width / 2f, height * 0.33f, text)
        val result = e.runResult
        if (result != null) {
            text.textSize = height * 0.06f; text.color = 0xFF80DEEA.toInt()
            c.drawText("+${result.tokens} Tokens earned", width / 2f, height * 0.41f, text)
            text.textSize = height * 0.036f; text.color = 0xFFB0BEC5.toInt()
            c.drawText(
                "Kills ${result.kills} · Bosses ${result.bossKills} · Gold earned ${GoldFormat.compact(result.goldEarned)} · spend Tokens in Upgrades",
                width / 2f, height * 0.465f, text
            )
        }
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
