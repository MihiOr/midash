package com.mihior.midash

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.PathParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** All geometry uses the original 2000 x 800 design space. No layout/recomposition per sample. */
class DashboardView(context: Context, val controller: DashboardController) :
    View(context), Choreographer.FrameCallback {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val regular =
        Typeface.Builder(context.assets, "Roboto-Variable.ttf")
            .setFontVariationSettings("'wght' 400")
            .setWeight(400)
            .build()

    private val light =
        Typeface.Builder(context.assets, "Roboto-Variable.ttf")
            .setFontVariationSettings("'wght' 300")
            .setWeight(300)
            .build()

    private val bold =
        Typeface.Builder(context.assets, "Roboto-Variable.ttf")
            .setFontVariationSettings("'wght' 500")
            .setWeight(500)
            .build()

    private val rect = RectF()

    private val arc = RectF(693f, 50f, 1307f, 664f)

    private val theme = Motion(0f)

    private val rows = Array(6) { PanelPose(it < 4, it >= 4) }

    private val gearX = Motion(1002f)

    private val gearAlpha = Array(3) { Motion(if (it == 1) 1f else 0f) }

    private val doorAlpha = Array(2) { Motion(0f) }

    private val modeAlpha = Array(2) { Motion(if (it == 0) 1f else 0f) }

    private var modeTime = -10_000_000_000L

    private var gearTime = -10_000_000_000L

    private var gearSweepFrom = 1002f

    private var gearSweepTo = 1002f

    private var now = 0L

    private var running = false

    private var scale = 1f

    private var left = 0f

    private var top = 0f

    var frameCount = 0L
        private set

    private var accent = CYAN

    private var soft = 0xffabd9e7.toInt()

    private var dim = 0xff285b70.toInt()

    private var panelAlpha = 1f

    private val ticks = FloatArray(101 * 4)

    private val labelXY = FloatArray(11 * 2)

    private val labels = Array(11) { (it * 20).toString() }

    private val surface =
        Path().apply {
            moveTo(1000f, 357f)

            arcTo(arc, 150f, 240f)

            close()
        }

    private fun path(d: String) = PathParser.createPathFromPathData(d)!!

    private val body = path(DashboardPaths.body)

    private val wings = arrayOf(path(DashboardPaths.leftWing), path(DashboardPaths.rightWing))

    private val openOutline =
        Path().apply {
            addPath(body)

            wings.forEach { addPath(it) }
        }

    private val battery = path(DashboardPaths.battery0)

    private val thermometer = path(DashboardPaths.thermometer0)

    private val thermometerStem = path(DashboardPaths.thermometer1)

    private val parking = path(DashboardPaths.parking0)

    private val arrow = path(DashboardPaths.turn0)

    private val lowBeam = path(DashboardPaths.lowBeam0)

    private val highBeam = path(DashboardPaths.highBeam0)

    private val speaker = path(DashboardPaths.speaker0)

    private val speakerWaves = path(DashboardPaths.speaker1)

    private val connectors =
        path(
            "M1372 189H1520L1554 222M1945 189H1780L1743 222M1372 514H1520L1554 480M1945 514H1780L1743 480"
        )

    private val doorLines =
        arrayOf(path("M1403 313H1450L1484 337"), path("M1914 313H1854L1820 337"))

    private fun bitmap(name: String) =
        context.assets.open(name).use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888

                    inSampleSize = if (name.startsWith("car-")) 2 else 4
                },
            )!!
        }

    private val cars = arrayOf(bitmap("car-closed.png"), bitmap("car-open.png"))

    private val covers = controller.audio.tracks.map { bitmap(it.cover) }

    private val carBounds = RectF(0f, 0f, 1254f, 1254f)

    private val coverBounds = RectF(1374f, 621f, 1510f, 757f)

    private val coverClip = Path().apply { addRoundRect(coverBounds, 5f, 5f, Path.Direction.CW) }

    private val background =
        RadialGradient(
                1020f,
                296f,
                1100f,
                intArrayOf(0xff03090c.toInt(), BG, 0xff010303.toInt()),
                floatArrayOf(0f, .48f, 1f),
                Shader.TileMode.CLAMP,
            )
            .apply { setLocalMatrix(Matrix().apply { setScale(1f, .48f, 1020f, 296f) }) }

    private val backgrounds =
        Array(2) { i ->
            RadialGradient(
                    1000f,
                    280.25f,
                    307f,
                    intArrayOf(
                        alpha(if (i == 0) 0xff285b70.toInt() else 0xff79512f.toInt(), .09f),
                        0x08031117,
                        alpha(if (i == 0) 0xff285b70.toInt() else 0xff79512f.toInt(), .34f),
                    ),
                    floatArrayOf(0f, .86f, 1f),
                    Shader.TileMode.CLAMP,
                )
                .apply { setLocalMatrix(Matrix().apply { setScale(1f, .75f, 1000f, 280.25f) }) }
        }

    private val warning =
        RadialGradient(
            1000f,
            335f,
            215f,
            intArrayOf(0x45ff303f, 0x24b81826, 0x00ff303f),
            floatArrayOf(0f, .5f, 1f),
            Shader.TileMode.CLAMP,
        )

    private val doorWarning =
        RadialGradient(
            0f,
            0f,
            106f,
            intArrayOf(0xa6ff142f.toInt(), 0x61ff142f, 0x00ff142f),
            floatArrayOf(0f, .65f, 1f),
            Shader.TileMode.CLAMP,
        )

    private val aura =
        Array(2) { i ->
            RadialGradient(
                1000f,
                400f,
                1050f,
                intArrayOf(0, alpha(if (i == 0) CYAN else ORANGE, .6f), 0),
                floatArrayOf(0f, .5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

    private val header =
        Array(2) { i ->
            Array(2) { side ->
                val a = if (i == 0) CYAN else ORANGE

                val s = if (i == 0) 0xffabd9e7.toInt() else 0xfff2cda9.toInt()

                LinearGradient(
                    if (side == 0) 50f else 1950f,
                    77f,
                    if (side == 0) 610f else 1390f,
                    77f,
                    intArrayOf(alpha(s, .9f), alpha(a, .65f), alpha(a, 0f)),
                    floatArrayOf(0f, .65f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
        }

    private val rim =
        Array(2) { i ->
            LinearGradient(
                1000f,
                50f,
                1000f,
                664f,
                if (i == 0) 0xffabd9e7.toInt() else 0xfff2cda9.toInt(),
                if (i == 0) CYAN else ORANGE,
                Shader.TileMode.CLAMP,
            )
        }

    private val fills =
        Array(2) { i ->
            LinearGradient(
                0f,
                0f,
                0f,
                61f,
                intArrayOf(
                    alpha(if (i == 0) 0xff285b70.toInt() else 0xff79512f.toInt(), .42f),
                    0x1f020e12,
                    alpha(if (i == 0) 0xff285b70.toInt() else 0xff79512f.toInt(), .35f),
                ),
                floatArrayOf(0f, .55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

    // Blur is baked once on a software bitmap; hardware drawing only composites it.

    private val dialGlow =
        Array(3) { i ->
            glow(650, 650, if (i == 0) CYAN else if (i == 1) ORANGE else RED, 7f, 14f) { c, paint ->
                c.drawArc(18f, 18f, 632f, 632f, 150f, 240f, false, paint)
            }
        }

    private val modeGlow =
        Array(2) { i ->
            glow(224, 95, if (i == 0) CYAN else ORANGE, 4f, 2f) { c, paint ->
                c.drawRoundRect(17f, 17f, 207f, 78f, 22f, 22f, paint)
            }
        }

    private val gearGlow =
        Array(2) { i ->
            glow(106, 88, if (i == 0) CYAN else ORANGE, 4f, 2f) { c, paint ->
                c.drawRoundRect(17f, 17f, 89f, 71f, 12f, 12f, paint)
            }
        }

    private val needleGlow =
        glow(60, 49, 0xffd6f8ff.toInt(), 5f, 7f) { c, paint ->
            c.drawLine(17f, 32f, 43f, 17f, paint)
        }

    private var speedText = "0"

    private var speedInt = 0

    private var gText = "0.00 G"

    private var lastG = -1

    private var clockText = ""

    private val clockFormat = SimpleDateFormat("HH:mm", Locale.ROOT)

    private val date = Date()

    private var stateDirty = false
    private var cacheTime = 0L

    private var trackPosition = 0L

    private var trackDuration = 0L

    private var elapsedText = "0:00"

    private var durationText = "0:00"

    private var volumeText = "15%"

    private var trackIndex = 0

    private var outsideText = "24°C"

    private var limitText = "50"

    private val batteryText = Array(4) { "" }

    private val temperatureText = Array(4) { "" }

    private val values = Array(7) { "" }

    private var rangeText = "312"

    private val rowLabels = arrayOf("RECOVERED", "TRIP", "AVG CONSUMPTION", "RANGE")

    init {

        isFocusable = false

        isFocusableInTouchMode = false

        contentDescription =
            "Minini automotive dashboard. Receives vehicle telemetry from the BRIDGE."

        for (i in 0..100) {

            val angle = Math.toRadians(-210.0 + i * 2.4)

            val r = if (i % 10 == 0) 272f else if (i % 5 == 0) 277f else 282f

            ticks[i * 4] = 1000f + cos(angle).toFloat() * 292

            ticks[i * 4 + 1] = 357f + sin(angle).toFloat() * 292

            ticks[i * 4 + 2] = 1000f + cos(angle).toFloat() * r

            ticks[i * 4 + 3] = 357f + sin(angle).toFloat() * r
        }

        for (i in 0..10) {

            val a = Math.toRadians(-210.0 + i * 24)

            labelXY[i * 2] = 1000 + cos(a).toFloat() * 247

            labelXY[i * 2 + 1] = 357 + sin(a).toFloat() * 247 + 10
        }

        controller.onState = { old, new -> stateChanged(old, new) }

        cacheState(controller.state)
    }

    private fun glow(
        w: Int,
        h: Int,
        color: Int,
        blur: Float,
        width: Float,
        draw: (Canvas, Paint) -> Unit,
    ): Bitmap {

        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color

                style = Paint.Style.STROKE

                strokeWidth = width

                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }

        draw(Canvas(b), paint)

        return b
    }

    private fun stateChanged(old: VehicleState, s: VehicleState) {

        val time = SystemClock.elapsedRealtimeNanos()

        if (old.mode != s.mode) {

            theme.target(if (s.mode == DriveMode.SPORT) 1f else 0f, time, 850, easeOut = false)

            for (i in rows.indices) rows[i].target(
                if (i < 4) s.mode == DriveMode.COMFORT else s.mode == DriveMode.SPORT,
                if (i < 4) i else i - 4,
                time,
            )

            for (i in 0..1) modeAlpha[i].target(if (s.mode.ordinal == i) 1f else 0f, time, 560)

            modeTime = time
        }

        if (old.gear != s.gear) {

            gearSweepFrom = gearX.sample(time)

            gearSweepTo = 917f + s.gear.ordinal * 85

            gearX.target(gearSweepTo, time, 580)

            for (i in 0..2) gearAlpha[i].target(if (i == s.gear.ordinal) 1f else 0f, time, 450)

            gearTime = time
        }

        if (old.leftDoor != s.leftDoor) doorAlpha[0].target(if (s.leftDoor) 1f else 0f, time, 250)

        if (old.rightDoor != s.rightDoor)
            doorAlpha[1].target(if (s.rightDoor) 1f else 0f, time, 250)

        cacheState(s)
    }

    private fun cacheState(s: VehicleState) {

        outsideText = if (s.has("outsideC")) "${s.outsideC.roundToInt()}°C" else "—°C"

        limitText = if (s.has("speedLimit")) s.speedLimit.toString() else "—"

        for (i in 0..3) {

            batteryText[i] =
                if (s.has("wheels.${TelemetryProtocol.wheelNames[i]}.battery"))
                    "${s.wheels[i].battery.roundToInt()}%"
                else "—%"

            temperatureText[i] =
                if (s.has("wheels.${TelemetryProtocol.wheelNames[i]}.temperature"))
                    "${s.wheels[i].temperature.roundToInt()}°C"
                else "—°C"
        }

        values[0] = format(s.recoveredKwh, 1)

        values[1] = "+${format(s.recoveredKm,1)}"

        values[2] = format(s.tripKm, 1)

        values[3] = format(s.tripKwh, 1)

        values[4] = format(s.averageKwh, 1)

        values[5] = s.rangeKm.roundToInt().toString()

        values[6] = format(s.remainingKwh, 2).replace('.', ',')

        val energyKeys =
            listOf(
                "recoveredKwh",
                "recoveredKm",
                "tripKm",
                "tripKwh",
                "averageKwh",
                "rangeKm",
                "remainingKwh",
            )

        for (i in values.indices) if (!s.has(energyKeys[i])) values[i] = "—"

        rangeText = values[5]
    }

    fun resume() {

        if (running) return

        running = true

        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pause() {

        running = false

        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {

        if (!running) return

        now = SystemClock.elapsedRealtimeNanos()

        if (stateDirty) {
            cacheState(controller.state)
            stateDirty = false
        }
        controller.frame(now)

        frameCount++

        val blend = theme.sample(now)

        accent = mix(CYAN, ORANGE, blend)

        soft = mix(0xffabd9e7.toInt(), 0xfff2cda9.toInt(), blend)

        dim = mix(0xff285b70.toInt(), 0xff79512f.toInt(), blend)

        rows.forEach { it.sample(now) }

        gearX.sample(now)

        gearAlpha.forEach { it.sample(now) }

        doorAlpha.forEach { it.sample(now) }

        modeAlpha.forEach { it.sample(now) }

        val speed = controller.displaySpeed.roundToInt()

        if (speed != speedInt) {

            speedInt = speed

            speedText = speed.toString()
        }

        val g = (hypot(controller.displayGx, controller.displayGy) * 100).roundToInt()

        if (g != lastG) {

            lastG = g

            gText = format(g / 100f, 2) + " G"
        }

        if (now >= cacheTime) {

            date.time = System.currentTimeMillis()

            clockText =
                if (controller.state.has("clock"))
                    controller.state.clock ?: clockFormat.format(date)
                else "—:—"

            val audio = controller.audio

            trackPosition = audio.positionMs

            trackDuration = audio.durationMs

            elapsedText = time(trackPosition)

            durationText = time(trackDuration)

            volumeText = "${(audio.volume*100).roundToInt()}%"

            trackIndex = audio.trackIndex

            cacheTime = now + 100_000_000
        }

        invalidate()

        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDetachedFromWindow() {

        pause()

        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {

        scale = min(w / 2000f, h / 800f)

        left = (w - 2000 * scale) / 2

        top = (h - 800 * scale) / 2
    }

    fun screenX(x: Float) = left + x * scale

    fun screenY(y: Float) = top + y * scale

    // Every frame paints the entire surface, including the letterbox area.

    override fun isOpaque() = true

    override fun onDraw(c: Canvas) {

        c.drawColor(BG)

        c.save()

        c.translate(left, top)

        c.scale(scale, scale)

        fill(BG)

        p.shader = background

        c.drawRect(0f, 0f, 2000f, 800f, p)

        p.shader = null

        drawAura(c)

        drawHeader(c)

        for (i in 0..3) withPanel(c, i, 210f + i * 130) { drawEnergy(c, i) }

        withPanel(c, 4, 316f) { drawG(c) }

        withPanel(c, 5, 650f) {
            text(c, "RANGE", 260f, 628f, 22f, soft, spacing = .07f)

            valueUnit(c, rangeText, "km", 257f, 686f, 61f, 35f, PRIMARY, 12f)
        }

        drawDial(c)

        drawGear(c)

        drawCar(c)

        drawLights(c)

        drawMode(c)

        drawMusic(c)

        c.restore()
    }

    private fun fill(color: Int, opacity: Float = 1f) {

        p.reset()

        p.isAntiAlias = true

        p.isFilterBitmap = true

        p.color = alpha(color, opacity * panelAlpha)

        p.style = Paint.Style.FILL
    }

    private fun stroke(color: Int, width: Float = 1f, opacity: Float = 1f) {

        fill(color, opacity)

        p.style = Paint.Style.STROKE

        p.strokeWidth = width
    }

    private fun line(
        c: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: Int,
        width: Float = 1f,
        opacity: Float = 1f,
    ) {

        stroke(color, width, opacity)

        c.drawLine(x1, y1, x2, y2, p)
    }

    private fun text(
        c: Canvas,
        s: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int = PRIMARY,
        align: Paint.Align = Paint.Align.LEFT,
        weight: Int = 400,
        opacity: Float = 1f,
        spacing: Float = 0f,
    ) {

        fill(color, opacity)

        p.textSize = size

        p.typeface = if (weight == 300) light else if (weight >= 500) bold else regular

        p.textAlign = align

        p.fontFeatureSettings = "tnum,lnum"

        if (spacing == 0f) c.drawText(s, x, y, p)
        else {

            val width = p.measureText(s) + max(0, s.length - 1) * size * spacing

            var px =
                when (align) {
                    Paint.Align.CENTER -> x - width / 2

                    Paint.Align.RIGHT -> x - width

                    else -> x
                }

            p.textAlign = Paint.Align.LEFT

            for (i in s.indices) {

                c.drawText(s, i, i + 1, px, y, p)

                px += p.measureText(s, i, i + 1) + size * spacing
            }
        }
    }

    private fun valueUnit(
        c: Canvas,
        s: String,
        unit: String,
        x: Float,
        y: Float,
        size: Float,
        unitSize: Float,
        color: Int,
        gap: Float = 10f,
    ) {

        text(c, s, x, y, size, color, weight = 300)

        val width = p.measureText(s)

        text(c, unit, x + width + gap, y, unitSize, soft, weight = 300)
    }

    private fun drawHeader(c: Canvas) {

        text(c, clockText, 49f, 58f, 47f, weight = 300, spacing = .032f)

        text(c, "TIME", 49f, 98f, 17f, soft, spacing = .118f)

        text(c, outsideText, 1950f, 58f, 42f, align = Paint.Align.RIGHT, weight = 300)

        text(c, "OUTSIDE", 1950f, 98f, 17f, soft, Paint.Align.RIGHT, spacing = .118f)

        val t = theme.value

        for (side in 0..1) for (i in 0..1) {

            stroke(Color.WHITE, 1.6f, if (i == 0) 1 - t else t)

            p.shader = header[i][side]

            c.drawLine(if (side == 0) 50f else 1950f, 77f, if (side == 0) 610f else 1390f, 77f, p)

            p.shader = null
        }

        val phase = ((now - modeTime) / 800_000_000f)

        if (phase in 0f..1f)
            for (side in 0..1) {

                val x = if (side == 0) 50 + 560 * phase else 1950 - 560 * phase

                line(
                    c,
                    x,
                    77f,
                    x + (if (side == 0) 70 else -70),
                    77f,
                    soft,
                    3f,
                    sin(phase * PI).toFloat(),
                )
            }
    }

    private inline fun withPanel(c: Canvas, i: Int, pivot: Float, draw: () -> Unit) {

        val pose = rows[i]

        if (pose.alpha.value < .001f) return

        panelAlpha = pose.alpha.value

        c.save()

        c.translate(pose.x.value, pose.y.value)

        c.scale(pose.scale.value, pose.scale.value, 338f, pivot)

        draw()

        c.restore()

        panelAlpha = 1f
    }

    private fun drawEnergy(c: Canvas, i: Int) {

        val y = 222f + i * 130

        text(c, rowLabels[i], 115f, y - 50, 21f, soft, spacing = .105f)

        when (i) {
            0 -> {

                valueUnit(c, values[0], "kWh", 112f, y, 45f, 25f, PRIMARY)

                valueUnit(c, values[1], "km", 355f, y, 36f, 25f, accent, 9f)
            }

            1 -> {

                valueUnit(c, values[2], "km", 112f, y, 45f, 25f, PRIMARY)

                valueUnit(c, values[3], "kWh", 355f, y, 36f, 25f, PRIMARY, 9f)
            }

            2 -> valueUnit(c, values[4], "kWh/100 km", 112f, y, 45f, 25f, PRIMARY, 12f)

            3 -> {

                valueUnit(c, values[5], "km", 112f, y, 45f, 25f, PRIMARY)

                valueUnit(c, values[6], "kWh", 355f, y, 36f, 25f, PRIMARY, 9f)
            }
        }

        if (i < 3) line(c, 115f, y + 38, 575f, y + 38, accent, 1f, .22f)
    }

    private fun drawG(c: Canvas) {

        stroke(dim, 1.2f)

        for (i in 1..5) c.drawCircle(338f, 316f, i * .25f / 1.3f * 168, p)

        stroke(accent, 1.5f)

        c.drawCircle(338f, 316f, 168f, p)

        line(c, 157f, 316f, 519f, 316f, accent, 1.2f, .8f)

        line(c, 338f, 135f, 338f, 497f, accent, 1.2f, .8f)

        text(c, "1.3 G", 338f, 130f, 21f, soft, Paint.Align.CENTER)

        text(c, "−1.3 G", 144f, 322f, 21f, soft, Paint.Align.RIGHT)

        text(c, "1.3 G", 532f, 322f, 21f, soft)

        text(c, "−1.3 G", 338f, 509f, 21f, soft, Paint.Align.CENTER)

        val magnitude = hypot(controller.displayGx, controller.displayGy)

        val factor = 168f / max(1.3f, magnitude)

        val x = 338f + controller.displayGx * factor

        val y = 316f + controller.displayGy * factor

        if (controller.state.has("lateralG") && controller.state.has("longitudinalG")) {

            fill(accent, .10f)

            c.drawCircle(x, y, 15f, p)

            fill(accent)

            c.drawCircle(x, y, 8.2f, p)
        }

        text(
            c,
            if (controller.state.has("lateralG") && controller.state.has("longitudinalG")) gText
            else "— G",
            338f,
            556f,
            46f,
            soft,
            Paint.Align.CENTER,
            300,
        )
    }

    private fun drawDial(c: Canvas) {

        val t = theme.value

        val over = controller.state.overspeed

        for (i in 0..1) {

            fill(Color.WHITE, if (i == 0) 1 - t else t)

            p.shader = backgrounds[i]

            c.drawPath(surface, p)

            p.shader = null
        }

        if (over) {

            fill(Color.WHITE, .40f)

            c.drawBitmap(dialGlow[2], 675f, 32f, p)
        } else
            for (i in 0..1) {

                fill(Color.WHITE, (if (i == 0) 1 - t else t) * .35f)

                c.drawBitmap(dialGlow[i], 675f, 32f, p)
            }

        stroke(if (over) RED else dim, 12f, .35f)

        c.drawArc(arc, 150f, 240f, false, p)

        if (over) {

            stroke(RED, 3f)

            c.drawArc(arc, 150f, 240f, false, p)
        } else
            for (i in 0..1) {

                stroke(Color.WHITE, 3f, if (i == 0) 1 - t else t)

                p.shader = rim[i]

                c.drawArc(arc, 150f, 240f, false, p)

                p.shader = null
            }

        for (i in 0..100) {

            stroke(
                if (i % 5 == 0) soft else accent,
                if (i % 10 == 0) 3f else if (i % 5 == 0) 2f else 1.4f,
                if (i % 5 == 0) 1f else .8f,
            )

            c.drawLines(ticks, i * 4, 4, p)
        }

        for (i in 0..10) text(
            c,
            labels[i],
            labelXY[i * 2],
            labelXY[i * 2 + 1],
            31f,
            soft,
            Paint.Align.CENTER,
        )

        c.save()

        c.clipRect(790f, 140f, 1210f, 503f)

        stroke(accent, 2f)

        c.drawCircle(1000f, 357f, 194f, p)

        stroke(dim, 2f, .7f)

        c.drawCircle(1000f, 357f, 183f, p)

        c.restore()

        if (controller.state.has("speedKmh")) {

            stroke(if (over) RED else accent, 3f, .8f)

            c.drawArc(arc, 150f, controller.displaySpeed.coerceIn(0f, 200f) * 1.2f, false, p)

            c.save()

            c.rotate(controller.displaySpeed.coerceIn(0f, 200f) * 1.2f, 1000f, 357f)

            fill(Color.WHITE, .85f)

            c.drawBitmap(needleGlow, 718f, 478f, p)

            line(c, 735f, 510f, 761f, 495f, soft, 7f)

            c.restore()
        }

        if (over && t < 1f) {

            fill(Color.WHITE, 1 - t)

            p.shader = warning

            c.drawCircle(1000f, 335f, 215f, p)

            p.shader = null
        }

        text(
            c,
            if (controller.state.has("speedKmh")) speedText else "—",
            1000f,
            378f,
            154f,
            if (over) mix(RED, PRIMARY, t) else mix(0xffc5e7f2.toInt(), 0xffece4d6.toInt(), t),
            Paint.Align.CENTER,
        )

        text(c, "km/h", 1000f, 419f, 33f, accent, Paint.Align.CENTER, 300)

        fill(0xffe8242b.toInt())

        c.drawCircle(1102f, 449f, 43f, p)

        fill(0xfff9fbfd.toInt())

        c.drawCircle(1102f, 449f, 34f, p)

        text(c, limitText, 1102f, 464f, 42f, 0xff03111b.toInt(), Paint.Align.CENTER, 600)

        line(c, 857f, 506f, 1140f, 506f, accent, 1f, .65f)
    }

    private fun activeBox(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float) {

        c.save()

        c.translate(x, y)

        for (i in 0..1) {

            fill(Color.WHITE, if (i == 0) 1 - theme.value else theme.value)

            p.shader = fills[i]

            c.drawRoundRect(0f, 0f, w, h, r, r, p)

            p.shader = null
        }

        stroke(accent, 2f)

        c.drawRoundRect(0f, 0f, w, h, r, r, p)

        c.restore()
    }

    private fun drawGear(c: Canvas) {

        if (!controller.state.has("gear")) {
            text(c, "—", 1000f, 556f, 37f, soft, Paint.Align.CENTER)
            return
        }

        if (controller.state.has("parking") && controller.state.parking) {

            c.save()

            c.translate(786f, 515f)

            c.scale(60f / 64, 47f / 50)

            stroke(RED, 2.8f)

            c.drawCircle(32f, 25f, 18f, p)

            stroke(RED, 3f)

            p.strokeCap = Paint.Cap.ROUND

            c.drawPath(parking, p)

            c.restore()
        }

        for (i in 0..1) {

            fill(Color.WHITE, if (i == 0) 1 - theme.value else theme.value)

            c.drawBitmap(gearGlow[i], gearX.value - 53f, 500f, p)
        }

        activeBox(c, gearX.value - 36f, 517f, 72f, 54f, 12f)

        for (i in 0..2) {

            val a = gearAlpha[i].value

            text(
                c,
                GEARS[i],
                917f + i * 85,
                556f - (1 - a) * 2,
                37f,
                mix(0xff506775.toInt(), 0xffedfbff.toInt(), a),
                Paint.Align.CENTER,
            )
        }

        val phase = (now - gearTime) / 800_000_000f

        if (phase in 0f..1f)
            line(
                c,
                gearSweepFrom + (gearSweepTo - gearSweepFrom) * phase,
                580f,
                gearSweepFrom + (gearSweepTo - gearSweepFrom) * (phase + .25f).coerceAtMost(1f),
                580f,
                soft,
                3f,
                sin(phase * PI).toFloat(),
            )
    }

    private fun drawCar(c: Canvas) {

        c.save()

        c.translate(1446f, 152.5f)

        c.scale(.3189792663f, .3189792663f)

        c.save()

        c.clipPath(body)

        fill(Color.WHITE)

        c.drawBitmap(cars[0], null, carBounds, p)

        c.restore()

        for (i in 0..1) if (doorAlpha[i].value > 0) {

            c.save()

            c.clipRect(if (i == 0) 0f else 627f, -40f, if (i == 0) 627f else 1254f, 1294f)

            c.clipPath(openOutline)

            fill(Color.WHITE, doorAlpha[i].value)

            c.drawBitmap(cars[1], null, carBounds, p)

            c.restore()
        }

        c.restore()

        val s = controller.state

        if (s.doorAlarm)
            for (i in 0..1) if (if (i == 0) s.leftDoor else s.rightDoor) {

                val pulse = (.35 + .65 * (.5 + .5 * cos(now / 1e9 * 2 * PI))).toFloat()

                c.save()

                c.translate(if (i == 0) 1530f else 1760f, 350f)

                c.scale(92f / 106, 1f)

                fill(Color.WHITE, pulse)

                p.shader = doorWarning

                c.drawCircle(0f, 0f, 106f, p)

                p.shader = null

                c.restore()

                c.save()

                c.translate(1446f, 152.5f)

                c.scale(.3189792663f, .3189792663f)

                fill(RED, .4f * pulse)

                c.drawPath(wings[i], p)

                stroke(RED, 26f, pulse)

                c.drawPath(wings[i], p)

                c.restore()
            }

        stroke(accent, 1.6f)

        c.drawPath(connectors, p)

        for (i in 0..3) drawWheel(c, i, if (i % 2 == 0) 1370f else 1810f, if (i < 2) 151f else 476f)

        for (i in 0..1) if (doorAlpha[i].value > 0) {

            panelAlpha = doorAlpha[i].value

            text(
                c,
                if (i == 0) "L OPEN" else "R OPEN",
                if (i == 0) 1373f else 1944f,
                301f,
                24f,
                AMBER,
                if (i == 0) Paint.Align.LEFT else Paint.Align.RIGHT,
            )

            stroke(AMBER, 1.5f)

            c.drawPath(doorLines[i], p)

            panelAlpha = 1f
        }
    }

    private fun drawWheel(c: Canvas, i: Int, x: Float, y: Float) {

        val w = controller.state.wheels[i]

        val batteryKnown = controller.state.has("wheels.${TelemetryProtocol.wheelNames[i]}.battery")

        val color =
            if (!batteryKnown) dim
            else if (w.battery <= 5) RED else if (w.battery < 25) AMBER else 0xffabd9e7.toInt()

        c.save()

        c.translate(x, y)

        c.save()

        c.scale(64f / 66, 1f)

        stroke(color, 2.2f)

        c.drawPath(battery, p)

        c.restore()

        fill(color)

        if (batteryKnown)
            c.drawRoundRect(7f, 7f, 7 + 48 * w.battery.coerceIn(0f, 100f) / 100, 25f, 1.5f, 1.5f, p)

        text(c, batteryText[i], 82f, 26f, 27f, if (w.battery < 25) color else PRIMARY, weight = 300)

        c.save()

        c.translate(0f, 49f)

        c.scale(27f / 30, 47f / 50)

        stroke(soft, 2.5f)

        c.drawPath(thermometer, p)

        stroke(soft, 3f)

        p.strokeCap = Paint.Cap.ROUND

        c.drawPath(thermometerStem, p)

        fill(soft)

        c.drawCircle(16f, 40f, 5f, p)

        c.restore()

        text(c, "MOTOR", 49f, 66f, 16f, soft, spacing = .06f)

        text(c, temperatureText[i], 49f, 95f, 27f, soft, weight = 300)

        c.restore()
    }

    private fun drawLights(c: Canvas) {

        val s = controller.state

        if (controller.blinkOn) {

            if (s.leftTurn || s.hazards)
                symbol(c, arrow, 213f, 717f, 36f / 42, 31f / 36, GREEN, true)

            if (s.rightTurn || s.hazards) {

                c.save()

                c.scale(-1f, 1f)

                symbol(c, arrow, -457f, 717f, 36f / 42, 31f / 36, GREEN, true)

                c.restore()
            }
        }

        if (s.lowBeam) symbol(c, lowBeam, 276f, 716f, 45f / 48, 34f / 36, GREEN)

        if (s.highBeam) symbol(c, highBeam, 347f, 716f, 45f / 48, 34f / 36, 0xff00b4ff.toInt())
    }

    private fun symbol(
        c: Canvas,
        path: Path,
        x: Float,
        y: Float,
        sx: Float,
        sy: Float,
        color: Int,
        filled: Boolean = false,
    ) {

        c.save()

        c.translate(x, y)

        c.scale(sx, sy)

        if (filled) fill(color) else stroke(color, 2.1f)

        p.strokeCap = Paint.Cap.ROUND

        c.drawPath(path, p)

        c.restore()
    }

    private fun drawMode(c: Canvas) {

        line(c, 746f, 624f, 950f, 624f, accent, 1f, .4f)

        line(c, 1050f, 624f, 1254f, 624f, accent, 1f, .4f)

        text(c, "MODE", 1000f, 632f, 22f, soft, Paint.Align.CENTER, spacing = .09f)

        for (i in 0..1) {

            fill(Color.WHITE, if (i == 0) 1 - theme.value else theme.value)

            c.drawBitmap(modeGlow[i], 888f, 635f, p)
        }

        activeBox(c, 905f, 652f, 190f, 61f, 22f)

        val phase = (now - modeTime) / 900_000_000f

        if (phase in 0f..1f) {

            stroke(soft, 2f, sin(phase * PI).toFloat())

            c.drawRoundRect(
                899 - phase * 4,
                646 - phase * 3,
                1101 + phase * 4,
                719 + phase * 3,
                27f,
                27f,
                p,
            )
        }

        for (i in 0..1) {

            val a = modeAlpha[i].value

            if (a > .001f)
                text(
                    c,
                    if (controller.state.has("mode")) MODES[i] else "—",
                    1000f,
                    693 + (1 - a) * 8,
                    29f,
                    PRIMARY,
                    Paint.Align.CENTER,
                    opacity = a,
                )
        }

        val launch = controller.state.launch

        if (launch != Launch.OFF)
            text(
                c,
                if (launch == Launch.ARMED) "LAUNCH CONTROL AVAILABLE" else "LAUNCH CONTROL ACTIVE",
                1000f,
                742f,
                19f,
                if (controller.state.mode == DriveMode.COMFORT) RED else accent,
                Paint.Align.CENTER,
                spacing = .021f,
            )
    }

    private fun drawAura(c: Canvas) {

        val phase = (now - modeTime) / 1_200_000_000f

        if (phase !in 0f..1f) return

        val size = .15f + phase * 1.15f

        c.save()

        c.scale(size, size * .4f, 1000f, 400f)

        fill(Color.WHITE, sin(phase * PI).toFloat() * .14f)

        p.shader = aura[controller.state.mode.ordinal]

        c.drawCircle(1000f, 400f, 1050f, p)

        p.shader = null

        c.restore()
    }

    private fun drawMusic(c: Canvas) {

        val audio = controller.audio

        line(c, 1372f, 602f, 1949f, 602f, accent, 1f, .6f)

        c.save()

        c.clipPath(coverClip)

        fill(Color.WHITE)

        c.drawBitmap(covers[trackIndex], null, coverBounds, p)

        c.restore()

        stroke(accent, 1f, .8f)

        c.drawRoundRect(coverBounds, 5f, 5f, p)

        if (!audio.playing) {

            fill(0xff061017.toInt(), .82f)

            c.drawCircle(1442f, 689f, 22f, p)

            line(c, 1435f, 680f, 1435f, 698f, 0xffd6f8ff.toInt(), 5f)

            line(c, 1449f, 680f, 1449f, 698f, 0xffd6f8ff.toInt(), 5f)
        }

        c.save()

        c.clipRect(1540f, 621f, 1950f, 696f)

        text(c, audio.tracks[trackIndex].title, 1544f, 654f, 30f)

        text(c, audio.tracks[trackIndex].artist, 1544f, 689f, 24f, soft, spacing = .042f)

        c.restore()

        text(c, elapsedText, 1544f, 716f, 21f, soft)

        text(c, durationText, 1950f, 716f, 21f, soft, Paint.Align.RIGHT)

        fill(dim)

        c.drawRoundRect(1622f, 705f, 1868f, 709f, 2f, 2f, p)

        if (trackDuration > 0) {

            fill(accent)

            c.drawRoundRect(
                1622f,
                705f,
                1622f + 246 * (trackPosition.toFloat() / trackDuration).coerceIn(0f, 1f),
                709f,
                2f,
                2f,
                p,
            )
        }

        c.save()

        c.translate(1544f, 733f)

        c.scale(40f / 42, 36f / 38)

        fill(soft)

        c.drawPath(speaker, p)

        stroke(soft, 2.4f)

        p.strokeCap = Paint.Cap.ROUND

        c.drawPath(speakerWaves, p)

        c.restore()

        val vx = 1602 + 238 * audio.volume

        stroke(dim, 7f)

        p.strokeCap = Paint.Cap.ROUND

        c.drawLine(1602f, 751f, 1840f, 751f, p)

        stroke(accent, 7f)

        p.strokeCap = Paint.Cap.ROUND

        c.drawLine(1602f, 751f, vx, 751f, p)

        fill(accent)

        c.drawCircle(vx, 751f, 10.5f, p)

        text(c, volumeText, 1950f, 762f, 29f, soft, Paint.Align.RIGHT, 300)
    }

    companion object {

        const val BG = 0xff010405.toInt()

        const val PRIMARY = 0xffdeeff8.toInt()

        const val CYAN = 0xff43e9fa.toInt()

        const val ORANGE = 0xffff9b42.toInt()

        const val RED = 0xffff343b.toInt()

        const val AMBER = 0xffffc55a.toInt()

        const val GREEN = 0xff00df54.toInt()

        private val GEARS = arrayOf("R", "N", "D")

        private val MODES = arrayOf("COMFORT", "SPORT")

        fun alpha(color: Int, a: Float) =
            (color and 0x00ffffff) or
                (((Color.alpha(color) * a).roundToInt().coerceIn(0, 255)) shl 24)

        fun mix(a: Int, b: Int, t: Float) =
            Color.rgb(
                (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt(),
            )

        private fun format(v: Number, n: Int) = String.format(Locale.ROOT, "%.${n}f", v.toDouble())

        private fun time(ms: Long): String {

            val sec = ms / 1000

            return "${sec/60}:${(sec%60).toString().padStart(2,'0')}"
        }
    }
}
