package com.example.btbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.telecom.TelecomManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.communication_bridge_app.R;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BluetoothServerService extends Service {
    private static final String TAG = "BT_Bridge_Service";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "BluetoothServerServiceChannel";

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private TelecomManager telecomManager;
    private Handler mainHandler;
    private boolean isAdvertising = false;
    private boolean serviceAdded = false;

    // Strong references to prevent garbage collection
    private AdvertiseCallback advertiseCallback;
    private BluetoothGattServerCallback gattServerCallback;
    private BroadcastReceiver bluetoothStateReceiver;

    public static final UUID SERVICE_UUID = UUID.fromString("a8b345a3-f547-4cfc-a843-8583487cb6e0");
    public static final UUID COMMAND_CHAR_UUID = UUID.fromString("a8b345a3-f547-4cfc-a843-8583487cb6e1");

    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        BluetoothServerService getService() {
            return BluetoothServerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "════════════════════════════════════");
        Log.i(TAG, "Service onCreate - Initializing...");
        Log.i(TAG, "════════════════════════════════════");

        mainHandler = new Handler(Looper.getMainLooper());
        bluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);

        if (bluetoothManager == null) {
            Log.e(TAG, "FATAL: BluetoothManager is null");
            stopSelf();
            return;
        }

        bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null) {
            Log.e(TAG, "FATAL: Bluetooth not supported");
            stopSelf();
            return;
        }

        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "FATAL: BLE not available");
            stopSelf();
            return;
        }

        // Register Bluetooth state receiver
        registerBluetoothStateReceiver();

        // Initialize callbacks
        initializeCallbacks();

        Log.i(TAG, "Service onCreate completed successfully");
    }

    private void registerBluetoothStateReceiver() {
        bluetoothStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final String action = intent.getAction();
                if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    handleBluetoothStateChange(state);
                }
            }
        };

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothStateReceiver, filter);
    }

    private void handleBluetoothStateChange(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
                Log.w(TAG, "Bluetooth turned OFF");
                cleanupBluetooth();
                break;
            case BluetoothAdapter.STATE_ON:
                Log.i(TAG, "Bluetooth turned ON - reinitializing...");
                mainHandler.postDelayed(() -> initializeBluetooth(), 1000);
                break;
        }
    }

    private void initializeCallbacks() {
        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                super.onStartSuccess(settingsInEffect);
                isAdvertising = true;
                Log.i(TAG, "════════════════════════════════════");
                Log.i(TAG, "✓✓✓ ADVERTISING SUCCESS ✓✓✓");
                Log.i(TAG, "Service UUID: " + SERVICE_UUID);
                Log.i(TAG, "Mode: " + settingsInEffect.getMode());
                Log.i(TAG, "Tx Power: " + settingsInEffect.getTxPowerLevel());
                Log.i(TAG, "════════════════════════════════════");
            }

            @Override
            public void onStartFailure(int errorCode) {
                super.onStartFailure(errorCode);
                isAdvertising = false;
                Log.e(TAG, "════════════════════════════════════");
                Log.e(TAG, "✗✗✗ ADVERTISING FAILED ✗✗✗");
                Log.e(TAG, "Error: " + getAdvertiseErrorString(errorCode));
                Log.e(TAG, "════════════════════════════════════");

                // Wait and retry
                mainHandler.postDelayed(() -> {
                    Log.i(TAG, "Retrying advertising...");
                    startAdvertising();
                }, 5000);
            }
        };

        gattServerCallback = new BluetoothGattServerCallback() {
            @Override
            public void onServiceAdded(int status, BluetoothGattService service) {
                Log.i(TAG, "════════════════════════════════════");
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "✓ GATT Service Added");
                    Log.i(TAG, "UUID: " + service.getUuid());
                    Log.i(TAG, "Characteristics: " + service.getCharacteristics().size());

                    serviceAdded = true;

                    if (gattServer != null && gattServer.getService(SERVICE_UUID) != null) {
                        Log.i(TAG, "✓ Service verified in GATT server");
                        Log.i(TAG, "════════════════════════════════════");

                        // Post to main thread to start advertising
                        mainHandler.post(() -> startAdvertising());
                    } else {
                        Log.e(TAG, "✗ Service verification failed");
                        Log.e(TAG, "════════════════════════════════════");
                    }
                } else {
                    Log.e(TAG, "✗ GATT Service Add Failed");
                    Log.e(TAG, "Status: " + status);
                    Log.e(TAG, "════════════════════════════════════");
                }
            }

            @Override
            public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
                super.onConnectionStateChange(device, status, newState);
                String state = (newState == BluetoothGatt.STATE_CONNECTED) ? "CONNECTED" : "DISCONNECTED";
                Log.i(TAG, "Connection: " + state + " | Device: " + device.getAddress());
            }

            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                     BluetoothGattCharacteristic characteristic,
                                                     boolean preparedWrite, boolean responseNeeded,
                                                     int offset, byte[] value) {

                Log.i(TAG, "Write Request | From: " + device.getAddress());

                if (characteristic.getUuid().equals(COMMAND_CHAR_UUID)) {
                    try {
                        String received = new String(value, StandardCharsets.UTF_8);
                        Log.i(TAG, "Command: " + received);

                        JSONObject json = new JSONObject(received);
                        String cmd = json.getString("cmd");

                        if ("dial".equals(cmd)) {
                            String number = json.getString("number");
                            placeCall(number);
                        }

                        sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value, responseNeeded);
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling command", e);
                        sendGattResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null, responseNeeded);
                    }
                }
            }
        };
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand called");

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Communication Bridge")
                .setContentText("BLE Server Active")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // Initialize Bluetooth on main thread
        mainHandler.post(() -> initializeBluetooth());

        return START_STICKY;
    }

    private void initializeBluetooth() {
        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Bluetooth is not enabled!");
            return;
        }

        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.e(TAG, "BLE Advertiser not available");
            return;
        }

        Log.i(TAG, "Bluetooth initialized, starting GATT server...");
        startGattServer();
    }

    private void startGattServer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted");
                return;
            }
        }

        try {
            // Close existing server if any
            if (gattServer != null) {
                try {
                    gattServer.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing existing GATT server", e);
                }
                gattServer = null;
            }

            gattServer = bluetoothManager.openGattServer(this, gattServerCallback);

            if (gattServer == null) {
                Log.e(TAG, "Failed to open GATT server");
                return;
            }

            Log.i(TAG, "GATT server opened");

            BluetoothGattService service = createGattService();
            boolean added = gattServer.addService(service);

            Log.i(TAG, "GATT service add " + (added ? "requested" : "failed"));
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException opening GATT server", e);
        } catch (Exception e) {
            Log.e(TAG, "Exception in startGattServer", e);
        }
    }

    private void startAdvertising() {
        if (!bluetoothAdapter.isEnabled()) {
            Log.e(TAG, "Cannot advertise: Bluetooth is not enabled");
            return;
        }

        if (isAdvertising) {
            Log.w(TAG, "Already advertising");
            return;
        }

        if (!serviceAdded) {
            Log.e(TAG, "Cannot advertise: service not added");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_ADVERTISE permission not granted");
                return;
            }
        }

        if (advertiser == null) {
            Log.e(TAG, "Advertiser is null, attempting to get it again");
            advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (advertiser == null) {
                Log.e(TAG, "Cannot get advertiser");
                return;
            }
        }

        try {
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();

            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                    .build();

            Log.i(TAG, "Starting advertising with UUID: " + SERVICE_UUID);
            advertiser.startAdvertising(settings, data, advertiseCallback);
            Log.i(TAG, "startAdvertising() called, waiting for callback...");

        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in startAdvertising", e);
        } catch (Exception e) {
            Log.e(TAG, "Exception in startAdvertising", e);
            e.printStackTrace();
        }
    }

    private void sendGattResponse(BluetoothDevice device, int requestId, int status,
                                  int offset, byte[] value, boolean responseNeeded) {
        if (!responseNeeded || gattServer == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED) {
                    gattServer.sendResponse(device, requestId, status, offset, value);
                }
            } else {
                gattServer.sendResponse(device, requestId, status, offset, value);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending GATT response", e);
        }
    }

    private void placeCall(String number) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "CALL_PHONE permission not granted");
            return;
        }

        if (telecomManager == null) {
            Log.e(TAG, "TelecomManager not available");
            return;
        }

        try {
            Uri uri = Uri.fromParts("tel", number, null);
            telecomManager.placeCall(uri, new Bundle());
            Log.i(TAG, "✓ Call placed: " + number);
        } catch (Exception e) {
            Log.e(TAG, "Error placing call", e);
        }
    }

    @NonNull
    private BluetoothGattService createGattService() {
        BluetoothGattService service = new BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
        );

        BluetoothGattCharacteristic commandChar = new BluetoothGattCharacteristic(
                COMMAND_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE |
                        BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );

        service.addCharacteristic(commandChar);
        Log.i(TAG, "GATT Service created: " + SERVICE_UUID);
        return service;
    }

    private void cleanupBluetooth() {
        isAdvertising = false;
        serviceAdded = false;

        try {
            if (advertiser != null && advertiseCallback != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE)
                            == PackageManager.PERMISSION_GRANTED) {
                        advertiser.stopAdvertising(advertiseCallback);
                    }
                } else {
                    advertiser.stopAdvertising(advertiseCallback);
                }
                Log.i(TAG, "Advertising stopped");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping advertising", e);
        }

        try {
            if (gattServer != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED) {
                        gattServer.close();
                    }
                } else {
                    gattServer.close();
                }
                gattServer = null;
                Log.i(TAG, "GATT server closed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing GATT server", e);
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy");

        try {
            if (bluetoothStateReceiver != null) {
                unregisterReceiver(bluetoothStateReceiver);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }

        cleanupBluetooth();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "BLE Communication Bridge",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private String getAdvertiseErrorString(int errorCode) {
        switch (errorCode) {
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "ALREADY_STARTED";
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "DATA_TOO_LARGE";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "FEATURE_UNSUPPORTED";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "INTERNAL_ERROR";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "TOO_MANY_ADVERTISERS";
            default:
                return "UNKNOWN (" + errorCode + ")";
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
