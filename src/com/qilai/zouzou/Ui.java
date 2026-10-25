package com.qilai.zouzou;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** 纯代码绘制的几个控件：配色、进度环、开关、图标。 */
final class Ui {
    private Ui() {}

    /** 一套界面配色（浅色 / 深色）。 */
    static final class Palette {
        final boolean dark;
        final int bg, surface, surfaceHigh, text, sub, line;
        final int primary, onPrimary, primaryContainer, onPrimaryContainer;
        final int warn, onWarn, warnContainer, onWarnContainer;
        final int amber, amberContainer, blue, blueContainer;

        private Palette(boolean dark) {
            this.dark = dark;
            if (dark) {
                bg = 0xFF0E1311; surface = 0xFF171E1B; surfaceHigh = 0xFF202925;
                text = 0xFFE3E9E6; sub = 0xFF97A39E; line = 0xFF2A3430;
                primary = 0xFF5FD3A3; onPrimary = 0xFF00382A;
                primaryContainer = 0xFF1C4A3A; onPrimaryContainer = 0xFFBDF0D8;
                warn = 0xFFFF8E84; onWarn = 0xFF5F1410;
                warnContainer = 0xFF4A2320; onWarnContainer = 0xFFFFDAD5;
                amber = 0xFFF2C063; amberContainer = 0xFF3F3218;
                blue = 0xFF8EC5FF; blueContainer = 0xFF1C3248;
            } else {
                bg = 0xFFF4F7F5; surface = 0xFFFFFFFF; surfaceHigh = 0xFFE9F0EC;
                text = 0xFF161D1A; sub = 0xFF5D6964; line = 0xFFE2E8E5;
                primary = 0xFF1A8661; onPrimary = 0xFFFFFFFF;
                primaryContainer = 0xFFD4EFE2; onPrimaryContainer = 0xFF00391F;
                warn = 0xFFD0453C; onWarn = 0xFFFFFFFF;
                warnContainer = 0xFFFDE6E2; onWarnContainer = 0xFF5C0F0A;
                amber = 0xFFB7791F; amberContainer = 0xFFFCEFD6;
                blue = 0xFF2F6FB5; blueContainer = 0xFFDDEBFA;
            }
        }

        static Palette of(Context c) {
            int night = c.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return new Palette(night == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        }
    }

    static float dp(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    static void tick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ------------------------------------------------------------------ 进度环

    /** 圆形进度环，中间放文字。进度变化时平滑过渡；提醒时外圈有呼吸光晕。 */
    static final class Ring extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float stroke;
        private float shown;      // 0..1，当前画出来的进度
        private float target;
        private float glow;       // 0..1，光晕强度
        private boolean pulsing;
        private ValueAnimator progressAnim;
        private final ValueAnimator pulse;

        Ring(Context c, float strokeDp) {
            super(c);
            stroke = dp(c, strokeDp);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            halo.setStyle(Paint.Style.STROKE);
            pulse = ValueAnimator.ofFloat(0f, 1f);
            pulse.setDuration(1400);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    glow = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
        }

        void setColors(int trackColor, int arcColor) {
            track.setColor(trackColor);
            arc.setColor(arcColor);
            halo.setColor(arcColor);
            invalidate();
        }

        void setProgress(float p, boolean animate) {
            p = Math.max(0f, Math.min(1f, p));
            if (p == target && (progressAnim == null || !progressAnim.isRunning())) {
                if (shown != p && !animate) { shown = p; invalidate(); }
                return;
            }
            target = p;
            if (progressAnim != null) progressAnim.cancel();
            if (!animate || !isAttachedToWindow()) {
                shown = p;
                invalidate();
                return;
            }
            progressAnim = ValueAnimator.ofFloat(shown, p);
            progressAnim.setDuration(700);
            progressAnim.setInterpolator(new DecelerateInterpolator(1.6f));
            progressAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    shown = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            progressAnim.start();
        }

        void setPulsing(boolean on) {
            if (on == pulsing) return;
            pulsing = on;
            if (on) pulse.start();
            else { pulse.cancel(); glow = 0; invalidate(); }
        }

        @Override protected void onDetachedFromWindow() {
            pulse.cancel();
            if (progressAnim != null) progressAnim.cancel();
            super.onDetachedFromWindow();
        }

        @Override protected void onMeasure(int w, int h) {
            int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
            if (MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED) size = MeasureSpec.getSize(w);
            setMeasuredDimension(size, size);
        }

        @Override protected void onDraw(Canvas c) {
            float pad = stroke * 1.6f;
            box.set(pad, pad, getWidth() - pad, getHeight() - pad);
            if (glow > 0) {
                halo.setStrokeWidth(stroke * (0.6f + glow * 1.4f));
                halo.setAlpha((int) (70 * (1f - glow * 0.6f)));
                c.drawOval(box, halo);
            }
            c.drawOval(box, track);
            if (shown > 0.001f) c.drawArc(box, -90f, 360f * shown, false, arc);
        }
    }

    // ------------------------------------------------------------------ 开关

    interface OnToggle { void onToggle(Toggle t, boolean on); }

    /** Material 3 风格的开关：药丸形轨道，选中时圆点变大并滑到右边。 */
    static final class Toggle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Palette p;
        private boolean checked;
        private float pos;   // 0 关 .. 1 开
        private ValueAnimator anim;
        private OnToggle listener;

        Toggle(Context c, Palette palette) {
            super(c);
            p = palette;
            setClickable(true);
            setFocusable(true);
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { toggleByUser(); }
            });
        }

        void setListener(OnToggle l) { listener = l; }
        boolean isChecked() { return checked; }

        void toggleByUser() {
            tick(this);
            setChecked(!checked, true);
            if (listener != null) listener.onToggle(this, checked);
        }

        /** 只改外观，不触发回调。 */
        void setChecked(boolean on, boolean animate) {
            checked = on;
            setContentDescription(on ? "已开启" : "已关闭");
            if (anim != null) anim.cancel();
            float to = on ? 1f : 0f;
            if (!animate || !isAttachedToWindow()) { pos = to; invalidate(); return; }
            anim = ValueAnimator.ofFloat(pos, to);
            anim.setDuration(180);
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    pos = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            anim.start();
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(dp(getContext(), 52)), Math.round(dp(getContext(), 32)));
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            float b = dp(getContext(), 2);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(p.surfaceHigh, p.primary, pos));
            r.set(0, 0, w, h);
            c.drawRoundRect(r, rad, rad, paint);
            if (pos < 1f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(b);
                paint.setColor(withAlpha(p.sub, (int) (255 * (1f - pos))));
                r.set(b / 2, b / 2, w - b / 2, h - b / 2);
                c.drawRoundRect(r, rad - b / 2, rad - b / 2, paint);
            }
            float knob = dp(getContext(), 8) + dp(getContext(), 4) * pos;   // 半径 8dp → 12dp
            float cx = rad + (w - 2 * rad) * pos;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blend(p.sub, p.onPrimary, pos));
            c.drawCircle(cx, h / 2f, knob, paint);
        }
    }

    // ------------------------------------------------------------------ 图标

    static final int ICON_TIMER = 0, ICON_CHART = 1, ICON_TUNE = 2, ICON_CHEVRON = 3,
            ICON_SEAT = 4, ICON_FLAG = 5, ICON_BELL = 6, ICON_STEPS = 7;

    /** 用线条画的简单图标（24dp 网格），避免引入图片资源。 */
    static final class Icon extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;
        private final int size;

        Icon(Context c, int kind, int color, float sizeDp) {
            this.kind = kind;
            size = Math.round(dp(c, sizeDp));
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int color) { paint.setColor(color); invalidateSelf(); }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }

        @Override public void draw(Canvas c) {
            android.graphics.Rect b = getBounds();
            float u = b.width() / 24f;
            c.save();
            c.translate(b.left, b.top);
            c.scale(u, u);
            paint.setStrokeWidth(2f);
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            switch (kind) {
                case ICON_TIMER:
                    c.drawCircle(12, 13, 8, paint);
                    path.moveTo(12, 13); path.lineTo(12, 9);
                    path.moveTo(10, 2.5f); path.lineTo(14, 2.5f);
                    path.moveTo(18.5f, 5.5f); path.lineTo(19.8f, 6.8f);
                    c.drawPath(path, paint);
                    break;
                case ICON_CHART:
                    path.moveTo(5, 20); path.lineTo(5, 13);
                    path.moveTo(12, 20); path.lineTo(12, 5);
                    path.moveTo(19, 20); path.lineTo(19, 10);
                    paint.setStrokeWidth(3f);
                    c.drawPath(path, paint);
                    break;
                case ICON_TUNE:
                    path.moveTo(4, 7); path.lineTo(20, 7);
                    path.moveTo(4, 17); path.lineTo(20, 17);
                    c.drawPath(path, paint);
                    paint.setStyle(Paint.Style.FILL);
                    c.drawCircle(9, 7, 3f, paint);
                    c.drawCircle(15, 17, 3f, paint);
                    break;
                case ICON_CHEVRON:
                    path.moveTo(9, 6); path.lineTo(15, 12); path.lineTo(9, 18);
                    c.drawPath(path, paint);
                    break;
                case ICON_SEAT:   // 沙漏：最长一次连续坐
                    path.moveTo(6, 3); path.lineTo(18, 3);
                    path.moveTo(6, 21); path.lineTo(18, 21);
                    path.moveTo(7.5f, 3); path.lineTo(7.5f, 6); path.lineTo(12, 12); path.lineTo(16.5f, 6); path.lineTo(16.5f, 3);
                    path.moveTo(7.5f, 21); path.lineTo(7.5f, 18); path.lineTo(12, 12); path.lineTo(16.5f, 18); path.lineTo(16.5f, 21);
                    c.drawPath(path, paint);
                    break;
                case ICON_FLAG:   // 向上箭头：起身
                    path.moveTo(12, 20); path.lineTo(12, 5);
                    path.moveTo(6, 11); path.lineTo(12, 5); path.lineTo(18, 11);
                    c.drawPath(path, paint);
                    break;
                case ICON_BELL:
                    path.moveTo(6, 17); path.lineTo(6, 11);
                    path.cubicTo(6, 7.5f, 8.5f, 5, 12, 5);
                    path.cubicTo(15.5f, 5, 18, 7.5f, 18, 11);
                    path.lineTo(18, 17); path.lineTo(4.5f, 17); path.lineTo(19.5f, 17);
                    path.moveTo(10, 20.5f); path.lineTo(14, 20.5f);
                    c.drawPath(path, paint);
                    break;
                case ICON_STEPS:  // 两个脚印
                    paint.setStyle(Paint.Style.FILL);
                    RectF o = new RectF(5, 4, 10, 12);
                    c.drawOval(o, paint);
                    c.drawCircle(7.5f, 15, 2f, paint);
                    o.set(14, 8, 19, 16);
                    c.drawOval(o, paint);
                    c.drawCircle(16.5f, 19, 2f, paint);
                    break;
                default:
                    break;
            }
            c.restore();
        }

        @Override public void setAlpha(int a) { paint.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter f) { paint.setColorFilter(f); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ------------------------------------------------------------------ 颜色

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return (Math.round(aa + (ba - aa) * t) << 24)
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }
}
