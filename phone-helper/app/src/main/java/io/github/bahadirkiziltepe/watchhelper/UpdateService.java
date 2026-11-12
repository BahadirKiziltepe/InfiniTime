package io.github.bahadirkiziltepe.watchhelper;

import android.app.Activity;

import no.nordicsemi.android.dfu.DfuBaseService;

/**
 * Sends a watch build to the watch. All the work is done by Nordic Semiconductor's update library,
 * the same one Gadgetbridge and nRF Connect use for this watch.
 */
public class UpdateService extends DfuBaseService {
  @Override
  protected Class<? extends Activity> getNotificationTarget() {
    return MainActivity.class;
  }

  @Override
  protected boolean isDebug() {
    return false;
  }
}
