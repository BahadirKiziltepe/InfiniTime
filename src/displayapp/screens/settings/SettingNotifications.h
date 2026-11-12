#pragma once

#include <array>
#include <cstdint>
#include <lvgl/lvgl.h>

#include "components/settings/Settings.h"
#include "displayapp/screens/Screen.h"
#include "displayapp/screens/CheckboxList.h"

namespace Pinetime {

  namespace Applications {
    namespace Screens {

      /**
       * Whether the watch itself buzzes and lights up for notifications, calls and its other
       * alerts: on, off, or "sleep" (off, and the watch does not wake by wrist movement either).
       *
       * This used to be a button on the settings screen. That button now switches the phone's
       * sound mode, so the watch's own switch moved here, into the list.
       */
      class SettingNotifications : public Screen {
      public:
        explicit SettingNotifications(Pinetime::Controllers::Settings& settingsController);
        ~SettingNotifications() override;

      private:
        CheckboxList checkboxList;
      };
    }
  }
}
