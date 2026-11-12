package io.github.bahadirkiziltepe.watchhelper;

import android.service.notification.NotificationListenerService;

/**
 * Holding notification access is what lets the app see Spotify's player, and it is also what makes
 * Android keep the app running in the background and start it again after a restart.
 *
 * <p>This version does not look at any notification: the notification callbacks are deliberately
 * not overridden.
 */
public class MediaListenerService extends NotificationListenerService {
  @Override
  public void onListenerConnected() {
    Core.get(this).onListenerConnected();
  }

  @Override
  public void onListenerDisconnected() {
    Core.get(this).onListenerDisconnected();
  }
}
