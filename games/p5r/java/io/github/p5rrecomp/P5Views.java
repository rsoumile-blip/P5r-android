package io.github.p5rrecomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** Small P5-inspired launcher accents; these views are never used by the game renderer. */
final class P5Views {
    private P5Views() {}

    static final class Backdrop extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int wallpaper;
        private boolean animated;
        private float phase;
        private final Runnable tick = new Runnable() {
            @Override public void run() { if (animated && isAttachedToWindow()) { phase += 0.035f; invalidate(); postDelayed(this, 100); } }
        };
        Backdrop(Context c, int wallpaper, boolean animated) { super(c); setAppearance(wallpaper, animated); }
        void setAppearance(int wallpaper, boolean animated) {
            removeCallbacks(tick);
            this.wallpaper = Math.max(0, Math.min(2, wallpaper));
            this.animated = animated;
            if (animated && isAttachedToWindow()) post(tick);
            invalidate();
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (animated) post(tick); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int base = wallpaper == 1 ? Color.rgb(8, 12, 30) : wallpaper == 2 ? Color.rgb(24, 3, 9) : Color.rgb(16, 4, 7);
            int accent = wallpaper == 1 ? Color.rgb(26, 36, 76) : wallpaper == 2 ? Color.rgb(112, 0, 18) : Color.rgb(84, 0, 12);
            c.drawColor(base);
            paint.setColor(accent); paint.setStyle(Paint.Style.FILL);
            Path p = new Path();
            float shift = animated ? (float)Math.sin(phase) * getWidth() * .025f : 0;
            p.moveTo(getWidth()*.72f + shift, 0); p.lineTo(getWidth(), 0); p.lineTo(getWidth(), getHeight()*.38f); p.lineTo(getWidth()*.84f, getHeight()*.48f); p.close(); c.drawPath(p, paint);
            paint.setColor(0x18ffffff); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.2f);
            for (int y=16; y<getHeight(); y+=56) for(int x=16; x<getWidth(); x+=56) c.drawCircle(x,y,1f,paint);
        }
    }

    static final class Sidebar extends LinearLayout {
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        Sidebar(Context c) { super(c); setWillNotDraw(false); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            edge.setStyle(Paint.Style.FILL); edge.setColor(0xFFe8101f);
            Path red = new Path(); red.moveTo(0,0); red.lineTo(w,0); red.lineTo(w*.91f,h); red.lineTo(0,h); red.close(); c.drawPath(red,edge);
            edge.setColor(0xFF0b0708);
            Path black = new Path(); black.moveTo(0,0); black.lineTo(w*.97f,0); black.lineTo(w*.88f,h); black.lineTo(0,h); black.close(); c.drawPath(black,edge);
            edge.setColor(0xE6ffffff); edge.setStrokeWidth(Math.max(1f,w*.004f)); edge.setStyle(Paint.Style.STROKE);
            c.drawLine(w*.97f,0,w*.88f,h,edge); edge.setStyle(Paint.Style.FILL);
        }
    }

    static final class SlashButton extends Button {
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float x = -180f;
        private int frames;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                x += Math.max(12f, getWidth() / 18f);
                if (++frames < 18) { invalidate(); postDelayed(this, 32); }
            }
        };
        SlashButton(Context c) { super(c); }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); post(tick); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int save = c.save(); c.clipRect(new RectF(0, 0, getWidth(), getHeight()));
            sheen.setShader(new LinearGradient(x-90, 0, x+90, getHeight(),
                    new int[]{0x00ffffff,0x70ffffff,0x00ffffff}, null, Shader.TileMode.CLAMP));
            Path slash = new Path(); slash.moveTo(x-35,0); slash.lineTo(x+15,0);
            slash.lineTo(x-45,getHeight()); slash.lineTo(x-95,getHeight()); slash.close();
            c.drawPath(slash,sheen); sheen.setShader(null); c.restoreToCount(save);
        }
    }
}
