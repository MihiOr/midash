package com.mihior.midash

enum class DriveMode {
    COMFORT,
    SPORT,
}

enum class Gear {
    R,
    N,
    D,
}

enum class Launch {
    OFF,
    ARMED,
    ACTIVE,
}

data class Wheel(val battery: Float, val temperature: Float)

/** Immutable sensor sample. Time is Android elapsedRealtimeNanos, never wall-clock time. */
data class VehicleState(
    val timeNanos: Long = 0,
    val known: Set<String> = emptySet(),
    val speedKmh: Float = 0f,
    val longitudinalG: Float = 0f,
    val lateralG: Float = 0f,
    val mode: DriveMode = DriveMode.COMFORT,
    val gear: Gear = Gear.N,
    val parking: Boolean = true,
    val speedLimit: Int = 50,
    val leftDoor: Boolean = false,
    val rightDoor: Boolean = false,
    val leftTurn: Boolean = false,
    val rightTurn: Boolean = false,
    val hazards: Boolean = false,
    val lowBeam: Boolean = false,
    val highBeam: Boolean = false,
    val throttle: Float = 0f,
    val brake: Float = 0f,
    val launch: Launch = Launch.OFF,
    val outsideC: Float = 0f,
    val clock: String? = null,
    val powerKw: Float = 0f,
    val steering: Float = 0f,
    val wheels: List<Wheel> = List(4) { Wheel(0f, 0f) },
    val recoveredKwh: Double = 0.0,
    val recoveredKm: Double = 0.0,
    val tripKm: Double = 0.0,
    val tripKwh: Double = 0.0,
    val averageKwh: Double = 0.0,
    val rangeKm: Double = 0.0,
    val remainingKwh: Double = 0.0,
) {
    fun has(field: String) = field in known

    val doorAlarm
        get() =
            ((has("leftDoor") && leftDoor) || (has("rightDoor") && rightDoor)) &&
                ((has("speedKmh") && speedKmh > .1f) ||
                    (has("gear") && has("throttle") && gear != Gear.N && throttle > 0f))

    val overspeed
        get() = has("speedKmh") && has("speedLimit") && speedKmh > speedLimit
}
