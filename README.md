# EverythingDroid

Android app that connects to **Voidtools Everything**'s built-in HTTP server,
runs Everything search queries, and downloads files from your PC to the
phone's app-private Downloads folder.

- Tech: Kotlin 2.0, Jetpack Compose (Material 3), OkHttp, DataStore.
- Min Android: 7.0 (SDK 24). Target / compile SDK: 35 (Android 15).

## Download

Grab the latest APK from the
[**Releases**](https://github.com/mickeyperry/EverythingDroid/releases/latest)
page (built automatically by GitHub Actions on every tag).

Install on Android via USB:

```powershell
C:\Android\adb.exe install -r EverythingDroid-vX.Y.Z-debug.apk
```

Or copy the APK to your phone and tap to install (enable "Install unknown apps"
for your file manager first).

---

## 1. Enable the Everything HTTP server on your PC

1. Open **Everything** (Voidtools).
2. `Tools → Options → HTTP Server`.
3. Tick **Enable HTTP server**.
4. Note the **port** (default 80). Pick something like 8080 if 80 is taken.
5. Optional but recommended on a LAN: set a **username** and **password**.
6. Click **OK**.
7. Make sure your firewall lets the phone reach that port. From the phone,
   visiting `http://<pc-ip>:<port>/` in a browser should show Everything's
   web search page.

The app uses Everything's official JSON endpoint:

```
http://<host>:<port>/?s=<query>&j=1&path_column=1&size_column=1&date_modified_column=1
```

And downloads files directly from `http://<host>:<port>/<path>` (the same URL
Everything's web UI links to).

---

## 2. Build the APK

You currently don't have a JDK or the Android SDK installed. Pick one path:

### Path A — Android Studio (recommended)

1. Install **Android Studio** (latest stable): <https://developer.android.com/studio>.
   It bundles the JDK and SDK, so you don't need to install them separately.
2. On first launch, let it install the SDK platform & build tools when prompted.
3. `File → Open…` and select this project folder:
   `C:\Users\Mickey\Documents\AndroidProjects\EverythingDroid`.
4. Wait for Gradle sync (a few minutes the first time — it downloads
   dependencies).
5. Plug in your phone with USB debugging enabled, or use an emulator.
6. Click **Run ▶** (or `Shift+F10`).

### Path B — Command-line build

Requires JDK 17 and the Android SDK / command-line tools on PATH with
`ANDROID_HOME` set. Then from this folder:

```powershell
.\gradlew.bat assembleDebug
```

The APK lands in `app\build\outputs\apk\debug\app-debug.apk`. Install with:

```powershell
C:\Android\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

> Note: the Gradle wrapper JAR (`gradle/wrapper/gradle-wrapper.jar`) is *not*
> checked into this scaffold. Android Studio writes it for you on first sync.
> For command-line builds without Studio, generate it once with
> `gradle wrapper` from a system Gradle install, or copy it from any existing
> Android project.

---

## 3. Using the app

1. Launch **EverythingDroid**, tap the gear icon to open Settings.
2. Enter your PC's LAN IP (e.g. `192.168.1.20`), port, and optional creds.
3. Save. Back on the search screen, type an Everything query (same syntax as
   the desktop Everything search box: `*.pdf`, `size:>10mb`, `ext:zip`, etc.)
   and tap the search icon.
4. Tap the download icon on any file row — it streams the file to
   `Android/data/com.mickey.everythingdroid/files/Download/` on the phone.

---

## 4. Notes & limits

- **Cleartext HTTP is enabled** because Everything's server is plain HTTP by
  default. If you front it with HTTPS (e.g. a reverse proxy), toggle the
  HTTPS switch in Settings.
- Auth is HTTP Basic; credentials are stored locally via DataStore.
- Folder download isn't supported (Everything's HTTP server serves files, not
  recursive folder archives). The app only enables the download button for
  `type == "file"` results.
- Downloads stream with a 64 KiB buffer and a 60s read timeout per chunk; very
  large files over slow links may need a longer read timeout (edit
  `EverythingApi.kt`).
