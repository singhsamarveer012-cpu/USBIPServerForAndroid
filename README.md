USB/IP server for Android

# USB/IP Server for Android 📱🔌

[![Android API](https://img.shields.io/badge/Android%20API-21%2B-brightgreen)](https://developer.android.com)
[![Target API](https://img.shields.io/badge/Target%20API-35-blue)](https://developer.android.com/studio/releases/platforms)
[![License: GPL v2](https://img.shields.io/badge/License-GPL%20v2-yellow.svg)](https://www.gnu.org/licenses/gpl-2.0.html)

**A modern, high-performance USB/IP server for Android that allows remote access to connected USB devices over a network connection.**

---

## 🌟 Features

### ✨ Core Functionality

- **Real-time USB Device Discovery**: Automatically detects and shares connected USB devices
- **USB/IP Protocol Support**: Full implementation of USB/IP protocol for network USB sharing
- **Background Service**: Runs as a foreground service for continuous operation
- **Cross-platform Compatibility**: Works with Linux, macOS, and other USB/IP clients

### 🎨 Modern User Interface

- **Dark Theme**: Professional dark interface with excellent contrast
- **Live Device List**: Real-time device monitoring with connection status
- **Material Design 3**: Modern Android design system
- **Responsive Layout**: Optimized for various screen sizes

### 🔧 Technical Features

- **Native USB Support**: JNI integration for enhanced USB performance
- **Auto USB Permissions**: Requests USB device access when devices are attached
- **Network Monitoring**: Maintains wake locks for reliable network connectivity
- **USB Speed Detection**: Automatically detects and reports USB device speeds
- **Bulk & Interrupt Transfers**: Full support for all USB transfer types

---

## 📋 Requirements

### Hardware Requirements

- **USB Host Support**: Android device with USB host functionality (OTG support)
- **Network Connectivity**: WiFi or Ethernet for USB/IP network communication

### Software Requirements

- **Android Version**: Android 5.0 (API 21) or higher
- **USB Host Permission**: Required for accessing USB devices
- **Network Permissions**: Required for network USB sharing

### Client Requirements

- **Linux/macOS**: Native USB/IP client support
- **USB/IP Tools**: Standard Linux USB/IP utilities
- **Network Access**: Access to Android device on same network

---

## 🚀 Installation

### 1. Download APK

```bash
# Download the latest APK from releases
wget https://github.com/alexonpeace/USBIPServerForAndroid/releases/latest/download/USBIPServerForAndroid.apk
```

### 2. Install on Android Device

1. Enable **"Install from Unknown Sources"** in Android Settings
2. Transfer APK file to your Android device
3. Open APK file and follow installation prompts

### 3. Grant Permissions

The app will request the following permissions on first launch:

- **USB Host Access**: Required to communicate with USB devices
- **Internet Access**: Required for USB/IP network communication
- **Notification Access**: Required for foreground service status

---

## 📖 Usage

### 1. Start the Server

1. **Launch the App**: Open USB/IP Server on your Android device
2. **Tap "Start Server"**: Begins the USB/IP server service
3. **Background Operation**: Service continues running even when app is closed

### 2. Connect USB Devices

1. **Plug in USB Device**: Connect any USB device to your Android device
2. **Grant Permission**: When prompted, grant USB access to the app
3. **Device Appears**: Device shows up in the connected devices list

### 3. Connect from Client Computer

```bash
# Discover devices (replace IP with your Android device IP)
sudo usbip list -r [ANDROID_IP]

# Attach a USB device (busid from previous command)
sudo usbip attach -r [ANDROID_IP] -b [BUSID]
```

### 4. Server Information

- **Server Port**: `3240` (default USB/IP port)
- **Discoverable**: Appears as "usbip-server-usbip" on network
- **Auto-restart**: Service automatically restarts on device reboot

---

## 🏗️ Building from Source

USB/IP server for Android

````
### Prerequisites

```bash
# Required tools
- Android Studio Arctic Fox or later
- Android NDK 27.x.x (already configured)
- Java 17 JDK (configured in build.gradle)

# Dependencies (automatically managed)
- AndroidX Libraries (AppCompat, Material, RecyclerView, CardView)
- Google Material Components
- Android USB Host APIs
````

### Build Steps

1. **Clone the Repository**

```bash
git clone https://github.com/alexonpeace/USBIPServerForAndroid.git
cd USBIPServerForAndroid
```

2. **Open in Android Studio**

```bash
# Open the project in Android Studio
# File > Open > Select USBIPServerForAndroid directory
```

3. **Build APK**

```bash
# Using Gradle wrapper
./gradlew assembleDebug

# Or build APK
./gradlew build

# APKs will be in app/build/outputs/apk/
```

4. **Install on Device**

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 🏛️ Architecture Overview

### 📁 Project Structure

```
USBIPServerForAndroid/
├── app/
│   ├── build.gradle      # App configuration & dependencies
│   ├── src/main/
│   │   ├── AndroidManifest.xml    # App permissions & configuration
│   │   ├── java/org/cgutman/
│   │   │   ├── usbip/
│   │   │   │   ├── config/         # UI activities & device management
│   │   │   │   ├── server/         # USB/IP server implementation
│   │   │   │   ├── service/        # Background service
│   │   │   │   ├── usb/           # USB utilities & control helpers
│   │   │   │   └── utils/         # Stream utilities
│   │   │   └── jni/               # Native USB implementation
│   │   └── res/                   # UI resources (layouts, colors, strings)
└── gradle/                        # Build configuration
```

### 🔄 Architecture Components

#### **1. Service Layer (`UsbIpService`)**

- **Foreground Service**: Continuously monitors USB devices
- **Broadcast Receivers**: Handles USB attachment/detachment events
- **Permission Management**: Requests USB permissions when devices connect
- **Network Socket**: USB/IP protocol server on port 3240

#### **2. UI Layer (`UsbIpConfig`)**

- **Material Design**: Modern dark theme interface
- **Device List**: Live device monitoring with RecyclerView
- **Service Controls**: Start/stop server with visual feedback
- **Permission Handling**: User-friendly permission requests

#### **3. Protocol Layer (`server/` package)**

- **USB/IP Implementation**: Full protocol support for device sharing
- **Device Enumeration**: Lists available USB devices with metadata
- **Urb Processing**: Handles USB transfers (bulk, interrupt, control)
- **Client Management**: Multi-client support for network connections

#### **4. Hardware Layer (JNI + Java)**

- **Native USB**: Enhanced performance with JNI libusblib
- **USB Detection**: Automatic speed and interface detection
- **Transfer Operations**: Direct USB host controller access
- **Device Control**: USB descriptors and endpoint management

---

## 🔧 USB/IP Protocol Support

### Supported Operations

- **Device Discovery**: List available USB devices network-wide
- **Device Attachment**: Share Android USB devices with remote machines
- **Data Transfer**: Full USB transfer types (Control, Bulk, Interrupt, Isochronous)
- **Device Control**: USB control transfers for configuration
- **Auto Acceleration**: Optimized data transfers for best performance

### Client Compatibility

- **Linux (usbip-utils)**: Native USB/IP client support
- **macOS (Linux VM)**: USB/IP through virtual machines
- **Windows**: Via WSL2 with usbip-utils
- **Network**: Any USB/IP compatible client

---

## 🔧 Development Notes

### Android 15 Support

- **Target API 35**: Updated for Android 15 compatibility
- **Modern USB APIs**: Uses latest Android USB framework
- **Permission Handling**: Implements Android 14+ USB requirements
- **Dark Theme**: Native system dark theme support

### Performance Optimizations

- **JNI Integration**: Native USB operations for low latency
- **Thread Pool**: Efficient concurrent USB transfer handling
- **Connection Pool**: Reused connections for multiple client sessions
- **Background Processing**: Non-blocking USB operations

### Security Considerations

- **USB Permissions**: Explicit user consent for device access
- **Network Access**: Isolated to USB/IP protocol port
- **Background Operation**: Respects Android battery optimization
- **Data Privacy**: No internet connectivity or data collection

---

## 🤝 Contributing

We welcome contributions! Here's how to get involved:

### 🐛 Reporting Issues

1. Use the [GitHub Issues](https://github.com/alexonpeace/USBIPServerForAndroid/issues) system
2. Include Android version, device model, and detailed reproduction steps
3. Provide logcat output when possible

### 🔧 Contributing Code

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### 🎯 Development Setup

```bash
# Clone and setup
git clone https://github.com/alexonpeace/USBIPServerForAndroid.git
cd USBIPServerForAndroid

# Use Android Studio or command line
./gradlew assembleDebug
```

---

## 📊 Roadmap

### 🚧 Current Features (✅ Ready)

- Android 15 compatibility
- Dark theme interface
- USB/IP protocol implementation
- Cross-platform client support
- Native USB performance

### 🚀 Future Enhancements (🔮 Planned)

- **Advanced Device Filtering**: Selective device sharing
- **Device Management UI**: Removable device controls
- **Performance Metrics**: Transfer speed monitoring
- **Bulk Operations**: Multi-device management
- **Android TV Support**: Embedded device integration

---

## 💬 FAQ

### 🔧 Installation Issues

**Q:** App crashes immediately on Android 15?
**A:** The app is fully compatible with Android 15 (API 35). Make sure you're using the latest build.

**Q:** "USB host not supported" error?
**A:** Your Android device may not support USB host mode (OTG). Check your device's specifications.

### 🔌 USB Issues

**Q:** Device doesn't appear in the list?
**A:** Grant USB permissions when prompted, or use "Refresh" to rescan for devices.

**Q:** Client can't connect to Android device?
**A:** Ensure both devices are on the same network. Check firewall rules and Android's "Local hotspot" settings.

### 🚀 Performance Issues

**Q:** Slow USB transfers?
**A:** This is normal for USB/IP over WiFi. For best performance, use Ethernet connection when possible.

---

## 🌟 Connect with Us

- **GitHub**: [alexonpeace/USBIPServerForAndroid](https://github.com/alexonpeace/USBIPServerForAndroid)
- **Issues**: [Report Bugs & Request Features](https://github.com/alexonpeace/USBIPServerForAndroid/issues)
- **Discussions**: [Community Questions](https://github.com/alexonpeace/USBIPServerForAndroid/discussions)

---

## 🚀 Getting Started

1. **Start the server** on your Android device
2. **Connect USB devices** and grant permissions
3. **Discover and attach** from your Linux/macOS client
4. **Enjoy remote USB access** from your computer!

Happy USB/IP networking! 🎉

---

## 📄 License

This project is licensed under the GNU General Public License v2.0.

**Key Points:**

- Free software - you can redistribute and/or modify it
- Commercial usage allowed
- Attribution required if derivative work
- See [LICENSE](LICENSE) for complete terms

---

**⭐ If you find this project useful, please star it on GitHub! ✨**

_Built with ❤️ for Android USB enthusiasts and network computing_
