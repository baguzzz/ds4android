package com.example.ds4remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {

    public interface OnMoveListener {
        void onMove(float x, float y);
    }

    private float cx, cy, baseRadius, stickRadius;
    private float stickX, stickY;
    private Paint basePaint, borderPaint, stickPaint;
    private int activePointer = -1;
    private OnMoveListener listener;

    public JoystickView(Context c) { super(c); init(); }
    public JoystickView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setColor(0x33FFFFFF);
        borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setColor(0x88FFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(4f);
        stickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stickPaint.setColor(0xCC4FC3F7);
    }

    public void setOnMoveListener(OnMoveListener l) { listener = l; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cx = w / 2f; cy = h / 2f;
        baseRadius = Math.min(w, h) * 0.42f;
        stickRadius = baseRadius * 0.42f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawCircle(cx, cy, baseRadius, basePaint);
        canvas.drawCircle(cx, cy, baseRadius, borderPaint);
        canvas.drawCircle(cx + stickX * baseRadius, cy + stickY * baseRadius,
                           stickRadius, stickPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                if (activePointer == -1) {
                    float dx = e.getX(idx) - cx, dy = e.getY(idx) - cy;
                    if (dx*dx + dy*dy <= baseRadius*baseRadius*1.5f) {
                        activePointer = e.getPointerId(idx);
                        updateStick(e.getX(idx), e.getY(idx));
                    }
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activePointer != -1) {
                    int idx = e.findPointerIndex(activePointer);
                    if (idx >= 0) updateStick(e.getX(idx), e.getY(idx));
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (e.getPointerId(e.getActionIndex()) == activePointer) {
                    activePointer = -1;
                    stickX = stickY = 0;
                    if (listener != null) listener.onMove(0, 0);
                    invalidate();
                }
                break;
            }
        }
        return true;
    }

    private void updateStick(float px, float py) {
        float dx = px - cx, dy = py - cy;
        float dist = (float)Math.sqrt(dx*dx + dy*dy);
        if (dist > baseRadius) {
            dx = dx / dist * baseRadius;
            dy = dy / dist * baseRadius;
        }
        stickX = dx / baseRadius;
        stickY = dy / baseRadius;
        if (listener != null) listener.onMove(stickX, stickY);
        invalidate();
    }
}