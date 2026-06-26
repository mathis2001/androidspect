# AndroidSpect

Swissknife Applicative Android Pentesting app that act like a C2 and embed a web server accessible through a browser on the same network. The app allows multiple generic actions like global proxy, frida-server management, processes monitoring and per-package static and dynamic analysis of installed apps.

## What it does

Per-package static inspection and dynamic tests:

* List installed apps with danger tags for debug, backup, network potential misconfigs.
* File browser for `/data/data/<pkg>`, with type-aware previews for text, JSON, XML, images, and a hex view for everything else.
* SQLite reader with tables, schema, paginated rows, ad-hoc SELECT, CSV export.
* SharedPreferences viewer and inline editor.
* Decoded `AndroidManifest.xml`
* Components list (activities, services, receivers, providers) with the exported badge in red, name filter, exported-only toggle, sort exported-first.
* Pre-builded (and callable) shell commands based on the Manifest and the desassembled code (+ extras names if static).
* Native libraries list per ABI with size and stripped-symbols flag, one-click `.so` download.
* Disassemble and store the smali code with `baksmali`.
* Import Decompiled code from `JadX` for in-app static analysis.
* Verify deeplinks assetlinks.
* Extract and open deeplinks (+ potential parameters if static).
* Extract Web related information (WebViews security assessment, URL, potential endpoints and potential parameters).
* PoC Overlay Attacks.
* A markdown editor to take notes directly through the web interface.
* A Snapshots feature allowing to register an app content at T0 and compare it with an other snapshot at T+N (for future versions updates or local storage monitoring).

Live runtime:

* Logcat tail over WebSocket with severity colouring, regex highlight, save-to-file.
* Process snapshot from `/proc` with PID, PPID, UID, RSS, state, full cmdline.
* TCP and UDP socket table from `/proc/net` decoded to `ip:port` with the owning package.

Actions:

* Start, force-stop, clear data.
* Pull every APK file that makes up a package (base plus splits) as a single ZIP.
* Root shell in the browser for one-shot `su` commands.
* Device file explorer/manager.
* Screen Capture (Screenshots and Screen recordings)
* Clipboard dumping and monitoring (Note: Since Android 10 (SDK 29), apps in background can't access the clipboard data and needs to go foreground.)
* Pentest environment management (global proxy settings and frida-server download/install/start/stop).


## Compatibility

| | |
| - | - |
| Minimum Android | 5.0 Lollipop (API 21) |
| Target Android | 16 (API 36) |
| Root | Magisk, KernelSU, APatch, or any `su` provider |
| Hooking framework | none. No Xposed, no LSPosed, no Zygisk. Pure root. |
| Build JDK | 17 |
| Gradle | 8.11.1 |
| AGP | 8.9.1 |

Core-library desugaring brings `java.time`, `java.util.function`, and `java.util.stream` to API 21 through 25, so Ktor 3 plus Netty work down to Lollipop.
Some feature might need to install utilities through Termux. (wget, wz-utils for example)

## Quick install

1. Download pre-build APK from the latest GitHub release.
2. Copy it to the phone and tap to install (enable "Install unknown apps" for the file manager you use).
3. Open AndroidSpect, tap **Start server**. Your root manager (Magisk / KernelSU) prompts for `su`. Grant it.
4. The phone screen now shows an `https://<phone-ip>:8008` URL, a SHA-256 fingerprint, and a six-character browser password.
5. Open that URL in any browser on the same network.

If the phone and your computer are on the same Wi-Fi you can use the LAN IP directly.

The shipped APK is a debug build, signed with Android's standard debug key. That keeps install simple (no custom-keystore trust prompt) and the package id stays `com.androidspect` so it upgrades cleanly between versions.

## Compile from source

You need JDK 17 and the Android SDK with platforms 21 to 36 installed.

```
git clone <repo>
cd AndroidSpect
gradlew.bat :app:assembleDebug      # Windows
./gradlew :app:assembleDebug        # macOS / Linux
```

Output APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

Or open the project root in Android Studio (Giraffe or newer) and use **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

The project ships a single `debug` build variant signed with Android's standard debug key. If you want a minified, custom-signed release later, add a `release { ... }` block to `app/build.gradle.kts` with your own `signingConfig`.

## Using an emulator (AVD)

The browser dashboard binds `0.0.0.0:8008` on the phone. On a physical device on the same Wi-Fi that works directly. On an AVD you forward the port over ADB:

```
adb forward tcp:8008 tcp:8008
```

Then open `https://localhost:8008/` in your browser. AVDs need root, so use a rooted system image (Magisk-on-AVD via rootAVD works on system image 34).

## Certificate warning on first browser visit

AndroidSpect serves its dashboard with a self-signed certificate generated on first run. The browser does not trust it. This is expected.

You will see an "insecure" or "not private" warning. The exact bypass depends on the browser:

**Chrome / Edge / Brave**
Click **Advanced**, then **Proceed to <ip> (unsafe)**. If the link does not appear, type `thisisunsafe` anywhere on the warning page (just type it, no input box needed) and the browser proceeds.

**Firefox**
Click **Advanced**, then **Accept the Risk and Continue**.

**Safari**
Click **Show Details**, then **visit this website**, confirm.

Before clicking through, compare the certificate fingerprint shown in the warning ("Subject" or "Details") against the SHA-256 fingerprint the AndroidSpect app displays on the phone screen. They must match. If they don't, something is on the network and you should not continue.

After the bypass, the browser asks for the six-character password shown in the app. Enter it. You're in.

## Screenshots

On-device dashboard. Start, stop, port, password, certificate fingerprint, status pill.

![Mobile dashboard](screenshots/01-mobile-dashboard.png)

Browser login.

![Browser login](screenshots/02-login.png)

Target picker and Files tab on the selected app.

![Files browser](screenshots/03-files.PNG)

Decoded manifest with summary chips on top.

![Manifest decoder](screenshots/04-manifest.PNG)

Components inspector with exported badge, filter and exported-only toggle.

![Components inspector](screenshots/05-components.PNG)

Dynamic ADB commands forging

![ADB commands1](screenshots/10-adb-commands.PNG)
![ADB commands2](screenshots/11-adb-commands.PNG)

Native libraries per ABI with size and stripped flag, one-click `.so` download.

![Native libraries](screenshots/06-native.png)

Deeplinks validation and testing

![Deeplinks](screenshots/15-Deeplinks.PNG)

Web Components static Analysis

![Web](screenshots/18-Web-components-static-analysis.PNG)

Overlay Attacks Capabilities

![Overlay](screenshots/19-TapJacking-PoC-Capabilities.PNG)

Snapshots & Diffs

![Snapshots](screenshots/14-Snapshots.PNG)

Markdown notes feature

![Notes1](screenshots/12-notes.PNG)
![Notes2](screenshots/13-notes.PNG)

Process list from `/proc`.

![Processes](screenshots/07-processes.png)

Logcat live tail.

![Logcat](screenshots/08-logcat.png)

File Explorer

![FileExplorer](screenshots/16-FileExplorer.PNG)

Screenshots & Screen Recordings

![ScreenCap](screenshots/20-Screencap.PNG)

Clipboard dumping and monitoring

![Clipboard](screenshots/21-Clipboard.PNG)

Pentesting environment management

![EnvSetup](screenshots/17-Env-Setup.PNG)

## Security model

* Password is set on first run and shown only in the on-device app. Browser sign-in uses a session cookie (HttpOnly, Secure, SameSite=Strict).
* Failed sign-ins are rate-limited per IP with exponential backoff and lockout.
* HTTPS only. The server generates a self-signed certificate the first time it starts. Its SHA-256 fingerprint is shown on the phone so you can verify the one your browser sees.
* `Host` header is restricted to `localhost`, `127.0.0.1`, and the current LAN IPv4 of the device. DNS-rebinding requests are rejected.
* All write operations (clear data, force-stop, prefs write, exec) are POST.
* The on-phone `su` grant is required. Without it the server starts but every privileged primitive returns an error.
* No analytics, no crash reporters, no auto-update, no outbound network calls of any kind.

## Stack

* Kotlin 2.1, Jetpack Compose, Material 3 for the on-device UI
* Ktor 3 (Netty, WebSockets, ContentNegotiation, kotlinx.serialization) for the embedded server
* libsu 6 for every privileged operation, single persistent shell, no per-call elevation
* BouncyCastle (`bcpkix-jdk18on`) for self-signed certificate generation
* AndroidX MultiDex plus `desugar_jdk_libs` 2.1.4 for API 21-25 compatibility

## Notes on design

* One `su` shell, always. `RootBridge` owns it. Every privileged primitive (file read, `/proc` parse, `am`, `pm`, `openssl`, `dd`) goes through it. Restarting the app reuses the shell as long as Magisk has not revoked the grant.
* The framework `SQLiteDatabase` cannot open a libsu-backed stream. The reader copies the target DB into the app cache (root readable, then world readable for our own UID) and opens it `OPEN_READONLY`. The original is never touched.
* The Compose UI is a launcher. The real product is the browser dashboard. The foreground service keeps the server alive across task removal.
* The Components tab links out to apkauditor.com instead of duplicating the heavy ADB exploit-helper work. That stays a browser-first tool.

## License

All rights reserved.

All credits goes to Sandeep Wawdane.
