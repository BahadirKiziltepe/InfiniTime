package io.github.bahadirkiziltepe.watchhelper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * A foreground service that does nothing but be there.
 *
 * <p>Since Android 17 an app may only change a volume or the phone's sound mode, or make a sound,
 * while its screen is open or while it runs a foreground service; in the background such requests
 * are dropped without a word. The watch asks for exactly these things while the phone is in a
 * pocket: volume up and down, the sound mode, and ringing so the phone can be found. So the app
 * keeps this service running for as long as it is alive. Its type, "connected device", is the one
 * Android has for apps that work with a watch.
 *
 * <p>Android wants a notification for every foreground service. The app does not ask for
 * permission to show notifications, so nothing appears among the phone's notifications; the
 * service is only listed where Android lists the apps that are active.
 */
public class LinkService extends Service {
  private static final String CHANNEL = "watch-link";
  private static final int NOTIFICATION_ID = 7;

  private static volatile boolean running;
  private static volatile String problem = "";

  /** True while the service is in the foreground. */
  static boolean running() {
    return running;
  }

  /** Why it is not, in a few words, or nothing. Never anything personal. */
  static String problem() {
    return problem;
  }

  /** Starts the service unless it is running. Safe to call again and again. */
  static void ensure(Context context) {
    if (running) {
      return;
    }
    try {
      context.startService(new Intent(context, LinkService.class));
    } catch (RuntimeException e) {
      // Android does not let an app that is entirely in the background start a service. The next
      // call, when the screen opens or the watch asks for something, tries again.
      problem = "could not be started (" + e.getClass().getSimpleName() + ")";
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (running) {
      return START_STICKY;
    }
    try {
      NotificationManager manager = getSystemService(NotificationManager.class);
      if (manager != null) {
        NotificationChannel channel =
            new NotificationChannel(CHANNEL, "Connection to the watch", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
      }
      PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
          PendingIntent.FLAG_IMMUTABLE);
      Notification notification = new Notification.Builder(this, CHANNEL)
          .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
          .setContentTitle("Watch Helper")
          .setContentText("Connected to the watch.")
          .setOngoing(true)
          .setContentIntent(open)
          .build();
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
      running = true;
      problem = "";
      return START_STICKY;
    } catch (RuntimeException e) {
      // Refused: for example because the Nearby devices permission is not there yet, which this
      // kind of service needs. It is tried again later.
      problem = "was refused (" + e.getClass().getSimpleName() + ")";
      stopSelf();
      return START_NOT_STICKY;
    }
  }

  @Override
  public void onDestroy() {
    running = false;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
