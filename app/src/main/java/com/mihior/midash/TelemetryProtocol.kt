package com.mihior.midash

import org.json.JSONObject

/** Strict, atomic patches: an omitted value is retained, a malformed value rejects the packet. */
object TelemetryProtocol {
    val wheelNames = listOf("FL", "FR", "RL", "RR")

    fun encode(s: VehicleState): JSONObject =
        JSONObject().apply {
            put("speedKmh", s.speedKmh)
            put("longitudinalG", s.longitudinalG)
            put("lateralG", s.lateralG)
            put("mode", s.mode.name)
            put("gear", s.gear.name)
            put("parking", s.parking)
            put("speedLimit", s.speedLimit)
            put("leftDoor", s.leftDoor)
            put("rightDoor", s.rightDoor)
            put("leftTurn", s.leftTurn)
            put("rightTurn", s.rightTurn)
            put("hazards", s.hazards)
            put("lowBeam", s.lowBeam)
            put("highBeam", s.highBeam)
            put("throttle", s.throttle)
            put("brake", s.brake)
            put("steering", s.steering)
            put("launch", s.launch.name)
            put("outsideC", s.outsideC)
            put("clock", s.clock ?: JSONObject.NULL)
            put("powerKw", s.powerKw)
            put(
                "wheels",
                org.json.JSONArray().apply {
                    s.wheels.forEach {
                        put(
                            JSONObject()
                                .put("battery", it.battery)
                                .put("temperature", it.temperature)
                        )
                    }
                },
            )
            put("recoveredKwh", s.recoveredKwh)
            put("recoveredKm", s.recoveredKm)
            put("tripKm", s.tripKm)
            put("tripKwh", s.tripKwh)
            put("averageKwh", s.averageKwh)
            put("rangeKm", s.rangeKm)
            put("remainingKwh", s.remainingKwh)
            for (key in fields) if (key != "wheels" && !s.has(key)) put(key, JSONObject.NULL)
            val wheelJson = getJSONArray("wheels")
            wheelNames.forEachIndexed { i, name ->
                for (key in listOf("battery", "temperature")) if (!s.has("wheels.$name.$key"))
                    wheelJson.getJSONObject(i).put(key, JSONObject.NULL)
            }
        }

    val fields =
        setOf(
            "speedKmh",
            "longitudinalG",
            "lateralG",
            "mode",
            "gear",
            "parking",
            "speedLimit",
            "leftDoor",
            "rightDoor",
            "leftTurn",
            "rightTurn",
            "hazards",
            "lowBeam",
            "highBeam",
            "throttle",
            "brake",
            "launch",
            "outsideC",
            "clock",
            "powerKw",
            "steering",
            "wheels",
            "recoveredKwh",
            "recoveredKm",
            "tripKm",
            "tripKwh",
            "averageKwh",
            "rangeKm",
            "remainingKwh",
        )

    fun patch(old: VehicleState, j: JSONObject, now: Long, bridge: Boolean = false): VehicleState {
        for (key in j.keys()) require(key in fields && (!bridge || key != "hazards")) {
            "Unknown telemetry field: $key"
        }
        fun n(key: String, fallback: Float, min: Float = -100000f, max: Float = 100000f): Float {
            if (!j.has(key)) return fallback
            require(j.get(key) is Number) { "$key must be a number" }
            val value = j.getDouble(key).toFloat()
            require(value.isFinite() && value in min..max) { "$key outside $min..$max" }
            return value
        }
        fun b(key: String, fallback: Boolean): Boolean {
            if (!j.has(key)) return fallback
            require(j.get(key) is Boolean) { "$key must be boolean" }
            return j.getBoolean(key)
        }
        fun s(key: String, fallback: String): String {
            if (!j.has(key)) return fallback
            require(j.get(key) is String) { "$key must be text" }
            return j.getString(key)
        }
        val observed = j.keys().asSequence().filter { it != "wheels" }.toMutableSet()
        fun energy(key: String, fallback: Double): Double {
            if (!j.has(key)) return fallback
            require(j.get(key) is Number) { "$key must be a number" }
            val v = j.getDouble(key)
            require(v.isFinite() && v in 0.0..(if (bridge) 4294967.295 else 100000.0)) {
                "Invalid $key"
            }
            return v
        }
        val wheels =
            if (j.has("wheels")) {
                val array =
                    if (bridge) {
                        val obj = j.getJSONObject("wheels")
                        for (key in obj.keys()) require(key in wheelNames) { "Unknown wheel: $key" }
                        org.json.JSONArray().apply {
                            wheelNames.forEach {
                                put(if (obj.has(it)) obj.getJSONObject(it) else JSONObject())
                            }
                        }
                    } else j.getJSONArray("wheels")
                require(array.length() == 4) { "wheels must contain FL, FR, RL, RR" }
                List(4) { index ->
                    val w = array.getJSONObject(index)
                    for (key in w.keys()) require(key == "battery" || key == "temperature") {
                        "Unknown wheel field: $key"
                    }
                    for (key in w.keys()) observed.add("wheels.${wheelNames[index]}.$key")
                    if (bridge && w.has("battery"))
                        require(w.get("battery") is Number && w.getDouble("battery") % 1.0 == 0.0) {
                            "battery must be an integer"
                        }
                    fun value(key: String, fallback: Float, min: Float, max: Float): Float {
                        if (!w.has(key)) return fallback
                        require(w.get(key) is Number)
                        val v = w.getDouble(key).toFloat()
                        require(v.isFinite() && v in min..max) { "Invalid wheel $key" }
                        return v
                    }
                    Wheel(
                        value("battery", old.wheels[index].battery, 0f, 100f),
                        value(
                            "temperature",
                            old.wheels[index].temperature,
                            if (bridge) -3276.8f else -100f,
                            if (bridge) 3276.7f else 300f,
                        ),
                    )
                }
            } else old.wheels
        val clock =
            if (j.has("clock")) {
                if (j.isNull("clock")) null
                else
                    s("clock", "").also {
                        require(it.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))) {
                            "clock must be HH:mm or null"
                        }
                    }
            } else old.clock
        val limit = n("speedLimit", old.speedLimit.toFloat(), 1f, 300f)
        require(limit % 1 == 0f)
        return old.copy(
            timeNanos = now,
            known = old.known + observed,
            speedKmh = n("speedKmh", old.speedKmh, 0f, if (bridge) 255f else 400f),
            longitudinalG =
                n(
                    "longitudinalG",
                    old.longitudinalG,
                    if (bridge) -2.048f else -20f,
                    if (bridge) 2.047f else 20f,
                ),
            lateralG =
                n(
                    "lateralG",
                    old.lateralG,
                    if (bridge) -2.048f else -20f,
                    if (bridge) 2.047f else 20f,
                ),
            mode = DriveMode.valueOf(s("mode", old.mode.name)),
            gear = Gear.valueOf(s("gear", old.gear.name)),
            parking = b("parking", old.parking),
            speedLimit = limit.toInt(),
            leftDoor = b("leftDoor", old.leftDoor),
            rightDoor = b("rightDoor", old.rightDoor),
            leftTurn = b("leftTurn", old.leftTurn),
            rightTurn = b("rightTurn", old.rightTurn),
            hazards = b("hazards", old.hazards),
            lowBeam = b("lowBeam", old.lowBeam),
            highBeam = b("highBeam", old.highBeam),
            throttle = n("throttle", old.throttle, 0f, 1f),
            brake = n("brake", old.brake, 0f, 1f),
            launch = Launch.valueOf(s("launch", old.launch.name)),
            outsideC =
                n(
                    "outsideC",
                    old.outsideC,
                    if (bridge) -3276.8f else -100f,
                    if (bridge) 3276.7f else 100f,
                ),
            clock = clock,
            powerKw =
                n(
                    "powerKw",
                    old.powerKw,
                    if (bridge) -3276.8f else -100000f,
                    if (bridge) 3276.7f else 100000f,
                ),
            steering = n("steering", old.steering, -1f, 1f),
            wheels = wheels,
            recoveredKwh = energy("recoveredKwh", old.recoveredKwh),
            recoveredKm = energy("recoveredKm", old.recoveredKm),
            tripKm = energy("tripKm", old.tripKm),
            tripKwh = energy("tripKwh", old.tripKwh),
            averageKwh = energy("averageKwh", old.averageKwh),
            rangeKm = energy("rangeKm", old.rangeKm),
            remainingKwh = energy("remainingKwh", old.remainingKwh),
        )
    }
}
