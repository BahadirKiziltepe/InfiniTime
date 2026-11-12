package io.github.bahadirkiziltepe.watchhelper;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;

/**
 * Reads the small icon an app puts on its own notification and turns it into the little picture
 * the watch shows next to its battery numbers.
 *
 * <p>The icon is taken from the notification at the moment it is shown on the phone, the same way
 * Android's own status bar gets it. Nothing of it is kept in this app's code.
 */
final class MarkIcon {
  /** Width and height of the picture on the watch, in pixels. */
  static final int SIZE = PhoneFrames.MARK_ICON_SIZE;
  /** The icon is first drawn this large, to find out how much of its square it really fills. */
  private static final int BIG = 96;
  /** Coverage below this (of 255) counts as empty when looking for the icon's edges. */
  private static final int FAINT = 24;

  private MarkIcon() {
  }

  /**
   * The notification's small icon as SIZE rows of SIZE pixels, two pixels to a byte, each pixel
   * saying how much of it the icon covers (0 to 15). Null if there is no icon to be had.
   */
  static byte[] render(Context app, StatusBarNotification sbn) {
    try {
      Notification n = sbn.getNotification();
      Icon icon = n == null ? null : n.getSmallIcon();
      Drawable drawable = icon == null ? null : icon.loadDrawable(app);
      if (drawable == null) {
        return null;
      }

      // Small icons come with empty space around them. Find the part that is drawn.
      Bitmap big = Bitmap.createBitmap(BIG, BIG, Bitmap.Config.ARGB_8888);
      drawable.setBounds(0, 0, BIG, BIG);
      drawable.draw(new Canvas(big));
      int[] pixels = new int[BIG * BIG];
      big.getPixels(pixels, 0, BIG, 0, 0, BIG, BIG);
      big.recycle();
      int left = BIG;
      int top = BIG;
      int right = -1;
      int bottom = -1;
      for (int y = 0; y < BIG; y++) {
        for (int x = 0; x < BIG; x++) {
          if ((pixels[y * BIG + x] >>> 24) > FAINT) {
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
          }
        }
      }
      if (right < left || bottom < top) {
        return null;
      }
      int width = right - left + 1;
      int height = bottom - top + 1;
      if (width == BIG && height == BIG && solid(pixels)) {
        // A filled square says nothing. The watch keeps its dot.
        return null;
      }

      // Draw it again, so that the drawn part fills the watch's picture.
      float scale = SIZE / (float) Math.max(width, height);
      Bitmap small = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(small);
      canvas.translate((SIZE - width * scale) / 2f - left * scale, (SIZE - height * scale) / 2f - top * scale);
      canvas.scale(scale, scale);
      drawable.setBounds(0, 0, BIG, BIG);
      drawable.draw(canvas);
      int[] shown = new int[SIZE * SIZE];
      small.getPixels(shown, 0, SIZE, 0, 0, SIZE, SIZE);
      small.recycle();

      byte[] out = new byte[SIZE * (SIZE / 2)];
      for (int y = 0; y < SIZE; y++) {
        for (int x = 0; x < SIZE; x++) {
          int strength = ((shown[y * SIZE + x] >>> 24) + 8) / 17;
          int at = y * (SIZE / 2) + x / 2;
          out[at] |= (byte) ((x & 1) == 0 ? strength << 4 : strength);
        }
      }
      return out;
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static boolean solid(int[] pixels) {
    for (int pixel : pixels) {
      if ((pixel >>> 24) < 200) {
        return false;
      }
    }
    return true;
  }

  /**
   * The colour the app asks for its icon to be shown in, as 0xRRGGBB, or 0 if it names none or
   * one too dark to see on the watch's black screen.
   */
  static int color(StatusBarNotification sbn) {
    Notification n = sbn.getNotification();
    int argb = n == null ? 0 : n.color;
    int red = (argb >> 16) & 0xff;
    int green = (argb >> 8) & 0xff;
    int blue = argb & 0xff;
    int brightness = (red * 299 + green * 587 + blue * 114) / 1000;
    return argb == Notification.COLOR_DEFAULT || brightness < 90 ? 0 : (red << 16) | (green << 8) | blue;
  }
}
