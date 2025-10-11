package com.example.btbridge;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class AudioTestService {

    private static final String TAG = "AudioTestService";

    private volatile boolean isLoopbackRunning = false;
    private Thread loopbackThread;
    private AudioRecord recorder;
    private AudioTrack player;

    public void startAudioLoopbackTest(Context context) {
        if (isLoopbackRunning) {
            Log.i(TAG, "Loopback test already running");
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted");
            return;
        }

        isLoopbackRunning = true;
        loopbackThread = new Thread(() -> {
            try {
                int sampleRate = 16000;
                int channelConfig = AudioFormat.CHANNEL_IN_MONO;
                int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

                int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
                if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Invalid AudioRecord parameters");
                    return;
                }

                recorder = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .build();

                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "Failed to initialize AudioRecord");
                    return;
                }

                player = new AudioTrack.Builder()
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(audioFormat)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .build();


                if (player.getState() != AudioTrack.STATE_INITIALIZED) {
                    Log.e(TAG, "Failed to initialize AudioTrack");
                    recorder.release();
                    return;
                }

                recorder.startRecording();
                player.play();

                Log.i(TAG, "🎧 Loopback test started. Speak into mic...");

                byte[] buffer = new byte[bufferSize];
                while (isLoopbackRunning) {
                    int read = recorder.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        player.write(buffer, 0, read);
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Loopback error: " + e.getMessage(), e);
            } finally {
                if (recorder != null) {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                    recorder.release();
                    recorder = null;
                }
                if (player != null) {
                    if (player.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        player.stop();
                    }
                    player.release();
                    player = null;
                }
                Log.i(TAG, "🎧 Loopback test stopped and resources released.");
            }
        });
        loopbackThread.start();
    }

    public void stopAudioLoopbackTest() {
        if (!isLoopbackRunning) {
            return;
        }
        isLoopbackRunning = false;
        if (loopbackThread != null) {
            try {
                loopbackThread.join(); // Wait for the thread to finish
            } catch (InterruptedException e) {
                Log.e(TAG, "Failed to stop loopback thread", e);
                Thread.currentThread().interrupt();
            }
        }
        loopbackThread = null;
    }
}
