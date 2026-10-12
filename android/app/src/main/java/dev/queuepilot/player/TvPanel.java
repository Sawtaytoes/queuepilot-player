package dev.queuepilot.player;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;

/** App-owned TV panel. Playback menus stay attached to their launching control. */
final class TvPanel extends Dialog {
  private final Activity owner;
  private final TvUi ui;
  private final View opener, anchor;
  private final ScrollView scroll;
  private final LinearLayout body;
  private final TextView message;
  private final FrameLayout custom;
  private final Map<Integer, TextView> buttons = new HashMap<>();
  private final Runnable opened, closed;
  private View initialFocus;
  private int panelWidth, panelHeight = -2;

  private TvPanel(Builder b) {
    super(b.owner);
    owner = b.owner;
    ui = new TvUi(owner);
    opener = owner.getCurrentFocus();
    anchor = b.anchor;
    opened = b.opened;
    closed = b.closed;
    panelWidth = ui.dp(330);
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    scroll = new ScrollView(owner);
    scroll.setFillViewport(false);
    scroll.setClipToOutline(true);
    scroll.setBackground(ui.shape(TvUi.BG, 0xff343b48, 16));
    body = ui.column();
    body.setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(18));
    scroll.addView(body);
    TextView title = ui.title(b.title, 22);
    title.setAccessibilityHeading(true);
    body.addView(title);
    message = ui.text(b.message, 14, TvUi.MUTED);
    message.setPadding(0, ui.dp(12), 0, ui.dp(12));
    message.setVisibility(b.message.isEmpty() ? View.GONE : View.VISIBLE);
    body.addView(message);
    ui.gap(body, 12);
    custom = new FrameLayout(owner);
    body.addView(custom, new LinearLayout.LayoutParams(-1, -2));
    if (b.view != null) custom.addView(b.view);
    if (b.items != null) {
      for (int i = 0; i < b.items.length; i++) {
        final int index = i;
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        row.setMinimumHeight(ui.dp(46));
        TextView label = ui.text(b.items[i], 16, TvUi.WHITE);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (i == b.selected) {
          TextView check = ui.text("✓", 19, TvUi.ACCENT);
          check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
          row.addView(check);
          row.setSelected(true);
          row.setStateDescription("Selected");
          initialFocus = row;
        }
        ui.focusable(
            row,
            () -> {
              dismiss();
              b.itemClick.onClick(this, index);
            },
            i == b.selected ? 0xff293425 : TvUi.PANEL);
        row.setContentDescription(b.items[i]);
        body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        ui.gap(body, 6);
      }
    }
    for (int which : new int[] {BUTTON_POSITIVE, BUTTON_NEUTRAL, BUTTON_NEGATIVE}) {
      String label = b.labels.get(which);
      if (label == null) continue;
      ui.gap(body, 8);
      TextView button =
          ui.button(
              label,
              () -> {
                dismiss();
                DialogInterface.OnClickListener action = b.actions.get(which);
                if (action != null) action.onClick(this, which);
              },
              which == BUTTON_POSITIVE);
      buttons.put(which, button);
      LinearLayout.LayoutParams actionLayout = new LinearLayout.LayoutParams(-2, -2);
      actionLayout.gravity = Gravity.END;
      body.addView(button, actionLayout);
    }
    setContentView(scroll);
    Window window = getWindow();
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    window.setDimAmount(anchor == null ? .45f : .12f);
    window
        .getDecorView()
        .setSystemUiVisibility(owner.getWindow().getDecorView().getSystemUiVisibility());
    setCanceledOnTouchOutside(true);
    setOnDismissListener(
        d -> {
          if (b.dismissed != null) b.dismissed.accept(this);
          if (closed != null) closed.run();
          if (opener != null && opener.isAttachedToWindow() && opener.isShown())
            opener.requestFocusFromTouch();
        });
  }

  @Override
  public void show() {
    if (opened != null) opened.run();
    super.show();
    position();
    if (initialFocus != null) initialFocus.requestFocusFromTouch();
    else if (body.findFocus() == null) {
      java.util.ArrayList<View> choices = body.getFocusables(View.FOCUS_FORWARD);
      if (!choices.isEmpty()) choices.get(0).requestFocusFromTouch();
    }
  }

  TextView getButton(int which) {
    return buttons.get(which);
  }

  void setMessage(String text) {
    message.setText(text);
    message.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    position();
  }

  void setView(View view) {
    custom.removeAllViews();
    custom.addView(view);
    position();
  }

  void setPanelSize(int width, int height) {
    panelWidth = width;
    panelHeight = height;
    if (isShowing()) position();
  }

  private void position() {
    View screen = owner.getWindow().getDecorView();
    int margin = ui.dp(24), gap = ui.dp(10);
    int screenWidth = screen.getWidth(), screenHeight = screen.getHeight();
    if (screenWidth == 0 || screenHeight == 0) return;
    int width = Math.min(panelWidth, screenWidth - margin * 2);
    int maxHeight = screenHeight - margin * 2;
    int x = screenWidth - margin - width, y;
    int[] point = new int[2];
    boolean above = false;
    if (anchor != null && anchor.isAttachedToWindow()) {
      anchor.getLocationInWindow(point);
      above = point[1] + anchor.getHeight() / 2 > screenHeight / 2;
      int available =
          above
              ? point[1] - gap - margin
              : screenHeight - margin - point[1] - anchor.getHeight() - gap;
      maxHeight = Math.min(maxHeight, Math.max(ui.dp(100), available));
      x = Math.max(margin, Math.min(x, point[0] + anchor.getWidth() - width));
    }
    body.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int height = Math.min(maxHeight, panelHeight > 0 ? panelHeight : body.getMeasuredHeight());
    if (anchor == null) y = (screenHeight - height) / 2;
    else y = above ? point[1] - gap - height : point[1] + anchor.getHeight() + gap;
    y = Math.max(margin, Math.min(screenHeight - margin - height, y));
    WindowManager.LayoutParams attrs = getWindow().getAttributes();
    attrs.gravity = Gravity.TOP | Gravity.LEFT;
    attrs.x = x;
    attrs.y = y;
    attrs.width = width;
    attrs.height = height;
    getWindow().setAttributes(attrs);
  }

  static final class Builder {
    private final Activity owner;
    private String title = "", message = "";
    private View view, anchor;
    private String[] items;
    private int selected = -1;
    private DialogInterface.OnClickListener itemClick;
    private Runnable opened, closed;
    private java.util.function.Consumer<TvPanel> created, dismissed;
    private final Map<Integer, String> labels = new HashMap<>();
    private final Map<Integer, DialogInterface.OnClickListener> actions = new HashMap<>();

    Builder(Activity owner) {
      this.owner = owner;
    }

    Builder setTitle(String value) {
      title = value;
      return this;
    }

    Builder setMessage(String value) {
      message = value;
      return this;
    }

    Builder setView(View value) {
      view = value;
      return this;
    }

    Builder setAnchor(View value, Runnable onOpen, Runnable onClose) {
      anchor = value;
      opened = onOpen;
      closed = onClose;
      return this;
    }

    Builder setSelected(int index) {
      selected = index;
      return this;
    }

    Builder setLifecycle(
        java.util.function.Consumer<TvPanel> onCreate,
        java.util.function.Consumer<TvPanel> onDismiss) {
      created = onCreate;
      dismissed = onDismiss;
      return this;
    }

    Builder setItems(String[] values, DialogInterface.OnClickListener action) {
      items = values;
      itemClick = action;
      return this;
    }

    private Builder button(int which, String label, DialogInterface.OnClickListener action) {
      labels.put(which, label);
      actions.put(which, action);
      return this;
    }

    Builder setPositiveButton(String label, DialogInterface.OnClickListener action) {
      return button(BUTTON_POSITIVE, label, action);
    }

    Builder setNegativeButton(String label, DialogInterface.OnClickListener action) {
      return button(BUTTON_NEGATIVE, label, action);
    }

    Builder setNeutralButton(String label, DialogInterface.OnClickListener action) {
      return button(BUTTON_NEUTRAL, label, action);
    }

    TvPanel create() {
      TvPanel panel = new TvPanel(this);
      if (created != null) created.accept(panel);
      return panel;
    }
  }
}
