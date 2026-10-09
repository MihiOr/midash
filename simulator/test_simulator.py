import unittest
from physics import HeldInputs, Simulator


class SimulatorTests(unittest.TestCase):
    def advance(self, sim, seconds, gas=1, brake=0, steer=0):
        for _ in range(round(seconds * 120)):
            sim.step(1 / 120, gas, brake, steer)

    def drive(self):
        sim = Simulator()
        sim.state["parking"] = False
        sim.gear("D")
        return sim

    def test_repeat_release_never_drops_held_gas(self):
        keys = HeldInputs()
        keys.press("up")
        for _ in range(50):
            keys.release("up", repeat=True)
            keys.press("up", repeat=True)
            self.assertEqual(keys.pedals(), (1, 0, 0))
        keys.release("up")
        self.assertEqual(keys.pedals(), (0, 0, 0))

    def test_combined_keys_remain_independent(self):
        keys = HeldInputs()
        keys.press("up")
        keys.press("right")
        self.assertEqual(keys.pedals(), (1, 0, 1))
        keys.release("right")
        self.assertEqual(keys.pedals(), (1, 0, 0))
        keys.press("w")
        keys.release("up")
        self.assertEqual(keys.pedals(), (1, 0, 0))
        keys.clear()
        self.assertEqual(keys.pedals(), (0, 0, 0))

    def test_gas_and_steering_still_accelerate(self):
        sim = self.drive()
        self.advance(sim, 2)
        before = sim.state["speedKmh"]
        self.advance(sim, 1, steer=1)
        self.assertEqual(sim.state["throttle"], 1)
        self.assertGreater(sim.state["speedKmh"], before)

    def test_neutral_to_drive_while_moving(self):
        sim = self.drive()
        self.advance(sim, 3)
        self.assertTrue(sim.gear("N"))
        self.advance(sim, 0.5, gas=0)
        self.assertGreater(sim.state["speedKmh"], 20)
        self.assertTrue(sim.gear("D"))
        self.assertFalse(sim.gear("R"))
        self.assertEqual(sim.state["gear"], "D")

    def test_reverse_neutral_coasts_and_reengages_reverse(self):
        sim = self.drive()
        sim.gear("R")
        self.advance(sim, 2)
        sim.gear("N")
        before = sim.velocity
        self.advance(sim, 0.1, gas=0)
        self.assertLess(sim.velocity, 0)
        self.assertGreater(sim.velocity, before)
        self.assertTrue(sim.gear("R"))
        self.assertFalse(sim.gear("D"))

    def test_launch_and_cancellation(self):
        sim = self.drive()
        self.advance(sim, 1, brake=1)
        self.assertEqual(sim.state["launch"], "ARMED")
        self.advance(sim, 0.5)
        self.assertEqual(sim.state["launch"], "ACTIVE")
        self.assertEqual(sim.state["mode"], "SPORT")
        self.assertGreater(sim.state["longitudinalG"], 1.1)
        self.advance(sim, 0.1, brake=0.001)
        self.assertEqual(sim.state["launch"], "OFF")
        self.assertEqual(sim.state["mode"], "SPORT")

    def test_open_door_dummy_cap(self):
        sim = self.drive()
        sim.state["leftDoor"] = True
        self.advance(sim, 4)
        self.assertLessEqual(sim.state["speedKmh"], 10)
        self.assertGreater(sim.state["speedKmh"], 8)

    def test_held_gas_does_not_alternate_between_zero_and_one(self):
        sim = self.drive()
        keys = HeldInputs()
        keys.press("up")
        for i in range(600):
            if i % 5 == 0:
                keys.release("up", repeat=True)
            sim.step(1 / 120, *keys.pedals())
            self.assertEqual(sim.state["throttle"], 1)
            if i > 120:
                self.assertGreater(sim.state["longitudinalG"], 0.3)


if __name__ == "__main__":
    unittest.main()
