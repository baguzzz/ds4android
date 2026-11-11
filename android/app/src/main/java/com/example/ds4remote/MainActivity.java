package com.example.ds4remote;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final byte SYNC = (byte) 0xAA;
    private static final int PACKET_SIZE = 12;
    private static final int SEND_INTERVAL_MS = 16;

    private TextView statusText;
    private EditText ipInput;
    private Button connectBtn, editLayoutBtn, resetLayoutBtn, exportLayoutBtn, importLayoutBtn;
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

    private final int[] allIds = {
        R.id.dpadUp, R.id.dpadDown, R.id.dpadLeft, R.id.dpadRight,
        R.id.btnY, R.id.btnX, R.id.btnA, R.id.btnB,
        R.id.btnL1, R.id.btnL2, R.id.btnR1, R.id.btnR2,
        R.id.btnShare, R.id.btnPS, R.id.btnOptions,
        R.id.leftStick, R.id.rightStick,
        R.id.btnL3, R.id.btnTouchpad, R.id.btnR3
    };

    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    writeLayoutToUri(result.getData().getData());
                }
            }
    );

    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    readLayoutFromUri(uri);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("GamepadLayout", Context.MODE_PRIVATE);

        statusText = findViewById(R.id.status);
        ipInput = findViewById(R.id.ipInput);
        connectBtn = findViewById(R.id.connectBtn);
        editLayoutBtn = findViewById(R.id.editLayoutBtn);
        resetLayoutBtn = findViewById(R.id.resetLayoutBtn);
        exportLayoutBtn = findViewById(R.id.exportLayoutBtn);
        importLayoutBtn = findViewById(R.id.importLayoutBtn);
        gamepadArea = findViewById(R.id.gamepadArea);

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

        editLayoutBtn.setOnClickListener(v -> {
            isEditMode = !isEditMode;
            if (isEditMode) {
                editLayoutBtn.setText("💾 Simpan");
                editLayoutBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4CAF50));
                resetLayoutBtn.setVisibility(View.VISIBLE);
                exportLayoutBtn.setVisibility(View.VISIBLE);
                importLayoutBtn.setVisibility(View.VISIBLE);
                statusText.setText("MODE EDIT: Geser tombol");
            } else {
                editLayoutBtn.setText("⚙️ Edit");
                editLayoutBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFFF9800));
                resetLayoutBtn.setVisibility(View.GONE);
                exportLayoutBtn.setVisibility(View.GONE);
                importLayoutBtn.setVisibility(View.GONE);
                statusText.setText("Tata letak disimpan!");
                saveLayout();
            }
        });

        resetLayoutBtn.setOnClickListener(v -> {
            Toast.makeText(this, "Reset tata letak...", Toast.LENGTH_SHORT).show();
            resetLayout();
        });

        exportLayoutBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "layout_default.json");
            exportLauncher.launch(intent);
        });

        importLayoutBtn.setOnClickListener(v -> {
            importLauncher.launch(new String[]{"application/json", "text/plain", "*/*"});
        });

        connectBtn.setOnClickListener(v -> {
            if (connected.get()) disconnect();
            else connect(ipInput.getText().toString().trim());
        });

        loadLayout();
        startSendLoop();
    }

    // ================= LAYOUT EDITOR & CONFIG =================

    private void loadLayout() {
        boolean loaded = false;
        for (int id : allIds) {
            View child = findViewById(id);
            if (child != null) {
                String idName = getResources().getResourceEntryName(id);
                if (prefs.contains(idName + "_x")) {
                    child.setX(prefs.getFloat(idName + "_x", 0f));
                    child.setY(prefs.getFloat(idName + "_y", 0f));
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    params.width = prefs.getInt(idName + "_w", params.width);
                    params.height = prefs.getInt(idName + "_h", params.height);
                    child.setLayoutParams(params);
                    loaded = true;
                }
            }
        }
        if (!loaded) {
            loadDefaultFromJson();
        }
    }

    private void loadDefaultFromJson() {
        try {
            InputStream is = getAssets().open("layout_default.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, StandardCharsets.UTF_8);
            applyJsonLayout(json, false);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void applyJsonLayout(String json, boolean saveToPrefs) {
        try {
            JSONObject obj = new JSONObject(json);
            float density = getResources().getDisplayMetrics().density;
            SharedPreferences.Editor editor = prefs.edit();

            for (int id : allIds) {
                String idName = getResources().getResourceEntryName(id);
                if (obj.has(idName)) {
                    View child = findViewById(id);
                    if (child != null) {
                        JSONObject props = obj.getJSONObject(idName);
                        float x = (float) props.getDouble("x") * density;
                        float y = (float) props.getDouble("y") * density;
                        int w = (int) (props.getInt("w") * density);
                        int h = (int) (props.getInt("h") * density);

                        child.setX(x);
                        child.setY(y);
                        ViewGroup.LayoutParams params = child.getLayoutParams();
                        params.width = w;
                        params.height = h;
                        child.setLayoutParams(params);

                        if (saveToPrefs) {
                            editor.putFloat(idName + "_x", x);
                            editor.putFloat(idName + "_y", y);
                            editor.putInt(idName + "_w", w);
                            editor.putInt(idName + "_h", h);
                        }
                    }
                }
            }
            if (saveToPrefs) editor.apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void resetLayout() {
        prefs.edit().clear().apply();
        // Kembalikan ke posisi semula dari XML (Margin)
        for (int id : allIds) {
            View child = findViewById(id);
            if (child != null) {
                child.setTranslationX(0f);
                child.setTranslationY(0f);
            }
        }
        loadDefaultFromJson();
    }

    private void saveLayout() {
        SharedPreferences.Editor editor = prefs.edit();
        for (int id : allIds) {
            View child = findViewById(id);
            if (child != null) {
                String idName = getResources().getResourceEntryName(id);
                editor.putFloat(idName + "_x", child.getX());
                editor.putFloat(idName + "_y", child.getY());
                editor.putInt(idName + "_w", child.getWidth());
                editor.putInt(idName + "_h", child.getHeight());
            }
        }
        editor.apply();
    }

    private void writeLayoutToUri(Uri uri) {
        try {
            JSONObject obj = new JSONObject();
            float density = getResources().getDisplayMetrics().density;

            for (int id : allIds) {
                View child = findViewById(id);
                if (child != null) {
                    String idName = getResources().getResourceEntryName(id);
                    JSONObject props = new JSONObject();
                    props.put("x", Math.round(child.getX() / density));
                    props.put("y", Math.round(child.getY() / density));
                    props.put("w", Math.round(child.getWidth() / density));
                    props.put("h", Math.round(child.getHeight() / density));
                    obj.put(idName, props);
                }
            }

            OutputStream os = getContentResolver().openOutputStream(uri);
            if (os != null) {
                os.write(obj.toString(4).getBytes(StandardCharsets.UTF_8));
                os.close();
                Toast.makeText(this, "Export berhasil!", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Gagal export: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void readLayoutFromUri(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, StandardCharsets.UTF_8);
            
            applyJsonLayout(json, true);
            Toast.makeText(this, "Import layout berhasil!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Gagal import: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void applyEditAndGamepadTouch(View view, Runnable onPress, Runnable onRelease) {
        view.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY, initialX, initialY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isEditMode) {
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            startX = event.getRawX();
                            startY = event.getRawY();
                            initialX = v.getX();
                            initialY = v.getY();
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = event.getRawX() - startX;
                            float dy = event.getRawY() - startY;
                            
                            float newX = initialX + dx;
                            float newY = initialY + dy;
                            
                            // === PEMBATAS LAYAR (CLAMPING) ===
                            float maxX = gamepadArea.getWidth() - v.getWidth();
                            float maxY = gamepadArea.getHeight() - v.getHeight();
                            
                            newX = Math.max(0, Math.min(newX, maxX));
                            newY = Math.max(0, Math.min(newY, maxY));
                            
                            v.setX(newX);
                            v.setY(newY);
                            return true;
                    }
                    return true;
                } else {
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
            }
        });
    }

    private void applyEditTouchOnly(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY, initialX, initialY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (!isEditMode) return false;

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        startY = event.getRawY();
                        initialX = v.getX();
                        initialY = v.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - startX;
                        float dy = event.getRawY() - startY;
                        
                        float newX = initialX + dx;
                        float newY = initialY + dy;
                        
                        // === PEMBATAS LAYAR (CLAMPING) ===
                        float maxX = gamepadArea.getWidth() - v.getWidth();
                        float maxY = gamepadArea.getHeight() - v.getHeight();
                        
                        newX = Math.max(0, Math.min(newX, maxX));
                        newY = Math.max(0, Math.min(newY, maxY));
                        
                        v.setX(newX);
                        v.setY(newY);
                        return true;
                }
                return true;
            }
        });
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

    // ================= NETWORKING =================
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