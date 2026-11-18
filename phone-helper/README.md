# Watch Helper (phone app)

The Android companion for the watch firmware in this repository. It is deliberately small:

- sends the song Spotify is playing, and its play state, to the watch
- acts on the watch's music buttons: play/pause, next, previous, volume
- keeps the watch's clock right
- passes notifications from Google Messages, WhatsApp and Discord on to the watch, and takes
  them off the watch again when they go away on the phone; a swipe to the right on a
  notification on the watch clears it on the phone
- starts Spotify on the phone when play is pressed on the watch and Spotify is not running;
  see below
- tells the watch when the phone rings or is in a call, and answers or declines a ringing
  call when the watch asks for it (calls made through apps such as WhatsApp need nothing
  extra; ordinary phone calls need the Phone, Call logs and Contacts permissions). During a
  call the watch also gets how long it has been going on and the call's volume
- passes on the battery of the earbuds or headphones that are connected to the phone, for
  as long as they share it; see below
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
- can silence the watch for the hours it is not worn, from the app's screen or from a
  "Watch silent" button in the phone's quick panel; see below
- installs watch builds from this repository's Releases page ("Update watch")
- shows what Spotify offers to other apps and produces a report, used to wire up the
  watch's shuffle / repeat / add buttons

What a notification says, and who is calling, goes from the phone to the watch over
Bluetooth and nowhere else. The app's log and report only note that something was sent.
Of the people chosen for the watch only the names are sent to it; the numbers stay on the phone.
The same goes for the call history: the watch gets a name (or the number, if the phone has no
name for it), the kind of call and the time.

## Starting Spotify from the watch

A Spotify that is not running cannot be told anything, neither through Android nor through
Spotify's web service. So when the watch wants music and Spotify is not there,
`SpotifyStarter` brings it up first, one way after the other:

1. A play button like a headset's. Android hands it to the app that played last and starts
   that app if need be. It is only pressed if that app is Spotify; otherwise it would wake
   a video somewhere else.
2. If Spotify has not turned up after three seconds: opening Spotify, the way a tap on its
   icon does. Android lets an app in the background do that only with the permission it
   calls "Appear on top" (`SYSTEM_ALERT_WINDOW`), which the app asks for under "Finish
   setting up". The app never draws anything on top of anything.
3. Once Spotify is there: play, on its own player or through the web service, a few times
   over if it does not listen at once.

The watch says "Starting Spotify..." meanwhile. After fifteen seconds without success it
says so, and the report names the step that failed.

## Earbuds

Earbuds tell the phone how full they are while they are connected to it, and again each time
that changes, most of them in steps of ten. Android keeps that one number for the pair and
announces every change; the app passes it on (and asks Android once a minute, in case an
announcement was missed), so the number on the watch follows the earbuds as they run down.
The watch buzzes when it gets to 20 % and to 10 %, the same way as for the phone's and the
watch's battery. Earbuds that are put away say nothing, and nothing can ask them.

The case is not in that number. Earbuds that pair the Fast Pair way broadcast it over
Bluetooth, together with each earbud by itself, for a few seconds when their case is opened
or closed with both of them inside ([Google's description][fp-battery]). `BudsBroadcast`
listens for that all the time, at a pace that is easy on the battery. The broadcast does not
say whose earbuds they are in a way an outsider can read, so `BudsPicker` goes by what can
be known: it comes from the address of a headset connected to this phone, or it is the
strongest one around within twenty seconds of the user's own earbuds connecting or
disconnecting and does not contradict the phone's number for the pair. That second way is
a judgement; if the phone's number then disagrees, the reading is given up again.

The watch shows `earbuds|case` right under the phone's and its own battery, yellow from
20 % down and red from 10 %:

- the earbuds' number is there while they are connected, and follows them
- the case's level is kept for good and shown all the time: it is the level from when the
  case was last opened or closed, which is also when it last changed
- while the earbuds are put away, the case stands alone

Each earbud by itself is only known for the moment the case was opened, not while the
earbuds are worn, so it is not shown: it serves to check a reading against the phone's
number. Neither the name nor the address of a headset is written to the log or the report.

[fp-battery]: https://developers.google.com/nearby/fast-pair/specifications/extensions/batterynotification

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

## Silencing the watch from the phone

A watch that lies on a table at night still buzzes for every notification. "Watch silent"
stops that: the watch neither buzzes nor lights up for notifications, calls, the Claude
mark or a low battery, and what comes in still collects on its notification screen. The
watch shows a crossed-out speaker next to its battery numbers while it is silent, and it
stays silent until it is told otherwise, also across a lost connection. This is separate
from the watch's own setting (list of settings, Notifications).

The watch is also silent by itself for as long as it is on its charger, with the same
sign on its screen. That needs nothing from the phone.

The switch is on the app's screen and, as a button, in the phone's quick panel
(`SilenceTile.java`): the app can ask Android to add it there, or it can be added with
the panel's pencil. The button is lit while the watch is silent.

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
| `SpotifyStarter.java` | Starts Spotify on the phone when the watch wants music and it is not running |
| `Earbuds.java` | The battery of the earbuds that are connected to the phone, and of their case |
| `BudsBroadcast.java`, `BudsReading.java`, `BudsPicker.java` | Hearing, reading and picking the earbuds' own battery broadcast |
| `SpotifyAccount.java`, `Http.java` | The login to Spotify's web service, and one web request |
| `MusicLibrary.java` | Playlists, songs and queue from Spotify's web service; play and add to queue |
| `WatchLists.java` | Answers the watch's requests for pages of its lists |
| `ContactStore.java`, `SuggestionStore.java` | The people chosen for the watch, and callers suggested for it |
| `CallHistory.java`, `CallRows.java` | The phone's call history for the watch, and the words for a call |
| `SoundMode.java`, `SoundSwitch.java` | Follows and changes the phone's sound mode |
| `PhoneFinder.java` | Rings so the phone can be found |
| `Weather.java`, `WeatherReport.java` | Where the phone is, the weather there, and the answer to the watch |
| `LinkService.java` | The foreground service Android 17 asks for |
| `SilenceTile.java` | The quick panel button that silences the watch |
| `MainActivity.java`, `Ui.java`, `Report.java` | The one screen, how it looks, and the report |
| `WatchText.java`, `WatchTime.java`, `ActionPicker.java`, `PhoneFrames.java` | Small pure helpers |
