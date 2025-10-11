package com.example.btbridge;

import android.content.Intent;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.util.Log;

public class DialerService extends ConnectionService {
    private static final String TAG = "DialerService";

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Log.d(TAG, "📞 Outgoing call: " + request.getAddress());

        // Create a basic connection
        Connection connection = new Connection() {
            @Override
            public void onDisconnect() {
                Log.i(TAG, "Call disconnected");
                sendBluetoothCommand("CALL_END");
                setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
                destroy();
            }

            @Override
            public void onAnswer() {
                Log.i(TAG, "Call answered");
                sendBluetoothCommand("CALL_ANSWERED");
                setActive();
            }

            @Override
            public void onReject() {
                Log.i(TAG, "Call rejected");
                sendBluetoothCommand("CALL_REJECTED");
                setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
                destroy();
            }
        };

        connection.setAddress(request.getAddress(), 0);
        connection.setDialing();

        // Start Bluetooth Service communication
        sendBluetoothCommand("CALL_START:" + request.getAddress());

        return connection;
    }

    @Override
    public Connection onCreateIncomingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Log.d(TAG, "📲 Incoming call: " + request.getAddress());

        Connection connection = new Connection() {
            @Override
            public void onAnswer() {
                Log.i(TAG, "Incoming call answered");
                sendBluetoothCommand("CALL_ANSWERED");
                setActive();
            }

            @Override
            public void onReject() {
                Log.i(TAG, "Incoming call rejected");
                sendBluetoothCommand("CALL_REJECTED");
                setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
                destroy();
            }
        };

        connection.setAddress(request.getAddress(), 0);
        connection.setRinging();

        sendBluetoothCommand("CALL_INCOMING:" + request.getAddress());

        return connection;    }

    private void sendBluetoothCommand(String command) {
        Log.d(TAG, "➡ Sending command to BluetoothService: " + command);
        Intent intent = new Intent(this, BluetoothServerService.class);
        intent.setAction("com.example.btbridge.SEND_COMMAND");
        intent.putExtra("command", command);
        startService(intent);
    }
}
