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

#include <cstdint>
#include <string>
#define min // workaround: nimble's min/max macros conflict with libstdc++
#define max
#include <host/ble_gap.h>
#include <host/ble_uuid.h>
#undef max
#undef min
#include <FreeRTOS.h>
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

      // True if the song that is playing was picked by Spotify (Smart Shuffle) and is not part of
      // the playlist. The phone works that out; see the seventh byte of the buttons characteristic.
      bool isSmartPick() const {
        return (trackFlags & TrackSmart) != 0;
      }

      enum MusicStatus { NotPlaying = 0x00, Playing = 0x01 };

    private:
      static constexpr uint8_t TrackSmart = 0x01;

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

      NimbleController& nimble;
      System::SystemTask& systemTask;
      NotificationManager& notificationManager;

      // A notification from the phone arrives in pieces. This is the one being put together.
      NotificationManager::Notification pending {};
      size_t pendingLength {0};
      bool pendingActive {false};
      bool pendingAlert {false};

      PhoneLists lists;
    };
  }
}
