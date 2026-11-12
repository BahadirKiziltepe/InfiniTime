# Watch Helper (phone app)

The Android companion for the watch firmware in this repository. It is deliberately small:

- sends the song Spotify is playing, and its play state, to the watch
- acts on the watch's music buttons: play/pause, next, previous, volume
- keeps the watch's clock right
- passes notifications from Google Messages, WhatsApp and Discord on to the watch, and takes
  them off the watch again when they go away on the phone; holding a notification on the
  watch clears it on the phone
- tells the watch when the phone rings or is in a call, and answers or declines a ringing
  call when the watch asks for it (calls made through apps such as WhatsApp need nothing
  extra; ordinary phone calls need the Phone, Call logs and Contacts permissions)
- shows the play queue, the playlists and their songs on the watch, plays a song in its
  playlist or adds it to the queue from there, and marks the songs Spotify picked itself
  (Smart Shuffle). This part needs Spotify's web service; see below
- keeps a short list of people, chosen in the app, that the watch can call
- installs watch builds from this repository's Releases page ("Update watch")
- shows what Spotify offers to other apps and produces a report, used to wire up the
  watch's shuffle / repeat / add buttons

What a notification says, and who is calling, goes from the phone to the watch over
Bluetooth and nowhere else. The app's log and report only note that something was sent.
Of the people chosen for the watch only the names are sent to it; the numbers stay on the phone.

## Playlists and queue: Spotify's web service

Spotify's app on the phone only tells other apps what is playing. Playlists and the queue
come from Spotify's web service, which wants every app registered. This app is used by one
person, so that person registers it once (the app's screen walks through it):

1. On <https://developer.spotify.com/dashboard>, with a Spotify Premium account, create an
   app with the redirect URI `http://127.0.0.1:21987/callback` and the Web API ticked.
2. Paste the app's Client ID into Watch Helper and log in.

The login happens in the browser. Spotify then sends the browser to that address on the
phone itself, where Watch Helper listens for a few minutes to pick up the answer. The
tokens stay in the app's private storage and are never written to the log or the report.

Spotify's rules for apps like this one (February 2026): the songs of a playlist are only
listed for playlists the account made or works on with others. A playlist that is merely
followed can be played from the watch, not opened.

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
| `SpotifyAccount.java`, `Http.java` | The login to Spotify's web service, and one web request |
| `MusicLibrary.java` | Playlists, songs and queue from Spotify's web service; play and add to queue |
| `WatchLists.java` | Answers the watch's requests for pages of its lists |
| `ContactStore.java` | The people chosen for the watch |
| `MainActivity.java`, `Ui.java`, `Report.java` | The one screen, how it looks, and the report |
| `WatchText.java`, `WatchTime.java`, `ActionPicker.java`, `PhoneFrames.java` | Small pure helpers |
