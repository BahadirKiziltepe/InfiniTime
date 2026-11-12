#include "displayapp/screens/settings/SettingNotifications.h"

#include <lvgl/lvgl.h>

#include "displayapp/DisplayApp.h"
#include "displayapp/screens/Styles.h"
#include "displayapp/screens/Screen.h"
#include "displayapp/screens/Symbols.h"

using namespace Pinetime::Applications::Screens;

namespace {
  struct Option {
    Pinetime::Controllers::Settings::Notification status;
    const char* name;
  };

  constexpr std::array<Option, 3> options = {{
    {Pinetime::Controllers::Settings::Notification::On, "On"},
    {Pinetime::Controllers::Settings::Notification::Off, "Off"},
    {Pinetime::Controllers::Settings::Notification::Sleep, "Sleep"},
  }};

  std::array<CheckboxList::Item, CheckboxList::MaxItems> CreateOptionArray() {
    std::array<Pinetime::Applications::Screens::CheckboxList::Item, CheckboxList::MaxItems> optionArray;
    for (size_t i = 0; i < CheckboxList::MaxItems; i++) {
      if (i >= options.size()) {
        optionArray[i].name = "";
        optionArray[i].enabled = false;
      } else {
        optionArray[i].name = options[i].name;
        optionArray[i].enabled = true;
      }
    }
    return optionArray;
  }

  uint32_t GetDefaultOption(Pinetime::Controllers::Settings::Notification currentOption) {
    for (size_t i = 0; i < options.size(); i++) {
      if (options[i].status == currentOption) {
        return i;
      }
    }
    return 0;
  }
}

SettingNotifications::SettingNotifications(Pinetime::Controllers::Settings& settingsController)
  : checkboxList(
      0,
      1,
      "Notifications",
      Symbols::bell,
      GetDefaultOption(settingsController.GetNotificationStatus()),
      [&settings = settingsController](uint32_t index) {
        settings.SetNotificationStatus(options[index].status);
        settings.SaveSettings();
      },
      CreateOptionArray()) {
}

SettingNotifications::~SettingNotifications() {
  lv_obj_clean(lv_scr_act());
}
