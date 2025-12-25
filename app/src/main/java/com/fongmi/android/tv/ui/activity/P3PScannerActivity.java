package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fongmi.android.tv.R;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class P3PScannerActivity extends AppCompatActivity {

    private EditText etUrl;
    private Button btnScan;
    private TextView tvStatus;
    private TextView tvResult;
    private ExecutorService executorService;
    private boolean isScanning = false;

    // Pattern to match p3p://ip:(start-end)/id
    // Example: p3p://192.168.1.1:(8080-8090)/mvc
    private static final Pattern PATTERN = Pattern.compile("p3p://([^:]+):\\((\\d+)-(\\d+)\\)/(.+)");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        etUrl = findViewById(R.id.et_url);
        btnScan = findViewById(R.id.btn_scan);
        tvStatus = findViewById(R.id.tv_status);
        tvResult = findViewById(R.id.tv_result);

        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isScanning) {
                    stopScan();
                } else {
                    startScan();
                }
            }
        });
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

        String ip = matcher.group(1);
        int startPort = Integer.parseInt(matcher.group(2));
        int endPort = Integer.parseInt(matcher.group(3));
        String id = matcher.group(4);

        if (startPort > endPort || startPort < 1 || endPort > 65535) {
            tvStatus.setText("Error: Invalid port range");
            return;
        }

        isScanning = true;
        btnScan.setText("Stop Scan");
        tvStatus.setText("Scanning " + ip + " [" + startPort + "-" + endPort + "]...");
        tvResult.setText("");

        int totalPorts = endPort - startPort + 1;
        AtomicInteger scannedCount = new AtomicInteger(0);
        executorService = Executors.newFixedThreadPool(200); // High concurrency

        for (int port = startPort; port <= endPort; port++) {
            final int p = port;
            executorService.execute(new Runnable() {
                @Override
                public void run() {
                    if (Thread.currentThread().isInterrupted() || !isScanning)
                        return;

                    boolean isOpen = checkPort(ip, p);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isOpen) {
                                String validUrl = "p3p://" + ip + ":" + p + "/" + id;
                                tvResult.append(validUrl + "\n");
                            }
                            int count = scannedCount.incrementAndGet();
                            if (count % 50 == 0 || count == totalPorts) {
                                tvStatus.setText("Scanning: " + count + "/" + totalPorts);
                            }
                            if (count == totalPorts) {
                                finishScan();
                            }
                        }
                    });
                }
            });
        }
    }

    private boolean checkPort(String ip, int port) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(ip, port), 2000); // 2s timeout
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void stopScan() {
        isScanning = false;
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        btnScan.setText("Start Scan");
        tvStatus.append(" (Stopped)");
    }

    private void finishScan() {
        isScanning = false;
        btnScan.setText("Start Scan");
        tvStatus.setText(tvStatus.getText() + " (Completed)");
        if (executorService != null) {
            executorService.shutdown();
            executorService = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
    }
}
