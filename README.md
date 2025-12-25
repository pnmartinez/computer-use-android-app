# 🎙️ Android App for Voice Computer Use

[![Build Status](https://github.com/pnmartinez/computer-use-android-app/workflows/Build/badge.svg)](https://github.com/pnmartinez/computer-use-android-app/actions/workflows/build.yml)

A simple Android application that lets you control your PC remotely using voice commands. It is a client for the [simple-computer-use](https://github.com/pnmartinez/simple-computer-use) project.

![imagen](https://github.com/user-attachments/assets/e2b6d12b-5051-484e-86a8-d458b90be663)

## ✨ Features

- 🎤 Record audio through your Android device
- 📡 Send voice commands to your PC via secure WebSocket connection
- 🔊 Receive and play audio responses from your PC
- 🔒 Secure communication using TLS encryption
- 🔄 CI/CD with automatic builds via GitHub Actions

## 🔊 Text-to-Speech (TTS)

This app now initializes Android’s native `TextToSpeech` engine in the audio service to speak
server responses when no audio file is available (no extra dependencies needed beyond the
Android SDK). The response text currently comes from the `result` field in
`AudioService.processJsonResponse()` and is passed to `createTextToSpeechResponse(...)`, which
uses `TextToSpeechManager.speak(...)`.

**Para más adelante:** si el backend cambia el formato, o si quieres priorizar otro campo,
ajusta la llamada a `createTextToSpeechResponse(...)` dentro de `processJsonResponse()` para
pasar el texto correcto. El siguiente paso sería guardar el audio sintetizado en un archivo
(por ejemplo `response_audio.ogg`) para que se pueda reproducir como cualquier otra respuesta.

## 🚀 Getting Started

0. 📦 Build the app from source (Android Studio or gradlew...)
1. 📱 Install the app on your Android device
2. ⚙️ Configure your server address in the app settings
3. 🔐 Grant necessary audio recording permissions
4. 🗣️ Start sending voice commands to your PC

## 📋 Requirements

- 📱 Android 6.0 (Marshmallow) or higher
- 🌐 Internet connection
- 💻 A properly configured server running the companion software, get it: https://github.com/pnmartinez/simple-computer-use

## 🔒 Privacy

This app only records audio when you activate the recording function, and all communications with your PC are encrypted. 

## 🛠️ CI/CD

This project uses GitHub Actions for continuous integration. Every push to the main branch automatically:
- Builds debug and release versions of the app
- Makes APKs available as downloadable artifacts
