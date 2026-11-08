# Audiobook Player (Capacitor Android)

Offline audiobook player. The app is one web page (`www/index.html`) wrapped as a real Android app with Capacitor.
GitHub Actions builds the APK in the cloud, so nothing needs to be installed on your phone or computer.

## Repository layout
```
.github/workflows/build.yml     builds the APK (Actions tab -> Build Android APK -> Run workflow)
capacitor.config.json           app id + name  (change appId BEFORE publishing anywhere)
package.json                    Capacitor + build dependencies
src/native.js                   bridge: notification, SD-card folders, backup (bundled into www/native.js by the build)
native-android/                 Android code copied into the generated project by the build
  FolderAccessPlugin.java         pick any folder / SD card, stream files, write + read backups
  LocalServer.java                private loopback server so audio can stream from the SD card and seek
  MediaNotificationPlugin.java    bridge to the notification service
  PlaybackService.java            media notification (buttons + seek bar), lock screen controls
  MainActivity.java               back button behaviour, notification permission
  network_security_config.xml     allows the private 127.0.0.1 audio server
www/                            what the app shows
  index.html  sw.js  manifest.json
  icons/icon-192.png  icon-512.png  icon-maskable-512.png
assets/                         icon-only.png  icon-foreground.png  icon-background.png  (launcher icon source)
signing/debug.keystore          created by the first build; keep this repository PRIVATE
```

## First build
1. Back up first (old install): Settings -> Backup -> Choose backup folder -> Back up now.
2. Run the workflow, download the artifact, unzip, install `app-debug.apk`.
   The very first build with the stable signing key requires uninstalling the older app once.
3. Restore: Settings -> Backup -> Restore from backup folder.

## If the build fails
Open the failed run, open the red step, copy the last ~40 lines of its log and send them over.
Common causes: Actions workflow permissions (Settings -> Actions -> General -> Read and write),
a plugin version that does not exist (change `^8.0.0` to `latest` in package.json), or a missing file.

## If the notification buttons are missing
Settings -> About -> Test notification shows the Android version, whether notifications are allowed
and whether the playback service is running.
