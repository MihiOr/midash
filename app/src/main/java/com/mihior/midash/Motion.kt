package com.mihior.midash

/** Retarget samples the current pose, so reversing never jumps back to an old endpoint. */
class Motion(initial: Float) {
    var value = initial
        private set

    private var from = initial
    private var to = initial
    private var start = 0L
    private var duration = 1L
    private var entering = true

    fun target(
        next: Float,
        now: Long,
        milliseconds: Long,
        delay: Long = 0,
        easeOut: Boolean = true,
    ) {
        sample(now)
        from = value
        to = next
        start = now + delay * 1_000_000
        duration = (milliseconds * 1_000_000).coerceAtLeast(1)
        entering = easeOut
        if (milliseconds == 0L && delay == 0L) {
            value = next
            from = next
        }
    }

    fun sample(now: Long): Float {
        val t = ((now - start).toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
        val eased = if (entering) 1 - (1 - t) * (1 - t) * (1 - t) else t * t * (3 - 2 * t)
        value = from + (to - from) * eased
        return value
    }
}

class PanelPose(visible: Boolean, private val sport: Boolean) {
    val alpha = Motion(if (visible) 1f else 0f)
    val x = Motion(if (visible) 0f else 36f)
    val y = Motion(if (visible) 0f else if (sport) 8f else 22f)
    val scale = Motion(if (visible || !sport) 1f else .94f)

    fun target(show: Boolean, index: Int, now: Long) {
        val delay = if (show) 250L + index * 95 else index * 30L
        val duration = if (show) 720L else 270L
        if (show && alpha.sample(now) < .001f) {
            x.target(36f, now, 0)
            y.target(if (sport) 8f else 22f, now, 0)
            scale.target(if (sport) .94f else 1f, now, 0)
        }
        alpha.target(if (show) 1f else 0f, now, duration, delay, show)
        x.target(if (show) 0f else -26f, now, duration, delay, show)
        y.target(if (show) 0f else -12f, now, duration, delay, show)
        scale.target(if (show || !sport) 1f else .94f, now, duration, delay, show)
    }

    fun sample(now: Long) {
        alpha.sample(now)
        x.sample(now)
        y.sample(now)
        scale.sample(now)
    }
}
