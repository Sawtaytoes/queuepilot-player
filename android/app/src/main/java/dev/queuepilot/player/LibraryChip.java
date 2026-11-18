package dev.queuepilot.player;

import android.content.Context;
import android.widget.TextView;
import org.json.JSONObject;

/** The small library-name chip on a title in All Movies and Shows, in its library's own color. */
final class LibraryChip {
  private LibraryChip() {}

  /** The chip for a merged title, or null for a title outside All Movies and Shows. */
  static TextView view(Context context, TvUi ui, JSONObject facts) {
    String title = facts == null ? "" : facts.optString("libraryTitle");
    if (title.isEmpty()) return null;
    int color = Math.floorMod(facts.optInt("libraryColor"), TvTheme.CHIP_CONTENT.length);
    TextView chip = ui.text(title, 11, TvTheme.CHIP_CONTENT[color]);
    chip.setTypeface(ui.medium());
    chip.setSingleLine(true);
    chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
    chip.setPadding(ui.dp(7), ui.dp(2), ui.dp(7), ui.dp(3));
    chip.setBackground(
        ui.outlined(TvTheme.CHIP_SURFACE[color], TvTheme.CHIP_BORDER[color], TvTheme.RADIUS_SM));
    chip.setTag("library-chip");
    chip.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return chip;
  }

  /** The card's spoken name with its library: "Glass City, in Films". */
  static String spoken(String spoken, JSONObject facts) {
    String title = facts == null ? "" : facts.optString("libraryTitle");
    return title.isEmpty() ? spoken : spoken + ", in " + title;
  }
}
