package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements MulticastService.MessageListener {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private MulticastService multicastService;
    private EditText messageInput;
    private Button sendButton;
    private Button startButton;
    private Button stopButton;
    private TextView receivedMessages;
    private TextView statusText;
    private Switch hexModeSwitch;

    private boolean isHexMode = false;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupMulticastService();
        checkPermissions();
    }

    private void initViews() {
        messageInput = findViewById(R.id.messageInput);
        sendButton = findViewById(R.id.sendButton);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        receivedMessages = findViewById(R.id.receivedMessages);
        statusText = findViewById(R.id.statusText);
        hexModeSwitch = findViewById(R.id.hexModeSwitch);

        // Make received messages scrollable
        receivedMessages.setMovementMethod(new ScrollingMovementMethod());

        sendButton.setOnClickListener(v -> sendMessage());
        startButton.setOnClickListener(v -> startListening());
        stopButton.setOnClickListener(v -> stopListening());

        // Hex mode switch listener
        hexModeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                isHexMode = isChecked;
                updateInputHint();
            }
        });

        updateInputHint();

        EditText et_port = findViewById(R.id.et_port);
        et_port.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                String string = s.toString();
                multicastService.setMulticastPort(Integer.parseInt(string));
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {

            }
        });
    }

    private void setupMulticastService() {
        multicastService = new MulticastService(this);
        multicastService.setMessageListener(this);

        updateUI(false);
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] permissions = {
                    Manifest.permission.INTERNET,
                    Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.ACCESS_NETWORK_STATE
            };

            boolean allGranted = true;
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permissions required for multicast", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startListening() {
        if (multicastService.isListening()) {
            Toast.makeText(this, "Already listening", Toast.LENGTH_SHORT).show();
            return;
        }

        appendMessage("[System]", "Starting multicast listener...");
        boolean success = multicastService.startListening();

        if (success) {
            updateUI(true);
        } else {
            Toast.makeText(this, "Failed to start listening", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopListening() {
        if (!multicastService.isListening()) {
            Toast.makeText(this, "Not listening", Toast.LENGTH_SHORT).show();
            return;
        }

        multicastService.stopListening();
        updateUI(false);
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();

        if (message.isEmpty()) {
            Toast.makeText(this, "Please enter a message", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isHexMode) {
            // Validate hex format
            if (!isValidHexString(message)) {
                Toast.makeText(this, "Invalid hex format. Use space-separated hex bytes (e.g., '55 AA 02 6B DA')",
                        Toast.LENGTH_LONG).show();
                return;
            }
            multicastService.sendHexMessage(message);
        } else {
            multicastService.sendMessage(message);
        }
        messageInput.setText("");
    }

    /**
     * Updates the input hint based on current mode
     */
    private void updateInputHint() {
        if (isHexMode) {
            messageInput.setHint("Enter hex bytes (e.g., 55 AA 02 6B DA)");
        } else {
            messageInput.setHint("Enter message to send");
        }
    }

    /**
     * Validates hex string format
     * @param hexString The string to validate
     * @return true if valid hex format, false otherwise
     */
    private boolean isValidHexString(String hexString) {
        if (hexString == null || hexString.trim().isEmpty()) {
            return false;
        }

        // Check format: space-separated hex bytes
        String[] hexBytes = hexString.trim().split("\\s+");
        if (hexBytes.length < 7) {
            Toast.makeText(this, "数据不符合要求", Toast.LENGTH_SHORT).show();
            return false;
        }
        if ("55".equals(hexBytes[0]) && "AA".equals(hexBytes[1])) {
            try {
                String endHexByte = hexBytes[(hexBytes.length - 1)];
                int endInt = Integer.parseInt(endHexByte, 16);
                int startTotal = 0;
                for (int i = 2; i < hexBytes.length - 1; i++) {
                    startTotal += Integer.parseInt(hexBytes[i], 16);
                }
                if (endInt != startTotal) {
                    Toast.makeText(this, "数据不符合要求", Toast.LENGTH_SHORT).show();
                    return false;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "数据不符合要求", Toast.LENGTH_SHORT).show();
                return false;
            }
        }else {
            Toast.makeText(this, "数据不符合要求", Toast.LENGTH_SHORT).show();
            return false;
        }
        for (String hexByte : hexBytes) {
            // Each hex byte should be 1-2 characters and valid hex
            if (hexByte.length() < 1 || hexByte.length() > 2) {
                return false;
            }
            try {
                Integer.parseInt(hexByte, 16);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private void updateUI(boolean isListening) {
        startButton.setEnabled(!isListening);
        stopButton.setEnabled(isListening);
        sendButton.setEnabled(isListening);

        String status = isListening ? "Listening" : "Stopped";
        String info = multicastService.getMulticastInfo();
        statusText.setText(status + "\n" + info);
    }

    @Override
    public void onMessageReceived(String message, String senderAddress) {
        appendMessage(senderAddress, message);
    }

    @Override
    public void onError(String error) {
        appendMessage("[Error]", error);
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
    }

    private void appendMessage(String sender, String message) {
        String timestamp = timeFormat.format(new Date());
        String formattedMessage = String.format("[%s] %s: %s\n",
                timestamp, sender, message);

        receivedMessages.append(formattedMessage);

        // Auto-scroll to bottom
        final int scrollAmount = receivedMessages.getLayout().getLineTop(receivedMessages.getLineCount())
                - receivedMessages.getHeight();
        if (scrollAmount > 0) {
            receivedMessages.scrollTo(0, scrollAmount);
        } else {
            receivedMessages.scrollTo(0, 0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");

        // Clean up multicast service
        if (multicastService != null && multicastService.isListening()) {
            multicastService.stopListening();
        }
    }
}