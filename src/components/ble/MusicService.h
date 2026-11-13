/*  Copyright (C) 2020-2021 JF, Adam Pigg, Avamander

    This file is part of InfiniTime.

    InfiniTime is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    InfiniTime is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
#pragma once

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#define min // workaround: nimble's min/max macros conflict with libstdc++
#define max
#include <host/ble_gap.h>
#include <host/ble_uuid.h>
#undef max
#undef min
#include <FreeRTOS.h>
#include "components/battery/LowBatteryWatch.h"
#include "components/ble/NotificationManager.h"
#include "components/ble/PhoneLists.h"

namespace Pinetime {
  namespace System {
    class SystemTask;
  }

  namespace Controllers {
    class NimbleController;

    class MusicService {
    public:
      MusicService(NimbleController& nimble, System::SystemTask& systemTask, NotificationManager& notificationManager);

      void Init();

      int OnCommand(struct ble_gatt_access_ctxt* ctxt);

      void event(char event);

      // Asks the phone for a page of one of its lists. The answer arrives in Lists().
      // "parent" is the playlist a list of songs belongs to; it is 0 for the other lists.
      void RequestList(PhoneLists::Kinds kind, uint8_t token, uint16_t offset, uint16_t parent);
      // Asks the phone to do something with one row of a list. What the actions mean depends on the list:
      // a song is added to the queue (ActionTap) or played in its playlist (ActionHold), a playlist is
      // played (ActionHold), a person is called (ActionTap).
      void ActOnRow(PhoneLists::Kinds kind, uint8_t action, uint16_t index, uint16_t parent, uint8_t snapshot);
      // Asks the phone to clear one of its notifications. The phone then takes it back from the watch too.
      void DismissNotification(uint16_t phoneId);

      // Asks the phone for the weather where it is. The answer arrives in ReadWeather().
      void RequestWeather();

      // Asks the phone to change its sound mode (one of the Sound values below). The phone reports
      // the mode it really ends up in; see getPhoneSoundMode().
      void SetPhoneSoundMode(uint8_t mode);

      // Makes the phone ring at full volume so it can be found, or stops that
      void FindPhone(bool ring);

      static constexpr uint8_t ActionTap = 1;
      static constexpr uint8_t ActionHold = 2;

      PhoneLists& Lists() {
        return lists;
      }

      // The phone is gone, and with it everything the watch knew about its call
      void OnDisconnect();

      std::string getArtist() const;

      std::string getTrack() const;

      std::string getAlbum() const;

      int getProgress() const;

      int getTrackLength() const;

      float getPlaybackSpeed() const;

      bool isPlaying() const;

      static const char EVENT_MUSIC_OPEN = 0xe0;
      static const char EVENT_MUSIC_PLAY = 0x00;
      static const char EVENT_MUSIC_PAUSE = 0x01;
      static const char EVENT_MUSIC_NEXT = 0x03;
      static const char EVENT_MUSIC_PREV = 0x04;
      static const char EVENT_MUSIC_VOLUP = 0x05;
      static const char EVENT_MUSIC_VOLDOWN = 0x06;
      // The second row of buttons on the watch face. Stock companion apps ignore these codes;
      // the Watch Helper phone app acts on them.
      static const char EVENT_MUSIC_SHUFFLE = 0x10;
      static const char EVENT_MUSIC_REPEAT = 0x11;
      static const char EVENT_MUSIC_SAVE = 0x12;
      // Sent from the call screen. The Watch Helper phone app answers or declines the ringing call,
      // and turns the volume of the call (not of the music) up or down.
      static const char EVENT_CALL_ANSWER = 0x20;
      static const char EVENT_CALL_DECLINE = 0x21;
      static const char EVENT_CALL_VOLUP = 0x22;
      static const char EVENT_CALL_VOLDOWN = 0x23;
      static const char EVENT_CALL_HANGUP = 0x24;
      // Longer messages for the Watch Helper phone app. The first byte says what follows:
      //   0x30 a page of a list, please: list, request number, offset (2 bytes, low byte first), parent (2 bytes)
      //   0x31 do something with a row: list, action, row number in the list (2 bytes), parent (2 bytes),
      //        the snapshot number that came with the page
      //   0x32 clear a notification on the phone: its number (2 bytes)
      static constexpr uint8_t EVENT_LIST_PAGE = 0x30;
      static constexpr uint8_t EVENT_LIST_ACTION = 0x31;
      static constexpr uint8_t EVENT_NOTIFICATION_DISMISS = 0x32;
      //   0x33 the weather, please (one more byte, not used yet)
      //   0x34 put the phone in a sound mode: 0 mute, 1 vibrate, 2 sound
      //   0x35 ring so the phone can be found (1), or stop ringing (0)
      static constexpr uint8_t EVENT_WEATHER = 0x33;
      static constexpr uint8_t EVENT_SOUND_MODE = 0x34;
      static constexpr uint8_t EVENT_FIND_PHONE = 0x35;

      // What the player's shuffle, repeat and "save this song" buttons currently show, as told
      // by the phone in one three-byte write. Unknown until the phone has sent it.
      static constexpr uint8_t StateUnknown = 0xff;
      static constexpr uint8_t ShuffleOff = 0;
      static constexpr uint8_t ShuffleOn = 1;
      static constexpr uint8_t ShuffleSmart = 2;
      static constexpr uint8_t RepeatOff = 0;
      static constexpr uint8_t RepeatAll = 1;
      static constexpr uint8_t RepeatOne = 2;
      static constexpr uint8_t SavedNo = 0;
      static constexpr uint8_t SavedYes = 1;

      uint8_t getShuffleState() const {
        return shuffleState;
      }

      uint8_t getRepeatState() const {
        return repeatState;
      }

      uint8_t getSavedState() const {
        return savedState;
      }

      // The phone's media volume in the phone's own steps, how many steps there are, and the
      // phone's battery in percent. StateUnknown until the phone app has sent them.
      uint8_t getPhoneVolume() const {
        return phoneVolume;
      }

      uint8_t getPhoneVolumeMax() const {
        return phoneVolumeMax;
      }

      uint8_t getPhoneBattery() const {
        return phoneBattery;
      }

      // The phone's sound mode as the phone itself reports it (the eighth byte of the buttons
      // characteristic): sound, vibrate or mute. StateUnknown until the phone app has sent it.
      static constexpr uint8_t SoundMute = 0;
      static constexpr uint8_t SoundVibrate = 1;
      static constexpr uint8_t SoundOn = 2;

      uint8_t getPhoneSoundMode() const {
        return phoneSoundMode;
      }

      // True while the phone is ringing to be found (bit 0 of the ninth byte)
      bool isPhoneRinging() const {
        return (phoneFlags & FindRinging) != 0;
      }

      // True while the phone is on a charger (bit 1 of the ninth byte, since phone app build 16)
      bool isPhoneCharging() const {
        return (phoneFlags & PhoneCharging) != 0;
      }

      // The weather where the phone is, as the phone last sent it
      static constexpr uint8_t WeatherNone = 0;
      static constexpr uint8_t WeatherFresh = 1;
      // The phone could not get newer numbers than these
      static constexpr uint8_t WeatherOld = 2;
      // No numbers at all, and why: the phone app may not see where the phone is, the phone does
      // not know where it is, or the weather service did not answer
      static constexpr uint8_t WeatherNoPermission = 0x10;
      static constexpr uint8_t WeatherNoPlace = 0x11;
      static constexpr uint8_t WeatherNoAnswer = 0x12;
      static constexpr uint8_t WeatherHours = 10;
      static constexpr uint8_t WeatherUnknown = 0xff;
      // The weather in a word or two ("Partly cloudy"), and what kind of weather that is.
      // The screen shows the words in the colour of the kind.
      static constexpr uint8_t WeatherConditionSize = 18;
      static constexpr uint8_t WeatherKindNone = 0;
      static constexpr uint8_t WeatherKindSun = 1;
      // Weather with no colour of its own: a clear night
      static constexpr uint8_t WeatherKindPlain = 2;
      static constexpr uint8_t WeatherKindCloud = 3;
      static constexpr uint8_t WeatherKindRain = 4;
      static constexpr uint8_t WeatherKindSnow = 5;
      // Freezing rain or drizzle
      static constexpr uint8_t WeatherKindIce = 6;
      static constexpr uint8_t WeatherKindStorm = 7;

      struct Weather {
        uint8_t status = WeatherNone;
        bool fahrenheit = false;
        bool milesPerHour = false;
        int16_t temperature = 0;
        int16_t feelsLike = 0;
        uint8_t wind = 0;
        // In percent; WeatherUnknown if the phone did not say
        uint8_t humidity = WeatherUnknown;
        uint8_t rainToday = WeatherUnknown;
        // What falls today, if anything does, is snow rather than rain
        bool snowToday = false;
        // When these numbers are from, on the phone's clock
        uint8_t hour = 0;
        uint8_t minute = 0;
        // The chance of rain hour by hour, starting with the hour "firstHour" (0-23) that is under way
        uint8_t firstHour = 0;
        uint8_t hours = 0;
        std::array<uint8_t, WeatherHours> rain {};
        // One bit per hour, lowest first: what would fall in that hour is snow
        uint16_t snowHours = 0;
        // Empty if the phone did not say (a phone app older than build 15)
        std::array<char, WeatherConditionSize + 1> condition {};
        uint8_t conditionKind = WeatherKindNone;
        // When the watch received them
        TickType_t arrived = 0;

        bool hasNumbers() const {
          return status == WeatherFresh || status == WeatherOld;
        }
      };

      // Goes up whenever the phone has said something about the weather
      uint32_t getWeatherRevision() const {
        return weatherRevision;
      }

      // Copies the newest weather. False if it was being replaced at that moment: look again on the next refresh.
      bool ReadWeather(Weather& out) const {
        for (int attempt = 0; attempt < 3; attempt++) {
          const uint32_t before = weatherSequence;
          if ((before & 1u) != 0) {
            continue;
          }
          out = weather;
          if (weatherSequence == before) {
            return true;
          }
        }
        return false;
      }

      // True if the song that is playing was picked by Spotify (Smart Shuffle) and is not part of
      // the playlist. The phone works that out; see the seventh byte of the buttons characteristic.
      bool isSmartPick() const {
        return (trackFlags & TrackSmart) != 0;
      }

      // True while an app the wearer wants to know about has a notification waiting on the phone.
      // The phone app decides which app that is (Claude) and only passes on the fact, never what
      // the notification says. It is bit 1 of the same byte.
      bool hasWaitingMark() const {
        return (trackFlags & WaitingMark) != 0;
      }

      // The picture for that mark, if the phone has sent one: the small icon the app puts on its
      // own notifications, as the phone read it there. It is never stored in the watch's firmware.
      // The data is laid out the way LVGL wants an "indexed, 4 bit" image: sixteen colours (the
      // icon's colour at sixteen strengths), then one row after the other, two pixels per byte.
      static constexpr uint8_t MarkIconSize = 20;
      static constexpr size_t MarkIconPaletteBytes = 16 * 4;
      static constexpr size_t MarkIconPixelBytes = MarkIconSize * (MarkIconSize / 2);
      static constexpr size_t MarkIconBytes = MarkIconPaletteBytes + MarkIconPixelBytes;

      bool hasMarkIcon() const {
        return markIconRevision != 0;
      }

      const uint8_t* markIconData() const {
        return markIcon.data();
      }

      // Goes up whenever a picture has arrived, so the screen knows to draw it again
      uint32_t getMarkIconRevision() const {
        return markIconRevision;
      }

      enum MusicStatus { NotPlaying = 0x00, Playing = 0x01 };

    private:
      static constexpr uint8_t TrackSmart = 0x01;
      static constexpr uint8_t WaitingMark = 0x02;
      static constexpr uint8_t FindRinging = 0x01;
      static constexpr uint8_t PhoneCharging = 0x02;

      int OnPhoneMessage(struct os_mbuf* om);
      void Send(const uint8_t* data, uint16_t length);

      struct ble_gatt_chr_def characteristicDefinition[16];
      struct ble_gatt_svc_def serviceDefinition[2];

      uint16_t eventHandle {};

      std::string trackName;
      std::string albumName;
      std::string artistName {"Not Playing"};

      bool playing {false};

      int trackProgress {0};
      int trackLength {0};
      int trackNumber {};
      int tracksTotal {};
      TickType_t trackProgressUpdateTime {0};

      float playbackSpeed {1.0f};

      bool repeat {false};
      bool shuffle {false};

      uint8_t shuffleState {StateUnknown};
      uint8_t repeatState {StateUnknown};
      uint8_t savedState {StateUnknown};
      uint8_t phoneVolume {StateUnknown};
      uint8_t phoneVolumeMax {StateUnknown};
      uint8_t phoneBattery {StateUnknown};
      uint8_t trackFlags {0};
      uint8_t phoneSoundMode {StateUnknown};
      uint8_t phoneFlags {0};
      // Tells when the phone's battery has run low
      LowBatteryWatch phoneBatteryWatch;

      // The weather as it is shown, and the one that is arriving: the hours come first, then the rest
      Weather weather {};
      Weather incomingWeather {};
      std::atomic<uint32_t> weatherSequence {0};
      std::atomic<uint32_t> weatherRevision {0};

      NimbleController& nimble;
      System::SystemTask& systemTask;
      NotificationManager& notificationManager;

      // A notification from the phone arrives in pieces. This is the one being put together.
      NotificationManager::Notification pending {};
      size_t pendingLength {0};
      bool pendingActive {false};
      bool pendingAlert {false};

      PhoneLists lists;

      // The mark's picture as it is shown, and the one that is arriving in pieces
      std::array<uint8_t, MarkIconBytes> markIcon {};
      std::atomic<uint32_t> markIconRevision {0};
      std::array<uint8_t, MarkIconPixelBytes> incomingIcon {};
      std::array<uint8_t, 3> incomingIconColor {};
      size_t incomingIconFilled {0};
      bool incomingIconActive {false};
    };
  }
}
