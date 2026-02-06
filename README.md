# Whisper Voice 🎤

A state-of-the-art Android voice assistant app featuring a sleek Material 3 interface and powerful offline transcription. Powered by OpenAI's Whisper for human-level speech recognition accuracy.

## Key Features

### ✨ New: Video & Audio Transcription
- **One-Click Transcription**: Upload local video or audio files for instant text extraction.
- **Smart Queueing**: Uses background services with data-sync types for reliable processing even when the app is closed.
- **Auto-Redirect**: Seamlessly transitions from processing to organized history results.
- **Privacy First**: All processing is strictly local; temporary audio cache is wiped immediately after usage.

### 🎯 Pro Floating Overlay
- **Material 3 FAB**: Sleeek microphone floating button that appears in whitelisted apps.
- **Tap to Toggle**: Modern interaction—tap to record, tap to stop.
- **Accessibility Integration**: Automatically injects transcribed text into any input field.
- **Customizable**: Adjustable button sizes (Small/Medium/Large) and drag-to-reposition anywhere.

### 🔊 Core Engine
- **OpenAI Whisper**: State-of-the-art offline speech recognition.
- **Zero Latency**: Works **completely offline** after initial model download.
- **Multilingual Support**: Supports 99+ languages with optional **English translation**.
- **Memory Efficient**: Optimized for low RAM usage with chunked 16kHz mono processing.

### 🎨 Premium Dark UI
- **Material 3 Design**: Clean, monochromatic aesthetic with professional glassmorphism.
- **Vault/History**: Organized history view with Copy, Share, and Export (TXT) capabilities.
- **Dynamic Feedback**: Real-time logging and status cards during transcription.

## Setup

1. **Download Models**: On first launch, the app downloads high-precision Whisper models (~435 MB).
2. **Grant Permissions**: Overlay permission for floating mode, Accessibility for text injection.
3. **Whitelist Apps**: Choose which apps (Google, Gemini, WhatsApp, etc.) should show the floating assistant.

## Tech Stack

- **Kotlin / Java**: Native Android development.
- **TensorFlow Lite**: Whisper model inference with hardware acceleration.
- **Android MediaCodec**: High-performance audio extraction from video streams.
- **Material Design 3**: Modern UI/UX components.

## Credits

Based on the [whisperIME](https://github.com/woheller69/whisperIME) core, with major enhancements:
- Complete Material 3 UI/UX overhaul.
- New Background Video Transcription system.
- Android 14+ Foreground Service compliance.
- Memory-safe chunked processing for long audio files.

## License

MIT License

---

Made with ❤️ by [adithyakrish0](https://github.com/adithyakrish0)
