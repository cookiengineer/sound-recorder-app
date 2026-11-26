# Sound Recorder

A small Android app for recording long room and meeting conversations with the
phone lying on a table. The recordings are stored incrementally on disk, can be
played back, shared as raw AAC, or shared as a ZIP file so that messaging apps
cannot transcode them.

Made for LineageOS or AOSP without Google Play services. There is no
INTERNET permission, no GMS, no Firebase, no analytics and no ads in this app.

## Features

- Long recordings, it's designed for multi-hour recording sessions. The pipeline
  test simulates two hours.
- Foreground service with a persistent notification, elapsed-time chronometer
  and Stop action.
- Partial wake lock, so recording survives screen-off and locked-phone use.
- Crash-safe `.aac.part` files. The ADTS AAC frames are self-contained, so an
  interrupted recording remains playable and is recovered on the next app launch.
- Microphone source selection. Auto, Unprocessed, Android's Voice Recognition
  profile, or the default microphone.
- Multiple Quality presets.
- Share the raw AAC recording as an `audio/aac` file.
- Share the recording as a compressed ZIP file so that messenger apps don't
  transcode it again (with worse quality).
- Automatic copy of finished recordings to `Music/Sound Recorder` folder via
  MediaStore, so they survive an uninstall of the App. The app-private working
  copy is kept for playback and sharing.

## Why AAC instead of MP3?

Android has no built-in MP3 encoder. Real MP3 output would require bundling a
native encoder such as LAME. This project uses Android's native AAC-LC encoder
through `MediaCodec` instead.

AAC in general has better quality at the same bitrate, and the ADTS `.aac` frames
can be appended directly to disk without an MP4 finalisation step. This allows to
have long session recordings that will work even when the App crashes or runs out
of memory during recording.

Filenames therefore end in `.aac`, for example:

```
2026-11-25 00:01:02.aac
```

## Requirements

- Android 10 / API 29 or newer (LineageOS 17.1+).
- A device with a working microphone and an AAC encoder. AOSP/LineageOS
  includes an AAC encoder. The app validates the selected configuration at startup
  and shows an error message if the encoder rejects it.
- `RECORD_AUDIO` permission.
- `POST_NOTIFICATIONS` permission on Android 13+ for the ongoing recording
  notification. Recording still works if it is denied, but Android hides the
  notification, and it might be battery optimized when the Screen is turning
  itself off after a timeout or is locked.

## Build

The build is fully containerised with Podman. No host Android SDK or JDK is
required.

```sh
make image      # build the builder image (first time only)
make            # debug + release APKs
make test       # JVM unit tests
make lint       # Android lint
make verify     # manifest/permission/no-GMS checks
make install    # adb install the release APK
```

Build outputs:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

The release build falls back to the debug key when no `keystore.properties`
exists, so the APK remains installable. For a real release, create
`keystore.properties`:

```properties
storeFile=keystore/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## Usage

1. Launch the app and grant microphone access.
2. On Android 13+, grant notification access so the ongoing recording
   notification is visible.
3. Place the phone on the table and tap the red microphone button.
4. Tap the stop button (or Stop in the notification) when the conversation ends.
5. Use the recording card to:
   - Play the recording.
   - Share the raw `.aac`.
   - Share a `.zip`.
   - Save a copy to `Music/Sound Recorder`.
   - Delete the recording.

Recording continues when the screen turns off, the app goes to the background,
or the task is swiped away. If the process is killed, the `.aac.part` file is
recovered automatically the next time the app starts.

### LineageOS Battery settings

For reliable multi-hour recordings, set the app to Unrestricted or Unoptimized
in the LineageOS battery settings. The app shows a shortcut to the battery
optimisation list until it is exempted.

Some privacy-guard configurations can also block microphone access even after
the runtime permission is granted. Check the Privacy Guard settings if recording
produces silence.

## Storage

Default working directory:

```
Android/data/engineer.cookie.soundrecorder/files/Music/Recordings
```

This is app-specific external storage, so no storage permission is required.
If external storage is unavailable, the app falls back to internal storage.

Each recording consists of:

- `<timestamp>.aac` contains the finished ADTS AAC stream
- `<timestamp>.aac.part` contains the in-progress file
- `<timestamp>.aac.json` contains metadata (settings, duration, export state)

Filenames use the local start time in the requested format. Spaces and colons
are legal on Android's ext4/f2fs storage, but not on FAT/exFAT. Copying a file
to such a filesystem may require renaming it. The public MediaStore copy uses a
sanitised name when necessary:

```
2026-11-25_00-01-02.aac
```

Uninstalling the app deletes the app-private working copies. With auto-save
enabled (the default), a copy remains in `Music/Sound Recorder`.

## License

This project is released under the MIT license. See [X11/MIT LICENSE](LICENSE_MIT.txt) for details.

The AndroidX/Compose dependencies keep their own Apache-2.0 licences.
