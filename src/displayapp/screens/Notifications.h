#pragma once

#include <lvgl/lvgl.h>
#include <FreeRTOS.h>
#include <cstdint>
#include "displayapp/screens/Screen.h"
#include "displayapp/widgets/TapPad.h"
#include "components/ble/NotificationManager.h"
#include "systemtask/SystemTask.h"
#include "systemtask/WakeLock.h"

namespace Pinetime {
  namespace Controllers {
    class MotorController;
    class MusicService;
    class Settings;
  }

  namespace Applications {
    namespace Screens {

      /**
       * The notifications the phone is showing, one at a time.
       *
       * - Top line: the app it came from ("Text", "WhatsApp", "Discord") and the time it arrived.
       * - Then who it is from, the message, and at the bottom which one of how many this is.
       *   A conversation's unread messages arrive as one notification, with a blank line between them.
       * - A tap goes to the next notification, a double tap goes back. Both wrap around.
       * - Text that does not fit scrolls: swipe up to read on, swipe down to go back up.
       * - The screen lies above the main screen and below the settings. Swiping up at the end of the
       *   text leads back to the main screen, swiping down at its top leads on to the settings, and
       *   the contacts are one swipe to the left.
       * - Holding the screen clears the notification on the phone. A notification goes away when the
       *   phone takes it back, which the Watch Helper phone app does when it is cleared or read on the
       *   phone - or was just cleared from here. Notices from the watch itself have no phone behind
       *   them; they go once this screen has been opened and closed, or when they are held.
       *
       * In Preview mode the screen has come up by itself for a new notification. It closes again after
       * a few seconds unless the screen is touched; from then on it behaves like the normal one.
       */
      class Notifications : public Screen {
      public:
        enum class Modes { Normal, Preview };
        Notifications(Pinetime::Controllers::NotificationManager& notificationManager,
                      Pinetime::Controllers::MotorController& motorController,
                      Pinetime::Controllers::Settings& settingsController,
                      Pinetime::Controllers::MusicService& musicService,
                      System::SystemTask& systemTask,
                      Modes mode);
        ~Notifications() override;

        void Refresh() override;
        bool OnTouchEvent(Pinetime::Applications::TouchEvents event) override;

      private:
        void Reload();
        void Show();
        void ScrollTo(lv_coord_t offset, bool animate);

        Pinetime::Controllers::NotificationManager& notificationManager;
        Pinetime::Controllers::MotorController& motorController;
        Pinetime::Controllers::Settings& settingsController;
        Pinetime::Controllers::MusicService& musicService;
        Modes mode;
        System::WakeLock wakeLock;

        lv_obj_t* labelSource;
        lv_obj_t* labelTime;
        lv_obj_t* labelTitle;
        lv_obj_t* bodyBox;
        lv_obj_t* labelBody;
        lv_obj_t* scrollBar;
        lv_obj_t* labelCounter;
        lv_obj_t* labelEmpty;
        Widgets::TapPad tapPad;

        // Which notification is showing: its place in the list (0 is the newest) and its id,
        // so the screen can stay on it when the list changes around it
        uint8_t index = 0;
        uint8_t count = 0;
        bool showing = false;
        Pinetime::Controllers::NotificationManager::Notification::Id currentId = 0;
        uint16_t currentPhoneId = 0;
        uint32_t shownRevision = 0;

        // How far the message is scrolled, and how far it can be
        lv_coord_t scrollOffset = 0;
        lv_coord_t scrollMax = 0;

        // How many pieces of the buzz for a new notification have been started, and when the last one was
        uint8_t buzzPieces = 0;
        TickType_t buzzTick = 0;

        // Preview mode: when it came up, whether a touch has made it stay,
        // and whether the tap that made it stay still has to be swallowed
        TickType_t shownTick = 0;
        bool pinned = false;
        bool swallowTap = false;

        // The phone was asked to clear the notification that is showing and has not done it yet
        bool clearing = false;
        TickType_t clearingTick = 0;

        lv_task_t* taskRefresh;
      };
    }
  }
}
