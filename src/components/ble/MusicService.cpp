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
#include "components/ble/MusicService.h"
#include "components/ble/NimbleController.h"
#include "components/ble/NotificationManager.h"
#include "systemtask/SystemTask.h"
#include <algorithm>
#include <cstring>
#include <FreeRTOS.h>
#include <task.h>

namespace {
  // 0000yyxx-78fc-48fe-8e23-433b3a1942d0
  constexpr ble_uuid128_t CharUuid(uint8_t x, uint8_t y) {
    return ble_uuid128_t {.u = {.type = BLE_UUID_TYPE_128},
                          .value = {0xd0, 0x42, 0x19, 0x3a, 0x3b, 0x43, 0x23, 0x8e, 0xfe, 0x48, 0xfc, 0x78, x, y, 0x00, 0x00}};
  }

  // 00000000-78fc-48fe-8e23-433b3a1942d0
  constexpr ble_uuid128_t BaseUuid() {
    return CharUuid(0x00, 0x00);
  }

  constexpr ble_uuid128_t msUuid {BaseUuid()};

  constexpr ble_uuid128_t msEventCharUuid {CharUuid(0x01, 0x00)};
  constexpr ble_uuid128_t msStatusCharUuid {CharUuid(0x02, 0x00)};
  constexpr ble_uuid128_t msArtistCharUuid {CharUuid(0x03, 0x00)};
  constexpr ble_uuid128_t msTrackCharUuid {CharUuid(0x04, 0x00)};
  constexpr ble_uuid128_t msAlbumCharUuid {CharUuid(0x05, 0x00)};
  constexpr ble_uuid128_t msPositionCharUuid {CharUuid(0x06, 0x00)};
  constexpr ble_uuid128_t msTotalLengthCharUuid {CharUuid(0x07, 0x00)};
  constexpr ble_uuid128_t msTrackNumberCharUuid {CharUuid(0x08, 0x00)};
  constexpr ble_uuid128_t msTrackTotalCharUuid {CharUuid(0x09, 0x00)};
  constexpr ble_uuid128_t msPlaybackSpeedCharUuid {CharUuid(0x0a, 0x00)};
  constexpr ble_uuid128_t msRepeatCharUuid {CharUuid(0x0b, 0x00)};
  constexpr ble_uuid128_t msShuffleCharUuid {CharUuid(0x0c, 0x00)};
  // Not part of stock InfiniTime: the state of the shuffle, repeat and save buttons (three bytes),
  // followed since phone app build 7 by the phone's media volume, its number of volume steps and
  // the phone's battery in percent, and since build 8 by a byte of flags about the song that is
  // playing (bit 0: Spotify picked it, it is not in the playlist) and other marks for the main
  // screen (bit 1: Claude has a notification waiting on the phone).
  constexpr ble_uuid128_t msButtonsCharUuid {CharUuid(0x0d, 0x00)};
  // Not part of stock InfiniTime either: notifications and the state of the phone's call, as sent by
  // the Watch Helper phone app. Every message fits one 20-byte packet, so a long notification is
  // sent in pieces. The first byte of a message says what it is:
  //   0x01 a notification starts: number (2 bytes, low byte first), hour, minute,
  //        flags (bit 0: alert the wearer), length of the app name, the app name
  //   0x04 the next piece of its text: the title, a 0 byte, then the message
  //   0x05 the notification is complete: show it
  //   0x02 take a notification back: number (2 bytes)
  //   0x03 take all notifications back
  //   0x10 call: state (0 none, 1 ringing, 2 in a call), then the start of the caller's name
  //   0x11 the rest of the caller's name
  // Pages of the phone's lists (see PhoneLists) arrive the same way:
  //   0x20 a page starts: list, request number, offset (2 bytes), total (2 bytes), rows on this page, snapshot
  //   0x21 a row starts: request number, row (0-3), flags, text
  //   0x22 more text for a row: request number, row, text
  //   0x23 the title of the page: request number, text
  //   0x24 a piece of the sentence shown in place of the rows: request number, text
  //   0x25 the page is complete: request number
  //   0x28 what became of something the watch asked for: list, result, text
  constexpr ble_uuid128_t msPhoneCharUuid {CharUuid(0x0e, 0x00)};
  constexpr uint8_t PhoneNotificationBegin {0x01};
  constexpr uint8_t PhoneNotificationRemove {0x02};
  constexpr uint8_t PhoneNotificationClear {0x03};
  constexpr uint8_t PhoneNotificationText {0x04};
  constexpr uint8_t PhoneNotificationEnd {0x05};
  constexpr uint8_t PhoneCall {0x10};
  constexpr uint8_t PhoneCallMore {0x11};
  constexpr uint8_t PhoneListBegin {0x20};
  constexpr uint8_t PhoneListRow {0x21};
  constexpr uint8_t PhoneListRowMore {0x22};
  constexpr uint8_t PhoneListTitle {0x23};
  constexpr uint8_t PhoneListNote {0x24};
  constexpr uint8_t PhoneListEnd {0x25};
  constexpr uint8_t PhoneListResult {0x28};
  constexpr size_t PhoneListBeginSize {9};
  constexpr size_t PhoneMessageSize {20};
  constexpr size_t PhoneBeginHeaderSize {7};

  constexpr uint8_t MaxStringSize {40};

  int MusicCallback(uint16_t /*conn_handle*/, uint16_t /*attr_handle*/, struct ble_gatt_access_ctxt* ctxt, void* arg) {
    return static_cast<Pinetime::Controllers::MusicService*>(arg)->OnCommand(ctxt);
  }
}

Pinetime::Controllers::MusicService::MusicService(Pinetime::Controllers::NimbleController& nimble,
                                                 Pinetime::System::SystemTask& systemTask,
                                                 Pinetime::Controllers::NotificationManager& notificationManager)
  : nimble(nimble), systemTask {systemTask}, notificationManager {notificationManager} {
  characteristicDefinition[0] = {.uuid = &msEventCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_NOTIFY,
                                 .val_handle = &eventHandle};
  characteristicDefinition[1] = {.uuid = &msStatusCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[2] = {.uuid = &msTrackCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[3] = {.uuid = &msArtistCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[4] = {.uuid = &msAlbumCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[5] = {.uuid = &msPositionCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[6] = {.uuid = &msTotalLengthCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[7] = {.uuid = &msTotalLengthCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[8] = {.uuid = &msTrackNumberCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[9] = {.uuid = &msTrackTotalCharUuid.u,
                                 .access_cb = MusicCallback,
                                 .arg = this,
                                 .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[10] = {.uuid = &msPlaybackSpeedCharUuid.u,
                                  .access_cb = MusicCallback,
                                  .arg = this,
                                  .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[11] = {.uuid = &msRepeatCharUuid.u,
                                  .access_cb = MusicCallback,
                                  .arg = this,
                                  .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[12] = {.uuid = &msShuffleCharUuid.u,
                                  .access_cb = MusicCallback,
                                  .arg = this,
                                  .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  characteristicDefinition[13] = {.uuid = &msButtonsCharUuid.u,
                                  .access_cb = MusicCallback,
                                  .arg = this,
                                  .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ};
  // List pages are written without waiting for an answer to each piece, which is several times quicker
  characteristicDefinition[14] = {.uuid = &msPhoneCharUuid.u,
                                  .access_cb = MusicCallback,
                                  .arg = this,
                                  .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP};
  characteristicDefinition[15] = {0};

  serviceDefinition[0] = {.type = BLE_GATT_SVC_TYPE_PRIMARY, .uuid = &msUuid.u, .characteristics = characteristicDefinition};
  serviceDefinition[1] = {0};
}

void Pinetime::Controllers::MusicService::Init() {
  uint8_t res = 0;
  res = ble_gatts_count_cfg(serviceDefinition);
  ASSERT(res == 0);

  res = ble_gatts_add_svcs(serviceDefinition);
  ASSERT(res == 0);
}

int Pinetime::Controllers::MusicService::OnPhoneMessage(struct os_mbuf* om) {
  using Notifications = Pinetime::Controllers::NotificationManager;

  uint8_t message[PhoneMessageSize] = {0};
  const size_t length = std::min<size_t>(OS_MBUF_PKTLEN(om), sizeof(message));
  if (length == 0 || os_mbuf_copydata(om, 0, length, message) != 0) {
    return 0;
  }
  const auto* payload = reinterpret_cast<const char*>(&message[1]);
  const size_t payloadLength = length - 1;

  switch (message[0]) {
    case PhoneNotificationBegin: {
      if (length < PhoneBeginHeaderSize) {
        break;
      }
      pending = {};
      pending.category = Notifications::Categories::SimpleAlert;
      pending.phoneId = static_cast<uint16_t>(message[1] | (message[2] << 8));
      pending.hour = message[3];
      pending.minute = message[4];
      pendingAlert = (message[5] & 0x01) != 0;

      const size_t labelLength = std::min<size_t>({message[6], length - PhoneBeginHeaderSize, Notifications::LabelSize});
      std::memcpy(pending.label.data(), &message[PhoneBeginHeaderSize], labelLength);
      pending.label[labelLength] = '\0';

      pendingLength = 0;
      pendingActive = true;
      break;
    }
    case PhoneNotificationText: {
      if (!pendingActive) {
        break;
      }
      const size_t room = Notifications::MessageSize - 1 - pendingLength;
      const size_t count = std::min(payloadLength, room);
      std::memcpy(pending.message.data() + pendingLength, payload, count);
      pendingLength += count;
      break;
    }
    case PhoneNotificationEnd:
      if (pendingActive && pendingLength > 0) {
        pending.message[pendingLength] = '\0';
        pending.size = static_cast<uint8_t>(pendingLength + 1);
        notificationManager.Upsert(std::move(pending), pendingAlert);
        if (pendingAlert) {
          systemTask.PushMessage(Pinetime::System::Messages::OnNewNotification);
        }
      }
      pendingActive = false;
      break;
    case PhoneNotificationRemove:
      if (length >= 3) {
        notificationManager.RemoveByPhoneId(static_cast<uint16_t>(message[1] | (message[2] << 8)));
      }
      break;
    case PhoneNotificationClear:
      notificationManager.Clear();
      break;
    case PhoneCall: {
      if (length < 2 || message[1] > static_cast<uint8_t>(Notifications::CallStates::Active)) {
        break;
      }
      const auto state = static_cast<Notifications::CallStates>(message[1]);
      const bool startsRinging = state == Notifications::CallStates::Ringing &&
                                 notificationManager.CallState() != Notifications::CallStates::Ringing;
      notificationManager.SetCall(state, payload + 1, payloadLength - 1);
      if (startsRinging) {
        systemTask.PushMessage(Pinetime::System::Messages::OnNewCall);
      }
      break;
    }
    case PhoneCallMore:
      notificationManager.AppendCaller(payload, payloadLength);
      break;
    case PhoneListBegin: {
      if (length < PhoneListBeginSize || message[1] > static_cast<uint8_t>(PhoneLists::Kinds::Contacts)) {
        break;
      }
      lists.Begin(static_cast<PhoneLists::Kinds>(message[1]),
                  message[2],
                  static_cast<uint16_t>(message[3] | (message[4] << 8)),
                  static_cast<uint16_t>(message[5] | (message[6] << 8)),
                  message[7],
                  message[8]);
      break;
    }
    case PhoneListRow:
      if (length >= 4) {
        lists.RowText(message[1], message[2], true, message[3], payload + 3, payloadLength - 3);
      }
      break;
    case PhoneListRowMore:
      if (length >= 3) {
        lists.RowText(message[1], message[2], false, 0, payload + 2, payloadLength - 2);
      }
      break;
    case PhoneListTitle:
      if (length >= 2) {
        lists.Title(message[1], payload + 1, payloadLength - 1);
      }
      break;
    case PhoneListNote:
      if (length >= 2) {
        lists.Note(message[1], payload + 1, payloadLength - 1);
      }
      break;
    case PhoneListEnd:
      if (length >= 2) {
        lists.End(message[1]);
      }
      break;
    case PhoneListResult:
      if (length >= 3 && message[1] <= static_cast<uint8_t>(PhoneLists::Kinds::Contacts)) {
        lists.SetResult(static_cast<PhoneLists::Kinds>(message[1]), message[2], payload + 2, payloadLength - 2);
      }
      break;
    default:
      break;
  }
  return 0;
}

int Pinetime::Controllers::MusicService::OnCommand(struct ble_gatt_access_ctxt* ctxt) {
  if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR && ble_uuid_cmp(ctxt->chr->uuid, &msPhoneCharUuid.u) == 0) {
    return OnPhoneMessage(ctxt->om);
  }
  if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
    size_t notifSize = OS_MBUF_PKTLEN(ctxt->om);
    size_t bufferSize = notifSize;
    if (notifSize > MaxStringSize) {
      bufferSize = MaxStringSize;
    }

    char data[bufferSize + 1];
    os_mbuf_copydata(ctxt->om, 0, bufferSize, data);

    if (notifSize > bufferSize) {
      data[bufferSize - 1] = '.';
      data[bufferSize - 2] = '.';
      data[bufferSize - 3] = '.';
    }
    data[bufferSize] = '\0';

    char* s = &data[0];
    if (ble_uuid_cmp(ctxt->chr->uuid, &msArtistCharUuid.u) == 0) {
      artistName = s;
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msTrackCharUuid.u) == 0) {
      trackName = s;
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msAlbumCharUuid.u) == 0) {
      albumName = s;
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msStatusCharUuid.u) == 0) {
      playing = s[0];
      // These variables need to be updated, because the progress may not be updated immediately,
      // leading to getProgress() returning an incorrect position.
      if (playing) {
        trackProgressUpdateTime = xTaskGetTickCount();
      } else {
        trackProgress +=
          static_cast<int>((static_cast<float>(xTaskGetTickCount() - trackProgressUpdateTime) / 1024.0f) * getPlaybackSpeed());
      }
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msRepeatCharUuid.u) == 0) {
      repeat = s[0];
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msShuffleCharUuid.u) == 0) {
      shuffle = s[0];
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msButtonsCharUuid.u) == 0) {
      if (notifSize >= 3) {
        shuffleState = static_cast<uint8_t>(s[0]);
        repeatState = static_cast<uint8_t>(s[1]);
        savedState = static_cast<uint8_t>(s[2]);
      }
      if (notifSize >= 6) {
        phoneVolume = static_cast<uint8_t>(s[3]);
        phoneVolumeMax = static_cast<uint8_t>(s[4]);
        phoneBattery = static_cast<uint8_t>(s[5]);
      }
      trackFlags = notifSize >= 7 ? static_cast<uint8_t>(s[6]) : 0;
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msPositionCharUuid.u) == 0) {
      trackProgress = (s[0] << 24) | (s[1] << 16) | (s[2] << 8) | s[3];
      trackProgressUpdateTime = xTaskGetTickCount();
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msTotalLengthCharUuid.u) == 0) {
      trackLength = (s[0] << 24) | (s[1] << 16) | (s[2] << 8) | s[3];
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msTrackNumberCharUuid.u) == 0) {
      trackNumber = (s[0] << 24) | (s[1] << 16) | (s[2] << 8) | s[3];
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msTrackTotalCharUuid.u) == 0) {
      tracksTotal = (s[0] << 24) | (s[1] << 16) | (s[2] << 8) | s[3];
    } else if (ble_uuid_cmp(ctxt->chr->uuid, &msPlaybackSpeedCharUuid.u) == 0) {
      playbackSpeed = static_cast<float>(((s[0] << 24) | (s[1] << 16) | (s[2] << 8) | s[3])) / 100.0f;
    }
  }
  return 0;
}

std::string Pinetime::Controllers::MusicService::getAlbum() const {
  return albumName;
}

std::string Pinetime::Controllers::MusicService::getArtist() const {
  return artistName;
}

std::string Pinetime::Controllers::MusicService::getTrack() const {
  return trackName;
}

bool Pinetime::Controllers::MusicService::isPlaying() const {
  return playing;
}

float Pinetime::Controllers::MusicService::getPlaybackSpeed() const {
  return playbackSpeed;
}

int Pinetime::Controllers::MusicService::getProgress() const {
  if (isPlaying()) {
    return trackProgress +
           static_cast<int>((static_cast<float>(xTaskGetTickCount() - trackProgressUpdateTime) / 1024.0f) * getPlaybackSpeed());
  }
  return trackProgress;
}

int Pinetime::Controllers::MusicService::getTrackLength() const {
  return trackLength;
}

void Pinetime::Controllers::MusicService::OnDisconnect() {
  pendingActive = false;
  phoneVolume = StateUnknown;
  phoneVolumeMax = StateUnknown;
  phoneBattery = StateUnknown;
  trackFlags = 0;
  lists.Reset();
  notificationManager.SetCall(Pinetime::Controllers::NotificationManager::CallStates::Idle, nullptr, 0);
}

void Pinetime::Controllers::MusicService::event(char event) {
  const auto code = static_cast<uint8_t>(event);
  Send(&code, 1);
}

void Pinetime::Controllers::MusicService::Send(const uint8_t* data, uint16_t length) {
  uint16_t connectionHandle = nimble.connHandle();

  if (connectionHandle == 0 || connectionHandle == BLE_HS_CONN_HANDLE_NONE) {
    return;
  }

  // Allocate the buffer only once we know there is a connection to send it on.
  // Allocating first and returning above leaked one of the 12 buffers of the BLE stack
  // on every event sent while disconnected.
  auto* om = ble_hs_mbuf_from_flat(data, length);
  if (om == nullptr) {
    return;
  }

  ble_gattc_notify_custom(connectionHandle, eventHandle, om);
}

void Pinetime::Controllers::MusicService::RequestList(PhoneLists::Kinds kind, uint8_t token, uint16_t offset, uint16_t parent) {
  const uint8_t message[] = {EVENT_LIST_PAGE,
                             static_cast<uint8_t>(kind),
                             token,
                             static_cast<uint8_t>(offset & 0xff),
                             static_cast<uint8_t>(offset >> 8),
                             static_cast<uint8_t>(parent & 0xff),
                             static_cast<uint8_t>(parent >> 8)};
  Send(message, sizeof(message));
}

void Pinetime::Controllers::MusicService::ActOnRow(PhoneLists::Kinds kind,
                                                   uint8_t action,
                                                   uint16_t index,
                                                   uint16_t parent,
                                                   uint8_t snapshot) {
  const uint8_t message[] = {EVENT_LIST_ACTION,
                             static_cast<uint8_t>(kind),
                             action,
                             static_cast<uint8_t>(index & 0xff),
                             static_cast<uint8_t>(index >> 8),
                             static_cast<uint8_t>(parent & 0xff),
                             static_cast<uint8_t>(parent >> 8),
                             snapshot};
  Send(message, sizeof(message));
}

void Pinetime::Controllers::MusicService::DismissNotification(uint16_t phoneId) {
  const uint8_t message[] = {EVENT_NOTIFICATION_DISMISS, static_cast<uint8_t>(phoneId & 0xff), static_cast<uint8_t>(phoneId >> 8)};
  Send(message, sizeof(message));
}
