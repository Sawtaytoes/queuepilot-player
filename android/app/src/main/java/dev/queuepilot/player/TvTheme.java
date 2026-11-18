package dev.queuepilot.player;

import android.content.Context;
import android.graphics.Typeface;

/**
 * Every color, radius, focus and surface value in the TV interface, and its typeface. This is
 * the only file that holds a color literal; {@code tools/theme_check.py} enforces that.
 *
 * <p>The look is the QueuePilot web app's own theme with a lime accent: Charcuterie's {@code
 * daylight} variant in the dark scheme ({@code resolveTokens({scheme: "dark", variant:
 * "daylight"})}, tokens source at Charcuterie {@code 33891c2}), with the {@code intent.accent}
 * roles replaced by the library's categorical family 4, Lime. Each constant names the token it
 * copies. Charcuterie emits CSS and JSON, not Android resources, so these values are copied by
 * hand; regenerate them from the library when its tokens change.
 */
final class TvTheme {
  private TvTheme() {}

  // Surfaces.
  /** {@code surface.base}: the page, and the collapsed rail (the web app's rail is the page). */
  static final int SURFACE_BASE = 0xff131822;
  /** {@code surface.raised}: cards, queue rows, and image placeholders behind artwork. */
  static final int SURFACE_RAISED = 0xff1d2430;
  /** {@code surface.sunken}: wells behind artwork before it loads. */
  static final int SURFACE_SUNKEN = 0xff0b0f16;
  /** {@code surface.overlay}: side panels and dialogs. */
  static final int SURFACE_OVERLAY = 0xff252d3b;

  // Text and icons.
  /** {@code content.primary}. */
  static final int CONTENT_PRIMARY = 0xffedf0f5;
  /** {@code content.secondary}: supporting text. */
  static final int CONTENT_SECONDARY = 0xffa9b2c1;
  /** {@code content.muted}: inactive rail entries. */
  static final int CONTENT_MUTED = 0xff8b94a5;

  // Borders.
  /** {@code border.subtle}: the resting edge of a card. */
  static final int BORDER_SUBTLE = 0xff212936;
  /** {@code border.default}: panel edges and progress tracks. */
  static final int BORDER_DEFAULT = 0xff2e3745;

  // Neutral intent: secondary buttons, chips, and the rail's current page.
  /** {@code intent.neutral.surface}. */
  static final int NEUTRAL_SURFACE = 0xff222a37;

  // Accent: categorical.4 (Lime), in place of the daylight indigo intent.accent.
  /** {@code categorical.4.solid}: primary buttons and progress fill behind dark ink. */
  static final int ACCENT_SOLID = 0xff73a40a;
  /** {@code categorical.4.content}: accent text, icons, and progress. */
  static final int ACCENT_CONTENT = 0xffa3cf68;
  /** {@code categorical.4.onSolid}: text and icons on {@link #ACCENT_SOLID}. */
  static final int ACCENT_ON_SOLID = 0xff0c1204;
  /** {@code categorical.4.surface}: a tinted card, such as the queue to resume. */
  static final int ACCENT_SURFACE = 0xff1f2910;
  /** {@code categorical.4.border}. */
  static final int ACCENT_BORDER = 0xff5b7a2d;

  // Danger: Charcuterie's intent.danger in the same scheme and variant (tokens at Charcuterie
  // db44bcd). Compose's error roles use them; no View screen draws an error color yet.
  /** {@code intent.danger.solid}. */
  static final int DANGER_SOLID = 0xffbe3241;
  /** {@code intent.danger.onSolid}: text and icons on {@link #DANGER_SOLID}. */
  static final int DANGER_ON_SOLID = 0xffffffff;
  /** {@code intent.danger.surface}. */
  static final int DANGER_SURFACE = 0xff351920;
  /** {@code intent.danger.content}. */
  static final int DANGER_CONTENT = 0xfff79399;

  // Focus.
  /**
   * {@code categorical.4.content} as the focus ring, the proposed {@code tv} density's ring. The
   * daylight {@code focus.ring} is indigo and disappears against a lime button.
   */
  static final int FOCUS_RING = ACCENT_CONTENT;
  /** The proposed {@code tv} density's {@code focusRing.width}, 3 px, as dp. */
  static final int FOCUS_RING_WIDTH = 3;
  /**
   * {@code elevation.medium} ({@code 0 4px 14px}) as a View elevation for the focused card. The
   * look's 4% lift is not applied: a scaled View is clipped by its row at the screen edge.
   */
  static final int FOCUS_ELEVATION = 4;

  /** {@code elevation.high} ({@code 0 16px 40px}) as a View elevation for the open rail. */
  static final int RAIL_ELEVATION = 16;
  /** {@code scrim}, {@code rgb(6 9 16 / 0.66)}: over the content while the rail is open. */
  static final int SCRIM = 0xa8060910;

  // Radii, in dp ({@code radius.*}; the TV layout keeps the px values as dp).
  /** {@code radius.none}: posters and stills are square. */
  static final int RADIUS_NONE = 0;
  /** {@code radius.sm}: badges and small chips. */
  static final int RADIUS_SM = 4;
  /** {@code radius.md}: buttons, rail entries, and chips. */
  static final int RADIUS_MD = 8;
  /** {@code radius.lg}: cards and rows. */
  static final int RADIUS_LG = 10;
  /** {@code radius.xl}: panels and large cards. */
  static final int RADIUS_XL = 14;
  /** {@code radius.full}, for a circle at any size Charcuterie has. */
  static final int RADIUS_FULL = 9999;

  // Translucent surfaces. Charcuterie has no translucent surface token except the scrim, so
  // these are the named surface at a stated opacity.
  /** {@code surface.base} at 82%: the player's title bar over video. */
  static final int PLAYER_BAR = 0xd0131822;
  /** {@code surface.base} at 80%: a position number over artwork. */
  static final int ART_LABEL = 0xcc131822;
  /** {@code surface.raised} at 40%: a focused Media3 control. */
  static final int CONTROL_FOCUSED = 0x661d2430;
  /** {@code surface.raised} at 53%: a pressed Media3 control. */
  static final int CONTROL_PRESSED = 0x881d2430;

  /**
   * Library chips in All Movies and Shows: Charcuterie's categorical hues 1, 2, 3, 5, 6, 7, 8, 9
   * and 10 in the daylight dark scheme (Red, Orange, Amber, Green, Teal, Blue, Indigo, Purple,
   * Pink). Lime, hue 4, is left out: it is the accent and the focus ring. Index i of each array is
   * one hue: {@code categorical.<n>.content} for the text, {@code .surface} for the fill, {@code
   * .border} for the edge.
   */
  static final int[] CHIP_CONTENT = {
    0xffffa5a2, 0xffffaa69, 0xffdfbd45, 0xff57d8a1, 0xff00d5e2, 0xff7bc8ff, 0xffb0bbff,
    0xffdea9ff, 0xffff9fd2
  };

  static final int[] CHIP_SURFACE = {
    0xff381c1b, 0xff361f0e, 0xff2d2508, 0xff0d2c1e, 0xff002b2f, 0xff0e2839, 0xff20233b,
    0xff2d1f35, 0xff351c29
  };

  static final int[] CHIP_BORDER = {
    0xffac5857, 0xffa3612a, 0xff876e06, 0xff168058, 0xff007d85, 0xff2577a9, 0xff626bb2,
    0xff8960a2, 0xffa25881
  };

  /** Video letterboxing: black, not a theme surface, so bars never tint the picture. */
  static final int VIDEO_BACKDROP = 0xff000000;

  /**
   * The fictional demo catalog's drawn artwork. Picture content, not chrome, so no token
   * applies; it lives here only so every color literal is in one file.
   */
  static final int[] DEMO_ART_BACKGROUNDS = {
    0xff112737, 0xff203233, 0xff252641, 0xff123d3d, 0xff352834
  };

  static final int DEMO_ART_PLANET = 0xff669da5,
      DEMO_ART_RING = 0xffc5e6cc,
      DEMO_ART_TOWER = 0xffb4a063,
      DEMO_ART_TOWER_TOP = 0xfff0e4ae,
      DEMO_ART_CITY = 0xff73748e,
      DEMO_ART_CITY_ALTERNATE = 0xff43827e;

  // Type: Charcuterie's font.sans, Outfit (SIL OFL 1.1, tools/fonts/OFL.txt), bundled as the
  // variable font with named weights in res/font/outfit.xml.
  /** {@code fontWeight.normal}. */
  static final int WEIGHT_NORMAL = 400;
  /** {@code fontWeight.medium}: titles, labels, and buttons. */
  static final int WEIGHT_MEDIUM = 500;

  private static Typeface regular, medium;

  /** Outfit at {@link #WEIGHT_NORMAL}. */
  static Typeface regular(Context context) {
    if (regular == null) regular = Typeface.create(family(context), WEIGHT_NORMAL, false);
    return regular;
  }

  /** Outfit at {@link #WEIGHT_MEDIUM}. */
  static Typeface medium(Context context) {
    if (medium == null) medium = Typeface.create(family(context), WEIGHT_MEDIUM, false);
    return medium;
  }

  private static Typeface family(Context context) {
    return context.getResources().getFont(R.font.outfit);
  }
}
