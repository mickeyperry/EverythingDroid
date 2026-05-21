# EverythingDroid

**Your PC's file index, in your pocket.**
Search anything on your computer from your phone. Download it. Play it. Done.

Powered by [Voidtools Everything](https://www.voidtools.com/) and its HTTP server — so the search is instant, even across millions of files.

---

## What it does

- 🔍 **Search your whole PC** with Everything's blazing-fast index (same syntax: `*.pdf`, `ext:mp3 size:>5mb`, etc.)
- 📂 **Tap a folder** to drill in. Back button to come out.
- ⬇️ **Tap a file** to download. Progress bar included.
- ▶️ **Play media** — for music & video, hit ▶ and your default player (VLC, MX Player, etc.) streams it directly. No download needed.
- 📋 **Long-press anything** for a menu: Copy Windows path, Copy URL, Share, Open folder.
- 🎛️ **Sort** by name, size, or date — instantly.
- 🎨 **Icons that make sense** — music notes for `.mp3`, video reels for `.mkv`, archive boxes for `.zip`, and so on.

---

## Get the APK

Grab the latest build straight from the
[**Releases page**](https://github.com/mickeyperry/EverythingDroid/releases/latest) →
download the `.apk` to your phone and tap it (you may need to allow "Install unknown apps" for your file manager).

Or sideload via USB:

```powershell
adb install -r EverythingDroid-vX.Y.Z-debug.apk
```

> Builds are CI-built and debug-signed. Same keystore every time, so updates install over the top cleanly.

---

## First-time setup

### 1. Turn on Everything's HTTP server

Open **Everything** on your PC → `Tools → Options → HTTP Server`:

1. Tick **Enable HTTP server**
2. Pick a port (default `80`, or something like `8080` if 80 is taken)
3. Optional but smart: set a **username + password**
4. Hit OK

From your phone, hitting `http://<pc-ip>:<port>/` in a browser should now show Everything's search page. If it doesn't, your firewall is blocking — open the port for your private network.

### 2. Point the app at it

Launch EverythingDroid → tap the ⚙️ icon → fill in:
- **Host** — your PC's LAN IP (e.g. `192.168.1.20`) or Tailscale/VPN address
- **Port** — what you set in step 1
- **Username / Password** — only if you set them

Hit Save. You're ready.

### 3. Search & go

Type a query. Tap a result. That's the whole app.

---

## Tips

- **Everything search syntax** is the real one — `ext:flac`, `size:>100mb`, `dm:lastweek`, `path:music`, wildcards, regex, all of it.
- **Folder browse** uses Everything's `parent:` operator under the hood.
- **Downloads** land in `Android/data/com.mickey.everythingdroid/files/Download/` (the app's private storage — uninstalling the app wipes them).
- **Stream auth**: if you've set Basic Auth, the Play button embeds credentials in the URL so VLC etc. can fetch protected files. Anyone sniffing the URL on your LAN can see them — fine for a personal tool, less fine on hostile networks.

---

## Heads up

This is a personal LAN/Tailscale tool, not a hardened production app:

- **HTTP is cleartext.** Everything's server is plain HTTP by default. Use Tailscale or a VPN if you don't trust the network between phone and PC.
- **Credentials live in app storage**, not encrypted at rest. Don't put real production passwords here.
- **UNC paths** (network shares indexed by Everything) work — the app builds the right URL form (`%5C%5C<server>/...`). If a download 404s, the error toast shows the exact URL it tried — screenshot it and open an issue.

---

## Build it yourself

You'll want **Android Studio** ([download](https://developer.android.com/studio)) — it bundles the JDK and Android SDK. Then `File → Open` this folder, let Gradle sync, hit Run.

Or from the command line with JDK 17 + Android SDK on PATH:

```powershell
./gradlew assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`.

---

## Under the hood

- Kotlin 2.0 + Jetpack Compose (Material 3)
- OkHttp for the HTTP/JSON dance with Everything
- DataStore for settings
- FileProvider for opening downloaded files in other apps
- GitHub Actions builds the APK on every `v*` tag push and attaches it to a Release

---

🤖 *Built with the help of [Claude Code](https://claude.com/claude-code).*
