"""Deterministically bake the original dashboard tones. No runtime synthesis."""
from pathlib import Path
import math, wave, struct

OUT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/audio/cues'
OUT.mkdir(parents=True, exist_ok=True)
RATE = 48000

def render(name, notes):
    length = max(t + duration for _, t, duration, _, _, _ in notes) + .025
    samples = [0.0] * math.ceil(length * RATE)
    for freq, start, duration, gain, shape, end in notes:
        phase = 0.0
        for i in range(math.ceil(duration * RATE)):
            t = i / RATE
            f = freq * ((end / freq) ** (t / duration) if end else 1)
            phase += f / RATE
            # Band-limited triangle; smooth attack and zero-valued release avoid clicks.
            value = math.sin(2 * math.pi * phase) if shape == 'sine' else sum(
                (-1) ** k * math.sin(2 * math.pi * (2*k+1) * phase) / (2*k+1)**2
                for k in range(8)) * 8 / math.pi**2
            attack = .5 - .5 * math.cos(math.pi * min(1, t / .012))
            release = .5 - .5 * math.cos(math.pi * min(1, max(0, duration-t) / .015))
            envelope = attack * release * math.exp(-7 * max(0, t-.012) / duration)
            samples[round(start*RATE)+i] += value * gain * envelope
    assert max(map(abs, samples)) < .3, 'Leave headroom for simultaneous cues and music'
    with wave.open(str(OUT / (name+'.wav')), 'wb') as wav:
        wav.setparams((1, 2, RATE, 0, 'NONE', 'not compressed'))
        wav.writeframes(b''.join(struct.pack('<h', round(s*32767)) for s in samples))

g = .18 * .55
render('comfort', [(f, i*.12, .85, g*.6, 'sine', 0) for i,f in enumerate([523.25,659.25,783.99])])
render('sport', [(130,0,.48,g,'triangle',390),(260,.05,.4,g*.65,'triangle',780),(1047,.27,.26,g*.55,'sine',0)])
for name, notes in dict(gear=[740,988],on=[660,990],off=[880,587],warning=[440,349,440],overspeed=[880,660],door=[520,390,520],armed=[659,988],launch=[784,1175],cancel=[659,440]).items():
    render(name, [(f,i*.095,.22,g,'sine',0) for i,f in enumerate(notes)])
for name, freq in [('tick_on',1050),('tick_off',740)]:
    render(name, [(freq,0,.045,.055*.4,'triangle',0)])
print(f'Generated {len(list(OUT.glob("*.wav")))} short PCM cues at {RATE} Hz')
