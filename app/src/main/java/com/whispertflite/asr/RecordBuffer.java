package com.whispertflite.asr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RecordBuffer {
    // Static variable to store the byte array
    private static byte[] outputBuffer;

    // Synchronized method to set the byte array
    public static synchronized void setOutputBuffer(byte[] buffer) {
        outputBuffer = buffer;
    }

    // Synchronized method to get the byte array
    public static synchronized byte[] getOutputBuffer() {
        return outputBuffer;
    }

    public static float[] getSamples() {
        return getSamples(0, outputBuffer.length / 2);
    }

    public static float[] getSamples(int startSample, int numSamples) {
        if (outputBuffer == null) return new float[0];
        
        ByteBuffer byteBuffer = ByteBuffer.wrap(outputBuffer);
        byteBuffer.order(ByteOrder.nativeOrder());
        byteBuffer.position(startSample * 2);

        float[] samples = new float[numSamples];
        float maxAbsValue = 0.0f;

        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) (byteBuffer.getShort() / 32768.0);
            float absVal = samples[i] < 0 ? -samples[i] : samples[i];
            if (absVal > maxAbsValue) {
                maxAbsValue = absVal;
            }
        }

        // Normalize the samples
        if (maxAbsValue > 0.0f) {
            for (int i = 0; i < numSamples; i++) {
                samples[i] /= maxAbsValue;
            }
        }

        return samples;
    }
}
