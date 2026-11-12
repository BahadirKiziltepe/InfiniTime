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
- keeps a short list of people, chosen in the app, that the watch can call, and suggests
  people from the phone's contacts who called and are not on it yet
- shows the phone's call history on the watch (made, received, missed), from where a person
  can be called back
- follows the phone's sound mode (sound, vibrate, mute) for the sound button on the watch,
  and changes it when that button is tapped
- rings at full volume when the watch's "find my phone" button is tapped, also on mute,
  with a sound picked in the app or else the phone's alarm sound
- fetches the weather for where the phone is, for the watch's weather screen; see below
- installs watch builds from this repository's Releases page ("Update watch")
- shows what Spotify offers to other apps and produces a report, used to wire up the
  watch's shuffle / repeat / add buttons

What a notification says, and who is calling, goes from the phone to the watch over
Bluetooth and nowhere else. The app's log and report only note that something was sent.
Of the people chosen for the watch only the names are sent to it; the numbers stay on the phone.
The same goes for the call history: the watch gets a name (or the number, if the phone has no
name for it), the kind of call and the time.

## Weather

The watch's weather screen shows the weather in a word or two ("Partly cloudy"), the
temperature, what it feels like, wind, humidity, the chance of rain for the day and for each
of the next ten hours. The numbers come from [Open-Meteo](https://open-meteo.com) (weather
data by Open-Meteo.com, CC BY 4.0), which needs no account.

The service's chance of rain is the chance of anything falling. Whether that would be rain
or snow is worked out in `WeatherReport.java`, from how much of each the forecast expects
and, where it expects neither, from the temperature. The watch shows a chance of rain in
blue and a chance of snow in light blue, and the words at the top in the colour of their
kind (yellow sun, grey clouds, blue rain, light blue snow, violet freezing rain, orange
thunderstorm).

The weather is for where the phone is. The app only asks Android for the rough position,
rounds it to two decimals (about a kilometre) and sends that to the weather service, over
an encrypted connection, and nowhere else. The rounded position is kept in the app's
private storage and is never written to the log or the report. Android only says where
the phone is while the app's screen is closed if "Allow all the time" was picked; without
it the watch gets the weather for where the phone was when the screen was last open.

## The foreground service

Since Android 17 an app may only change a volume or the phone's sound mode, or make a sound,
while its screen is open or while it runs a foreground service; in the background such
requests are dropped without an error. The watch asks for exactly these things while the
phone is in a pocket (volume, sound mode, find my phone), so the app keeps a foreground
service of the "connected device" kind running. It does nothing else. The app does not ask
for permission to show notifications, so the service's notification does not appear.

## Sound mode and Do Not Disturb

Asking Android for a ringer mode is one call, but phones differ in what they make of it.
Plain Android switches Do Not Disturb on when an app asks for silence. `SoundSwitch.java`
therefore asks, reads back what the phone really did, tries the other ways there are, and
puts Do Not Disturb back the way it was whenever the phone holds the mode without it. The
watch is always told the mode the phone is really in.

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
| `ContactStore.java`, `SuggestionStore.java` | The people chosen for the watch, and callers suggested for it |
| `CallHistory.java`, `CallRows.java` | The phone's call history for the watch, and the words for a call |
| `SoundMode.java`, `SoundSwitch.java` | Follows and changes the phone's sound mode |
| `PhoneFinder.java` | Rings so the phone can be found |
| `Weather.java`, `WeatherReport.java` | Where the phone is, the weather there, and the answer to the watch |
| `LinkService.java` | The foreground service Android 17 asks for |
| `MainActivity.java`, `Ui.java`, `Report.java` | The one screen, how it looks, and the report |
| `WatchText.java`, `WatchTime.java`, `ActionPicker.java`, `PhoneFrames.java` | Small pure helpers |
