#pragma once

#include <memory>

#include <FreeRTOS.h>
#include <queue.h>
#include <task.h>
#include <timers.h>
#include <heartratetask/HeartRateTask.h>
#include <components/settings/Settings.h>
#include <drivers/Bma421.h>
#include <drivers/PinMap.h>
#include <components/motion/MotionController.h>

#include "systemtask/SystemMonitor.h"
#include "components/ble/NimbleController.h"
#include "components/ble/NotificationManager.h"
#include "components/stopwatch/StopWatchController.h"
#include "components/alarm/AlarmController.h"
#include "components/battery/LowBatteryWatch.h"
#include "components/fs/FS.h"
#include "touchhandler/TouchHandler.h"
#include "buttonhandler/ButtonHandler.h"
#include "buttonhandler/ButtonActions.h"

#ifdef PINETIME_IS_RECOVERY
  #include "displayapp/DisplayAppRecovery.h"
#else
  #include "components/settings/Settings.h"
  #include "displayapp/DisplayApp.h"
#endif

#include "drivers/Watchdog.h"
#include "systemtask/Messages.h"

extern std::chrono::time_point<std::chrono::system_clock, std::chrono::nanoseconds> NoInit_BackUpTime;

namespace Pinetime {
  namespace Drivers {
    class Cst816S;
    class SpiMaster;
    class SpiNorFlash;
    class St7789;
    class TwiMaster;
    class Hrs3300;
  }

  namespace Controllers {
    class Battery;
    class TouchHandler;
    class ButtonHandler;
  }

  namespace System {
    class SystemTask {
    public:
      enum class SystemTaskState { Sleeping, Running, GoingToSleep, AODSleeping };
      SystemTask(Drivers::SpiMaster& spi,
                 Pinetime::Drivers::SpiNorFlash& spiNorFlash,
                 Drivers::TwiMaster& twiMaster,
                 Drivers::Cst816S& touchPanel,
                 Controllers::Battery& batteryController,
                 Controllers::Ble& bleController,
                 Controllers::DateTime& dateTimeController,
                 Controllers::StopWatchController& stopWatchController,
                 Controllers::AlarmController& alarmController,
                 Drivers::Watchdog& watchdog,
                 Pinetime::Controllers::NotificationManager& notificationManager,
                 Pinetime::Drivers::Hrs3300& heartRateSensor,
                 Pinetime::Controllers::MotionController& motionController,
                 Pinetime::Drivers::Bma421& motionSensor,
                 Controllers::Settings& settingsController,
                 Pinetime::Controllers::HeartRateController& heartRateController,
                 Pinetime::Applications::DisplayApp& displayApp,
                 Pinetime::Applications::HeartRateTask& heartRateApp,
                 Pinetime::Controllers::FS& fs,
                 Pinetime::Controllers::TouchHandler& touchHandler,
                 Pinetime::Controllers::ButtonHandler& buttonHandler);

      void Start();
      void PushMessage(Messages msg);

      bool IsSleepDisabled() {
        return wakeLocksHeld > 0;
      }

      Pinetime::Controllers::NimbleController& nimble() {
        return nimbleController;
      };

      Pinetime::Controllers::NotificationManager& GetNotificationManager() {
        return notificationManager;
      };

      Pinetime::Controllers::Settings& GetSettings() {
        return settingsController;
      };

      bool IsSleeping() const {
        return state != SystemTaskState::Running;
      }

    private:
      TaskHandle_t taskHandle;

      Pinetime::Drivers::SpiMaster& spi;
      Pinetime::Drivers::SpiNorFlash& spiNorFlash;
      Pinetime::Drivers::TwiMaster& twiMaster;
      Pinetime::Drivers::Cst816S& touchPanel;
      Pinetime::Controllers::Battery& batteryController;

      Pinetime::Controllers::Ble& bleController;
      Pinetime::Controllers::DateTime& dateTimeController;
      Pinetime::Controllers::StopWatchController& stopWatchController;
      Pinetime::Controllers::AlarmController& alarmController;
      QueueHandle_t systemTasksMsgQueue;
      Pinetime::Drivers::Watchdog& watchdog;
      Pinetime::Controllers::NotificationManager& notificationManager;
      Pinetime::Drivers::Hrs3300& heartRateSensor;
      Pinetime::Drivers::Bma421& motionSensor;
      Pinetime::Controllers::Settings& settingsController;
      Pinetime::Controllers::HeartRateController& heartRateController;
      Pinetime::Controllers::MotionController& motionController;

      Pinetime::Applications::DisplayApp& displayApp;
      Pinetime::Applications::HeartRateTask& heartRateApp;
      Pinetime::Controllers::FS& fs;
      Pinetime::Controllers::TouchHandler& touchHandler;
      Pinetime::Controllers::ButtonHandler& buttonHandler;
      Pinetime::Controllers::NimbleController nimbleController;

      static void Process(void* instance);
      void Work();
      bool isBleDiscoveryTimerRunning = false;
      uint8_t bleDiscoveryTimer = 0;
      TimerHandle_t measureBatteryTimer;
      uint8_t wakeLocksHeld = 0;
      SystemTaskState state = SystemTaskState::Running;

      void HandleButtonAction(Controllers::ButtonActions action);
      bool fastWakeUpDone = false;

      void GoToRunning();
      void GoToSleep();
      void UpdateMotion();
      void WarnLowBattery(bool critical);
      bool AlertsOn();

      // Tells when the watch's own battery has run low
      Pinetime::Controllers::LowBatteryWatch lowBattery;

      // Movements that turn the screen off as well as on (see UpdateMotion). Motion is read every 100 ms.
      static constexpr uint8_t motionSettleUpdates = 7;
      static constexpr uint8_t raiseHoldoffUpdates = 10;
      // Updates in a row with the shake speed at or under the wake setting
      uint8_t calmUpdates = 0;
      // Updates in a row without a shake, counted since the watch woke up or was last touched
      uint8_t steadyUpdates = 0;
      // Updates left during which raising the wrist does not wake the watch
      uint8_t raiseWakeHoldoff = 0;
      bool shakeWakeBlocked = false;
      static constexpr TickType_t batteryMeasurementPeriod = pdMS_TO_TICKS(10 * 60 * 1000);

      SystemMonitor monitor;
    };
  }
}
