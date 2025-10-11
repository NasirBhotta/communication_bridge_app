package com.example.communication_bridge_app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.telecom.TelecomManager;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.btbridge.BluetoothServerService;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 123;

    private final ActivityResultLauncher<Intent> requestDefaultDialerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                checkIfDefaultDialer();
            });

    private final ActivityResultLauncher<Intent> enableBluetoothLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    Log.i(TAG, "Bluetooth enabled by user");
                    checkAndRequestPermissions();
                } else {
                    Toast.makeText(this, "Bluetooth is required for this app", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.i(TAG, "════════════════════════════════════");
        Log.i(TAG, "MainActivity onCreate");
        Log.i(TAG, "════════════════════════════════════");

        checkBluetoothEnabled();
    }

    private void checkBluetoothEnabled() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Log.w(TAG, "Bluetooth is not enabled, requesting user to enable it");
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBluetoothLauncher.launch(enableBtIntent);
        } else {
            Log.i(TAG, "Bluetooth is already enabled");
            checkAndRequestPermissions();
        }
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        // Bluetooth Permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH);
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        // Phone & SMS Permissions
        permissionsToRequest.add(Manifest.permission.CALL_PHONE);
        permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE);
        permissionsToRequest.add(Manifest.permission.READ_CALL_LOG);
        permissionsToRequest.add(Manifest.permission.SEND_SMS);
        permissionsToRequest.add(Manifest.permission.RECEIVE_SMS);
        permissionsToRequest.add(Manifest.permission.READ_SMS);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissionsToRequest.add(Manifest.permission.MANAGE_OWN_CALLS);
        }

        List<String> finalPermissionsList = new ArrayList<>();
        for (String permission : permissionsToRequest) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                finalPermissionsList.add(permission);
                Log.d(TAG, "Need permission: " + permission);
            }
        }

        if (!finalPermissionsList.isEmpty()) {
            Log.i(TAG, "Requesting " + finalPermissionsList.size() + " permissions");
            ActivityCompat.requestPermissions(this, finalPermissionsList.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        } else {
            Log.i(TAG, "All permissions granted");
            checkIfDefaultDialer();
        }
    }

    private void checkIfDefaultDialer() {
        // The default dialer prompt is not appearing, which prevents the service from starting.
        // We are bypassing this check for now to allow the Bluetooth service to run.
        // You can investigate the dialer functionality separately.
        Log.i(TAG, "Bypassing default dialer check and starting service.");
        startBluetoothService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            List<String> deniedPermissions = new ArrayList<>();

            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i]);
                    Log.w(TAG, "Permission denied: " + permissions[i]);
                }
            }

            if (deniedPermissions.isEmpty()) {
                Log.i(TAG, "All requested permissions granted");
                checkIfDefaultDialer();
            } else {
                Log.e(TAG, "Some permissions denied: " + deniedPermissions);
                Toast.makeText(this, "Some permissions were denied. App may not function correctly.", Toast.LENGTH_LONG).show();
                // Try to continue anyway - some permissions might be optional
                checkIfDefaultDialer();
            }
        }
    }

    private void startBluetoothService() {
        Log.i(TAG, "════════════════════════════════════");
        Log.i(TAG, "Starting Bluetooth Service");
        Log.i(TAG, "════════════════════════════════════");

        Intent serviceIntent = new Intent(this, BluetoothServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        Toast.makeText(this, "BLE Service Started", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "Service start requested");
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
        Log.i(TAG, "onDestroy");
        super.onDestroy();
    }
}
