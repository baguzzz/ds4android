package com.example.ds4remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final byte SYNC = (byte) 0xAA;
    private static final int PACKET_SIZE = 12;
    private static final int SEND_INTERVAL_MS = 16;

    private TextView statusText;
    private EditText ipInput;
    private Button connectBtn, editLayoutBtn;
    private FrameLayout gamepadArea;

    private Socket socket;
    private OutputStream output;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private boolean isEditMode = false;
    private SharedPreferences prefs;

    // State
    private volatile int buttons = 0;
    private volatile int lx = 128, ly = 128, rx = 128, ry = 128;
    private volatile int l2 = 0, r2 = 0;
    private volatile int dpad = 8;

    private static final int BTN_CROSS=0, BTN_CIRCLE=1, BTN_SQUARE=2, BTN_TRIANGLE=3, BTN_L1=4, BTN_R1=5,
            BTN_SHARE=6, BTN_OPTIONS=7, BTN_L3=8, BTN_R3=9, BTN_PS=10, BTN_TOUCHPAD=11;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("GamepadLayout", Context.MODE_PRIVATE);

        statusText = findViewById(R.id.status);
        ipInput = findViewById(R.id.ipInput);
        connectBtn = findViewById(R.id.connectBtn);
        editLayoutBtn = findViewById(R.id.editLayoutBtn);
        gamepadArea = findViewById(R.id.gamepadArea);

        // --- Setup Analog Sticks ---
        JoystickView leftStick = findViewById(R.id.leftStick);
        JoystickView rightStick = findViewById(R.id.rightStick);

        leftStick.setOnMoveListener((x, y) -> {
            if (isEditMode) return;
            lx = clamp255((x + 1f) / 2f * 255f);
            ly = clamp255((y + 1f) / 2f * 255f);
        });
        rightStick.setOnMoveListener((x, y) -> {
            if (isEditMode) return;
            rx = clamp255((x + 1f) / 2f * 255f);
            ry = clamp255((y + 1f) / 2f * 255f);
        });
        
        applyEditTouchOnly(leftStick);
        applyEditTouchOnly(rightStick);

        // --- Setup Buttons & D-Pad ---
        setupButton(R.id.btnA, BTN_CROSS);
        setupButton(R.id.btnB, BTN_CIRCLE);
        setupButton(R.id.btnX, BTN_SQUARE);
        setupButton(R.id.btnY, BTN_TRIANGLE);
        setupButton(R.id.btnL1, BTN_L1);
        setupButton(R.id.btnR1, BTN_R1);
        setupButton(R.id.btnShare, BTN_SHARE);
        setupButton(R.id.btnOptions, BTN_OPTIONS);
        setupButton(R.id.btnL3, BTN_L3);
        setupButton(R.id.btnR3, BTN_R3);
        setupButton(R.id.btnPS, BTN_PS);
        setupButton(R.id.btnTouchpad, BTN_TOUCHPAD);

        setupTrigger(R.id.btnL2, true);
        setupTrigger(R.id.btnR2, false);

        setupDpad(R.id.dpadUp, 0);
        setupDpad(R.id.dpadRight, 2);
        setupDpad(R.id.dpadDown, 4);
        setupDpad(R.id.dpadLeft, 6);

        // --- Tombol Toggle Edit Mode ---
        editLayoutBtn.setOnClickListener(v -> {
            isEditMode = !isEditMode;
            if (isEditMode) {
                editLayoutBtn.setText("💾 Simpan");
                editLayoutBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
                statusText.setText("MODE EDIT: Geser tombol, Cubit untuk ubah ukuran");
                Toast.makeText(this, "Mode Edit Aktif", Toast.LENGTH_SHORT).show();
            } else {
                editLayoutBtn.setText("⚙️ Edit");
                editLayoutBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF9800));
                statusText.setText("Tata letak disimpan!");
                saveLayout();
            }
        });

        connectBtn.setOnClickListener(v -> {
            if (connected.get()) disconnect();
            else connect(ipInput.getText().toString().trim());
        });

        loadLayout();
        startSendLoop();
    }

    // ================= LAYOUT EDITOR LOGIC =================

    // Listener gabungan untuk Button (Bisa untuk Edit Mode & Gamepad Mode)
    private void applyEditAndGamepadTouch(View view, Runnable onPress, Runnable onRelease) {
        final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!isEditMode) return false;
                float scale = detector.getScaleFactor();
                ViewGroup.LayoutParams params = view.getLayoutParams();
                params.width = (int) Math.max(40, params.width * scale);
                params.height = (int) Math.max(40, params.height * scale);
                view.setLayoutParams(params);
                return true;
            }
        });

        view.setOnTouchListener((v, event) -> {
            if (isEditMode) {
                // Mode Edit: Geser & Resize
                scaleDetector.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!scaleDetector.isInProgress()) {
                            v.setX(v.getX() + event.getX() - (v.getWidth() / 2f));
                            v.setY(v.getY() + event.getY() - (v.getHeight() / 2f));
                        }
                        return true;
                }
                return true;
            } else {
                // Mode Main: Kirim Sinyal Gamepad
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (onPress != null) onPress.run();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (onRelease != null) onRelease.run();
                        return true;
                }
                return false;
            }
        });
    }

    // Listener khusus untuk Analog (Hanya merespon saat mode edit, saat mode main biarkan JoystickView yang bekerja)
    private void applyEditTouchOnly(View view) {
        final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (!isEditMode) return false;
                float scale = detector.getScaleFactor();
                ViewGroup.LayoutParams params = view.getLayoutParams();
                params.width = (int) Math.max(40, params.width * scale);
                params.height = (int) Math.max(40, params.height * scale);
                view.setLayoutParams(params);
                return true;
            }
        });

        view.setOnTouchListener((v, event) -> {
            if (!isEditMode) return false; // Biarkan JoystickView memproses sentuhan

            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress()) {
                        v.setX(v.getX() + event.getX() - (v.getWidth() / 2f));
                        v.setY(v.getY() + event.getY() - (v.getHeight() / 2f));
                    }
                    return true;
            }
            return true;
        });
    }

    private void saveLayout() {
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < gamepadArea.getChildCount(); i++) {
            View child = gamepadArea.getChildAt(i);
            if (child.getId() != View.NO_ID) {
                String idName = getResources().getResourceEntryName(child.getId());
                editor.putFloat(idName + "_x", child.getX());
                editor.putFloat(idName + "_y", child.getY());
                editor.putInt(idName + "_w", child.getWidth());
                editor.putInt(idName + "_h", child.getHeight());
            }
        }
        editor.apply();
    }

    private void loadLayout() {
        for (int i = 0; i < gamepadArea.getChildCount(); i++) {
            View child = gamepadArea.getChildAt(i);
            if (child.getId() != View.NO_ID) {
                String idName = getResources().getResourceEntryName(child.getId());
                if (prefs.contains(idName + "_x")) {
                    child.setX(prefs.getFloat(idName + "_x", 0));
                    child.setY(prefs.getFloat(idName + "_y", 0));
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    params.width = prefs.getInt(idName + "_w", params.width);
                    params.height = prefs.getInt(idName + "_h", params.height);
                    child.setLayoutParams(params);
                }
            }
        }
    }

    // ================= INPUT HANDLERS =================
    private static int clamp255(float v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    private void setupButton(int id, int bitIdx) {
        Button b = findViewById(id);
        applyEditAndGamepadTouch(b, 
            () -> { if (!isEditMode) buttons |= (1 << bitIdx); },
            () -> { if (!isEditMode) buttons &= ~(1 << bitIdx); }
        );
    }

    private void setupTrigger(int id, boolean isL2) {
        Button b = findViewById(id);
        applyEditAndGamepadTouch(b, 
            () -> { if (!isEditMode) { if (isL2) l2 = 255; else r2 = 255; } },
            () -> { if (!isEditMode) { if (isL2) l2 = 0; else r2 = 0; } }
        );
    }

    private void setupDpad(int id, int dpadValue) {
        View b = findViewById(id);
        applyEditAndGamepadTouch(b, 
            () -> { if (!isEditMode) dpad = dpadValue; },
            () -> { if (!isEditMode) dpad = 8; }
        );
    }

    // ================= NETWORKING (TCP) =================
    private void connect(String ip) {
        if (ip.isEmpty()) { Toast.makeText(this, "Masukkan IP PC", Toast.LENGTH_SHORT).show(); return; }
        statusText.setText("⏳ Menghubungkan...");
        connectBtn.setEnabled(false);

        new Thread(() -> {
            try {
                Socket newSocket = new Socket();
                newSocket.connect(new InetSocketAddress(ip, 27020), 3000);
                newSocket.setTcpNoDelay(true);
                socket = newSocket;
                output = socket.getOutputStream();
                connected.set(true);
                uiHandler.post(() -> {
                    statusText.setText("✅ Terhubung ke " + ip);
                    connectBtn.setText("Disconnect");
                    connectBtn.setEnabled(true);
                });
            } catch (IOException ex) {
                final String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.toString();
                uiHandler.post(() -> {
                    statusText.setText("❌ Gagal: " + errMsg);
                    connectBtn.setText("Connect");
                    connectBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private void disconnect() {
        connected.set(false);
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null; output = null;
        runOnUiThread(() -> {
            statusText.setText("Terputus");
            connectBtn.setText("Connect");
            connectBtn.setEnabled(true);
        });
    }

    private void startSendLoop() {
        new Thread(() -> {
            while (!Thread.interrupted()) {
                if (connected.get()) {
                    try { sendPacket(); } catch (IOException ex) { disconnect(); }
                }
                try { Thread.sleep(SEND_INTERVAL_MS); } catch (InterruptedException e) { return; }
            }
        }).start();
    }

    private void sendPacket() throws IOException {
        OutputStream out = this.output;
        if (out == null) return;
        byte[] p = new byte[PACKET_SIZE];
        p[0] = SYNC;
        p[1] = (byte)(buttons & 0xFF);
        p[2] = (byte)((buttons >> 8) & 0xFF);
        p[3] = (byte) lx; p[4] = (byte) ly;
        p[5] = (byte) rx; p[6] = (byte) ry;
        p[7] = (byte) l2; p[8] = (byte) r2;
        p[9] = (byte) dpad;
        p[10] = 0;
        byte ck = 0;
        for (int i = 1; i < PACKET_SIZE - 1; i++) ck ^= p[i];
        p[PACKET_SIZE - 1] = ck;
        out.write(p);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }
}