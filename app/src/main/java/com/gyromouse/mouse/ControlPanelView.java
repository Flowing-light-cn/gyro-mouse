package com.gyromouse.mouse;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * 控制面板。每个按钮和倾角指示器都有独立的归一化位置/大小（相对屏幕宽高），
 * 默认是右侧一列；编辑模式下可拖动、拖右下角圆点缩放，布局可保存。
 */
public class ControlPanelView extends View {

    public interface Listener {
        void onButtonDown(int mask);

        void onButtonUp(int mask);

        void onWheel(int delta);
    }

    public static final int BTN_LEFT = 1;
    public static final int BTN_RIGHT = 2;
    public static final int BTN_MIDDLE = 4;
    public static final int BTN_BACK = 8;
    public static final int BTN_FORWARD = 16;

    public static final int ITEM_LEFT = 0;
    public static final int ITEM_RIGHT = 1;
    public static final int ITEM_WHEEL = 2;
    public static final int ITEM_BACK = 3;
    public static final int ITEM_FORWARD = 4;
    public static final int ITEM_DIAL = 5;
    public static final int ITEM_COUNT = 6;

    private static final int COLOR_BG = 0xFF101418;
    private static final int COLOR_BTN = 0xFF1B232B;
    private static final int COLOR_PRESSED = 0xFF32424F;
    private static final int COLOR_ACCENT = 0xFF4FC3F7;
    private static final int COLOR_TEXT = 0xFFE3EAF2;
    private static final int COLOR_MUTED = 0xFF8794A3;
    private static final int COLOR_BORDER = 0xFF2A343D;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF[] items = new RectF[ITEM_COUNT];
    private final RectF[] pxCache = new RectF[ITEM_COUNT];

    private final float density;
    private int pressedMask = 0;
    private Listener listener;

    private boolean customLayout = false;
    private boolean editMode = false;
    private int editItem = -1;
    private boolean editResize = false;
    private float editDownX = 0f;
    private float editDownY = 0f;
    private final RectF editStart = new RectF();

    private float wheelStepDp = 34f;

    private final SparseIntArray pointerButton = new SparseIntArray();
    private final HashMap<Integer, Float> scrollLastY = new HashMap<>();
    private final HashMap<Integer, Float> scrollAccum = new HashMap<>();
    private final HashMap<Integer, Long> scrollDownTime = new HashMap<>();
    private final HashSet<Integer> scrollMoved = new HashSet<>();

    private float tiltX = 0f;
    private float tiltY = 0f;
    private float tiltAngle = 0f;

    public ControlPanelView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < ITEM_COUNT; i++) {
            items[i] = new RectF();
            pxCache[i] = new RectF();
        }
        text.setTextAlign(Paint.Align.CENTER);
        dash.setStyle(Paint.Style.STROKE);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setEditMode(boolean on) {
        if (editMode == on) return;
        editMode = on;
        editItem = -1;
        editResize = false;
        invalidate();
    }

    public boolean isEditMode() {
        return editMode;
    }

    /** 手指每滑动多少 dp 滚动一格；值越小越灵敏。 */
    public void setWheelStepDp(float stepDp) {
        wheelStepDp = Math.max(6f, Math.min(120f, stepDp));
    }

    public void setTilt(float x, float y, float angle) {
        if (Math.abs(x - tiltX) < 0.0005f && Math.abs(y - tiltY) < 0.0005f
                && Math.abs(angle - tiltAngle) < 0.05f) {
            return;
        }
        tiltX = x;
        tiltY = y;
        tiltAngle = angle;
        postInvalidateOnAnimation();
    }

    /** 导出归一化布局，每项为 "left,top,right,bottom"。 */
    public String[] exportLayout() {
        String[] out = new String[ITEM_COUNT];
        for (int i = 0; i < ITEM_COUNT; i++) {
            out[i] = items[i].left + "," + items[i].top + "," + items[i].right + "," + items[i].bottom;
        }
        return out;
    }

    /** 导入归一化布局；数据不合法时保持当前布局。 */
    public void importLayout(String[] data) {
        if (data == null || data.length != ITEM_COUNT) return;
        RectF[] parsed = new RectF[ITEM_COUNT];
        try {
            for (int i = 0; i < ITEM_COUNT; i++) {
                if (data[i] == null) return;
                String[] p = data[i].split(",");
                if (p.length != 4) return;
                float l = Float.parseFloat(p[0]);
                float t = Float.parseFloat(p[1]);
                float r = Float.parseFloat(p[2]);
                float b = Float.parseFloat(p[3]);
                if (r <= l || b <= t) return;
                parsed[i] = new RectF(l, t, r, b);
            }
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < ITEM_COUNT; i++) {
            items[i].set(parsed[i]);
        }
        customLayout = true;
        invalidate();
    }

    /** 恢复默认布局。 */
    public void resetLayout() {
        customLayout = false;
        computeDefault(getWidth(), getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!customLayout) {
            computeDefault(w, h);
        }
    }

    private void computeDefault(int w, int h) {
        if (w <= 0 || h <= 0) return;
        float m = dp(10);
        float gap = dp(10);
        float bottom = h - dp(14);
        float panelW = Math.min(dp(200), w * 0.44f);
        float x0 = w - panelW;
        float x1 = w;

        float avail = bottom - dp(8);
        float sideH = Math.min(dp(42), avail * 0.12f);
        float rest = Math.max(dp(60), avail - sideH - gap * 3);
        float btnH = rest * 0.30f;
        float wheelH = rest * 0.40f;

        float leftTop = bottom - btnH;
        float rightTop = leftTop - gap - btnH;
        float wheelTop = rightTop - gap - wheelH;
        float sideTop = wheelTop - gap - sideH;

        setPx(ITEM_LEFT, x0 + m, leftTop, x1 - m, bottom, w, h);
        setPx(ITEM_RIGHT, x0 + m, rightTop, x1 - m, leftTop - gap, w, h);
        setPx(ITEM_WHEEL, x0 + m, wheelTop, x1 - m, rightTop - gap, w, h);
        setPx(ITEM_BACK, x0 + m, sideTop, (x0 + x1) / 2f - gap / 2f, wheelTop - gap, w, h);
        setPx(ITEM_FORWARD, (x0 + x1) / 2f + gap / 2f, sideTop, x1 - m, wheelTop - gap, w, h);

        float dialAreaW = w - panelW;
        float dialBottom = sideTop - gap;
        float dialH = Math.max(0, dialBottom - dp(10));
        float r = Math.min(dp(110), Math.min(dialAreaW * 0.36f, dialH * 0.42f));
        float cx = dialAreaW / 2f;
        float cy = dp(10) + dialH / 2f;
        setPx(ITEM_DIAL, cx - r, cy - r, cx + r, cy + r, w, h);
    }

    private void setPx(int i, float l, float t, float r, float b, int w, int h) {
        items[i].set(l / w, t / h, r / w, b / h);
    }

    private RectF px(int i) {
        RectF r = pxCache[i];
        r.set(items[i].left * getWidth(), items[i].top * getHeight(),
                items[i].right * getWidth(), items[i].bottom * getHeight());
        return r;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(COLOR_BG);
        drawDial(c, px(ITEM_DIAL));
        drawButton(c, px(ITEM_LEFT), "左键", BTN_LEFT);
        drawButton(c, px(ITEM_RIGHT), "右键", BTN_RIGHT);
        drawWheel(c, px(ITEM_WHEEL));
        drawButton(c, px(ITEM_BACK), "后退", BTN_BACK);
        drawButton(c, px(ITEM_FORWARD), "前进", BTN_FORWARD);
        if (editMode) {
            drawEditOverlay(c);
        }
    }

    private void drawButton(Canvas c, RectF r, String label, int mask) {
        if (r.width() <= 0 || r.height() <= 0) return;
        boolean pressed = (pressedMask & mask) != 0;
        float rad = Math.min(dp(18), r.height() / 2f - dp(1));

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(pressed ? COLOR_PRESSED : COLOR_BTN);
        c.drawRoundRect(r, rad, rad, fill);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.2f));
        stroke.setColor(pressed ? COLOR_ACCENT : COLOR_BORDER);
        c.drawRoundRect(r, rad, rad, stroke);

        text.setStyle(Paint.Style.FILL);
        text.setColor(pressed ? COLOR_ACCENT : COLOR_TEXT);
        text.setTextSize(sp(17));
        text.setTextAlign(Paint.Align.CENTER);
        float baseline = r.centerY() - (text.ascent() + text.descent()) / 2f;
        c.drawText(label, r.centerX(), baseline, text);
    }

    private void drawWheel(Canvas c, RectF r) {
        if (r.width() <= 0 || r.height() <= 0) return;
        boolean active = !scrollLastY.isEmpty() || (pressedMask & BTN_MIDDLE) != 0;
        float rad = Math.min(dp(18), r.height() / 2f - dp(1));

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(active ? COLOR_PRESSED : COLOR_BTN);
        c.drawRoundRect(r, rad, rad, fill);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1.2f));
        stroke.setColor(active ? COLOR_ACCENT : COLOR_BORDER);
        c.drawRoundRect(r, rad, rad, stroke);

        text.setStyle(Paint.Style.FILL);
        text.setColor(active ? COLOR_ACCENT : COLOR_TEXT);
        text.setTextSize(sp(17));
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("滚轮", r.centerX(), r.centerY() - dp(8), text);

        if (r.height() > dp(70)) {
            text.setColor(COLOR_MUTED);
            text.setTextSize(sp(11));
            c.drawText("上下滑动", r.centerX(), r.centerY() + dp(12), text);
            c.drawText("轻点 = 中键", r.centerX(), r.centerY() + dp(27), text);
        }
    }

    private void drawDial(Canvas c, RectF r) {
        float radius = Math.min(r.width(), r.height()) / 2f;
        if (radius < dp(16)) return;
        float cx = r.centerX();
        float cy = r.centerY();

        accent.setStyle(Paint.Style.STROKE);
        accent.setStrokeWidth(dp(1.5f));
        accent.setColor(0x334FC3F7);
        c.drawCircle(cx, cy, radius, accent);

        accent.setStrokeWidth(dp(1));
        accent.setColor(0x224FC3F7);
        c.drawLine(cx - radius, cy, cx + radius, cy, accent);
        c.drawLine(cx, cy - radius, cx, cy + radius, accent);

        float maxOffset = Math.max(0, radius - dp(14));
        float bx = cx + tiltX * maxOffset;
        float by = cy + tiltY * maxOffset;
        float dx = bx - cx;
        float dy = by - cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len > maxOffset && len > 0) {
            bx = cx + dx / len * maxOffset;
            by = cy + dy / len * maxOffset;
        }

        accent.setStyle(Paint.Style.FILL);
        accent.setColor(0x554FC3F7);
        c.drawCircle(bx, by, dp(17), accent);
        accent.setColor(COLOR_ACCENT);
        c.drawCircle(bx, by, dp(10), accent);

        text.setStyle(Paint.Style.FILL);
        text.setColor(COLOR_MUTED);
        text.setTextSize(sp(12));
        text.setTextAlign(Paint.Align.CENTER);
        String label = String.format(Locale.US, "倾角 %.1f°", tiltAngle);
        float baseline = cy + radius + dp(18) - (text.ascent() + text.descent()) / 2f;
        c.drawText(label, cx, baseline, text);
    }

    private void drawEditOverlay(Canvas c) {
        dash.setPathEffect(new DashPathEffect(new float[]{dp(7), dp(5)}, 0));
        dash.setStrokeWidth(dp(1.5f));
        for (int i = 0; i < ITEM_COUNT; i++) {
            RectF r = px(i);
            dash.setColor(i == editItem ? COLOR_ACCENT : 0x774FC3F7);
            c.drawRoundRect(r, dp(10), dp(10), dash);

            fill.setStyle(Paint.Style.FILL);
            fill.setColor(i == editItem ? COLOR_ACCENT : 0x664FC3F7);
            c.drawCircle(r.right, r.bottom, dp(9), fill);
        }
        dash.setPathEffect(null);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editMode) {
            return handleEditTouch(e);
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                onPointerDown(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (scrollLastY.containsKey(id)) {
                        onScrollMove(id, e.getY(i));
                    }
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                onPointerUp(e.getPointerId(i));
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelAll();
                break;
            }
            default:
                break;
        }
        return true;
    }

    private boolean handleEditTouch(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                editItem = -1;
                editResize = false;
                for (int i = 0; i < ITEM_COUNT; i++) {
                    RectF r = px(i);
                    float hx = x - r.right;
                    float hy = y - r.bottom;
                    if (Math.sqrt(hx * hx + hy * hy) < dp(26)) {
                        editItem = i;
                        editResize = true;
                        break;
                    }
                }
                if (editItem < 0) {
                    for (int i = 0; i < ITEM_COUNT; i++) {
                        RectF r = px(i);
                        if (r.contains(x, y)) {
                            editItem = i;
                            break;
                        }
                    }
                }
                if (editItem >= 0) {
                    editDownX = x;
                    editDownY = y;
                    editStart.set(items[editItem]);
                    invalidate();
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (editItem < 0 || getWidth() <= 0 || getHeight() <= 0) break;
                float dx = (x - editDownX) / getWidth();
                float dy = (y - editDownY) / getHeight();
                RectF r = items[editItem];
                if (editResize) {
                    float minW = dp(56) / getWidth();
                    float minH = dp(36) / getHeight();
                    float right = Math.max(editStart.left + minW,
                            Math.min(1f, editStart.right + dx));
                    float bottom = Math.max(editStart.top + minH,
                            Math.min(1f, editStart.bottom + dy));
                    r.set(editStart.left, editStart.top, right, bottom);
                } else {
                    float w = editStart.width();
                    float h = editStart.height();
                    float l = Math.max(0f, Math.min(1f - w, editStart.left + dx));
                    float t = Math.max(0f, Math.min(1f - h, editStart.top + dy));
                    r.set(l, t, l + w, t + h);
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                editItem = -1;
                editResize = false;
                invalidate();
                break;
            }
            default:
                break;
        }
        return true;
    }

    private void onPointerDown(int id, float x, float y) {
        if (px(ITEM_WHEEL).contains(x, y)) {
            scrollLastY.put(id, y);
            scrollAccum.put(id, 0f);
            scrollDownTime.put(id, SystemClock.uptimeMillis());
            scrollMoved.remove(id);
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            invalidate();
            return;
        }

        int mask = 0;
        if (px(ITEM_LEFT).contains(x, y)) mask = BTN_LEFT;
        else if (px(ITEM_RIGHT).contains(x, y)) mask = BTN_RIGHT;
        else if (px(ITEM_BACK).contains(x, y)) mask = BTN_BACK;
        else if (px(ITEM_FORWARD).contains(x, y)) mask = BTN_FORWARD;

        if (mask != 0) {
            pointerButton.put(id, mask);
            pressedMask |= mask;
            if (listener != null) listener.onButtonDown(mask);
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            invalidate();
        }
    }

    private void onScrollMove(int id, float y) {
        Float last = scrollLastY.get(id);
        Float acc = scrollAccum.get(id);
        if (last == null || acc == null) return;

        float dy = y - last;
        scrollLastY.put(id, y);

        float accum = acc + dy;
        float step = dp(wheelStepDp);
        int wheel = 0;
        while (accum <= -step) {
            wheel += 1;
            accum += step;
        }
        while (accum >= step) {
            wheel -= 1;
            accum -= step;
        }
        scrollAccum.put(id, accum);

        if (wheel != 0) {
            scrollMoved.add(id);
            if (listener != null) listener.onWheel(wheel);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void onPointerUp(int id) {
        if (scrollLastY.containsKey(id)) {
            Float accObj = scrollAccum.get(id);
            float acc = accObj == null ? 0f : accObj;
            Long downObj = scrollDownTime.get(id);
            long downTime = downObj == null ? 0L : downObj;
            long dt = SystemClock.uptimeMillis() - downTime;
            boolean moved = scrollMoved.contains(id);

            scrollLastY.remove(id);
            scrollAccum.remove(id);
            scrollDownTime.remove(id);
            scrollMoved.remove(id);

            if (!moved && Math.abs(acc) < dp(18) && dt < 400) {
                if (listener != null) {
                    listener.onButtonDown(BTN_MIDDLE);
                    listener.onButtonUp(BTN_MIDDLE);
                }
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            invalidate();
            return;
        }

        int mask = pointerButton.get(id, 0);
        if (mask != 0) {
            pointerButton.delete(id);
            boolean stillPressed = false;
            for (int i = 0; i < pointerButton.size(); i++) {
                if (pointerButton.valueAt(i) == mask) {
                    stillPressed = true;
                    break;
                }
            }
            if (!stillPressed) {
                pressedMask &= ~mask;
                if (listener != null) listener.onButtonUp(mask);
            }
            invalidate();
        }
    }

    private void cancelAll() {
        for (int i = 0; i < pointerButton.size(); i++) {
            int mask = pointerButton.valueAt(i);
            if (listener != null) listener.onButtonUp(mask);
        }
        pointerButton.clear();
        for (Integer id : new HashSet<>(scrollLastY.keySet())) {
            if (listener != null) listener.onButtonUp(BTN_MIDDLE);
        }
        scrollLastY.clear();
        scrollAccum.clear();
        scrollDownTime.clear();
        scrollMoved.clear();
        pressedMask = 0;
        invalidate();
    }

    private float dp(float v) {
        return v * density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
