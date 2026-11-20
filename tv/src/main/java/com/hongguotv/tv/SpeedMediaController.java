package com.hongguotv.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.TextView;

import java.util.Locale;

/**
 * 系统 MediaController + 播放器内置倍速（点按切换；上下键加减）。
 */
public class SpeedMediaController extends MediaController {
    public interface OnSpeedChangeListener {
        void onSpeedSelected(float speed);
    }

    private static final float[] STEPS = {
            0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.25f, 2.5f, 2.75f, 3.0f
    };

    private TextView speedView;
    private float speed = 1.5f;
    private OnSpeedChangeListener listener;
    private boolean injected;

    public SpeedMediaController(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SpeedMediaController(Context context) {
        super(context, true);
    }

    public void setOnSpeedChangeListener(OnSpeedChangeListener l) {
        this.listener = l;
    }

    public void setSpeedLabel(float s) {
        this.speed = s;
        if (speedView != null) {
            speedView.setText(format(s));
        }
    }

    public float getSpeed() {
        return speed;
    }

    @Override
    public void setAnchorView(View view) {
        super.setAnchorView(view);
        injectSpeedControl();
    }

    private void injectSpeedControl() {
        if (injected) {
            setSpeedLabel(speed);
            return;
        }
        try {
            if (getChildCount() == 0) return;
            View root = getChildAt(0);
            LinearLayout bar = findControlBar(root);
            if (bar == null) {
                if (root instanceof ViewGroup) {
                    bar = new LinearLayout(getContext());
                    bar.setOrientation(LinearLayout.HORIZONTAL);
                    bar.setGravity(Gravity.CENTER_VERTICAL);
                    ((ViewGroup) root).addView(bar, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | Gravity.END));
                } else {
                    return;
                }
            }
            speedView = new TextView(getContext());
            speedView.setText(format(speed));
            speedView.setTextColor(Color.WHITE);
            speedView.setTextSize(16f);
            speedView.setTypeface(Typeface.DEFAULT_BOLD);
            speedView.setPadding(dp(16), dp(10), dp(16), dp(10));
            speedView.setBackgroundColor(0x66000000);
            speedView.setFocusable(true);
            speedView.setClickable(true);
            speedView.setContentDescription("播放倍速");
            speedView.setOnClickListener(v -> stepSpeed(true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMargins(dp(8), 0, dp(8), 0);
            bar.addView(speedView, lp);
            injected = true;
        } catch (Exception ignored) {}
    }

    /** forward=true 加快，false 减慢；到边界停止 */
    public void stepSpeed(boolean forward) {
        int idx = nearestIndex(speed);
        if (forward) {
            if (idx < STEPS.length - 1) idx++;
        } else {
            if (idx > 0) idx--;
        }
        float next = STEPS[idx];
        if (Math.abs(next - speed) < 0.01f) return;
        speed = next;
        setSpeedLabel(speed);
        if (listener != null) listener.onSpeedSelected(speed);
    }

    private static int nearestIndex(float s) {
        int idx = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < STEPS.length; i++) {
            float d = Math.abs(STEPS[i] - s);
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        return idx;
    }

    private static LinearLayout findControlBar(View root) {
        if (root instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) root;
            if (ll.getOrientation() == LinearLayout.HORIZONTAL && ll.getChildCount() >= 2) {
                return ll;
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                LinearLayout found = findControlBar(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private int dp(int v) {
        float d = getContext().getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private static String format(float s) {
        if (Math.abs(s - Math.round(s)) < 0.01f) {
            return String.format(Locale.US, "%dx", Math.round(s));
        }
        return String.format(Locale.US, "%.2fx", s);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_DPAD_UP
                    || code == KeyEvent.KEYCODE_CHANNEL_UP
                    || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                stepSpeed(true);
                show();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN
                    || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                    || code == KeyEvent.KEYCODE_MEDIA_REWIND) {
                stepSpeed(false);
                show();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
}
