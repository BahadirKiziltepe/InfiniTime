#include "displayapp/screens/Notifications.h"

#include <task.h>
#include <cstring>

#include "displayapp/InfiniTimeTheme.h"
#include "components/motor/MotorController.h"
#include "components/settings/Settings.h"

using namespace Pinetime::Applications::Screens;

namespace {
  // Layout of the 240x240 screen.
  //
  //   y   4.. 29  where it came from (left) and when (right)
  //   y  32.. 57  who it is from
  //   y  60..210  the message, six lines at a time, with a thin scroll bar at the right when there is more
  //   y 213..238  which one this is ("2/5")
  constexpr lv_coord_t sideMargin = 6;
  constexpr lv_coord_t topRowY = 4;
  constexpr lv_coord_t titleY = 32;
  constexpr lv_coord_t bodyY = 60;
  constexpr lv_coord_t bodyHeight = 150;
  constexpr lv_coord_t textWidth = 228;
  constexpr lv_coord_t bodyWidth = 222;
  constexpr lv_coord_t scrollBarWidth = 3;
  constexpr lv_coord_t scrollBarMinHeight = 14;
  // One swipe moves the message by four of the six lines, so two lines stay on screen to read on from
  constexpr lv_coord_t scrollStep = 100;
  constexpr uint16_t scrollTimeMs = 180;

  // A notification that came up by itself goes away again after this long, unless the screen is touched
  constexpr TickType_t previewDuration = pdMS_TO_TICKS(7000);
  // The touch that was meant for the screen before does not count
  constexpr TickType_t previewMute = pdMS_TO_TICKS(400);

  // The buzz for a new notification lasts 0.6 s, long enough to feel while working. The motor only
  // runs a quarter of a second per request, so it is started again before it has run out.
  constexpr uint8_t buzzPieceCount = 3;
  constexpr TickType_t buzzPieceEvery = pdMS_TO_TICKS(200);
  constexpr uint8_t buzzPieceMs = 250;
  constexpr uint8_t buzzLastPieceMs = 200;

  void SetLabelY(void* label, lv_anim_value_t y) {
    lv_obj_set_y(static_cast<lv_obj_t*>(label), static_cast<lv_coord_t>(y));
  }

  struct Source {
    const char* name;
    lv_color_t color;
  };
  // The app names the phone sends, in the colours people know them by. Anything else is shown in orange.
  constexpr Source sources[] = {
    {"Text", LV_COLOR_MAKE(0x4a, 0x90, 0xff)},
    {"WhatsApp", LV_COLOR_MAKE(0x25, 0xd3, 0x66)},
    {"Discord", LV_COLOR_MAKE(0x7b, 0x8c, 0xff)},
  };

  lv_color_t ColorOf(const char* source) {
    for (const auto& known : sources) {
      if (std::strcmp(source, known.name) == 0) {
        return known.color;
      }
    }
    return Colors::orange;
  }
}

Notifications::Notifications(Pinetime::Controllers::NotificationManager& notificationManager,
                             Pinetime::Controllers::MotorController& motorController,
                             Pinetime::Controllers::Settings& settingsController,
                             System::SystemTask& systemTask,
                             Modes mode)
  : notificationManager {notificationManager},
    motorController {motorController},
    settingsController {settingsController},
    mode {mode},
    wakeLock(systemTask) {

  notificationManager.ClearNewNotificationFlag();

  labelSource = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_text_static(labelSource, "");
  lv_obj_align(labelSource, lv_scr_act(), LV_ALIGN_IN_TOP_LEFT, sideMargin, topRowY);

  labelTime = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelTime, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_text_static(labelTime, "");
  lv_obj_align(labelTime, lv_scr_act(), LV_ALIGN_IN_TOP_RIGHT, -sideMargin, topRowY);

  // Who it is from. A name that does not fit scrolls.
  labelTitle = lv_label_create(lv_scr_act(), nullptr);
  lv_label_set_long_mode(labelTitle, LV_LABEL_LONG_SROLL_CIRC);
  lv_obj_set_width(labelTitle, textWidth);
  lv_label_set_text_static(labelTitle, "");
  lv_obj_align(labelTitle, lv_scr_act(), LV_ALIGN_IN_TOP_LEFT, sideMargin, titleY);

  // The message, in a window six lines high. The text is as tall as it needs to be and is moved up
  // and down behind the window; what is outside the window is not drawn.
  bodyBox = lv_obj_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_bg_opa(bodyBox, LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, LV_OPA_TRANSP);
  lv_obj_set_style_local_border_width(bodyBox, LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, 0);
  lv_obj_set_size(bodyBox, bodyWidth, bodyHeight);
  lv_obj_set_pos(bodyBox, sideMargin, bodyY);

  labelBody = lv_label_create(bodyBox, nullptr);
  lv_obj_set_style_local_text_color(labelBody, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_long_mode(labelBody, LV_LABEL_LONG_BREAK);
  lv_obj_set_width(labelBody, bodyWidth);
  lv_label_set_text(labelBody, "");
  lv_obj_set_pos(labelBody, 0, 0);

  scrollBar = lv_obj_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_bg_color(scrollBar, LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, Colors::gray);
  lv_obj_set_style_local_border_width(scrollBar, LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, 0);
  lv_obj_set_style_local_radius(scrollBar, LV_OBJ_PART_MAIN, LV_STATE_DEFAULT, 1);
  lv_obj_set_size(scrollBar, scrollBarWidth, bodyHeight);
  lv_obj_set_pos(scrollBar, LV_HOR_RES - scrollBarWidth - 2, bodyY);
  lv_obj_set_hidden(scrollBar, true);

  labelCounter = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelCounter, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::gray);
  lv_label_set_text_static(labelCounter, "");
  lv_obj_align(labelCounter, lv_scr_act(), LV_ALIGN_IN_BOTTOM_MID, 0, -2);

  labelEmpty = lv_label_create(lv_scr_act(), nullptr);
  lv_obj_set_style_local_text_color(labelEmpty, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, Colors::lightGray);
  lv_label_set_text_static(labelEmpty, "No notifications");
  lv_obj_align(labelEmpty, lv_scr_act(), LV_ALIGN_CENTER, 0, 0);
  lv_obj_set_hidden(labelEmpty, true);

  // Tap for the next one, double tap for the one before
  tapPad.Create(true, 0);

  shownTick = xTaskGetTickCount();
  if (mode == Modes::Preview) {
    wakeLock.Lock();
    tapPad.Mute(shownTick, previewMute);
  } else {
    // Opened by hand: nothing to buzz for
    buzzPieces = buzzPieceCount;
  }

  Reload();
  taskRefresh = lv_task_create(RefreshTaskCallback, LV_DISP_DEF_REFR_PERIOD, LV_TASK_PRIO_MID, this);
}

Notifications::~Notifications() {
  lv_task_del(taskRefresh);
  // Notices from the watch itself have now been seen
  if (mode == Modes::Normal || pinned) {
    notificationManager.RemoveLocal();
  }
  lv_obj_clean(lv_scr_act());
}

void Notifications::Refresh() {
  const TickType_t now = xTaskGetTickCount();
  auto tap = tapPad.Update(now);

  if (buzzPieces < buzzPieceCount && (buzzPieces == 0 || (now - buzzTick) >= buzzPieceEvery)) {
    buzzTick = now;
    buzzPieces++;
    motorController.RunForDuration(buzzPieces < buzzPieceCount ? buzzPieceMs : buzzLastPieceMs);
  }

  if (mode == Modes::Preview && !pinned) {
    if (tapPad.TakeTouched()) {
      // Touching the screen keeps it open. That touch does not also move on to the next notification.
      pinned = true;
      swallowTap = true;
      wakeLock.Release();
    } else if ((now - shownTick) >= previewDuration) {
      running = false;
      return;
    }
  }

  if (swallowTap) {
    if (tap != Widgets::TapPad::Events::None) {
      swallowTap = false;
      tap = Widgets::TapPad::Events::None;
    } else if (!tapPad.Busy()) {
      // That touch was a swipe: there is no tap to swallow
      swallowTap = false;
    }
  }

  if (count > 1) {
    if (tap == Widgets::TapPad::Events::Tap) {
      index = (index + 1) % count;
      Show();
    } else if (tap == Widgets::TapPad::Events::DoubleTap) {
      index = (index + count - 1) % count;
      Show();
    }
  }

  if (notificationManager.Revision() != shownRevision) {
    Reload();
  }
}

void Notifications::Reload() {
  // Taken before looking at the list: if the list changes while it is being read, the next refresh reads it again
  shownRevision = notificationManager.Revision();

  count = static_cast<uint8_t>(notificationManager.NbNotifications());
  if (count == 0) {
    if (mode == Modes::Preview && !pinned) {
      // What this came up for is gone again
      running = false;
    }
    index = 0;
    Show();
    return;
  }

  // Stay on the notification that was showing, if it is still there
  auto position = showing ? notificationManager.IndexOf(currentId) : count;
  if (position >= count && showing) {
    // A conversation that got another message comes back as a new entry with the same phone number
    position = notificationManager.IndexOfPhoneId(currentPhoneId);
  }
  if (position < count) {
    index = position;
  } else if (index >= count) {
    index = count - 1;
  }
  Show();
}

void Notifications::Show() {
  const auto notification = notificationManager.GetAt(index);
  const bool empty = count == 0 || !notification.valid || notification.size == 0;

  lv_obj_set_hidden(labelEmpty, !empty);
  lv_obj_set_hidden(labelSource, empty);
  lv_obj_set_hidden(labelTime, empty);
  lv_obj_set_hidden(labelTitle, empty);
  lv_obj_set_hidden(bodyBox, empty);
  lv_obj_set_hidden(labelCounter, empty);
  showing = !empty;
  if (empty) {
    scrollMax = 0;
    ScrollTo(0, false);
    return;
  }
  currentId = notification.id;
  currentPhoneId = notification.phoneId;

  const char* source = notification.label[0] != '\0' ? notification.label.data() : "Notice";
  lv_obj_set_style_local_text_color(labelSource, LV_LABEL_PART_MAIN, LV_STATE_DEFAULT, ColorOf(source));
  lv_label_set_text(labelSource, source);

  if (notification.hour == Pinetime::Controllers::NotificationManager::UnknownHour) {
    lv_label_set_text_static(labelTime, "");
  } else if (settingsController.GetClockType() == Controllers::Settings::ClockType::H12) {
    uint8_t hour = notification.hour % 12;
    if (hour == 0) {
      hour = 12;
    }
    lv_label_set_text_fmt(labelTime, "%d:%02d %s", hour, notification.minute, notification.hour >= 12 ? "PM" : "AM");
  } else {
    lv_label_set_text_fmt(labelTime, "%02d:%02d", notification.hour, notification.minute);
  }
  lv_obj_realign(labelTime);

  const char* title = notification.Title();
  lv_label_set_text(labelTitle, title != nullptr ? title : "");
  const char* message = notification.Message();
  lv_label_set_text(labelBody, message);
  const lv_coord_t textHeight = lv_obj_get_height(labelBody);
  scrollMax = textHeight > bodyHeight ? textHeight - bodyHeight : 0;

  // A conversation's messages come oldest first, with an empty line between them. Open at the newest
  // one, the way a chat does: its first line at the top of the window, or the end of the text if
  // what follows does not fill the window. The older ones are a swipe down away.
  lv_coord_t start = 0;
  const char* newest = nullptr;
  for (const char* at = message; *at != '\0'; at++) {
    if (at[0] == '\n' && at[1] == '\n' && at[2] != '\0') {
      newest = at + 2;
    }
  }
  if (newest != nullptr) {
    // The label counts letters, not bytes
    uint32_t letters = 0;
    for (const char* at = message; at < newest; at++) {
      if ((*at & 0xC0) != 0x80) {
        letters++;
      }
    }
    lv_point_t position;
    lv_label_get_letter_pos(labelBody, letters, &position);
    start = position.y;
  }
  ScrollTo(start < scrollMax ? start : scrollMax, false);

  lv_label_set_text_fmt(labelCounter, "%d/%d", index + 1, count);
  lv_obj_realign(labelCounter);
}

void Notifications::ScrollTo(lv_coord_t offset, bool animate) {
  lv_anim_del(labelBody, nullptr);
  if (animate) {
    lv_anim_t slide;
    lv_anim_init(&slide);
    lv_anim_set_var(&slide, labelBody);
    lv_anim_set_exec_cb(&slide, SetLabelY);
    lv_anim_set_values(&slide, -scrollOffset, -offset);
    lv_anim_set_time(&slide, scrollTimeMs);
    lv_anim_start(&slide);
  } else {
    lv_obj_set_y(labelBody, -offset);
  }
  scrollOffset = offset;

  // The bar shows how much of the message the window covers, and where in it the window is
  if (scrollMax <= 0) {
    lv_obj_set_hidden(scrollBar, true);
    return;
  }
  lv_coord_t barHeight = static_cast<lv_coord_t>((bodyHeight * bodyHeight) / (bodyHeight + scrollMax));
  if (barHeight < scrollBarMinHeight) {
    barHeight = scrollBarMinHeight;
  }
  const lv_coord_t barY = static_cast<lv_coord_t>(bodyY + ((bodyHeight - barHeight) * scrollOffset) / scrollMax);
  lv_obj_set_height(scrollBar, barHeight);
  lv_obj_set_y(scrollBar, barY);
  lv_obj_set_hidden(scrollBar, false);
}

bool Notifications::OnTouchEvent(Pinetime::Applications::TouchEvents event) {
  switch (event) {
    case TouchEvents::SwipeUp:
      // Read on. With nothing further down, the swipe does nothing.
      if (scrollOffset < scrollMax) {
        const lv_coord_t next = scrollOffset + scrollStep;
        ScrollTo(next < scrollMax ? next : scrollMax, true);
      }
      return true;
    case TouchEvents::SwipeDown:
      if (scrollOffset > 0) {
        const lv_coord_t previous = scrollOffset - scrollStep;
        ScrollTo(previous > 0 ? previous : 0, true);
        return true;
      }
      // Already at the top: the swipe leaves the screen
      return false;
    default:
      return false;
  }
}
