package com.fongmi.android.tv.ui.activity;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.forcetech.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Response;

public class P3PScannerActivity extends AppCompatActivity implements ServiceConnection {

    private EditText etUrl;
    private Button btnScan;
    private TextView tvStatus;
    private TextView tvResult;
    private ExecutorService scannerExecutor;
    private Thread verifierThread;
    private boolean isScanning = false;
    private BlockingQueue<Integer> candidatePorts;
    private boolean isServiceConnected = false;

    // Pattern to match p3p://ip:(start-end)/id
    private static final Pattern PATTERN = Pattern.compile("p3p://([^:]+):\\((\\d+)-(\\d+)\\)/(.+)");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        etUrl = findViewById(R.id.et_url);
        btnScan = findViewById(R.id.btn_scan);
        tvStatus = findViewById(R.id.tv_status);
        tvResult = findViewById(R.id.tv_result);

        btnScan.setOnClickListener(v -> {
            if (isScanning) {
                stopScan();
            } else {
                startScan();
            }
        });

        // Bind service on creation to be ready
        initService();
    }

    private void initService() {
        try {
            App.get().bindService(Util.intent(App.get(), "p3p"), this, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            tvStatus.setText("Error binding service: " + e.getMessage());
        }
    }

    private void startScan() {
        String url = etUrl.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            tvStatus.setText("Error: Empty URL");
            return;
        }

        Matcher matcher = PATTERN.matcher(url);
        if (!matcher.find()) {
            tvStatus.setText("Error: Invalid format. Use p3p://ip:(start-end)/id");
            return;
        }

        String targetIp = matcher.group(1);
        int startPort = Integer.parseInt(matcher.group(2));
        int endPort = Integer.parseInt(matcher.group(3));
        String id = matcher.group(4);

        if (startPort > endPort || startPort < 1 || endPort > 65535) {
            tvStatus.setText("Error: Invalid port range");
            return;
        }

        isScanning = true;
        btnScan.setText("Stop Scan");
        tvStatus.setText("Initializing scan for " + targetIp + "...");
        verifierThread.start();
    }

    private boolean verifyP3P(String ip, int port, String id) {
        try {
            int localPort = Util.P3P; // 9907
            // 1. Switch Channel
            // http://127.0.0.1:9907/cmd.xml?cmd=switch_chan&server=ip:port&id=id
            String cmdUrl = "http://127.0.0.1:" + localPort + "/cmd.xml?cmd=switch_chan&server=" + ip + ":" + port
                    + "&id=" + id;
            OkHttp.string(cmdUrl, Map.of(HttpHeaders.USER_AGENT, "MTV")); // Send command, ignore result

            // 2. Try to Read Stream
            // http://127.0.0.1:9907/id
            String streamUrl = "http://127.0.0.1:" + localPort + "/" + id;

            // Allow some time for buffering
            Thread.sleep(500);

            try (Response response = OkHttp.newCall(streamUrl, Map.of(HttpHeaders.USER_AGENT, "MTV")).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    // Try to read a small chunk to ensure it's a real stream
                    byte[] buffer = new byte[1024];
                    int read = response.body().byteStream().read(buffer);
                    return read > 0;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private void stopScan() {
        isScanning = false;
        if (scannerExecutor != null) {
            scannerExecutor.shutdownNow();
            scannerExecutor = null;
        }
        if (verifierThread != null) {
            verifierThread.interrupt();
            verifierThread = null;
        }
        btnScan.setText("Start Scan");
        tvStatus.setText("Status: Idle");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        if (isServiceConnected) {
            App.get().unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        isServiceConnected = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        isServiceConnected = false;
    }
}
