package dev.queuepilot.player;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Bounded TV controls and shared styling for the approved queue-first presentation. */
final class TvUi {
  /** Short names for the theme roles the screens use most; the values live in {@link TvTheme}. */
  static final int BG = TvTheme.SURFACE_BASE,
      PANEL = TvTheme.SURFACE_RAISED,
      MUTED = TvTheme.CONTENT_SECONDARY,
      ACCENT = TvTheme.ACCENT_CONTENT,
      WHITE = TvTheme.CONTENT_PRIMARY;

  /**
   * The size a text style is drawn at, in sp at 100%, before the viewer's Display size. Type was
   * far too large on a large panel seen from close by. Body sizes are reduced by a fifth, and
   * every sp above 16 counts a little over half, so a 34 sp page title becomes 22.7 sp while a
   * 16 sp label becomes 12.8 sp: closer to Charcuterie's ramp at 1 rem = 12 dp (page title 19.5,
   * card title 12.75, body 11.25).
   */
  static float typeSize(int size) {
    return size <= 16 ? size * 0.8f : 12.8f + (size - 16) * 0.55f;
  }

  final Context context;

  /** The viewer's Display size, 1.0 at 100%; every dimension and text size goes through it. */
  final float scale;

  TvUi(Context context) {
    this.context = context;
    // Read on every construction, so the activity and each side panel agree on the size.
    this.scale =
        DisplaySize.scale(
            DisplaySize.percent(context.getSharedPreferences("state", Context.MODE_PRIVATE)));
  }

  int dp(float size) {
    return Math.round(size * context.getResources().getDisplayMetrics().density * scale);
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(context);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(context);
    v.setOrientation(LinearLayout.HORIZONTAL);
    return v;
  }

  TextView text(String value, int size, int color) {
    TextView v = new TextView(context);
    v.setText(value);
    v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, typeSize(size) * scale);
    v.setTextColor(color);
    v.setTypeface(TvTheme.regular(context));
    v.setFontFeatureSettings("kern");
    v.setIncludeFontPadding(false);
    return v;
  }

  TextView title(String value, int size) {
    TextView v = text(value, size, WHITE);
    v.setTypeface(medium());
    return v;
  }

  /** Outfit at the medium weight, for titles, labels, and buttons. */
  Typeface medium() {
    return TvTheme.medium(context);
  }

  GradientDrawable shape(int fill, int stroke, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radius));
    // At least two pixels, so a focus outline stays visible at the smallest Display size.
    if (stroke != 0) d.setStroke(Math.max(2, dp(TvTheme.FOCUS_RING_WIDTH)), stroke);
    return d;
  }

  /** A resting card edge: one pixel of {@code border.subtle}. */
  GradientDrawable outlined(int fill, int border, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radius));
    d.setStroke(1, border);
    return d;
  }

  /** Focus raises the view by {@code elevation.medium}, so it reads as lifted from a distance. */
  void lift(View view, boolean focused) {
    view.setTranslationZ(focused ? dp(TvTheme.FOCUS_ELEVATION) : 0);
  }

  void gap(LinearLayout parent, int height) {
    parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height)));
  }

  void focusable(View view, Runnable action, int fill) {
    focusable(view, action, fill, TvTheme.RADIUS_LG);
  }

  /** Posters and stills use radius 0: a rounded outline does not fit square artwork. */
  void focusable(View view, Runnable action, int fill, int radius) {
    view.setFocusable(true);
    view.setClickable(true);
    view.setOnClickListener(
        v -> {
          v.requestFocusFromTouch();
          action.run();
        });
    view.setBackground(shape(fill, 0, radius));
    view.setOnFocusChangeListener(
        (v, focused) -> {
          v.setBackground(shape(fill, focused ? TvTheme.FOCUS_RING : 0, radius));
          lift(v, focused);
        });
  }

  TextView button(String label, Runnable action, boolean primary) {
    // Charcuterie Button: the primary is the accent solid with its own ink, the rest are neutral.
    int fill = primary ? TvTheme.ACCENT_SOLID : TvTheme.NEUTRAL_SURFACE;
    TextView v = text(label, 16, primary ? TvTheme.ACCENT_ON_SOLID : WHITE);
    v.setTypeface(medium());
    v.setGravity(Gravity.CENTER);
    v.setPadding(dp(16), dp(10), dp(16), dp(10));
    v.setMinHeight(dp(40));
    focusable(v, action, fill, TvTheme.RADIUS_MD);
    v.setOnFocusChangeListener(
        (view, focused) -> {
          view.setBackground(shape(fill, focused ? TvTheme.FOCUS_RING : 0, TvTheme.RADIUS_MD));
          lift(view, focused);
        });
    v.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    return v;
  }

  /** A button that leads with a Lucide icon drawn in the button's own text color. */
  TextView button(String label, int icon, Runnable action, boolean primary) {
    TextView v = button(label, action, primary);
    withIcon(v, icon);
    return v;
  }

  /**
   * Puts a Lucide icon before a label. The icon is tinted with the label's color, so it needs
   * no color of its own, and it follows the Display size with the text.
   */
  void withIcon(TextView view, int icon) {
    android.graphics.drawable.Drawable drawable = context.getDrawable(icon).mutate();
    int size = Math.round(view.getTextSize() * 1.25f);
    drawable.setBounds(0, 0, size, size);
    view.setCompoundDrawablesRelative(drawable, null, null, null);
    view.setCompoundDrawablePadding(dp(8));
    view.setCompoundDrawableTintList(view.getTextColors());
  }

  /** A standalone Lucide icon in an existing color. */
  ImageView icon(int icon, int color, int size) {
    ImageView v = new ImageView(context);
    v.setImageResource(icon);
    v.setImageTintList(android.content.res.ColorStateList.valueOf(color));
    v.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return v;
  }

  void action(LinearLayout row, String title, Runnable action, boolean primary) {
    action(row, title, 0, action, primary);
  }

  void action(LinearLayout row, String title, int icon, Runnable action, boolean primary) {
    TextView button = button(title, action, primary);
    if (icon != 0) withIcon(button, icon);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.setMargins(0, 0, dp(12), 0);
    row.addView(button, lp);
  }

  LinearLayout card(
      String eyebrow, String title, String description, String action, Runnable click, int width) {
    return card(eyebrow, 0, title, description, action, click, width);
  }

  /** A card whose eyebrow leads with a Lucide icon; {@code icon} 0 means none. */
  LinearLayout card(
      String eyebrow,
      int icon,
      String title,
      String description,
      String action,
      Runnable click,
      int width) {
    LinearLayout card = column();
    card.setPadding(dp(18), dp(18), dp(18), dp(16));
    TextView label = text(eyebrow.toUpperCase(java.util.Locale.ROOT), 12, ACCENT);
    label.setTag("card-eyebrow");
    if (icon != 0) withIcon(label, icon);
    card.addView(label);
    gap(card, 10);
    card.addView(title(title, 23));
    gap(card, 8);
    TextView body = text(description, 15, MUTED);
    body.setMaxLines(3);
    body.setMinLines(3);
    card.addView(body);
    gap(card, 12);
    TextView cta = text(action + "  ›", 16, WHITE);
    cta.setTypeface(medium());
    cta.setTag("card-action");
    cta.setPadding(dp(14), dp(9), dp(14), dp(9));
    cta.setBackground(shape(TvTheme.NEUTRAL_SURFACE, 0, TvTheme.RADIUS_MD));
    card.addView(cta);
    // Charcuterie Card: surface.raised with a border.subtle edge; focus draws the ring instead.
    focusable(card, click, PANEL);
    card.setBackground(outlined(PANEL, TvTheme.BORDER_SUBTLE, TvTheme.RADIUS_LG));
    card.setOnFocusChangeListener(
        (v, focused) -> {
          v.setBackground(
              focused
                  ? shape(PANEL, TvTheme.FOCUS_RING, TvTheme.RADIUS_LG)
                  : outlined(PANEL, TvTheme.BORDER_SUBTLE, TvTheme.RADIUS_LG));
          lift(v, focused);
        });
    card.setContentDescription(title + ". " + action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), -2);
    lp.setMargins(0, 0, dp(14), 0);
    card.setLayoutParams(lp);
    return card;
  }

  void primaryCard(View card) {
    TextView action = card.findViewWithTag("card-action");
    action.setTextColor(TvTheme.ACCENT_ON_SOLID);
    action.setBackground(shape(TvTheme.ACCENT_SOLID, 0, TvTheme.RADIUS_MD));
  }

  /** Vector artwork for the fictional, clearly labeled demo catalog. */
  View demoArt(int index) {
    return new View(context) {
      final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

      @Override
      protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        int[] colors = TvTheme.DEMO_ART_BACKGROUNDS;
        canvas.drawColor(colors[Math.floorMod(index, colors.length)]);
        paint.setColor(TvTheme.DEMO_ART_PLANET);
        if (index % 5 == 0) {
          canvas.drawCircle(w * .57f, h * .47f, w * .31f, paint);
          paint.setColor(TvTheme.DEMO_ART_RING);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(w * .012f);
          canvas.save();
          canvas.rotate(-28, w * .5f, h * .48f);
          canvas.drawOval(w * .06f, h * .43f, w * .96f, h * .54f, paint);
          canvas.restore();
          paint.setStyle(Paint.Style.FILL);
        } else if (index % 5 == 1) {
          paint.setColor(TvTheme.DEMO_ART_TOWER);
          Path path = new Path();
          path.moveTo(w * .47f, h * .27f);
          path.lineTo(w * .61f, h * .81f);
          path.lineTo(w * .39f, h * .81f);
          path.close();
          canvas.drawPath(path, paint);
          paint.setColor(TvTheme.DEMO_ART_TOWER_TOP);
          canvas.drawRect(w * .37f, h * .26f, w * .64f, h * .32f, paint);
        } else {
          paint.setColor(
              index % 5 == 2 ? TvTheme.DEMO_ART_CITY : TvTheme.DEMO_ART_CITY_ALTERNATE);
          for (int i = 0; i < 9; i++)
            canvas.drawRect(
                w * i / 9f, h * (.25f + (i % 3) * .1f), w * (i + .7f) / 9f, h * .9f, paint);
        }
      }
    };
  }
}
