# 🎙️ Android App for Voice Computer Use

A simple Android application that lets you control your PC remotely using voice commands. It is a client for the [simple-computer-use](https://github.com/pnmartinez/simple-computer-use) project.

![imagen](https://github.com/user-attachments/assets/4606ea63-9514-4aae-867c-65a1e4341f28)
![imagen](https://github.com/user-attachments/assets/26338ea2-a063-4bff-b72d-6d2ee1001cab)


## ✨ Features

- 🎤 Record audio through your Android device
- 📡 Send voice commands to your PC via secure WebSocket connection
- 🔊 Receive and play audio responses from your PC
- 🔒 Secure communication using TLS encryption
- 🔄 CI/CD with automatic builds via GitHub Actions

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
