/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */


package baritone.gui.anim;

/**
 * Frame-rate independent exponential easing toward a target. {@code speed} is roughly "1/time constant" in
 * seconds, so 12 settles in about 0.25 s. Pure Java; the clock can be injected for tests.
 */
public final class Anim {

    public interface Clock {
        long nanos();
    }

    private static final Clock SYSTEM = System::nanoTime;

    private final float speed;
    private final Clock clock;
    private float value;
    private float target;
    private long last = -1;

    public Anim(float initial, float speed) {
        this(initial, speed, SYSTEM);
    }

    public Anim(float initial, float speed, Clock clock) {
        this.value = initial;
        this.target = initial;
        this.speed = speed;
        this.clock = clock;
    }

    public Anim target(float t) {
        this.target = t;
        return this;
    }

    public Anim target(boolean on) {
        return target(on ? 1f : 0f);
    }

    public void snap(float v) {
        value = target = v;
    }

    /** Advance to now and return the current value. */
    public float get() {
        long now = clock.nanos();
        if (last >= 0) {
            float dt = Math.min(0.25f, (now - last) / 1e9f);
            value += (target - value) * (1f - (float) Math.exp(-dt * speed));
            if (Math.abs(target - value) < 0.001f) {
                value = target;
            }
        }
        last = now;
        return value;
    }
}
