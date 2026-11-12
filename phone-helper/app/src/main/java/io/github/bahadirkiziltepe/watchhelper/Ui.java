package io.github.bahadirkiziltepe.watchhelper;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * How the app looks: its colours for day and night, and the handful of pieces every part of the
 * screen is put together from (cards, lines of status, buttons).
 *
 * <p>Everything is made in code, with the phone's own widgets and font, so the app needs no
 * design library and stays small.
 */
final class Ui {
  static final int FILLED = 0;
  static final int TONAL = 1;
  static final int PLAIN = 2;

  final Context context;
  final boolean night;
  final int background;
  final int surface;
  final int raised;
  final int text;
  final int soft;
  final int faint;
  final int line;
  final int accent;
  final int onAccent;
  final int good;
  final int warn;
  final int bad;

  private final float density;
  private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

  Ui(Context context) {
    this.context = context;
    density = context.getResources().getDisplayMetrics().density;
    night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
    if (night) {
      background = 0xFF000000;
      surface = 0xFF17181A;
      raised = 0xFF26282B;
      text = 0xFFF1F3F4;
      soft = 0xFFA9AEB4;
      faint = 0xFF757B82;
      line = 0xFF2A2C2F;
      accent = 0xFF1ED760;
      onAccent = 0xFF04220E;
      good = 0xFF1ED760;
      warn = 0xFFF5B642;
      bad = 0xFFFF6B6B;
    } else {
      background = 0xFFF1F2F4;
      surface = 0xFFFFFFFF;
      raised = 0xFFE9EBEE;
      text = 0xFF15181C;
      soft = 0xFF5A616A;
      faint = 0xFF8B9299;
      line = 0xFFE4E6E9;
      accent = 0xFF12833B;
      onAccent = 0xFFFFFFFF;
      good = 0xFF12833B;
      warn = 0xFFA86400;
      bad = 0xFFC62F2F;
    }
  }

  int dp(float value) {
    return Math.round(value * density);
  }

  // ---------------------------------------------------------------- reading the app's status lines

  /** The app's status lines start with a tick or a cross. The screen shows a coloured dot instead. */
  static String plain(String line) {
    if (line == null) {
      return "";
    }
    if (line.startsWith("✓ ") || line.startsWith("✗ ") || line.startsWith("⚠ ")) {
      return line.substring(2);
    }
    return line;
  }

  /** The colour for a status line: good, bad, under way, or nothing in particular. */
  int tone(String line) {
    if (line == null) {
      return faint;
    }
    if (line.startsWith("✓")) {
      return good;
    }
    if (line.startsWith("✗")) {
      return bad;
    }
    return line.contains("…") ? warn : faint;
  }

  // ---------------------------------------------------------------- text

  TextView text(String content, float sp, int color) {
    TextView view = new TextView(context);
    view.setText(content);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
    view.setTextColor(color);
    view.setLineSpacing(0f, 1.12f);
    return view;
  }

  /** The app's name at the top. */
  TextView title(String content) {
    TextView view = text(content, 30, text);
    view.setTypeface(Typeface.DEFAULT_BOLD);
    return view;
  }

  /** The caption above a card. */
  TextView caption(String content) {
    TextView view = text(content, 13.5f, soft);
    view.setTypeface(medium);
    view.setPadding(dp(8), dp(26), dp(8), dp(8));
    return view;
  }

  /** The main line of something. */
  TextView strong(String content) {
    TextView view = text(content, 16.5f, text);
    view.setTypeface(medium);
    return view;
  }

  TextView body(String content) {
    return text(content, 15, text);
  }

  TextView note(String content) {
    return text(content, 14, soft);
  }

  /** Something to copy exactly, in a box of its own. */
  TextView code(String content) {
    TextView view = text(content, 13.5f, text);
    view.setTypeface(Typeface.MONOSPACE);
    view.setTextIsSelectable(true);
    view.setBackground(round(raised, 12));
    view.setPadding(dp(12), dp(10), dp(12), dp(10));
    return view;
  }

  /** A small label with a rounded background, for a fact such as "Shuffle: on". */
  TextView chip(String content) {
    TextView view = text(content, 13, soft);
    view.setBackground(round(raised, 14));
    view.setPadding(dp(11), dp(5), dp(11), dp(6));
    return view;
  }

  EditText field(String hint) {
    EditText view = new EditText(context);
    view.setHint(hint);
    view.setSingleLine(true);
    view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    view.setTextColor(text);
    view.setHintTextColor(faint);
    view.setBackground(round(raised, 14));
    view.setPadding(dp(14), dp(12), dp(14), dp(12));
    return view;
  }

  // ---------------------------------------------------------------- containers

  LinearLayout column() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  LinearLayout row() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.HORIZONTAL);
    view.setGravity(Gravity.CENTER_VERTICAL);
    return view;
  }

  /** A rounded panel that holds one subject. */
  LinearLayout card() {
    LinearLayout view = column();
    view.setBackground(round(surface, 24));
    view.setPadding(dp(18), dp(16), dp(18), dp(16));
    return view;
  }

  /** A thin line between two things in one card. */
  View divider() {
    View view = new View(context);
    view.setBackgroundColor(line);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
    params.topMargin = dp(14);
    params.bottomMargin = dp(14);
    view.setLayoutParams(params);
    return view;
  }

  /** Buttons next to each other, going on to a new line when they do not fit. */
  Flow flow(View... children) {
    Flow view = new Flow(context, dp(10), dp(10));
    for (View child : children) {
      view.addView(child);
    }
    return view;
  }

  /** Adds a view to a column, this far below what is above it. */
  <T extends View> T add(LinearLayout parent, T view, float topDp) {
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.topMargin = dp(topDp);
    parent.addView(view, params);
    return view;
  }

  // ---------------------------------------------------------------- buttons

  TextView button(String label, int style, View.OnClickListener onClick) {
    int fill = style == FILLED ? accent : style == TONAL ? raised : 0x00000000;
    int ink = style == FILLED ? onAccent : style == TONAL ? text : accent;
    TextView view = text(label, 15, ink);
    view.setTypeface(medium);
    view.setGravity(Gravity.CENTER);
    view.setSingleLine(true);
    view.setMinHeight(dp(44));
    view.setMinWidth(dp(style == PLAIN ? 48 : 72));
    view.setPadding(dp(style == PLAIN ? 12 : 20), 0, dp(style == PLAIN ? 12 : 20), 0);
    view.setBackground(pressable(fill, 22));
    view.setClickable(true);
    view.setFocusable(true);
    view.setOnClickListener(onClick);
    return view;
  }

  /** A small round button with one sign on it, for the end of a row. */
  TextView iconButton(String sign, String description, View.OnClickListener onClick) {
    TextView view = text(sign, 16, soft);
    view.setGravity(Gravity.CENTER);
    view.setMinHeight(dp(40));
    view.setMinWidth(dp(40));
    view.setBackground(pressable(raised, 20));
    view.setContentDescription(description);
    view.setClickable(true);
    view.setFocusable(true);
    view.setOnClickListener(onClick);
    return view;
  }

  /** A button that cannot be used right now is shown paler. */
  static void enable(View button, boolean on) {
    button.setEnabled(on);
    button.setAlpha(on ? 1f : 0.38f);
  }

  static void show(View view, boolean on) {
    view.setVisibility(on ? View.VISIBLE : View.GONE);
  }

  GradientDrawable round(int color, float radiusDp) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(color);
    shape.setCornerRadius(dp(radiusDp));
    return shape;
  }

  /** A rounded shape that shows the touch spreading over it when pressed. */
  private Drawable pressable(int color, float radiusDp) {
    int touch = night ? 0x33FFFFFF : 0x22000000;
    return new RippleDrawable(ColorStateList.valueOf(touch), round(color, radiusDp), round(0xFFFFFFFF, radiusDp));
  }

  // ---------------------------------------------------------------- a line of status

  /** A coloured dot, a main line next to it and, if wanted, a smaller line below. */
  final class Status {
    final LinearLayout view;
    private final View dot;
    private final TextView main;
    private final TextView detail;

    Status() {
      view = new LinearLayout(context);
      view.setOrientation(LinearLayout.HORIZONTAL);
      dot = new View(context);
      LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(10), dp(10));
      dotParams.topMargin = dp(6);
      dotParams.setMarginEnd(dp(12));
      view.addView(dot, dotParams);
      LinearLayout words = column();
      main = strong("");
      detail = note("");
      words.addView(main);
      LinearLayout.LayoutParams detailParams =
          new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      detailParams.topMargin = dp(2);
      words.addView(detail, detailParams);
      view.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** A short main line, and a smaller line below it if there is one. */
    void set(int color, String mainLine, String detailLine) {
      paint(color);
      main.setText(mainLine);
      show(main, true);
      detail.setText(detailLine == null ? "" : detailLine);
      detail.setTextColor(soft);
      detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
      show(detail, detailLine != null && !detailLine.isEmpty());
    }

    /** A sentence or two next to the dot, with no main line. */
    void say(int color, String sentence) {
      paint(color);
      show(main, false);
      detail.setText(sentence);
      detail.setTextColor(text);
      detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
      show(detail, true);
    }

    private void paint(int color) {
      GradientDrawable shape = new GradientDrawable();
      shape.setShape(GradientDrawable.OVAL);
      shape.setColor(color);
      dot.setBackground(shape);
    }
  }

  // ---------------------------------------------------------------- a row of things that wraps

  /** Lays its children out in a row and starts a new row when the next one does not fit. */
  static final class Flow extends ViewGroup {
    private final int gapX;
    private final int gapY;

    Flow(Context context, int gapX, int gapY) {
      super(context);
      this.gapX = gapX;
      this.gapY = gapY;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      boolean bounded = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED;
      int room = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
      int childWidthSpec = bounded ? MeasureSpec.makeMeasureSpec(Math.max(0, room), MeasureSpec.AT_MOST)
          : MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
      int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
      int x = 0;
      int y = 0;
      int rowHeight = 0;
      int widest = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child.getVisibility() == GONE) {
          continue;
        }
        child.measure(childWidthSpec, childHeightSpec);
        int width = child.getMeasuredWidth();
        if (bounded && x > 0 && x + width > room) {
          x = 0;
          y += rowHeight + gapY;
          rowHeight = 0;
        }
        x += width;
        widest = Math.max(widest, x);
        x += gapX;
        rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
      }
      int width = bounded ? MeasureSpec.getSize(widthSpec) : widest + getPaddingLeft() + getPaddingRight();
      setMeasuredDimension(width, resolveSize(y + rowHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      int room = right - left - getPaddingLeft() - getPaddingRight();
      int x = 0;
      int y = 0;
      int rowHeight = 0;
      for (int i = 0; i < getChildCount(); i++) {
        View child = getChildAt(i);
        if (child.getVisibility() == GONE) {
          continue;
        }
        int width = child.getMeasuredWidth();
        int height = child.getMeasuredHeight();
        if (x > 0 && x + width > room) {
          x = 0;
          y += rowHeight + gapY;
          rowHeight = 0;
        }
        child.layout(getPaddingLeft() + x, getPaddingTop() + y, getPaddingLeft() + x + width, getPaddingTop() + y + height);
        x += width + gapX;
        rowHeight = Math.max(rowHeight, height);
      }
    }
  }
}
