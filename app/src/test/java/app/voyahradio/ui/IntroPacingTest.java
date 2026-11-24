package app.voyahradio.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IntroPacingTest {
    private static final long FRAME = 16_666_667L;

    @Test public void easingStartsAndEndsInPlaceAndStandardNeverOvershoots() {
        for (IntroPacing.Easing easing : new IntroPacing.Easing[] {
                IntroPacing.Easing.STANDARD, IntroPacing.Easing.DECELERATE, IntroPacing.Easing.SPRING,
                IntroPacing.Easing.BACK}) {
            assertEquals(0f, easing.at(0f), 0f);
            assertEquals(1f, easing.at(1f), 0f);
            assertEquals(0f, easing.at(-1f), 0f);
            assertEquals(1f, easing.at(2f), 0f);
        }
        float previous = 0f;
        for (int step = 1; step <= 100; step++) {
            float value = IntroPacing.Easing.STANDARD.at(step / 100f);
            assertTrue(value >= previous);
            assertTrue(value <= 1f);
            previous = value;
        }
        assertEquals(.5f, new IntroPacing.Easing(.25f, .25f, .75f, .75f).at(.5f), 1e-4f);
    }

    @Test public void overshootingCurvesPeakOnceAndSettle() {
        assertPeak(IntroPacing.Easing.SPRING, 1.005f, 1.02f);
        assertPeak(IntroPacing.Easing.BACK, 1.07f, 1.13f);
    }

    private static void assertPeak(IntroPacing.Easing easing, float low, float high) {
        float peak = 0f;
        int peakStep = 0;
        for (int step = 0; step <= 200; step++) {
            float value = easing.at(step / 200f);
            if (value > peak) {
                peak = value;
                peakStep = step;
            }
        }
        assertTrue(peak > low);
        assertTrue(peak < high);
        // After the peak the curve only falls back toward its end.
        for (int step = peakStep + 1; step <= 200; step++) assertTrue(easing.at(step / 200f) <= peak);
    }

    @Test public void spanClampsToTheStage() {
        assertEquals(0f, IntroPacing.span(100f, 200f, 300f), 0f);
        assertEquals(.5f, IntroPacing.span(250f, 200f, 300f), 1e-6f);
        assertEquals(1f, IntroPacing.span(400f, 200f, 300f), 0f);
        assertEquals(1f, IntroPacing.span(300f, 300f, 300f), 0f);
    }

    @Test public void settleWaitsForSteadyFramesAndRestartsAfterAStall() {
        IntroPacing.Settle settle = new IntroPacing.Settle(FRAME, 3);
        long time = 0L;
        assertFalse(settle.onFrame(time));
        assertFalse(settle.onFrame(time += FRAME));
        assertFalse(settle.onFrame(time += 2 * FRAME));
        assertFalse(settle.onFrame(time += 6 * FRAME));
        assertFalse(settle.onFrame(time += FRAME));
        assertFalse(settle.onFrame(time += FRAME));
        assertTrue(settle.onFrame(time + FRAME));
    }

    @Test public void clockTurnsAStallIntoAShortPause() {
        IntroPacing.Clock clock = new IntroPacing.Clock(FRAME, 3_000_000_000L);
        assertEquals(0L, clock.onFrame(5_000L));
        assertEquals(FRAME, clock.onFrame(5_000L + FRAME));
        // Half a second without frames moves the intro on by two frames only.
        assertEquals(3 * FRAME, clock.onFrame(5_000L + FRAME + 500_000_000L));
    }

    @Test public void clockNeverFallsMoreThanHalfThePlanBehind() {
        long planned = 1_000_000_000L;
        IntroPacing.Clock clock = new IntroPacing.Clock(FRAME, planned);
        clock.onFrame(0L);
        long intro = 0L;
        for (long time = 100_000_000L; time <= 2_000_000_000L; time += 100_000_000L) intro = clock.onFrame(time);
        assertEquals(2_000_000_000L - planned / 2, intro);
    }

    @Test public void radioStagesPlayInOrderAndTheEntranceEndsBeforeTheHold() {
        assertTrue(RadioIntro.Timeline.BODY_START < RadioIntro.Timeline.BODY_VISIBLE);
        float lastWave = RadioIntro.Timeline.WAVES_START + 3 * RadioIntro.Timeline.WAVE_STAGGER
                + RadioIntro.Timeline.WAVE_OPEN;
        // The needle locks as the outer wave settles, and the charge follows the lock.
        assertTrue(Math.abs(lastWave - RadioIntro.Timeline.NEEDLE_LOCK) <= 80f);
        assertTrue(RadioIntro.Timeline.CHARGE_START <= RadioIntro.Timeline.NEEDLE_LOCK);
        assertTrue(RadioIntro.Timeline.CHARGE_FULL > RadioIntro.Timeline.NEEDLE_LOCK);
        float charged = RadioIntro.Timeline.CHARGE_END + 4 * RadioIntro.Timeline.CHARGE_STAGGER;
        float pulses = RadioIntro.Timeline.PULSE_SECOND + RadioIntro.Timeline.PULSE_LENGTH;
        assertTrue(Math.max(charged, Math.max(pulses, RadioIntro.Timeline.GLINT_END)) < RadioIntro.Timeline.EXIT_START);
        assertTrue(RadioIntro.Timeline.EXIT_START < RadioIntro.Timeline.BACKDROP_START);
        assertTrue(RadioIntro.Timeline.BACKDROP_START < RadioIntro.Timeline.EXIT_END);
        assertTrue(RadioIntro.Timeline.EXIT_END < RadioIntro.Timeline.END);
        assertTrue(RadioIntro.Timeline.END <= 3_000f);
    }
}
