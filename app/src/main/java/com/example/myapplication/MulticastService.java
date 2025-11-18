package com.example.myapplication;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class MulticastService {
    private static final String TAG = "MulticastService";
    private static final String MULTICAST_GROUP = "239.0.0.1";
//    private static final int MULTICAST_PORT = 3003;
    private static final int BUFFER_SIZE = 1024;

    private int multicastPort;

    public int getMulticastPort() {
        return multicastPort;
    }

    public void setMulticastPort(int multicastPort) {
        this.multicastPort = multicastPort;
    }

    private Context context;
    private MulticastSocket socket;
    private InetAddress group;
    private WifiManager.MulticastLock multicastLock;
    private HandlerThread receiverThread;
    private Handler receiverHandler;
    private volatile boolean isListening = false;
    private MessageListener messageListener;
    private NetworkInterface selectedInterface;

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
            // Discover and select network interface
            Log.i(TAG, "Discovering multicast-enabled network interfaces...");
            List<NetworkInterface> availableInterfaces = getMulticastEnabledInterfaces();

            if (availableInterfaces.isEmpty()) {
                String error = "No multicast-capable network interfaces found. " +
                        "Please check WiFi/Ethernet connection.";
                Log.e(TAG, error);
                notifyError(error);
                return false;
            }

            selectedInterface = selectBestInterface(availableInterfaces);
            if (selectedInterface == null) {
                String error = "Failed to select network interface for multicast";
                Log.e(TAG, error);
                notifyError(error);
                return false;
            }

            String interfaceType = classifyInterfaceType(selectedInterface);
            Log.i(TAG, "Using interface: " + selectedInterface.getName() +
                    " (" + interfaceType + ")");

            // Conditionally acquire multicast lock - ONLY for WiFi
            boolean needsMulticastLock = hasWifiInterface(availableInterfaces);
            if (needsMulticastLock) {
                WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wifiManager != null) {
                    multicastLock = wifiManager.createMulticastLock("MulticastLock");
                    multicastLock.setReferenceCounted(true);
                    multicastLock.acquire();
                    Log.d(TAG, "Multicast lock acquired (WiFi interface detected)");
                }
            } else {
                Log.d(TAG, "Multicast lock not needed (Ethernet-only, saves battery)");
            }

            // Create and configure multicast socket
            socket = new MulticastSocket(getMulticastPort());
            socket.setReuseAddress(true);
            group = InetAddress.getByName(MULTICAST_GROUP);

            // Join multicast group using modern API with explicit interface
            SocketAddress groupAddress = new InetSocketAddress(group, getMulticastPort());
            socket.joinGroup(groupAddress, selectedInterface);
            Log.d(TAG, "Joined multicast group: " + MULTICAST_GROUP + ":" + getMulticastPort() +
                    " on interface " + selectedInterface.getName());

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

            notifyMessage("Started listening on " + MULTICAST_GROUP + ":" + getMulticastPort() +
                    " via " + interfaceType);
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
            // Leave multicast group using modern API with explicit interface
            if (socket != null && group != null && selectedInterface != null) {
                SocketAddress groupAddress = new InetSocketAddress(group, getMulticastPort());
                socket.leaveGroup(groupAddress, selectedInterface);
                Log.d(TAG, "Left multicast group on interface " + selectedInterface.getName());
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

        // Release multicast lock (if it was acquired)
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
            multicastLock = null;
            Log.d(TAG, "Multicast lock released");
        }

        // Clear selected interface
        selectedInterface = null;

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

                // Use same interface as receiver for consistency
                if (selectedInterface != null) {
                    sendSocket.setNetworkInterface(selectedInterface);
                    Log.d(TAG, "Sending on interface: " + selectedInterface.getName());
                }

                byte[] data = message.getBytes();
                DatagramPacket packet = new DatagramPacket(
                        data,
                        data.length,
                        sendGroup,
                        getMulticastPort()
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
        StringBuilder info = new StringBuilder();
        info.append("Group: ").append(MULTICAST_GROUP).append("\n");
        info.append("Port: ").append(getMulticastPort());

        if (selectedInterface != null) {
            String interfaceType = classifyInterfaceType(selectedInterface);
            info.append("\nInterface: ").append(selectedInterface.getName())
                .append(" (").append(interfaceType).append(")");
        }

        return info.toString();
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

                // Use same interface as receiver for consistency
                if (selectedInterface != null) {
                    sendSocket.setNetworkInterface(selectedInterface);
                    Log.d(TAG, "Sending hex on interface: " + selectedInterface.getName());
                }

                DatagramPacket packet = new DatagramPacket(
                        data,
                        data.length,
                        sendGroup,
                        getMulticastPort()
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

    // ==================== Network Interface Management ====================

    /**
     * Finds all multicast-enabled network interfaces on the device.
     * Filters for interfaces that are up, not loopback, support multicast, and have IP addresses.
     *
     * @return List of usable NetworkInterface objects, empty list if none found
     */
    private List<NetworkInterface> getMulticastEnabledInterfaces() {
        List<NetworkInterface> usableInterfaces = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                Log.w(TAG, "No network interfaces found");
                return usableInterfaces;
            }

            while (interfaces.hasMoreElements()) {
                NetworkInterface netInterface = interfaces.nextElement();

                try {
                    // Check if interface meets all requirements
                    if (!netInterface.isUp()) {
                        Log.v(TAG, "Skipping interface " + netInterface.getName() + " (not up)");
                        continue;
                    }

                    if (netInterface.isLoopback()) {
                        Log.v(TAG, "Skipping interface " + netInterface.getName() + " (loopback)");
                        continue;
                    }

                    if (!netInterface.supportsMulticast()) {
                        Log.v(TAG, "Skipping interface " + netInterface.getName() + " (no multicast)");
                        continue;
                    }

                    // Check if interface has at least one IP address
                    Enumeration<InetAddress> addresses = netInterface.getInetAddresses();
                    if (!addresses.hasMoreElements()) {
                        Log.v(TAG, "Skipping interface " + netInterface.getName() + " (no IP address)");
                        continue;
                    }

                    // Interface is usable
                    usableInterfaces.add(netInterface);
                    String type = classifyInterfaceType(netInterface);
                    Log.d(TAG, "Found usable interface: " + netInterface.getName() +
                            " (" + type + ") - " + netInterface.getDisplayName());

                } catch (SocketException e) {
                    Log.w(TAG, "Error checking interface " + netInterface.getName(), e);
                }
            }

        } catch (SocketException e) {
            Log.e(TAG, "Error enumerating network interfaces", e);
        }

        Log.i(TAG, "Found " + usableInterfaces.size() + " multicast-enabled interface(s)");
        return usableInterfaces;
    }

    /**
     * Classifies the network interface type based on its name.
     * Uses common naming patterns for WiFi and Ethernet interfaces.
     *
     * @param netInterface The NetworkInterface to classify
     * @return String describing the interface type ("WiFi", "Ethernet", or "Other")
     */
    private String classifyInterfaceType(NetworkInterface netInterface) {
        if (netInterface == null) {
            return "Unknown";
        }

        String name = netInterface.getName().toLowerCase();

        // WiFi patterns
        if (name.startsWith("wlan") || name.startsWith("wifi")) {
            return "WiFi";
        }

        // Ethernet patterns (including USB Ethernet)
        if (name.startsWith("eth") || name.startsWith("en") ||
            name.startsWith("rmnet") || name.startsWith("rndis") ||
            name.startsWith("usb")) {
            return "Ethernet";
        }

        return "Other";
    }

    /**
     * Selects the best network interface for multicast communication.
     * Priority order: Ethernet > WiFi > Other
     *
     * @param interfaces List of available NetworkInterface objects
     * @return The selected NetworkInterface, or null if list is empty
     */
    private NetworkInterface selectBestInterface(List<NetworkInterface> interfaces) {
        if (interfaces == null || interfaces.isEmpty()) {
            Log.w(TAG, "No interfaces available for selection");
            return null;
        }

        NetworkInterface ethernetInterface = null;
        NetworkInterface wifiInterface = null;
        NetworkInterface otherInterface = null;

        for (NetworkInterface netInterface : interfaces) {
            String type = classifyInterfaceType(netInterface);

            switch (type) {
                case "Ethernet":
                    if (ethernetInterface == null) {
                        ethernetInterface = netInterface;
                    }
                    break;
                case "WiFi":
                    if (wifiInterface == null) {
                        wifiInterface = netInterface;
                    }
                    break;
                default:
                    if (otherInterface == null) {
                        otherInterface = netInterface;
                    }
                    break;
            }
        }

        // Select based on priority: Ethernet > WiFi > Other
        NetworkInterface selected = null;
        String reason = "";

        if (ethernetInterface != null) {
            selected = ethernetInterface;
            reason = "Ethernet (highest priority)";
        } else if (wifiInterface != null) {
            selected = wifiInterface;
            reason = "WiFi (Ethernet not available)";
        } else if (otherInterface != null) {
            selected = otherInterface;
            reason = "Other (WiFi/Ethernet not available)";
        }

        if (selected != null) {
            Log.i(TAG, "Selected interface: " + selected.getName() + " - " + reason);
        }

        return selected;
    }

    /**
     * Returns the currently selected network interface for multicast communication.
     * This is the interface that was chosen when startListening() was called.
     *
     * @return The selected NetworkInterface, or null if not listening
     */
    public NetworkInterface getSelectedInterface() {
        return selectedInterface;
    }

    /**
     * Checks if a WiFi interface exists among the available interfaces.
     * Used to determine if MulticastLock should be acquired.
     *
     * @param interfaces List of NetworkInterface objects to check
     * @return true if at least one WiFi interface exists, false otherwise
     */
    private boolean hasWifiInterface(List<NetworkInterface> interfaces) {
        if (interfaces == null || interfaces.isEmpty()) {
            return false;
        }

        for (NetworkInterface netInterface : interfaces) {
            if ("WiFi".equals(classifyInterfaceType(netInterface))) {
                return true;
            }
        }

        return false;
    }

    // ==================== Helper Methods ====================

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
