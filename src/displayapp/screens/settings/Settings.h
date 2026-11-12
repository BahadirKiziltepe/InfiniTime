#pragma once

#include <array>
#include <memory>
#include "displayapp/screens/Screen.h"
#include "displayapp/screens/ScreenList.h"
#include "displayapp/screens/Symbols.h"
#include "displayapp/screens/List.h"

namespace Pinetime {

  namespace Applications {
    namespace Screens {

      class Settings : public Screen {
      public:
        Settings(DisplayApp* app, Pinetime::Controllers::Settings& settingsController);
        ~Settings() override;

        bool OnTouchEvent(Pinetime::Applications::TouchEvents event) override;

      private:
        DisplayApp* app;
        auto CreateScreenList() const;
        std::unique_ptr<Screen> CreateScreen(unsigned int screenNum) const;

        Controllers::Settings& settingsController;

        static constexpr int entriesPerScreen = 4;

        // Increment this when more space is needed
        static constexpr int nScreens = 2;

        // Only the settings this firmware still needs. Firmware comes first because
        // every new build has to be validated there.
        static constexpr std::array<List::Applications, entriesPerScreen * nScreens> entries {{
          {Symbols::check, "Firmware", Apps::FirmwareValidation},
          {Symbols::shieldAlt, "Over-the-air", Apps::SettingOTA},
          {Symbols::sun, "Display", Apps::SettingDisplay},
          {Symbols::eye, "Wake Up", Apps::SettingWakeUp},

          {Symbols::clock, "Time format", Apps::SettingTimeFormat},
          {Symbols::batteryHalf, "Battery", Apps::BatteryInfo},
          {Symbols::bluetooth, "Bluetooth", Apps::SettingBluetooth},
          {Symbols::list, "About", Apps::SysInfo},
        }};
        ScreenList<nScreens> screens;
      };
    }
  }
}
