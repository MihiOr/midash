"""Desktop-only deterministic vehicle model. Android contains no simulation."""

from copy import deepcopy
from math import exp, hypot, tan


def initial_state():
    return dict(
        speedKmh=0.0,
        longitudinalG=0.0,
        lateralG=0.0,
        mode="COMFORT",
        gear="N",
        parking=True,
        speedLimit=50,
        leftDoor=False,
        rightDoor=False,
        leftTurn=False,
        rightTurn=False,
        hazards=False,
        lowBeam=False,
        highBeam=False,
        throttle=0.0,
        brake=0.0,
        steering=0.0,
        launch="OFF",
        outsideC=24.0,
        clock=None,
        powerKw=0.0,
        wheels=[
            dict(battery=b, temperature=t)
            for b, t in [(84, 42), (82, 43), (83, 44), (81, 42)]
        ],
        recoveredKwh=2.4,
        recoveredKm=14.3,
        tripKm=48.6,
        tripKwh=8.2,
        averageKwh=16.8,
        rangeKm=312.0,
        remainingKwh=18.58,
    )


class HeldInputs:
    """Each physical input owns a separate latch; auto-repeat never releases a latch."""

    def __init__(self):
        self.down = set()

    def press(self, key, repeat=False):
        if not repeat:
            self.down.add(key)

    def release(self, key, repeat=False):
        if not repeat:
            self.down.discard(key)

    def clear(self):
        self.down.clear()

    def pedals(self):
        return (
            float(bool(self.down & {"up", "w", "gas_button"})),
            float(bool(self.down & {"down", "s", "space", "brake_button"})),
            float(bool(self.down & {"right", "d", "right_button"}))
            - float(bool(self.down & {"left", "a", "left_button"})),
        )


def approach(a, b, dt, tau):
    return b + (a - b) * exp(-dt / tau)


class Simulator:
    def __init__(self):
        self.state = initial_state()
        self.velocity = 0.0
        self.throttle = 0.0
        self.brake = 0.0
        self.steer = 0.0
        self.turn_latched = False

    def gear(self, gear):
        if gear not in ("R", "N", "D"):
            raise ValueError("Unknown gear")
        # Engaging the same travel direction is valid while coasting in N.
        if gear == "D" and self.velocity < -1 / 3.6:
            return False
        if gear == "R" and self.velocity > 1 / 3.6:
            return False
        self.state["gear"] = gear
        self.state["launch"] = "OFF"
        return True

    @staticmethod
    def road_load(speed):
        return 0.5 * 1.225 * 0.55 * speed * speed / 1900 + 0.011 * 9.81 * min(
            1, speed / 0.5
        )

    def step(self, dt, throttle, brake, steering):
        if not 0 < dt <= 0.05:
            raise ValueError("Use fixed steps <= 50 ms")
        s = self.state
        throttle = max(0, min(1, throttle))
        brake = max(0, min(1, brake))
        steering = max(-1, min(1, steering))
        s.update(throttle=throttle, brake=brake, steering=steering)
        speed = abs(self.velocity)
        g = 9.81
        self.throttle = approach(
            self.throttle, throttle, dt, 0.32 if s["mode"] == "COMFORT" else 0.17
        )
        self.brake = approach(self.brake, brake, dt, 0.09)
        self.steer = approach(
            self.steer, steering * 0.48 / (1 + (speed / 18) ** 2), dt, 0.19
        )
        ready = (
            s["gear"] == "D"
            and not s["parking"]
            and not s["leftDoor"]
            and not s["rightDoor"]
        )
        launch = s["launch"]
        if not ready or throttle < 0.8 or (launch == "ACTIVE" and brake > 0):
            launch = "OFF"
        if (
            ready
            and speed < 1 / 3.6
            and self.throttle > 0.8
            and self.brake > 0.8
            and throttle > 0.8
            and brake > 0.8
        ):
            launch = "ARMED"
        if launch == "ARMED" and brake <= 0.01 and throttle >= 0.8:
            launch = "ACTIVE"
            self.brake = 0
            s["mode"] = "SPORT"
        s["launch"] = launch
        reverse = s["gear"] == "R"
        doors = s["leftDoor"] or s["rightDoor"]
        cap = 10 / 3.6 if doors else 20 / 3.6 if reverse else 170 / 3.6
        taper_start = 12 / 3.6 if reverse else 100 / 3.6
        top = 20 / 3.6 if reverse else 170 / 3.6
        peak = (
            0.25 * g
            if reverse
            else 1.2 * g + self.road_load(speed)
            if launch == "ACTIVE"
            else (0.5 if s["mode"] == "COMFORT" else 0.9) * g
        )
        motor = (
            peak
            if speed < taper_start
            else self.road_load(speed)
            + (peak - self.road_load(speed))
            * max(0, min(1, (top - speed) / (top - taper_start)))
        )
        drive = (
            (1 if launch == "ACTIVE" else self.throttle) * motor
            if s["gear"] != "N"
            and not s["parking"]
            and min(w["battery"] for w in s["wheels"]) > 0.1
            and brake == 0
            else 0
        )
        if doors:
            drive *= max(0, min(1, (cap - speed) / 0.5))
        regen = (
            0
            if s["gear"] == "N" or s["parking"] or launch == "ACTIVE"
            else 0.08 * g * (1 - self.throttle) * min(1, speed / 2)
        )
        direction = -1 if reverse else 1
        moving = (1 if self.velocity > 0 else -1) if speed > 0.001 else direction
        long = drive * direction - moving * (self.brake * 0.9 * g + regen)
        lateral = max(-0.9 * g, min(0.9 * g, self.velocity**2 * tan(self.steer) / 2.75))
        long_grip = 1.2 * g + self.road_load(speed) if launch == "ACTIVE" else 0.9 * g
        grip = min(1, 1 / max(0.000001, hypot(long / long_grip, lateral / (0.9 * g))))
        ax = long * grip - moving * self.road_load(speed)
        ay = lateral * grip
        if s["parking"]:
            self.velocity = 0
            ax = ay = 0
        else:
            nxt = self.velocity + ax * dt
            self.velocity = 0 if self.velocity * nxt < 0 and drive == 0 else nxt
            if speed < 0.02 and drive == 0:
                self.velocity = 0
            self.velocity = max(
                -cap if reverse or s["gear"] == "N" else 0,
                min(0 if reverse else cap, self.velocity),
            )
        if speed < 0.01 and abs(self.velocity) < 0.01:
            ax = ay = 0
        km = abs(self.velocity) * dt / 1000
        regen_torque = min(0.2, regen / g + self.brake * 0.12) * grip
        power = (
            1900
            * (drive * grip / 0.93 - min(regen_torque * g, 0.2 * g) * 0.65)
            * speed
            / 1000
            + 0.7
        )
        energy = power * dt / 3600
        s["tripKm"] += km
        s["powerKw"] = power
        if energy > 0:
            s["tripKwh"] += energy
        else:
            s["recoveredKwh"] -= energy
            s["recoveredKm"] -= energy / 0.168
        s["remainingKwh"] = max(0, s["remainingKwh"] - energy)
        torque = drive / max(0.001, peak) * grip
        for i, w in enumerate(s["wheels"]):
            w["battery"] = max(
                0,
                min(
                    100,
                    w["battery"]
                    - energy * (1 + (1 if i % 2 == 0 else -1) * ay / g * 0.035),
                ),
            )
            w["temperature"] += (
                torque**2 * 0.7
                + regen_torque**2 * 2
                - (w["temperature"] - s["outsideC"]) * (0.005 + speed * 0.00018)
            ) * dt
        if km > 0:
            s["rangeKm"] = approach(
                s["rangeKm"],
                sum(w["battery"] for w in s["wheels"])
                / 4
                / (0.265 + 0.0000015 * (speed * 3.6) ** 2),
                dt,
                12,
            )
        if (s["leftTurn"] and self.steer < -0.035) or (
            s["rightTurn"] and self.steer > 0.035
        ):
            self.turn_latched = True
        if self.turn_latched and abs(self.steer) < 0.014 and speed > 1:
            s["leftTurn"] = s["rightTurn"] = False
            self.turn_latched = False
        s.update(
            speedKmh=abs(self.velocity) * 3.6, longitudinalG=ax / g, lateralG=ay / g
        )

    def snapshot(self):
        return deepcopy(self.state)
