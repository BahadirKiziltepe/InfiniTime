if(DEFINED ENABLE_USERAPPS)
    set(USERAPP_TYPES ${ENABLE_USERAPPS} CACHE STRING "List of user apps to build into the firmware")
else ()
    # This firmware has no app launcher, so none of these can be opened from the UI. The quick
    # settings page shows today's step count itself. DisplayApp loads the Alarm and Timer screens
    # when an alarm or timer fires, so they have to stay in the list; Steps is kept with them.
    # The other stock apps (StopWatch, HeartRate, Music, Paint, Paddle, Twos, Dice, Metronome,
    # Navigation, Calculator, Weather) stay in the source tree but are left out of the build.
    set(DEFAULT_USER_APP_TYPES "Apps::Steps")
    set(DEFAULT_USER_APP_TYPES "${DEFAULT_USER_APP_TYPES}, Apps::Alarm")
    set(DEFAULT_USER_APP_TYPES "${DEFAULT_USER_APP_TYPES}, Apps::Timer")
    set(USERAPP_TYPES "${DEFAULT_USER_APP_TYPES}" CACHE STRING "List of user apps to build into the firmware")
endif ()

if(DEFINED ENABLE_WATCHFACES)
    set(WATCHFACE_TYPES ${ENABLE_WATCHFACES} CACHE STRING "List of watch faces to build into the firmware")
else()
    # The only watch face is the main screen (clock + music remote).
    set(DEFAULT_WATCHFACE_TYPES "WatchFace::Remote")
    set(WATCHFACE_TYPES "${DEFAULT_WATCHFACE_TYPES}" CACHE STRING "List of watch faces to build into the firmware")
endif()

add_library(infinitime_apps INTERFACE)
target_sources(infinitime_apps INTERFACE "${CMAKE_CURRENT_BINARY_DIR}/Apps.h")
target_include_directories(infinitime_apps INTERFACE "${CMAKE_CURRENT_BINARY_DIR}/")

# Generate the list of user apps to be compiled into the firmware
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/Apps.h.in ${CMAKE_CURRENT_BINARY_DIR}/Apps.h)
