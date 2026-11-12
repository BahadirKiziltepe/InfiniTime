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
        static constexpr int nScreens = 4;

        // Firmware and Over-the-air come first because every new build needs them.
        static constexpr std::array<List::Applications, entriesPerScreen * nScreens> entries {{
          {Symbols::check, "Firmware", Apps::FirmwareValidation},
          {Symbols::shieldAlt, "Over-the-air", Apps::SettingOTA},
          {Symbols::sun, "Display", Apps::SettingDisplay},
          {Symbols::eye, "Wake Up", Apps::SettingWakeUp},

          {Symbols::tachometer, "Shake Calib.", Apps::SettingShakeThreshold},
          {Symbols::clock, "Time format", Apps::SettingTimeFormat},
          {Symbols::clock, "Date & Time", Apps::SettingSetDateTime},
          {Symbols::clock, "Chimes", Apps::SettingChimes},

          {Symbols::batteryHalf, "Battery", Apps::BatteryInfo},
          {Symbols::bluetooth, "Bluetooth", Apps::SettingBluetooth},
          {Symbols::shoe, "Steps", Apps::SettingSteps},
          {Symbols::heartBeat, "Heartrate", Apps::SettingHeartRate},

          {Symbols::cloudSunRain, "Weather", Apps::SettingWeatherFormat},
          {Symbols::home, "Watch face", Apps::SettingWatchFace},
          {Symbols::list, "About", Apps::SysInfo},
        }};
        ScreenList<nScreens> screens;
      };
    }
  }
}
