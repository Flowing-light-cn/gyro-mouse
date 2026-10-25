package com.gyromouse.mouse;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 读取重力方向（TYPE_GRAVITY，由系统融合加速度计+陀螺仪得到），
 * 以启动/校准时的姿态为零点，把倾斜角映射为鼠标移动速度。
 */
public class TiltController implements SensorEventListener {

    public interface Sink {
        void onMove(int dx, int dy);
    }

    private static final float DEADZONE_DEG = 2.5f;
    private static final float MAX_TILT_DEG = 30f;
    private static final float CURVE = 1.6f;
    private static final float ALPHA = 0.62f;
    private static final long TICK_MS = 10L;

    private final SensorManager sensorManager;
    private final Sensor sensor;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Sink sink;

    private volatile float ux = 0f;
    private volatile float uy = 0f;
    private volatile float uz = 1f;
    private volatile float angleDeg = 0f;
    private volatile float lastDirX = 0f;
    private volatile float lastDirY = 0f;
    private volatile boolean inverted = false;

    private boolean hasData = false;
    private boolean zChecked = false;
    private boolean invertZ = false;

    private float n0x = 0f;
    private float n0y = 0f;
    private float n0z = 1f;
    private boolean calibrated = false;

    private float maxSpeed = 1400f;
    private double accX = 0;
    private double accY = 0;
    private long lastTick = 0;
    private boolean running = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            tick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    public TiltController(Context context, Sink sink) {
        this.sink = sink;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor s = null;
        if (sensorManager != null) {
            s = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        }
        this.sensor = s;
    }

    public boolean isAvailable() {
        return sensor != null;
    }

    public void start() {
        if (running) return;
        running = true;
        if (sensor != null) {
            try {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
            } catch (Exception ignored) {
            }
        }
        lastTick = SystemClock.uptimeMillis();
        handler.post(ticker);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(ticker);
        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Exception ignored) {
            }
        }
        accX = 0;
        accY = 0;
    }

    public void calibrate() {
        if (!hasData) return;
        n0x = ux;
        n0y = uy;
        n0z = uz;
        calibrated = true;
        accX = 0;
        accY = 0;
    }

    /** 0..100 的灵敏度档位，映射到 300..3500 单位/秒。 */
    public void setSpeedLevel(int level) {
        level = Math.max(0, Math.min(100, level));
        maxSpeed = 300f + level * 32f;
    }

    /** 陀螺仪反向：手机倒置/反装握持时把移动方向整体翻转。 */
    public void setInverted(boolean value) {
        inverted = value;
    }

    public float[] getState() {
        return new float[]{lastDirX, lastDirY, angleDeg};
    }

    /**
     * 把当前倾角（度）映射为指示器上的径向比例 0..1：
     * 死区内为 0（小球居中），到达 MAX_TILT_DEG 时为 1（小球贴边）。
     */
    public static float normalizeTilt(float angleDeg) {
        float t = (angleDeg - DEADZONE_DEG) / (MAX_TILT_DEG - DEADZONE_DEG);
        return Math.max(0f, Math.min(1f, t));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1f) return;
        x /= len;
        y /= len;
        z /= len;

        if (!zChecked) {
            zChecked = true;
            invertZ = z < 0f;
        }
        if (invertZ) {
            x = -x;
            y = -y;
            z = -z;
        }

        if (!hasData) {
            ux = x;
            uy = y;
            uz = z;
            hasData = true;
            if (!calibrated) {
                n0x = x;
                n0y = y;
                n0z = z;
                calibrated = true;
            }
            return;
        }

        ux = ALPHA * ux + (1f - ALPHA) * x;
        uy = ALPHA * uy + (1f - ALPHA) * y;
        uz = ALPHA * uz + (1f - ALPHA) * z;
        float l = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (l > 0.0001f) {
            ux /= l;
            uy /= l;
            uz /= l;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void tick() {
        long now = SystemClock.uptimeMillis();
        float dt = (now - lastTick) / 1000f;
        lastTick = now;
        if (dt <= 0f || dt > 0.1f) return;
        if (!hasData || !calibrated) return;

        float ux0 = ux;
        float uy0 = uy;
        float uz0 = uz;

        float dot = ux0 * n0x + uy0 * n0y + uz0 * n0z;
        dot = Math.max(-1f, Math.min(1f, dot));
        float angle = (float) Math.toDegrees(Math.acos(dot));
        angleDeg = angle;

        if (angle <= DEADZONE_DEG) {
            accX = 0;
            accY = 0;
            lastDirX = 0f;
            lastDirY = 0f;
            return;
        }

        float px = ux0 - dot * n0x;
        float py = uy0 - dot * n0y;
        float pz = uz0 - dot * n0z;
        float plen = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (plen < 0.0001f) {
            accX = 0;
            accY = 0;
            return;
        }
        px /= plen;
        py /= plen;

        // 重力下坡方向在屏幕上的分量：dx = -px（屏幕 y 向下的映射需取正）
        float dirX = -px;
        float dirY = py;
        if (inverted) {
            dirX = -dirX;
            dirY = -dirY;
        }
        float dl = (float) Math.sqrt(dirX * dirX + dirY * dirY);
        if (dl < 0.0001f) {
            accX = 0;
            accY = 0;
            lastDirX = 0f;
            lastDirY = 0f;
            return;
        }
        dirX /= dl;
        dirY /= dl;
        lastDirX = dirX;
        lastDirY = dirY;

        float t = (angle - DEADZONE_DEG) / (MAX_TILT_DEG - DEADZONE_DEG);
        t = Math.max(0f, Math.min(1f, t));
        float speed = maxSpeed * (float) Math.pow(t, CURVE);

        accX += dirX * speed * dt;
        accY += dirY * speed * dt;

        int dx = (int) Math.round(accX);
        int dy = (int) Math.round(accY);
        if (dx != 0 || dy != 0) {
            accX -= dx;
            accY -= dy;
            if (dx > 127) dx = 127;
            if (dx < -127) dx = -127;
            if (dy > 127) dy = 127;
            if (dy < -127) dy = -127;
            sink.onMove(dx, dy);
        }
    }
}
