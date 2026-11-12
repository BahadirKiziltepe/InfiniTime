#pragma once
#include <cstdint>

namespace Pinetime {
  namespace Applications {
    namespace Display {
      enum class Messages : uint8_t {
        GoToSleep,
        GoToAOD,
        GoToRunning,
        UpdateBleConnection,
        TouchEvent,
        ButtonPushed,
        ButtonLongPressed,
        ButtonLongerPressed,
        ButtonDoubleClicked,
        NewNotification,
        TimerDone,
        BleFirmwareUpdateStarted,
        // Resets the screen timeout timer when awake
        // Does nothing when asleep
        NotifyDeviceActivity,
        ShowPairingKey,
        AlarmTriggered,
        Chime,
        BleRadioEnableToggle,
        // The watch was shaken while awake. Turns the screen off if the main screen is showing.
        ShakeToSleep,
        // The phone is ringing: bring up the call screen
        IncomingCall,
        WaitingMark,
        // A battery has run low: buzz twice (at 15 %) or three times (at 5 %)
        BatteryLow,
        BatteryCritical,
      };
    }
  }
}
