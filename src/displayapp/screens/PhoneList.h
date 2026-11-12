#pragma once

#include <lvgl/lvgl.h>
#include <FreeRTOS.h>
#include <array>
#include <cstdint>
#include "displayapp/screens/Screen.h"
#include "displayapp/widgets/TapPad.h"
#include "components/ble/PhoneLists.h"

namespace Pinetime {
  namespace Controllers {
    class Ble;
    class MotorController;
    class MusicService;
    class NotificationManager;
  }

  namespace Applications {
    class DisplayApp;

    namespace Screens {

      /**
       * One page of a list that lives on the phone: the play queue, the playlists, the songs of a
       * playlist, the people the wearer chose to call from the watch, or the phone's call history.
       *
       * - Swipe up for the next page, swipe down for the one before.
       * - Queue: only shown. It hangs below the main screen: swiping down on its first page goes back
       *   there. The playlists are one swipe to the left further on, the history one swipe to the right.
       * - Playlists: a tap opens the songs, a hold plays the playlist.
       * - Songs: a tap adds the song to the queue, a hold plays it in its playlist.
       * - History (the songs played last, to the left of the queue): a tap adds the song to the
       *   queue, a hold plays it on its own, after which Spotify carries on with songs like it.
       * - Contacts: a tap asks "Call ...?". A second tap makes the phone place the call; holding the
       *   screen, or a swipe, takes the question back. It also goes away by itself after ten seconds.
       * - Calls (the call history, to the right of the contacts): who it was, and under that whether
       *   the call was made, received or missed and when. Missed calls are red. A tap asks
       *   "Call ...?" exactly as the contacts do.
       * - A star in front of a song means Spotify picked it itself (Smart Shuffle), the play sign
       *   means it is the song that is playing.
       *
       * The phone answers every request with a page (see PhoneLists) and says what became of
       * everything it was asked to do; that answer is shown for a moment where the title is.
       */
      class PhoneList : public Screen {
      public:
        using Kinds = Pinetime::Controllers::PhoneLists::Kinds;

        PhoneList(DisplayApp* app,
                  Kinds kind,
                  Pinetime::Controllers::MusicService& musicService,
                  Pinetime::Controllers::NotificationManager& notificationManager,
                  const Pinetime::Controllers::Ble& bleController,
                  Pinetime::Controllers::MotorController& motorController);
        ~PhoneList() override;

        void Refresh() override;
        bool OnTouchEvent(Pinetime::Applications::TouchEvents event) override;

      private:
        using Lists = Pinetime::Controllers::PhoneLists;

        void Ask(uint16_t offset);
        void Act(uint8_t action, uint16_t index, const char* doing);
        void TakeNews(TickType_t now);
        void OnTap(Widgets::TapPad::Events tap, TickType_t now);
        void ShowToast(const char* text, lv_color_t color, TickType_t now);
        void StopConfirming();
        int RowAt(lv_point_t point) const;
        void LightRow(int row);
        void Draw();
        void DrawTitle();

        DisplayApp* app;
        Kinds kind;
        Pinetime::Controllers::MusicService& musicService;
        Pinetime::Controllers::NotificationManager& notificationManager;
        const Pinetime::Controllers::Ble& bleController;
        Pinetime::Controllers::MotorController& motorController;
        Lists& lists;

        lv_obj_t* labelTitle;
        lv_obj_t* labelPage;
        std::array<lv_obj_t*, Lists::RowsPerPage> rowBox;
        std::array<lv_obj_t*, Lists::RowsPerPage> rowLabel;
        lv_obj_t* labelNote;
        lv_obj_t* labelHint;
        Widgets::TapPad tapPad;

        // The page on the screen, and whether there is one yet
        Lists::Page page {};
        bool havePage = false;
        // Where a page from the phone is copied to before it is known to be the one that was asked for.
        // It is kept here because it is too big for the display task's stack.
        Lists::Page fresh {};
        // The playlist these songs belong to
        uint16_t parent = 0;

        // The page that was asked for last, and whether the phone still owes it
        uint16_t wantedOffset = 0;
        uint8_t token = 0;
        bool waiting = false;
        bool unanswered = false;
        TickType_t askedTick = 0;
        uint32_t seenRevision = 0;
        bool connected = false;

        // Something the phone was asked to do and has not reported on yet
        bool acting = false;
        TickType_t actingTick = 0;

        // A short message where the title is
        std::array<char, Lists::ResultSize + 1> toast {};
        bool toastVisible = false;
        lv_color_t toastColor = LV_COLOR_WHITE;
        TickType_t toastTick = 0;

        // Contacts and calls: the person the wearer is being asked about, and whether the call was asked for
        bool confirming = false;
        bool calling = false;
        uint16_t confirmIndex = 0;
        std::array<char, Lists::RowSize + 1> confirmName {};
        TickType_t confirmTick = 0;

        int litRow = -1;
        bool needsDraw = false;

        lv_task_t* taskRefresh;
      };
    }
  }
}
