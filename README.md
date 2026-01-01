# Whisper Voice 🎤

A modern Android voice assistant app with floating overlay mode, powered by OpenAI's Whisper for accurate offline speech recognition.

## Features

### 🎯 Floating Overlay Mode
- **Floating mic button** that appears in whitelisted apps (Google, Gemini, etc.)
- Tap to record, tap again to stop
- Automatically injects transcribed text into input fields
- Configurable button size (Small / Medium / Large)
- Drag to reposition anywhere on screen

### 🔊 Voice Recognition
- Powered by OpenAI Whisper - state-of-the-art speech recognition
- Works **completely offline** after initial model download
- Supports 99+ languages
- Optional **translate to English** from any language

### ⌨️ Keyboard Mode (IME)
- Use as Android input method
- Integrates with other keyboards like HeliBoard
- Append or replace text modes

### 🎨 Modern Dark UI
- Clean, monochromatic dark theme
- ChatGPT-inspired design
- Smooth animations and visual feedback

## Setup

1. **Download models** - On first launch, the app downloads Whisper models (~435 MB)
2. **Grant permissions** - Overlay permission for floating mode, Accessibility for text injection
3. **Select apps** - Choose which apps show the floating button

## Usage

- **Floating Mode**: Tap "Start Floating Mode" → Open a whitelisted app → Tap the floating mic → Speak → Tap again to stop
- **Keyboard Mode**: Enable Whisper as input method in Android settings

## Screenshots

| Main App | Floating Overlay |
|----------|------------------|
| Dark monochrome UI | Floating mic button in any app |

## Tech Stack

- Kotlin / Java
- TensorFlow Lite (Whisper model inference)
- Android Accessibility Service (text injection)
- Android WindowManager (floating overlay)

## Credits

Based on [whisperIME](https://github.com/woheller69/whisperIME) by woheller69, with significant modifications:
- Added floating overlay mode with app whitelisting
- Redesigned UI with modern dark theme
- Added configurable button sizing
- Enhanced app auto-start and accessibility features

## License

MIT License

---

Made with ❤️ by [adithyakrish0](https://github.com/adithyakrish0)
