package dev.queuepilot.player;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Bounded TV controls and shared styling for the approved queue-first presentation. */
final class TvUi {
  static final int BG = Color.rgb(16, 18, 24),
      PANEL = Color.rgb(32, 38, 48),
      MUTED = Color.rgb(165, 176, 191),
      ACCENT = Color.rgb(207, 237, 170),
      WHITE = Color.rgb(242, 243, 245);
  final Context context;

  TvUi(Context context) {
    this.context = context;
  }

  int dp(float size) {
    return Math.round(size * context.getResources().getDisplayMetrics().density);
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
    v.setTextSize(size);
    v.setTextColor(color);
    v.setFontFeatureSettings("kern");
    v.setIncludeFontPadding(false);
    return v;
  }

  TextView title(String value, int size) {
    TextView v = text(value, size, WHITE);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    return v;
  }

  GradientDrawable shape(int fill, int stroke, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(2), stroke);
    return d;
  }

  void gap(LinearLayout parent, int height) {
    parent.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height)));
  }

  void focusable(View view, Runnable action, int fill) {
    view.setFocusable(true);
    view.setClickable(true);
    view.setBackground(shape(fill, 0, 9));
    view.setOnClickListener(
        v -> {
          v.requestFocusFromTouch();
          action.run();
        });
    view.setOnFocusChangeListener(
        (v, focused) -> v.setBackground(shape(fill, focused ? ACCENT : 0, 9)));
  }

  TextView button(String label, Runnable action, boolean primary) {
    TextView v = text(label, 16, primary ? Color.rgb(24, 32, 14) : WHITE);
    v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    v.setGravity(Gravity.CENTER);
    v.setPadding(dp(19), dp(13), dp(19), dp(13));
    v.setMinHeight(dp(46));
    focusable(v, action, primary ? ACCENT : PANEL);
    v.setOnFocusChangeListener(
        (view, focused) ->
            view.setBackground(shape(primary ? ACCENT : PANEL, focused ? WHITE : 0, 8)));
    v.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
    return v;
  }

  void action(LinearLayout row, String title, Runnable action, boolean primary) {
    TextView button = button(title, action, primary);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.setMargins(0, 0, dp(12), 0);
    row.addView(button, lp);
  }

  LinearLayout card(
      String eyebrow, String title, String description, String action, Runnable click, int width) {
    LinearLayout card = column();
    card.setPadding(dp(22), dp(22), dp(22), dp(20));
    card.addView(text(eyebrow.toUpperCase(java.util.Locale.ROOT), 12, ACCENT));
    gap(card, 14);
    card.addView(title(title, 23));
    gap(card, 12);
    TextView body = text(description, 15, MUTED);
    body.setMaxLines(3);
    body.setMinLines(3);
    card.addView(body);
    gap(card, 16);
    TextView cta = text(action + "  ›", 16, WHITE);
    cta.setTag("card-action");
    cta.setPadding(dp(15), dp(11), dp(15), dp(11));
    cta.setBackground(shape(0xff191f28, 0, 7));
    card.addView(cta);
    focusable(card, click, PANEL);
    card.setContentDescription(title + ". " + action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(width), -2);
    lp.setMargins(0, 0, dp(14), 0);
    card.setLayoutParams(lp);
    return card;
  }

  void primaryCard(View card) {
    TextView action = card.findViewWithTag("card-action");
    action.setTextColor(Color.rgb(24, 32, 14));
    action.setBackground(shape(ACCENT, 0, 7));
  }

  /** Vector artwork for the fictional, clearly labeled demo catalog. */
  View demoArt(int index) {
    return new View(context) {
      final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

      @Override
      protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        int[] colors = {0xff112737, 0xff203233, 0xff252641, 0xff123d3d, 0xff352834};
        canvas.drawColor(colors[Math.floorMod(index, 5)]);
        paint.setColor(0xff669da5);
        if (index % 5 == 0) {
          canvas.drawCircle(w * .57f, h * .47f, w * .31f, paint);
          paint.setColor(0xffc5e6cc);
          paint.setStyle(Paint.Style.STROKE);
          paint.setStrokeWidth(w * .012f);
          canvas.save();
          canvas.rotate(-28, w * .5f, h * .48f);
          canvas.drawOval(w * .06f, h * .43f, w * .96f, h * .54f, paint);
          canvas.restore();
          paint.setStyle(Paint.Style.FILL);
        } else if (index % 5 == 1) {
          paint.setColor(0xffb4a063);
          Path path = new Path();
          path.moveTo(w * .47f, h * .27f);
          path.lineTo(w * .61f, h * .81f);
          path.lineTo(w * .39f, h * .81f);
          path.close();
          canvas.drawPath(path, paint);
          paint.setColor(0xfff0e4ae);
          canvas.drawRect(w * .37f, h * .26f, w * .64f, h * .32f, paint);
        } else {
          paint.setColor(index % 5 == 2 ? 0xff73748e : 0xff43827e);
          for (int i = 0; i < 9; i++)
            canvas.drawRect(
                w * i / 9f, h * (.25f + (i % 3) * .1f), w * (i + .7f) / 9f, h * .9f, paint);
        }
      }
    };
  }
}
