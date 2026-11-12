#include "displayapp/screens/PhoneList.h"

#include <task.h>
#include <cstring>

#include "displayapp/DisplayApp.h"
#include "displayapp/InfiniTimeTheme.h"
#include "components/ble/BleController.h"
#include "components/ble/MusicService.h"
#include "components/ble/NotificationManager.h"
#include "components/motor/MotorController.h"

using namespace Pinetime::Applications::Screens;

namespace {
  // The title line, then four rows of two lines of text each: together exactly the height of the screen
  constexpr lv_coord_t titleHeight = 26;
  constexpr lv_coord_t titleX = 6;
  constexpr lv_coord_t titleWidth = 170;
  constexpr lv_coord_t rowHeight = 52;
  constexpr lv_coord_t rowGap = 2;
  constexpr lv_coord_t rowPadding = 8;
  constexpr lv_coord_t rowWidth = 240;
  constexpr lv_coord_t rowTextWidth = rowWidth - 2 * rowPadding;
  constexpr lv_coord_t noteWidth = 224;

  constexpr lv_color_t musicColor = LV_COLOR_MAKE(0x1e, 0xd7, 0x60);
  constexpr lv_color_t contactsColor = LV_COLOR_MAKE(0x4a, 0x90, 0xff);
  constexpr lv_color_t rowColor = Colors::bgAlt;
  constexpr lv_color_t rowLitColor = LV_COLOR_MAKE(0x60, 0x60, 0x60);

  // Holding a row this long is a hold; lifting the finger sooner is a tap
  constexpr TickType_t holdDuration = pdMS_TO_TICKS(600);
  // The touch that brought this screen up is not meant for its rows
  constexpr TickType_t appearMute = pdMS_TO_TICKS(300);
  // How long the phone gets to send a page, and to say what became of something it was asked to do
  constexpr TickType_t answerTimeout = pdMS_TO_TICKS(6000);
  constexpr TickType_t actTimeout = pdMS_TO_TICKS(8000);
  constexpr TickType_t toastDuration = pdMS_TO_TICKS(2500);
  // "Call ...?" goes away by itself, and so does the wait for a call that never starts
  constexpr TickType_t confirmTimeout = pdMS_TO_TICKS(10000);
  constexpr TickType_t callTimeout = pdMS_TO_TICKS(10000);
  constexpr uint8_t confirmBuzzMs = 15;

  // The star also stands for Smart Shuffle on the shuffle button of the main screen
  constexpr const char* smartPrefix = "\xEF\x80\x85 ";
  constexpr const char* playingPrefix = "\xEF\x81\x8B ";

  template <size_t N> void CopyText(std::array<char, N>& target, const char* text) {
    size_t length = 0;
    while (length < N - 1 && text[length] != '\0') {
      length++;
    }
    std::memcpy(target.data(), text, length);
    target[length] = '\0';
  }

  // Adds "piece" to the text in "target", which is "capacity" bytes long, and returns the new length
  size_t AddText(char* target, size_t capacity, size_t used, const char* piece) {
    while (*piece != '\0' && used < capacity - 1) {
      target[used++] = *piece++;
    }
    target[used] = '\0';
    return used;
  }

  const char* NameOf(Pinetime::Controllers::PhoneLists::Kinds kind) {
    using Kinds = Pinetime::Controllers::PhoneLists::Kinds;
    switch (kind) {
      case Kinds::Queue:
        return "Queue";
      case Kinds::Playlists:
        return "Playlists";
      case Kinds::Songs:
        return "Songs";
      case Kinds::Contacts:
        return "Contacts";
      default:
        return "";
    }
  }
}

PhoneList::PhoneList(DisplayApp* app,
                     Kinds kind,
                     Pinetime::Controllers::MusicService& musicService,
                     Pinetime::Controllers::NotificationManager& notificationManager,
                     const Pinetime::Controllers::Ble& bleController,
                     Pinetime::Controllers::MotorController& motorController)
  : app {app},
    kind {kind},
    musicService {musicService},
    notificationManager {notificationManager},
    bleController {bleController},
    motorController {motorController},
    lists {musicService.Lists()} {

  labelTitle = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_long_mode(labelTitle, LV_LABEL_LONG_CROP);
  lv_obj_set_size(labelTitle, titleWidth, titleHeight);
  lv_label_set_text_static(labelTitle, "");
  lv_obj_set_pos(labelTitle, titleX, 0);

  labelPage = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelPage, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_text_static(labelPage, "");
  lv_obj_align(labelPage, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -6, 0);

  for (uint8_t i = 0; i < Lists::RowsPerPage; i++) {
    rowBox[i] = lv_obj_create(lv_scr_act(), nullptr);
    lv_obj_set_size(rowBox[i], rowWidth, rowHeight);
    lv_obj_set_pos(rowBox[i], 0, static_cast<lv_coord_t>(titleHeight + i * (rowHeight + rowGap)));
    lv_obj_set_style_local_bg_opa(rowBox[i], LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, LV_OPA_COVER);
    lv_obj_set_style_local_bg_color(rowBox[i], LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, rowColor);
    lv_obj_set_style_local_border_width(rowBox[i], LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, 0);
    lv_obj_set_style_local_radius(rowBox[i], LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, 10);
    lv_obj_set_hidden(rowBox[i], true);

    // Two lines of text. Anything longer is cut off by the edge of the row.
    rowLabel[i] = lv_label_create(rowBox[i], nullptr);
    lv_label_set_long_mode(rowLabel[i], LV_LABEL_LONG_BREAK);
    lv_obj_set_width(rowLabel[i], rowTextWidth);
    lv_label_set_text_static(rowLabel[i], "");
  }

  labelNote = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_long_mode(labelNote, LV_LABEL_LONG_BREAK);
  lv_obj_set_width(labelNote, noteWidth);
  lv_label_set_align(labelNote, LV_LABEL_ALIGN_CENTER);
  lv_label_set_text_static(labelNote, "");
  lv_obj_align(labelNote, lv_scr_act(), LV_ALIGN_CENTER, 0, 0);

  labelHint = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelHint, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_align(labelHint, LV_LABEL_ALIGN_CENTER);
  lv_label_set_text_static(labelHint, "Tap: call\nHold: cancel");
  lv_obj_align(labelHint, lv_scr_act(), LV_ALIGN_IN_BOTTOM_MID, 0, -6);
  lv_obj_set_hidden(labelHint, true);

  // Lies over everything else and tells taps from holds; the row is worked out from where the finger was
  tapPad.Create(false, holdDuration);
  tapPad.Mute(xTaskGetTickCount(), appearMute);

  if (kind == Kinds::Songs) {
    parent = lists.Parent();
  } else if (kind == Kinds::Playlists) {
    // Back from the songs of a playlist: show the page that playlist is on
    wantedOffset = lists.PlaylistsOffset();
  }
  seenRevision = lists.Revision();
  connected = bleController.IsConnected();
  if (connected) {
    Ask(wantedOffset);
  }
  Draw();

  taskRefresh = lv_task_create(RefreshTaskCallback, LV_DISP_DEF_REFR_PERIOD, LV_TASK_PRIO_MID, this);
}

PhoneList::~PhoneList() {
  lv_task_del(taskRefresh);
  lv_obj_clean(lv_scr_act());
}

void PhoneList::Ask(uint16_t offset) {
  wantedOffset = offset;
  token = lists.NextToken();
  waiting = true;
  unanswered = false;
  askedTick = xTaskGetTickCount();
  musicService.RequestList(kind, token, offset, parent);
}

void PhoneList::Act(uint8_t action, uint16_t index, const char* doing) {
  const TickType_t now = xTaskGetTickCount();
  musicService.ActOnRow(kind, action, index, parent, page.snapshot);
  motorController.RunForDuration(confirmBuzzMs);
  acting = true;
  actingTick = now;
  ShowToast(doing, Colors::lightGray, now);
}

void PhoneList::ShowToast(const char* text, lv_color_t color, TickType_t now) {
  CopyText(toast, text);
  toastColor = color;
  toastVisible = toast[0] != '\0';
  toastTick = now;
  DrawTitle();
}

void PhoneList::StopConfirming() {
  if (confirming) {
    confirming = false;
    calling = false;
    needsDraw = true;
  }
}

void PhoneList::Refresh() {
  const TickType_t now = xTaskGetTickCount();
  const auto tap = tapPad.Update(now);

  const bool nowConnected = bleController.IsConnected();
  if (nowConnected != connected) {
    connected = nowConnected;
    if (connected) {
      Ask(wantedOffset);
    } else {
      waiting = false;
      unanswered = false;
      acting = false;
      havePage = false;
      toastVisible = false;
      StopConfirming();
    }
    needsDraw = true;
  }

  if (waiting && (now - askedTick) >= answerTimeout) {
    waiting = false;
    unanswered = true;
    needsDraw = true;
  }
  if (acting && (now - actingTick) >= actTimeout) {
    acting = false;
    StopConfirming();
    ShowToast("No answer", Colors::orange, now);
  }
  if (toastVisible && !acting && (now - toastTick) >= toastDuration) {
    toastVisible = false;
    DrawTitle();
  }

  if (confirming) {
    using CallStates = Pinetime::Controllers::NotificationManager::CallStates;
    if (calling && notificationManager.CallState() != CallStates::Idle) {
      // The phone is making the call. From here on it belongs to the call screen.
      StopConfirming();
      app->StartApp(Apps::Call, DisplayApp::FullRefreshDirections::Down);
    } else if ((now - confirmTick) >= (calling ? callTimeout : confirmTimeout)) {
      StopConfirming();
    }
  }

  if (lists.Revision() != seenRevision) {
    TakeNews(now);
  }

  if (tap == Widgets::TapPad::Events::Tap || tap == Widgets::TapPad::Events::Hold) {
    OnTap(tap, now);
  }

  // The row under the finger lights up, where touching a row does something
  const bool rowsLive = kind != Kinds::Queue && connected && havePage && !waiting && !unanswered && !confirming;
  LightRow(rowsLive && tapPad.Pressing() ? RowAt(tapPad.PressPoint()) : -1);

  if (needsDraw) {
    Draw();
  }
}

void PhoneList::TakeNews(TickType_t now) {
  const uint32_t revision = lists.Revision();
  if (!lists.Read(fresh)) {
    // It is being replaced this very moment: look again on the next refresh
    return;
  }
  seenRevision = revision;

  if (waiting && fresh.kind == kind && fresh.token == token) {
    page = fresh;
    havePage = true;
    waiting = false;
    unanswered = false;
    // The phone may have moved the page, for example because the list got shorter
    wantedOffset = page.offset;
    if (kind == Kinds::Playlists) {
      lists.RememberPlaylistsOffset(page.offset);
    }
    needsDraw = true;
  }

  Lists::Result result;
  if (lists.TakeResult(result) && result.kind == kind) {
    acting = false;
    if (result.code == Lists::ResultStale) {
      StopConfirming();
      ShowToast("List changed", Colors::orange, now);
      Ask(wantedOffset);
    } else if (result.code == Lists::ResultDone) {
      ShowToast(result.text.data(), kind == Kinds::Contacts ? contactsColor : musicColor, now);
    } else {
      StopConfirming();
      ShowToast(result.text[0] != '\0' ? result.text.data() : "Did not work", Colors::orange, now);
    }
  }
}

void PhoneList::OnTap(Widgets::TapPad::Events tap, TickType_t now) {
  const bool isTap = tap == Widgets::TapPad::Events::Tap;
  if (!connected) {
    return;
  }
  if (unanswered) {
    Ask(wantedOffset);
    needsDraw = true;
    return;
  }
  if (confirming) {
    if (!calling) {
      if (isTap) {
        calling = true;
        confirmTick = now;
        Act(Pinetime::Controllers::MusicService::ActionTap, confirmIndex, "Calling...");
      } else {
        // Holding takes the question back, the way holding a ringing call declines it
        motorController.RunForDuration(confirmBuzzMs);
        StopConfirming();
      }
      needsDraw = true;
    }
    return;
  }
  if (!havePage || waiting || acting) {
    return;
  }
  const int row = RowAt(tapPad.PressPoint());
  if (row < 0) {
    return;
  }
  const auto index = static_cast<uint16_t>(page.offset + row);

  switch (kind) {
    case Kinds::Playlists:
      if (isTap) {
        lists.OpenPlaylist(index, page.rows[row].text.data());
        app->StartApp(Apps::Songs, DisplayApp::FullRefreshDirections::LeftAnim);
      } else {
        Act(Pinetime::Controllers::MusicService::ActionHold, index, "Starting...");
      }
      break;
    case Kinds::Songs:
      if (isTap) {
        Act(Pinetime::Controllers::MusicService::ActionTap, index, "Adding...");
      } else {
        Act(Pinetime::Controllers::MusicService::ActionHold, index, "Starting...");
      }
      break;
    case Kinds::Contacts:
      if (isTap) {
        confirming = true;
        calling = false;
        confirmIndex = index;
        confirmName = page.rows[row].text;
        confirmTick = now;
        needsDraw = true;
      }
      break;
    default:
      break;
  }
}

int PhoneList::RowAt(lv_point_t point) const {
  if (point.y < titleHeight) {
    return -1;
  }
  const int row = (point.y - titleHeight) / (rowHeight + rowGap);
  return row < page.count ? row : -1;
}

void PhoneList::LightRow(int row) {
  if (row == litRow) {
    return;
  }
  if (litRow >= 0) {
    lv_obj_set_style_local_bg_color(rowBox[litRow], LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, rowColor);
  }
  if (row >= 0) {
    lv_obj_set_style_local_bg_color(rowBox[row], LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, rowLitColor);
  }
  litRow = row;
}

void PhoneList::DrawTitle() {
  const lv_color_t kindColor = kind == Kinds::Contacts ? contactsColor : musicColor;
  if (toastVisible) {
    lv_label_set_text(labelTitle, toast.data());
    lv_obj_set_style_local_text_color(labelTitle, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, toastColor);
  } else {
    const char* title = NameOf(kind);
    if (havePage && page.title[0] != '\0') {
      title = page.title.data();
    } else if (kind == Kinds::Songs && lists.ParentName()[0] != '\0') {
      title = lists.ParentName();
    }
    lv_label_set_text(labelTitle, title);
    lv_obj_set_style_local_text_color(labelTitle, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, kindColor);
  }

  if (connected && havePage && page.total > Lists::RowsPerPage) {
    lv_label_set_text_fmt(labelPage,
                          "%d/%d",
                          wantedOffset / Lists::RowsPerPage + 1,
                          (page.total + Lists::RowsPerPage - 1) / Lists::RowsPerPage);
  } else {
    lv_label_set_text_static(labelPage, "");
  }
  lv_obj_realign(labelPage);
}

void PhoneList::Draw() {
  needsDraw = false;
  DrawTitle();

  const bool rowsVisible = connected && havePage && !unanswered && !confirming;
  for (uint8_t i = 0; i < Lists::RowsPerPage; i++) {
    const bool visible = rowsVisible && i < page.count;
    lv_obj_set_hidden(rowBox[i], !visible);
    if (!visible) {
      continue;
    }
    const Lists::Row& row = page.rows[i];
    lv_color_t color = LV_COLOR_WHITE;
    char text[Lists::RowSize + 8];
    size_t used = 0;
    text[0] = '\0';
    if ((row.flags & Lists::RowSmart) != 0) {
      used = AddText(text, sizeof(text), used, smartPrefix);
      color = musicColor;
    } else if ((row.flags & Lists::RowPlaying) != 0) {
      used = AddText(text, sizeof(text), used, playingPrefix);
    } else if ((row.flags & Lists::RowClosed) != 0) {
      color = Colors::lightGray;
    }
    AddText(text, sizeof(text), used, row.text.data());

    lv_label_set_text(rowLabel[i], text);
    lv_obj_set_style_local_text_color(rowLabel[i], LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, color);
    if (lv_obj_get_height(rowLabel[i]) <= rowHeight) {
      lv_obj_align(rowLabel[i], nullptr, LV_ALIGN_IN_LEFT_MID, rowPadding, 0);
    } else {
      lv_obj_align(rowLabel[i], nullptr, LV_ALIGN_IN_TOP_LEFT, rowPadding, 1);
    }
  }

  // What stands in place of the rows when there are none to show
  lv_color_t noteColor = Colors::lightGray;
  char question[Lists::RowSize + 16];
  const char* note = "";
  if (confirming) {
    size_t used = AddText(question, sizeof(question), 0, calling ? "Calling\n" : "Call\n");
    used = AddText(question, sizeof(question), used, confirmName.data());
    AddText(question, sizeof(question), used, calling ? "..." : "?");
    note = question;
    noteColor = LV_COLOR_WHITE;
  } else if (!connected) {
    note = "Phone not connected";
  } else if (unanswered) {
    note = "No answer from\nthe phone.\nTap to try again.";
  } else if (!havePage) {
    note = "Loading...";
  } else if (page.count == 0) {
    note = page.note[0] != '\0' ? page.note.data() : "Nothing here";
  }
  lv_label_set_text(labelNote, note);
  lv_obj_set_style_local_text_color(labelNote, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, noteColor);
  lv_obj_align(labelNote, lv_scr_act(), LV_ALIGN_CENTER, 0, confirming ? -20 : 0);
  lv_obj_set_hidden(labelHint, !confirming || calling);
}

bool PhoneList::OnTouchEvent(Pinetime::Applications::TouchEvents event) {
  const bool swipe = event == TouchEvents::SwipeUp || event == TouchEvents::SwipeDown || event == TouchEvents::SwipeLeft ||
                     event == TouchEvents::SwipeRight;
  if (!swipe) {
    // The touch controller also reports every tap and hold here. Those are told apart by the tap
    // pad and are nothing to act on in this place. (Taking them for a swipe is what made the
    // question "Call ...?" go away a moment after the tap that had asked it.)
    return false;
  }
  if (confirming) {
    // A swipe takes the question back too. Once the call has been asked for there is nothing to take back.
    if (!calling) {
      StopConfirming();
    }
    return true;
  }
  switch (event) {
    case TouchEvents::SwipeUp:
      if (connected && havePage && wantedOffset + Lists::RowsPerPage < page.total) {
        Ask(static_cast<uint16_t>(wantedOffset + Lists::RowsPerPage));
        needsDraw = true;
      }
      return true;
    case TouchEvents::SwipeDown:
      if (connected && wantedOffset >= Lists::RowsPerPage) {
        Ask(static_cast<uint16_t>(wantedOffset - Lists::RowsPerPage));
        needsDraw = true;
        return true;
      }
      // The queue hangs below the main screen: at its top, swiping down leads back there
      return kind != Kinds::Queue;
    case TouchEvents::SwipeLeft:
      if (kind == Kinds::Queue) {
        // The playlists are to the right of the queue
        app->StartApp(Apps::Playlists, DisplayApp::FullRefreshDirections::LeftAnim);
      }
      return true;
    case TouchEvents::SwipeRight:
      // For everything but the queue this is the way back, which is not this screen's business
      return kind == Kinds::Queue;
    default:
      return false;
  }
}
