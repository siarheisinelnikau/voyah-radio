package app.voyahradio.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import app.voyahradio.R;

import static app.voyahradio.ui.IntroPacing.Easing;
import static app.voyahradio.ui.IntroPacing.clamp;
import static app.voyahradio.ui.IntroPacing.ease;
import static app.voyahradio.ui.IntroPacing.span;

/**
 * The startup intro of Voyah Radio, in the language of the Open Voyah Controls intro: a steel mark on the dark
 * backdrop, quiet corner lights, a glint across the metal, an accent charge, a pulse and a still hold before the
 * mark leaves toward the viewer.
 *
 * <p>The mark is a broadcast mast. Its waves open on both sides like the wings of the Open Voyah Controls emblem,
 * and a tuning needle sweeps along the scale under the name. When the needle locks on the station, the accent
 * charges the steel from the mast outward and two rings leave the antenna.
 *
 * <p>The intro covers the activity window from its first frame, so the radio lays itself out underneath. It plays
 * once per process and only for a launch from the home screen; a tap closes it.
 */
@SuppressLint("ViewConstructor") // Created in code with the activity's run settings.
final class RadioIntro extends View {
    /** The stages in milliseconds of intro time. */
    static final class Timeline {
        private Timeline() {}

        static final float BODY_START = 120;
        static final float BODY_VISIBLE = 560;
        static final float TIP_START = 300;
        static final float TIP_FULL = 660;
        static final float WAVES_START = 380;
        static final float WAVE_STAGGER = 100;
        static final float WAVE_OPEN = 520;
        static final float WAVE_VISIBLE = 240;
        static final float TITLE_START = 560;
        static final float TITLE_VISIBLE = 900;
        static final float DIAL_START = 700;
        static final float DIAL_DRAWN = 1120;
        static final float NEEDLE_START = 760;
        static final float NEEDLE_LOCK = 1240;
        static final float CAPTION_START = 860;
        static final float CAPTION_VISIBLE = 1200;
        static final float GLINT_START = 620;
        static final float GLINT_END = 1800;
        static final float CHARGE_START = 1200;
        static final float CHARGE_FULL = 1400;
        static final float CHARGE_FADE = 1560;
        static final float CHARGE_END = 1960;
        /** Each wave charges this much later than the one inside it. */
        static final float CHARGE_STAGGER = 70;
        static final float PULSE_START = 1220;
        static final float PULSE_SECOND = 1420;
        static final float PULSE_LENGTH = 680;
        static final float EXIT_START = 2320;
        static final float EXIT_END = 2700;
        static final float BACKDROP_START = 2540;
        static final float END = 2900;
        /** A tap fades everything out over this time. */
        static final float DISMISS = 220;
        /** A point at which nearly every part is drawn, used to prepare the drawing before the intro shows. */
        static final float WARM = 1480;
    }

    /** The extra that holds a debuggable build's intro at one point of its timeline, for screenshots. */
    static final String DEBUG_PHASE = "intro_millis";

    private static final int STEADY_FRAMES = 8;
    private static final long SETTLE_LIMIT_NANOS = 700_000_000L;
    private static final float CHARGE_DEPTH = .82f;

    // The mark in its own units: 120 wide and 102 tall, the antenna tip at the center of the waves.
    private static final float WIDTH = 120f;
    private static final float HEIGHT = 102f;
    private static final float TIP_X = 60f;
    private static final float TIP_Y = 28f;
    private static final float TIP_RADIUS = 3.8f;
    private static final float APEX_Y = 44f;
    private static final float[] WAVE_RADII = {11.5f, 19.5f, 27.5f, 35.5f};
    private static final float[] WAVE_WIDTHS = {3.0f, 2.7f, 2.4f, 2.1f};
    private static final float[] WAVE_OPACITY = {1f, .9f, .78f, .66f};
    private static final float WAVE_HALF_SWEEP = 40f;

    private static boolean decided;

    private final float durationScale;
    private final float heldMillis;
    private final float density;
    private final String title;
    private final String caption;

    private final int backdrop;
    private final int accent;
    private final int warm;
    private final int text;
    private final int muted;
    private final int shade;
    private final int body;
    private final int light;
    private final int glowCore;
    private final int glowEdge;
    private final ColorFilter charged;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Matrix matrix = new Matrix();
    private final Path legs = new Path();
    private final Path braces = new Path();
    private final Path plinth = new Path();
    private final Shader[] waveShaders = new Shader[WAVE_RADII.length];
    private final Shader depthShader;
    private final Shader bevelShader;
    private final Shader rodShader;
    private final Shader ballShader;
    private final Shader haloShader;
    private final Shader glintShader;

    private Shader coolLight;
    private Shader warmShade;
    private Shader ambient;
    private Shader chargeGlow;
    private Shader shadow;
    private Shader rule;
    private Shader needleGlow;

    private float markHeight;
    private float markScale;
    private float centerX;
    private float top;
    private float titleTop;
    private float baselineY;
    private float captionTop;
    private float ruleWidth;
    private float pivotY;
    private float tipX;
    private float tipY;
    private float glowY;
    private float glowRadius;
    private float titleAscent;
    private float captionAscent;
    private String shownTitle = "";
    private String shownCaption = "";
    private float titleWidth;
    private float captionWidth;

    private final Choreographer.FrameCallback tick = this::onFrame;
    private IntroPacing.Settle settle;
    private IntroPacing.Clock clock;
    private long frameNanos = 16_666_667L;
    private long firstFrame = -1L;
    private long dismissedAt = -1L;
    private boolean dismissRequested;
    private boolean scheduled;
    private boolean finished;
    private boolean shown;
    private float introMillis;
    private float dismissFade = 1f;

    /**
     * Covers the window with the intro when this is the first launch of the radio from the home screen in this
     * process, the intro is enabled and system animations are on.
     */
    static void showOnLaunch(Activity activity, Bundle saved, boolean enabled) {
        float held = heldMillis(activity);
        if (held < 0f) {
            if (decided) return;
            decided = true;
            Intent intent = activity.getIntent();
            if (saved != null || !enabled || intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())) return;
        }
        float scale = Settings.Global.getFloat(activity.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (held < 0f && (!ValueAnimator.areAnimatorsEnabled() || scale <= 0f)) return;
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        decor.addView(new RadioIntro(activity, Math.max(scale, .01f), held),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static float heldMillis(Activity activity) {
        boolean debuggable = (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        Intent intent = activity.getIntent();
        return debuggable && intent != null ? intent.getFloatExtra(DEBUG_PHASE, -1f) : -1f;
    }

    private RadioIntro(Activity activity, float durationScale, float heldMillis) {
        super(activity);
        this.durationScale = durationScale;
        this.heldMillis = heldMillis;
        density = getResources().getDisplayMetrics().density;
        title = activity.getString(R.string.app_name);
        caption = activity.getString(R.string.intro_caption);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Above the bar backgrounds that the window adds later, without a shadow.
        setOutlineProvider(null);
        setTranslationZ(1_000f);

        backdrop = activity.getColor(R.color.radio_background);
        accent = RadioTheme.color(activity, R.attr.radio_accent_role);
        warm = activity.getColor(R.color.radio_warm);
        text = activity.getColor(R.color.radio_text);
        muted = activity.getColor(R.color.radio_muted);
        int steel = mix(muted, RadioTheme.color(activity, R.attr.radio_edge_role), .30f);
        shade = mix(mix(steel, backdrop, .78f), accent, .12f);
        body = mix(steel, backdrop, .32f);
        light = mix(steel, Color.WHITE, .86f);
        glowCore = mix(backdrop, accent, .30f);
        glowEdge = alpha(mix(backdrop, accent, .13f), .64f);
        // Multiplying by the accent keeps the bevels and shadows of the steel while it takes the accent color.
        charged = new LightingColorFilter(accent & 0xFFFFFF, 0);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        titlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titlePaint.setTextSize(sp(24));
        titlePaint.setLetterSpacing(.067f);
        captionPaint.setTextSize(sp(14));
        captionPaint.setLetterSpacing(.043f);

        buildMast();
        for (int i = 0; i < WAVE_RADII.length; i++) {
            float reach = WAVE_RADII[i] * .66f;
            waveShaders[i] = new LinearGradient(0f, TIP_Y - reach, 0f, TIP_Y + reach,
                    new int[] {tone(.95f, .80f), tone(.70f, .42f), tone(.40f, .28f), tone(.70f, .42f), tone(.92f, .75f)},
                    new float[] {0f, .30f, .50f, .70f, 1f}, Shader.TileMode.CLAMP);
        }
        depthShader = new LinearGradient(48f, APEX_Y, 72f, 100f,
                new int[] {tone(.95f), tone(0f), tone(.50f)}, null, Shader.TileMode.CLAMP);
        bevelShader = new LinearGradient(47f, APEX_Y, 73f, 100f,
                new int[] {tone(1f), tone(.80f), tone(.30f), tone(.97f), tone(.56f)},
                new float[] {0f, .38f, .57f, .72f, 1f}, Shader.TileMode.CLAMP);
        rodShader = new LinearGradient(TIP_X - 1f, 0f, TIP_X + 1f, 0f,
                new int[] {tone(.55f), tone(.98f), tone(.62f)}, null, Shader.TileMode.CLAMP);
        ballShader = new RadialGradient(TIP_X - 1.3f, TIP_Y - 1.5f, 5.6f,
                new int[] {tone(1f), tone(.66f), tone(.16f)}, new float[] {0f, .55f, 1f}, Shader.TileMode.CLAMP);
        haloShader = new RadialGradient(TIP_X, TIP_Y, 11f,
                new int[] {alpha(accent, .55f), alpha(accent, .16f), alpha(accent, 0f)}, new float[] {0f, .45f, 1f},
                Shader.TileMode.CLAMP);
        glintShader = new LinearGradient(0f, 0f, 30f, -17f,
                new int[] {Color.TRANSPARENT, mix(Color.WHITE, accent, .10f), Color.TRANSPARENT}, null,
                Shader.TileMode.CLAMP);
    }

    private void buildMast() {
        // An A-frame mast under the antenna rod: two tapered legs, two braces with a cross and a short plinth.
        legs.moveTo(59.3f, APEX_Y);
        legs.lineTo(60.5f, APEX_Y + 1f);
        legs.lineTo(51.8f, 99f);
        legs.lineTo(47.6f, 99f);
        legs.close();
        legs.moveTo(60.7f, APEX_Y);
        legs.lineTo(59.5f, APEX_Y + 1f);
        legs.lineTo(68.2f, 99f);
        legs.lineTo(72.4f, 99f);
        legs.close();
        braces.moveTo(57.4f, 64f);
        braces.lineTo(62.6f, 64f);
        braces.moveTo(54.6f, 82f);
        braces.lineTo(65.4f, 82f);
        braces.moveTo(57.4f, 64f);
        braces.lineTo(65.4f, 82f);
        braces.moveTo(62.6f, 64f);
        braces.lineTo(54.6f, 82f);
        plinth.addRoundRect(new RectF(43.5f, 98.4f, 76.5f, 101.4f), 1.4f, 1.4f, Path.Direction.CW);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Display display = getDisplay();
        float refresh = display == null ? 60f : display.getRefreshRate();
        frameNanos = (long) (1_000_000_000L / (refresh >= 20f ? refresh : 60f));
        settle = new IntroPacing.Settle(frameNanos, STEADY_FRAMES);
        if (heldMillis >= 0f) introMillis = heldMillis;
        else schedule();
    }

    @Override protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(tick);
        scheduled = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // Leaving the radio while the intro plays ends it; the radio is waiting underneath on return.
        if (visibility == VISIBLE) shown = true;
        else if (shown && heldMillis < 0f) post(this::finish);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) performClick();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        if (heldMillis >= 0f) {
            post(this::finish);
        } else {
            dismissRequested = true;
            schedule();
        }
        return true;
    }

    private void schedule() {
        if (scheduled || finished) return;
        scheduled = true;
        Choreographer.getInstance().postFrameCallback(tick);
    }

    private void onFrame(long now) {
        scheduled = false;
        if (finished) return;
        if (clock == null && !dismissRequested) {
            if (firstFrame < 0) firstFrame = now;
            if (settle.onFrame(now) || now - firstFrame >= SETTLE_LIMIT_NANOS) {
                clock = new IntroPacing.Clock(frameNanos, (long) (Timeline.END * durationScale * 1_000_000L));
            }
        }
        if (clock != null) introMillis = clock.onFrame(now) / 1_000_000f / durationScale;
        float dismissed = 0f;
        if (dismissRequested) {
            if (dismissedAt < 0) dismissedAt = now;
            dismissed = (now - dismissedAt) / (Timeline.DISMISS * durationScale * 1_000_000f);
            dismissFade = 1f - Easing.STANDARD.at(clamp(dismissed));
        }
        invalidate();
        if (introMillis >= Timeline.END || dismissed >= 1f) finish();
        else schedule();
    }

    private void finish() {
        if (finished) return;
        finished = true;
        Choreographer.getInstance().removeFrameCallback(tick);
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(this);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float maxText = Math.max(0f, width - dp(32));
        shownTitle = TextUtils.ellipsize(title, titlePaint, maxText, TextUtils.TruncateAt.END).toString();
        shownCaption = TextUtils.ellipsize(caption, captionPaint, maxText, TextUtils.TruncateAt.END).toString();
        titleWidth = titlePaint.measureText(shownTitle);
        captionWidth = captionPaint.measureText(shownCaption);
        Paint.FontMetrics titleMetrics = titlePaint.getFontMetrics();
        Paint.FontMetrics captionMetrics = captionPaint.getFontMetrics();
        titleAscent = titleMetrics.ascent;
        captionAscent = captionMetrics.ascent;
        float titleHeight = titleMetrics.descent - titleMetrics.ascent;
        float captionHeight = captionMetrics.descent - captionMetrics.ascent;

        markHeight = Math.min(dp(150), Math.min(height * .40f, width * .43f));
        markScale = markHeight / HEIGHT;
        centerX = width / 2f;
        float block = markHeight + dp(18) + titleHeight + dp(10) + dp(9) + dp(2) + dp(10) + captionHeight;
        top = Math.max(0f, (height - block) / 2f);
        titleTop = top + markHeight + dp(18);
        baselineY = titleTop + titleHeight + dp(10) + dp(9);
        captionTop = baselineY + dp(2) + dp(10);
        pivotY = top + block / 2f;
        ruleWidth = Math.min(Math.max(Math.max(titleWidth, captionWidth), dp(180)), maxText);
        tipX = centerX;
        tipY = top + TIP_Y * markScale;
        glowY = top + 40f * markScale;
        glowRadius = Math.max(1f, markHeight * 1.25f);

        float shortEdge = Math.max(1f, Math.min(width, height));
        coolLight = new RadialGradient(width * .94f, height * .06f, shortEdge * 1.1f,
                new int[] {alpha(accent, .09f), alpha(accent, .025f), alpha(accent, 0f)}, null, Shader.TileMode.CLAMP);
        warmShade = new RadialGradient(width * .03f, height * .98f, shortEdge * .85f,
                new int[] {alpha(warm, .035f), alpha(warm, 0f)}, null, Shader.TileMode.CLAMP);
        ambient = new RadialGradient(centerX, glowY, glowRadius,
                new int[] {glowCore, glowEdge, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP);
        chargeGlow = new RadialGradient(centerX, glowY, glowRadius * .9f,
                new int[] {alpha(accent, .42f), alpha(accent, 0f)}, null, Shader.TileMode.CLAMP);
        shadow = new RadialGradient(0f, 0f, 1f, new int[] {alpha(Color.BLACK, .65f), Color.TRANSPARENT}, null,
                Shader.TileMode.CLAMP);
        rule = new LinearGradient(centerX - ruleWidth / 2f, 0f, centerX + ruleWidth / 2f, 0f,
                new int[] {alpha(accent, 0f), accent, alpha(accent, 0f)}, null, Shader.TileMode.CLAMP);
        needleGlow = new RadialGradient(0f, 0f, dp(16),
                new int[] {alpha(accent, .45f), alpha(accent, 0f)}, null, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (clock == null && heldMillis < 0f && !dismissRequested) {
            // Drawn once per frame under the opaque backdrop while frames settle, so that the first visible
            // frames do not pay for shaders, paths and text.
            drawIntro(canvas, Timeline.WARM, 1f);
            plain.setShader(null);
            plain.setColor(backdrop);
            canvas.drawRect(0, 0, width, height, plain);
            return;
        }
        float t = introMillis;
        float cover = (1f - ease(t, Timeline.BACKDROP_START, Timeline.END)) * dismissFade;
        if (cover > 0f) {
            plain.setShader(null);
            plain.setColor(alpha(backdrop, cover));
            canvas.drawRect(0, 0, width, height, plain);
        }
        drawIntro(canvas, t, dismissFade);
    }

    private void drawIntro(Canvas canvas, float t, float fade) {
        if (t <= Timeline.BODY_START || markScale <= 0f) return;
        float exit = ease(t, Timeline.EXIT_START, Timeline.EXIT_END);
        float out = (1f - exit) * fade;
        if (out <= 0f) return;
        float reveal = ease(t, Timeline.BODY_START, Timeline.BODY_VISIBLE);
        float charge = charge(t);
        float titleAlpha = ease(t, Timeline.TITLE_START, Timeline.TITLE_VISIBLE);
        float dial = ease(t, Timeline.DIAL_START, Timeline.DIAL_DRAWN);
        float captionAlpha = ease(t, Timeline.CAPTION_START, Timeline.CAPTION_VISIBLE);

        fill.setColorFilter(null);
        fill.setShader(coolLight);
        fill.setAlpha(alpha255(reveal * out));
        canvas.drawRect(0, 0, getWidth(), getHeight(), fill);
        fill.setShader(warmShade);
        canvas.drawRect(0, 0, getWidth(), getHeight(), fill);

        canvas.save();
        // The mark leaves by easing slightly toward the viewer while it fades.
        float exitScale = 1f + .045f * exit;
        canvas.scale(exitScale, exitScale, centerX, pivotY);

        fill.setShader(ambient);
        fill.setAlpha(alpha255(reveal * out));
        canvas.drawCircle(centerX, glowY, glowRadius, fill);
        if (charge > 0f) {
            fill.setShader(chargeGlow);
            fill.setAlpha(alpha255(charge * out));
            canvas.drawCircle(centerX, glowY, glowRadius * .9f, fill);
        }
        drawPulse(canvas, span(t, Timeline.PULSE_START, Timeline.PULSE_START + Timeline.PULSE_LENGTH), out);
        drawPulse(canvas, span(t, Timeline.PULSE_SECOND, Timeline.PULSE_SECOND + Timeline.PULSE_LENGTH), out * .7f);

        canvas.save();
        canvas.translate(centerX, top + 100.4f * markScale);
        canvas.scale(markHeight * .27f, markHeight * .03f);
        fill.setShader(shadow);
        fill.setAlpha(alpha255(reveal * out));
        canvas.drawCircle(0f, 0f, 1f, fill);
        canvas.restore();

        float emblemScale = markScale * (.91f + .09f * reveal);
        float horizontalScale = emblemScale * (.90f + .10f * reveal);
        canvas.save();
        canvas.translate(centerX - WIDTH * horizontalScale / 2f,
                top + (markHeight - HEIGHT * emblemScale) / 2f + dp(6) * (1f - reveal));
        canvas.scale(horizontalScale, emblemScale);
        float glint = t >= Timeline.GLINT_START && t <= Timeline.GLINT_END
                ? -50f + 220f * span(t, Timeline.GLINT_START, Timeline.GLINT_END) : Float.NaN;
        drawWaves(canvas, t, out, glint);
        if (reveal > 0f) drawMast(canvas, reveal * out, charge, glint);
        drawTip(canvas, t, out, charge);
        canvas.restore();

        if (titleAlpha > 0f) {
            titlePaint.setColor(mix(text, accent, charge * .85f));
            titlePaint.setAlpha(alpha255(titleAlpha * out));
            canvas.drawText(shownTitle, centerX - titleWidth / 2f,
                    titleTop + dp(6) * (1f - titleAlpha) - titleAscent, titlePaint);
        }
        drawDial(canvas, t, dial, charge, out);
        if (captionAlpha > 0f) {
            captionPaint.setColor(muted);
            captionPaint.setAlpha(alpha255(captionAlpha * out));
            canvas.drawText(shownCaption, centerX - captionWidth / 2f,
                    captionTop + dp(6) * (1f - captionAlpha) - captionAscent, captionPaint);
        }
        canvas.restore();
    }

    private void drawPulse(Canvas canvas, float pulse, float out) {
        if (pulse <= 0f || pulse >= 1f) return;
        float fade = (1f - pulse) * (1f - pulse);
        stroke.setShader(null);
        stroke.setColorFilter(null);
        stroke.setStrokeWidth(dp(1.5f));
        stroke.setColor(alpha(accent, .5f * fade * out));
        canvas.drawCircle(tipX, tipY, markHeight * (.10f + 1.05f * Easing.STANDARD.at(pulse)), stroke);
    }

    private void drawWaves(Canvas canvas, float t, float out, float glint) {
        for (int i = 0; i < WAVE_RADII.length; i++) {
            float start = Timeline.WAVES_START + i * Timeline.WAVE_STAGGER;
            float opacity = ease(t, start, start + Timeline.WAVE_VISIBLE) * out * WAVE_OPACITY[i];
            if (opacity <= 0f) continue;
            float open = Easing.SPRING.at(span(t, start, start + Timeline.WAVE_OPEN));
            float radius = WAVE_RADII[i] * (.55f + .45f * open);
            float half = WAVE_HALF_SWEEP * (.12f + .88f * open);
            oval.set(TIP_X - radius, TIP_Y - radius, TIP_X + radius, TIP_Y + radius);
            stroke.setStrokeWidth(WAVE_WIDTHS[i]);
            stroke.setShader(waveShaders[i]);
            stroke.setColorFilter(null);
            stroke.setAlpha(alpha255(opacity));
            waves(canvas, half);
            float charge = charge(t - (i + 1) * Timeline.CHARGE_STAGGER);
            if (charge > 0f) {
                stroke.setColorFilter(charged);
                stroke.setAlpha(alpha255(opacity * charge * CHARGE_DEPTH));
                waves(canvas, half);
                stroke.setColorFilter(null);
            }
            stroke.setShader(null);
            stroke.setStrokeWidth(.45f);
            stroke.setColor(tone(.98f, .55f * opacity));
            waves(canvas, half);
            if (!Float.isNaN(glint)) {
                stroke.setStrokeWidth(WAVE_WIDTHS[i]);
                stroke.setShader(glint(glint));
                stroke.setAlpha(alpha255((.66f - .30f * charge) * opacity));
                waves(canvas, half);
                stroke.setShader(null);
            }
        }
    }

    private void waves(Canvas canvas, float half) {
        canvas.drawArc(oval, 180f - half, 2f * half, false, stroke);
        canvas.drawArc(oval, -half, 2f * half, false, stroke);
    }

    private void drawMast(Canvas canvas, float opacity, float charge, float glint) {
        mastPass(canvas, opacity, null);
        // Stop short of a full tint so the bevels of the steel stay readable under the accent.
        if (charge > 0f) mastPass(canvas, opacity * charge * CHARGE_DEPTH, charged);
        if (!Float.isNaN(glint)) {
            Shader band = glint(glint);
            float shine = (.66f - .30f * charge) * opacity;
            fill.setColorFilter(null);
            fill.setShader(band);
            fill.setAlpha(alpha255(shine));
            canvas.drawPath(legs, fill);
            canvas.drawPath(plinth, fill);
            stroke.setColorFilter(null);
            stroke.setShader(band);
            stroke.setStrokeWidth(1.7f);
            stroke.setAlpha(alpha255(shine));
            canvas.drawLine(TIP_X, TIP_Y + 2f, TIP_X, APEX_Y + 1f, stroke);
            stroke.setShader(null);
        }
    }

    private void mastPass(Canvas canvas, float opacity, ColorFilter filter) {
        fill.setColorFilter(filter);
        stroke.setColorFilter(filter);
        canvas.save();
        canvas.translate(1.2f, 2f);
        fill.setShader(depthShader);
        fill.setAlpha(alpha255(.9f * opacity));
        canvas.drawPath(legs, fill);
        canvas.restore();
        fill.setShader(bevelShader);
        fill.setAlpha(alpha255(opacity));
        canvas.drawPath(legs, fill);
        canvas.drawPath(plinth, fill);
        stroke.setShader(null);
        stroke.setStrokeWidth(1f);
        stroke.setColor(tone(.82f, .85f * opacity));
        canvas.drawPath(braces, stroke);
        stroke.setShader(rodShader);
        stroke.setStrokeWidth(1.7f);
        stroke.setAlpha(alpha255(opacity));
        canvas.drawLine(TIP_X, TIP_Y + 2f, TIP_X, APEX_Y + 1f, stroke);
        stroke.setShader(null);
        fill.setColorFilter(null);
        stroke.setColorFilter(null);
    }

    private void drawTip(Canvas canvas, float t, float out, float charge) {
        float pop = Easing.BACK.at(span(t, Timeline.TIP_START, Timeline.TIP_FULL));
        float opacity = ease(t, Timeline.TIP_START, Timeline.TIP_START + 180f) * out;
        if (opacity <= 0f) return;
        fill.setColorFilter(null);
        fill.setShader(haloShader);
        fill.setAlpha(alpha255((.35f + .65f * charge) * opacity));
        canvas.drawCircle(TIP_X, TIP_Y, 11f * pop, fill);
        fill.setShader(ballShader);
        fill.setAlpha(alpha255(opacity));
        canvas.drawCircle(TIP_X, TIP_Y, TIP_RADIUS * pop, fill);
        if (charge > 0f) {
            fill.setColorFilter(charged);
            fill.setAlpha(alpha255(opacity * charge * CHARGE_DEPTH));
            canvas.drawCircle(TIP_X, TIP_Y, TIP_RADIUS * pop, fill);
            fill.setColorFilter(null);
        }
    }

    /** The tuning scale: a rule that opens from the center, its ticks and a needle that locks on the center. */
    private void drawDial(Canvas canvas, float t, float drawn, float charge, float out) {
        if (drawn <= 0f) return;
        float half = ruleWidth / 2f;
        float width = ruleWidth * drawn;
        fill.setColorFilter(null);
        fill.setShader(rule);
        fill.setAlpha(alpha255((.55f + .45f * charge) * out));
        canvas.drawRect(centerX - width / 2f, baselineY, centerX + width / 2f, baselineY + dp(2), fill);

        int ticks = Math.max(11, Math.round(ruleWidth / dp(9)) | 1);
        int middle = ticks / 2;
        stroke.setShader(null);
        stroke.setColorFilter(null);
        for (int k = 0; k < ticks; k++) {
            float x = centerX - half + ruleWidth * k / (ticks - 1);
            float distance = Math.abs(x - centerX) / half;
            float appear = clamp((drawn - distance) * 6f);
            if (appear <= 0f) continue;
            boolean major = (k - middle) % 5 == 0;
            stroke.setStrokeWidth(dp(major ? 1.3f : 1f));
            stroke.setColor(alpha(accent, (major ? .55f : .34f) * (1f - distance * distance) * appear * out));
            canvas.drawLine(x, baselineY - dp(major ? 8 : 4.5f), x, baselineY - dp(1.5f), stroke);
        }

        float visible = ease(t, Timeline.NEEDLE_START, Timeline.NEEDLE_START + 200f) * out;
        if (visible <= 0f) return;
        float travel = Easing.BACK.at(span(t, Timeline.NEEDLE_START, Timeline.NEEDLE_LOCK));
        float x = centerX - half + ruleWidth * (.06f + .44f * travel);
        canvas.save();
        canvas.translate(x, baselineY - dp(3));
        fill.setShader(needleGlow);
        fill.setAlpha(alpha255((.45f + .55f * charge) * visible));
        canvas.drawCircle(0f, 0f, dp(16), fill);
        canvas.restore();
        fill.setShader(null);
        fill.setColor(alpha(mix(light, accent, .55f + .45f * charge), visible));
        oval.set(x - dp(1), baselineY - dp(13), x + dp(1), baselineY + dp(5));
        canvas.drawRoundRect(oval, dp(1), dp(1), fill);
    }

    private Shader glint(float x) {
        matrix.setTranslate(x, 0f);
        glintShader.setLocalMatrix(matrix);
        return glintShader;
    }

    private static float charge(float t) {
        return ease(t, Timeline.CHARGE_START, Timeline.CHARGE_FULL)
                * (1f - ease(t, Timeline.CHARGE_FADE, Timeline.CHARGE_END));
    }

    /** A three-point steel ramp: level 0 is the deepest shadow and 1 the brightest edge. */
    private int tone(float level) {
        return level < .5f ? mix(shade, body, level * 2f) : mix(body, light, (level - .5f) * 2f);
    }

    private int tone(float level, float opacity) {
        return alpha(tone(level), opacity);
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    private static int alpha255(float opacity) {
        return Math.round(clamp(opacity) * 255f);
    }

    private static int alpha(int color, float opacity) {
        return (color & 0xFFFFFF) | (Math.round(clamp(opacity) * Color.alpha(color)) << 24);
    }

    static int mix(int from, int to, float fraction) {
        float f = clamp(fraction);
        return Color.argb(
                Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
    }
}
