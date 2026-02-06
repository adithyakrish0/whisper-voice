package com.whispertflite.caption

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility class to extract and decode audio from video files
 * Converts to 16kHz mono PCM format required by Whisper
 */
object AudioExtractor {
    
    private const val TAG = "AudioExtractor"
    private const val SAMPLE_RATE = 16000 // Whisper requires 16kHz
    private const val TIMEOUT_US = 10000L
    
    /**
     * Extract audio from video file and convert to float samples for Whisper
     * @param videoFile The input video file
     * @return FloatArray of audio samples at 16kHz mono, or null if failed
     */
    fun extractAudio(videoFile: File): FloatArray? {
        if (!videoFile.exists()) {
            Log.e(TAG, "Video file does not exist: ${videoFile.absolutePath}")
            return null
        }
        
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        
        try {
            extractor.setDataSource(videoFile.absolutePath)
            
            // Find audio track
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                Log.e(TAG, "No audio track found in video")
                return null
            }
            
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            
            Log.d(TAG, "Audio format: $mime, sampleRate=$sampleRate, channels=$channelCount")
            
            // Create decoder
            try {
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize decoder for $mime", e)
                return null
            }
            
            // Decode audio
            val pcmData = try {
                decodeAudio(extractor, decoder)
            } catch (e: Exception) {
                Log.e(TAG, "Error during audio decoding", e)
                ShortArray(0)
            }
            
            if (pcmData.isEmpty()) {
                Log.e(TAG, "No audio data decoded")
                return null
            }
            
            Log.d(TAG, "Decoded ${pcmData.size} PCM samples")
            
            // Convert to mono if stereo
            // Update format from decoder (it might have changed during decoding, e.g., 22050 -> 44100)
            val outputFormat = decoder!!.outputFormat
            val actualSampleRate = if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                sampleRate
            }
            val actualChannelCount = if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                channelCount
            }
            
            Log.d(TAG, "Actual decoder output: rate=$actualSampleRate, channels=$actualChannelCount")

            val monoData = if (actualChannelCount > 1) {
                convertToMono(pcmData, actualChannelCount)
            } else {
                pcmData
            }
            
            // Resample to 16kHz if needed
            val resampledData = if (actualSampleRate != SAMPLE_RATE) {
                resample(monoData, actualSampleRate, SAMPLE_RATE)
            } else {
                monoData
            }
            
            // Convert to float array (-1.0 to 1.0)
            val floatSamples = FloatArray(resampledData.size)
            for (i in resampledData.indices) {
                floatSamples[i] = resampledData[i] / 32768.0f
            }
            
            Log.d(TAG, "Final audio: ${floatSamples.size} samples at 16kHz")
            return floatSamples
            
        } catch (e: Exception) {
            Log.e(TAG, "Audio extraction failed", e)
            return null
        } finally {
            try {
                decoder?.stop()
            } catch (ignore: Exception) {}
            try {
                decoder?.release()
            } catch (ignore: Exception) {}
            try {
                extractor.release()
            } catch (ignore: Exception) {}
        }
    }
    
    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                return i
            }
        }
        return -1
    }
    
    private fun decodeAudio(extractor: MediaExtractor, decoder: MediaCodec): ShortArray {
        var inputBuffers = decoder.inputBuffers
        var outputBuffers = decoder.outputBuffers
        val info = MediaCodec.BufferInfo()
        
        var allSamples = ShortArray(1024 * 1024) // Start with 1M samples (~2MB)
        var totalSamples = 0
        
        var inputDone = false
        var outputDone = false
        
        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = inputBuffers[inputIndex]
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            
            // Get output
            val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    outputBuffers = decoder.outputBuffers
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Output format changed: ${decoder.outputFormat}")
                }
                outputIndex >= 0 -> {
                    val outputBuffer = outputBuffers[outputIndex]
                    outputBuffer.position(info.offset)
                    outputBuffer.limit(info.offset + info.size)
                    
                    // Read PCM samples (16-bit signed)
                    val shortBuffer = outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val samplesCount = shortBuffer.remaining()
                    
                    // Resize if needed
                    if (totalSamples + samplesCount > allSamples.size) {
                        allSamples = allSamples.copyOf(allSamples.size * 2)
                    }
                    
                    shortBuffer.get(allSamples, totalSamples, samplesCount)
                    totalSamples += samplesCount
                    
                    decoder.releaseOutputBuffer(outputIndex, false)
                    
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }
        
        return allSamples.copyOf(totalSamples)
    }
    
    private fun convertToMono(stereoData: ShortArray, channels: Int): ShortArray {
        val monoData = ShortArray(stereoData.size / channels)
        for (i in monoData.indices) {
            var sum = 0
            for (c in 0 until channels) {
                sum += stereoData[i * channels + c]
            }
            monoData[i] = (sum / channels).toShort()
        }
        return monoData
    }
    
    private fun resample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate) return input
        
        val ratio = srcRate.toDouble() / dstRate
        val outputSize = (input.size / ratio).toInt()
        val output = ShortArray(outputSize)
        
        for (i in output.indices) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex
            
            if (srcIndex + 1 < input.size) {
                // Linear interpolation
                val sample = input[srcIndex] * (1 - frac) + input[srcIndex + 1] * frac
                output[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            } else if (srcIndex < input.size) {
                output[i] = input[srcIndex]
            }
        }
        
        return output
    }
}
