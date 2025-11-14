package com.example.myapplication;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

public class MulticastService {
    private static final String TAG = "MulticastService";
    private static final String MULTICAST_GROUP = "239.0.0.1";
    private static final int MULTICAST_PORT = 30004;
    private static final int BUFFER_SIZE = 1024;

    private Context context;
    private MulticastSocket socket;
    private InetAddress group;
    private WifiManager.MulticastLock multicastLock;
    private HandlerThread receiverThread;
    private Handler receiverHandler;
    private volatile boolean isListening = false;
    private MessageListener messageListener;

    public interface MessageListener {
        void onMessageReceived(String message, String senderAddress);
        void onError(String error);
    }

    public MulticastService(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    public synchronized boolean startListening() {
        if (isListening) {
            Log.w(TAG, "Already listening");
            return true;
        }

        try {
            // Acquire multicast lock
            WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("MulticastLock");
                multicastLock.setReferenceCounted(true);
                multicastLock.acquire();
                Log.d(TAG, "Multicast lock acquired");
            }

            // Create and configure multicast socket
            socket = new MulticastSocket(MULTICAST_PORT);
            socket.setReuseAddress(true);
            group = InetAddress.getByName(MULTICAST_GROUP);

            // Join multicast group
            socket.joinGroup(group);
            Log.d(TAG, "Joined multicast group: " + MULTICAST_GROUP + ":" + MULTICAST_PORT);

            // Start receiver thread
            receiverThread = new HandlerThread("MulticastReceiver");
            receiverThread.start();
            receiverHandler = new Handler(receiverThread.getLooper());

            isListening = true;

            // Start receiving messages
            receiverHandler.post(new Runnable() {
                @Override
                public void run() {
                    receiveMessages();
                }
            });

            notifyMessage("Started listening on " + MULTICAST_GROUP + ":" + MULTICAST_PORT);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error starting multicast listener", e);
            notifyError("Failed to start listening: " + e.getMessage());
            stopListening();
            return false;
        }
    }

    private void receiveMessages() {
        byte[] buffer = new byte[BUFFER_SIZE];
        Log.d(TAG, "Receiver thread started");

        while (isListening && socket != null && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                byte[] receivedData = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), 0, receivedData, 0, packet.getLength());

                String message;
                // Try to decode as UTF-8 text
                if (isValidUtf8(receivedData)) {
                    message = new String(receivedData, StandardCharsets.UTF_8);
                } else {
                    // If not valid UTF-8, display as hex
                    message = "[HEX] " + bytesToHexString(receivedData);
                }

                String senderAddress = packet.getAddress().getHostAddress();

                Log.d(TAG, "Received message from " + senderAddress + ": " + message);


                if (messageListener != null) {
                    // Post to main thread
                    Handler mainHandler = new Handler(context.getMainLooper());
                    final String finalMessage = message;
                    mainHandler.post(() -> messageListener.onMessageReceived(finalMessage, senderAddress));
                }

            } catch (IOException e) {
                if (isListening) {
                    Log.e(TAG, "Error receiving message", e);
                    notifyError("Error receiving: " + e.getMessage());
                }
                break;
            }
        }

        Log.d(TAG, "Receiver thread stopped");
    }

    public synchronized void stopListening() {
        if (!isListening) {
            Log.w(TAG, "Not listening");
            return;
        }

        isListening = false;
        Log.d(TAG, "Stopping multicast listener");

        try {
            // Leave multicast group
            if (socket != null && group != null) {
                socket.leaveGroup(group);
                Log.d(TAG, "Left multicast group");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error leaving multicast group", e);
        }

        // Close socket
        if (socket != null) {
            socket.close();
            socket = null;
            Log.d(TAG, "Socket closed");
        }

        // Stop receiver thread
        if (receiverThread != null) {
            receiverThread.quitSafely();
            try {
                receiverThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping receiver thread", e);
            }
            receiverThread = null;
            receiverHandler = null;
        }

        // Release multicast lock
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            multicastLock = null;
            Log.d(TAG, "Multicast lock released");
        }

        notifyMessage("Stopped listening");
    }

    public void sendMessage(final String message) {
        if (message == null || message.trim().isEmpty()) {
            notifyError("Message cannot be empty");
            return;
        }

        // Send on background thread
        new Thread(() -> {
            MulticastSocket sendSocket = null;
            try {
                sendSocket = new MulticastSocket();
                InetAddress sendGroup = InetAddress.getByName(MULTICAST_GROUP);

                byte[] data = message.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data,
                        data.length,
                        sendGroup,
                        MULTICAST_PORT
                );

                sendSocket.send(packet);
                Log.d(TAG, "Sent message: " + message);
                notifyMessage("Sent: " + message);

            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
                notifyError("Failed to send: " + e.getMessage());
            } finally {
                if (sendSocket != null) {
                    sendSocket.close();
                }
            }
        }).start();
    }

    public boolean isListening() {
        return isListening;
    }

    private void notifyMessage(String message) {
        if (messageListener != null) {
            Handler mainHandler = new Handler(context.getMainLooper());
            mainHandler.post(() -> messageListener.onMessageReceived(message, "System"));
        }
    }

    private void notifyError(String error) {
        if (messageListener != null) {
            Handler mainHandler = new Handler(context.getMainLooper());
            mainHandler.post(() -> messageListener.onError(error));
        }
    }

    public String getMulticastInfo() {
        return "Group: " + MULTICAST_GROUP + "\nPort: " + MULTICAST_PORT;
    }

    /**
     * Sends a hex-encoded message as raw bytes
     * @param hexString Hex string in format "55 AA 02 6B DA" (space-separated)
     */
    public void sendHexMessage(final String hexString) {
        if (hexString == null || hexString.trim().isEmpty()) {
            notifyError("Hex message cannot be empty");
            return;
        }

        // Send on background thread
        new Thread(() -> {
            MulticastSocket sendSocket = null;
            try {
                byte[] data = hexStringToBytes(hexString);
                if (data == null || data.length == 0) {
                    notifyError("Invalid hex format. Use space-separated hex bytes (e.g., '55 AA 02 6B DA')");
                    return;
                }

                sendSocket = new MulticastSocket();
                InetAddress sendGroup = InetAddress.getByName(MULTICAST_GROUP);

                DatagramPacket packet = new DatagramPacket(
                        data,
                        data.length,
                        sendGroup,
                        MULTICAST_PORT
                );

                sendSocket.send(packet);
                Log.d(TAG, "Sent hex message: " + hexString + " (" + data.length + " bytes)");
                notifyMessage("Sent HEX: " + hexString);

            } catch (Exception e) {
                Log.e(TAG, "Error sending hex message", e);
                notifyError("Failed to send hex: " + e.getMessage());
            } finally {
                if (sendSocket != null) {
                    sendSocket.close();
                }
            }
        }).start();
    }

    /**
     * Converts a hex string to byte array
     * Format: "55 AA 02 6B DA" (space-separated, case-insensitive)
     * @param hexString The hex string to convert
     * @return byte array or null if invalid format
     */
    private byte[] hexStringToBytes(String hexString) {
        if (hexString == null || hexString.trim().isEmpty()) {
            return null;
        }

        try {
            // Remove extra whitespace and split by spaces
            String[] hexBytes = hexString.trim().split("\\s+");
            byte[] bytes = new byte[hexBytes.length];

            for (int i = 0; i < hexBytes.length; i++) {
                // Parse each hex byte (e.g., "55" -> 0x55)
                bytes[i] = (byte) Integer.parseInt(hexBytes[i], 16);
            }

            return bytes;
        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid hex format: " + hexString, e);
            return null;
        }
    }

    /**
     * Converts byte array to hex string representation
     * @param bytes The byte array to convert
     * @return Hex string in format "55 AA 02 6B DA"
     */
    private String bytesToHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        StringBuilder hexString = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                hexString.append(" ");
            }
            hexString.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return hexString.toString();
    }

    /**
     * Checks if byte array is valid UTF-8 text
     * @param bytes The byte array to check
     * @return true if valid UTF-8, false otherwise
     */
    private boolean isValidUtf8(byte[] bytes) {
        try {
            String test = new String(bytes, StandardCharsets.UTF_8);
            // Check if the string contains mostly printable characters
            int printableCount = 0;
            for (char c : test.toCharArray()) {
                if (c >= 32 && c < 127 || c == '\n' || c == '\r' || c == '\t') {
                    printableCount++;
                }
            }
            // If at least 80% are printable ASCII/whitespace, consider it text
            return printableCount >= (test.length() * 0.8);
        } catch (Exception e) {
            return false;
        }
    }

}
