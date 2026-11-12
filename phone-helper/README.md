# Watch Helper (phone app)

The Android companion for the watch firmware in this repository. It is deliberately small:

- sends the song Spotify is playing, and its play state, to the watch
- acts on the watch's music buttons: play/pause, next, previous, volume
- keeps the watch's clock right
- passes notifications from Google Messages, WhatsApp and Discord on to the watch, and takes
  them off the watch again when they go away on the phone
- tells the watch when the phone rings or is in a call, and answers or declines a ringing
  call when the watch asks for it (calls made through apps such as WhatsApp need nothing
  extra; ordinary phone calls need the Phone, Call logs and Contacts permissions)
- installs watch builds from this repository's Releases page ("Update watch")
- shows what Spotify offers to other apps and produces a report, used to wire up the
  watch's shuffle / repeat / add buttons

What a notification says, and who is calling, goes from the phone to the watch over
Bluetooth and nowhere else. The app's log and report only note that something was sent.

It only ever sends the watch *app* firmware. Files that would replace the bootloader or
radio firmware are refused.

## Building

Pushing a change under `phone-helper/` to the `watch` branch builds the app on GitHub and
publishes `watch-helper-N.apk` as release `helper-N`. To build locally:

    gradle :app:assembleRelease

with JDK 17, Gradle 8.10+ and the Android SDK (platform 35, build-tools 35.0.0).

## Layout

| File | What it does |
| --- | --- |
| `Core.java` | Ties everything together; lives as long as the app's process |
| `WatchLink.java` | Bluetooth link to the watch: music info out, button presses in, clock |
| `SpotifyWatcher.java` | Follows Spotify's player and presses its buttons |
| `Updater.java`, `UpdateService.java` | Finds, checks and installs watch builds |
| `MediaListenerService.java` | Holds notification access: Spotify's player, the notifications and the calls all come in through it |
| `NotificationRelay.java` | Decides which notifications go to the watch and when they are taken back |
| `CallWatcher.java` | Follows the phone's call, and answers or declines for the watch |
| `MainActivity.java`, `Report.java` | The one screen, and the report |
| `WatchText.java`, `WatchTime.java`, `ActionPicker.java`, `PhoneFrames.java` | Small pure helpers |
