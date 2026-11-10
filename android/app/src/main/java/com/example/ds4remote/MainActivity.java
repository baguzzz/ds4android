package com.example.ds4remote;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
    private static final int SEND_INTERVAL_MS = 16; // 60Hz

    private TextView statusText;
    private EditText ipInput;
    private Button connectBtn;

    private Socket socket;
    private OutputStream output;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // State
    private volatile int buttons = 0;
    private volatile int lx = 128, ly = 128, rx = 128, ry = 128;
    private volatile int l2 = 0, r2 = 0;
    private volatile int dpad = 8;

    // Button bit indices
    private static final int BTN_CROSS    = 0;
    private static final int BTN_CIRCLE   = 1;
    private static final int BTN_SQUARE   = 2;
    private static final int BTN_TRIANGLE = 3;
    private static final int BTN_L1       = 4;
    private static final int BTN_R1       = 5;
    private static final int BTN_SHARE    = 6;
    private static final int BTN_OPTIONS  = 7;
    private static final int BTN_L3       = 8;
    private static final int BTN_R3       = 9;
    private static final int BTN_PS       = 10;
    private static final int BTN_TOUCHPAD = 11;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status);
        ipInput = findViewById(R.id.ipInput);
        connectBtn = findViewById(R.id.connectBtn);

        JoystickView leftStick = findViewById(R.id.leftStick);
        JoystickView rightStick = findViewById(R.id.rightStick);

        leftStick.setOnMoveListener((x, y) -> {
            lx = clamp255((x + 1f) / 2f * 255f);
            ly = clamp255((y + 1f) / 2f * 255f);
        });
        rightStick.setOnMoveListener((x, y) -> {
            rx = clamp255((x + 1f) / 2f * 255f);
            ry = clamp255((y + 1f) / 2f * 255f);
        });

        // Setup Buttons
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

        setupDpad(R.id.dpadUp,    0);
        setupDpad(R.id.dpadRight, 2);
        setupDpad(R.id.dpadDown,  4);
        setupDpad(R.id.dpadLeft,  6);
        setupDpadCenter();

        connectBtn.setOnClickListener(v -> {
            if (connected.get()) {
                disconnect();
            } else {
                connect(ipInput.getText().toString().trim());
            }
        });

        // Mulai pengiriman data di background thread terpisah
        startSendLoop();
    }

    private static int clamp255(float v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return (int) v;
    }

    private void setupButton(int id, int bitIdx) {
        Button b = findViewById(id);
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    buttons |= (1 << bitIdx); return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    buttons &= ~(1 << bitIdx); return true;
            }
            return false;
        });
    }

    private void setupTrigger(int id, boolean isL2) {
        Button b = findViewById(id);
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (isL2) l2 = 255; else r2 = 255; return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (isL2) l2 = 0; else r2 = 0; return true;
            }
            return false;
        });
    }

    private void setupDpad(int id, int dpadValue) {
        View b = findViewById(id);
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:  dpad = dpadValue; return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: dpad = 8; return true;
            }
            return false;
        });
    }

    private void setupDpadCenter() {
        View b = findViewById(R.id.dpadCenter);
        b.setOnClickListener(v -> { /* no-op */ });
    }

    // --- PERBAIKAN: Gunakan Thread baru untuk koneksi agar tidak macet ---
    private void connect(String ip) {
        if (ip.isEmpty()) {
            Toast.makeText(this, "Masukkan IP PC dulu", Toast.LENGTH_SHORT).show();
            return;
        }

        // Tampilkan indikator sedang menghubungkan
        statusText.setText("⏳ Menghubungkan ke " + ip + "...");
        connectBtn.setText("Connecting...");
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
                    Toast.makeText(MainActivity.this, "Gagal connect: " + errMsg,
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void disconnect() {
        connected.set(false);
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = null;
        output = null;
        runOnUiThread(() -> {
            statusText.setText("Terputus");
            connectBtn.setText("Connect");
            connectBtn.setEnabled(true);
        });
    }

    // --- PERBAIKAN: Gunakan Thread baru untuk looping pengiriman data ---
    private void startSendLoop() {
        new Thread(() -> {
            while (!Thread.interrupted()) {
                if (connected.get()) {
                    try {
                        sendPacket();
                    } catch (IOException ex) {
                        disconnect();
                    }
                }
                try { Thread.sleep(SEND_INTERVAL_MS); }
                catch (InterruptedException e) { return; }
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
        p[3] = (byte) lx;
        p[4] = (byte) ly;
        p[5] = (byte) rx;
        p[6] = (byte) ry;
        p[7] = (byte) l2;
        p[8] = (byte) r2;
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
