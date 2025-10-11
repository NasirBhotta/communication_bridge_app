package com.example.communication_bridge_app;

import android.Manifest;
import android.app.Activity;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.btbridge.AudioTestService;
import com.example.btbridge.BluetoothServerService;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSIONS_REQUEST_CODE = 123;

    private AudioTestService audioTestService;

    private final ActivityResultLauncher<Intent> requestRoleLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Log.i(TAG, "Default Dialer role granted.");
                    startBluetoothService();
                } else {
                    Log.w(TAG, "Default Dialer role not granted.");
                    Toast.makeText(this, "Default Dialer role is required for the app to function.", Toast.LENGTH_LONG).show();
                }
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

        audioTestService = new AudioTestService();

        Button startAudioTestButton = findViewById(R.id.start_audio_test_button);
        startAudioTestButton.setOnClickListener(v -> audioTestService.startAudioLoopbackTest(this));

        Button stopAudioTestButton = findViewById(R.id.stop_audio_test_button);
        stopAudioTestButton.setOnClickListener(v -> audioTestService.stopAudioLoopbackTest());

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

        // Audio Permission
        permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);

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
            // testPlaceCallThroughDialerService();

        }
    }

    private void checkIfDefaultDialer() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roleManager = getSystemService(RoleManager.class);
            if (roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                Log.i(TAG, "App is already the default dialer.");
                startBluetoothService();
            } else {
                Log.i(TAG, "App is not the default dialer, requesting role...");
                Intent intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                requestRoleLauncher.launch(intent);
            }
        } else {
            // For older Android versions, the original logic is kept as a fallback.
            TelecomManager tm = (TelecomManager) getSystemService(TELECOM_SERVICE);
            if (tm != null && getPackageName().equals(tm.getDefaultDialerPackage())) {
                startBluetoothService();
            } else {
                Intent intent = new Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, getPackageName());
                startActivity(intent);
            }
        }
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
                checkIfDefaultDialer(); // Continue to dialer check even if some permissions are denied
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
// for testing purposes
    private void testPlaceCallThroughDialerService() {
        TelecomManager telecomManager = (TelecomManager) getSystemService(TELECOM_SERVICE);

        Uri uri = Uri.fromParts("tel", "03123456789", null); // replace with any test number

        Bundle extras = new Bundle();
        extras.putParcelable(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                new PhoneAccountHandle(
                        new ComponentName(getPackageName(), "com.example.btbridge.DialerService"),
                        "BTBridgeAccount"
                )
        );

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        telecomManager.placeCall(uri, extras);
        Log.i("DialerTest", "✅ Call placed via custom DialerService");
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
        if (audioTestService != null) {
            audioTestService.stopAudioLoopbackTest();
        }
    }
}
