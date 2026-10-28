# SecureNote Mobile

SecureNote is a cross‑platform notes application built with **Expo** and **React Native**. It supports secure cloud storage via a custom native module and offers real export options for text, Excel (XLSX/CSV) and PDF.

---

## ✨ Features

- **Secure Cloud Saving** – Direct read/write access to Google Drive, Files app, etc. via the `secure-storage` native module.
- **Real Export Formats**
  - Export notes as plain **`.txt`** files.
  - Export notes as **Excel** (`.xlsx`) using the `xlsx` library.
  - Export notes as **CSV**.
  - Keep existing high‑quality **PDF** export.
- **Branding** – App renamed to **SecureNote** with a custom lock‑and‑pen icon and consistent coral background.
- **Rich Text Editing** – Powered by a custom `RichEditor` component.
- **Drawing Support** – Integrated drawing canvas.
- **Offline Sync** – Automatic background synchronization with cloud storage.
- **Dark Mode** – Adaptive UI that respects system theme.
- **Search** – Full‑text search across notes.
- **Tags & Categories** – Organize notes with custom tags.
- **Multi‑device Backup** – Seamless backup and restore across devices.
- **End-to-End Encryption** – Notes are encrypted locally with AES‑256 before being saved to cloud, ensuring only you can read them.

---

## 🚀 Getting Started

### Prerequisites

- **Node.js** (>= 18)
- **Expo CLI** (`npm i -g expo-cli`)
- **Android Studio** / **Xcode** for native builds (optional for development).

### Installation

```bash
# Clone the repository
git clone <your-repo-url>
cd SecureNotesMobile

# Install dependencies
npm install

# Install the required native module dependencies
npx expo install expo-file-system expo-sharing expo-print expo-modules-core

# Install the Excel library
npm i xlsx
```

### Running the App

```bash
# Start the development server
expo start

# Run on Android
npx expo run:android

# Run on iOS (macOS only)
npx expo run:ios
```

---

## 📦 Export Usage

In the editor screen tap the **Export** button and choose one of:
- **Text** – Saves a `.txt` file.
- **Excel** – Generates a true `.xlsx` spreadsheet.
- **CSV** – Generates a `.csv` file.
- **PDF** – Generates a PDF (unchanged).

All files are saved using `expo-file-system` and shared via `expo-sharing`.

---

## 🛠️ Development

- **Custom Native Module** – Located at `modules/secure-storage`. See `SecureStorageModule.kt` (Android) and `SecureStorageModule.swift` (iOS) for implementation details.
- **Brand Assets** – Icons are stored in `assets/`. The adaptive icon uses `adaptive-icon.png` with a solid background.

---

## 🤝 Contributing

Contributions are welcome! Please fork the repository, create a feature branch, and submit a pull request.

1. Fork the repo
2. Create a new branch (`git checkout -b feature/your-feature`)
3. Commit your changes (`git commit -m "Add feature"`)
4. Push to your fork (`git push origin feature/your-feature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the **MIT License**.
