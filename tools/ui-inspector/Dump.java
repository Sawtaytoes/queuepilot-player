package dev.queuepilot.inspector;

import android.app.Instrumentation;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.StringWriter;
import org.xmlpull.v1.XmlSerializer;

/** Emulator-only snapshot without waiting for playback animation to become globally idle. */
public class Dump extends Instrumentation {
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  public void onStart() {
    Bundle out = new Bundle();
    try {
      AccessibilityNodeInfo root = null;
      for (int attempt = 0; attempt < 20 && root == null; attempt++) {
        root = getUiAutomation().getRootInActiveWindow();
        if (root == null) Thread.sleep(100);
      }
      if (root == null) throw new IllegalStateException("No active window");
      StringWriter writer = new StringWriter();
      XmlSerializer xml = Xml.newSerializer();
      xml.setOutput(writer);
      xml.startTag(null, "hierarchy");
      if (root != null) node(xml, root);
      xml.endTag(null, "hierarchy");
      xml.flush();
      out.putString("xml", writer.toString());
      finish(0, out);
    } catch (Exception error) {
      out.putString("error", error.toString());
      finish(1, out);
    }
  }

  private void node(XmlSerializer xml, AccessibilityNodeInfo n) throws Exception {
    xml.startTag(null, "node");
    xml.attribute(null, "text", value(n.getText()));
    xml.attribute(null, "content-desc", value(n.getContentDescription()));
    xml.attribute(null, "class", value(n.getClassName()));
    xml.attribute(null, "resource-id", value(n.getViewIdResourceName()));
    xml.attribute(null, "selected", String.valueOf(n.isSelected()));
    xml.attribute(null, "state-desc", value(n.getStateDescription()));
    xml.attribute(null, "focused", String.valueOf(n.isFocused()));
    xml.attribute(null, "clickable", String.valueOf(n.isClickable()));
    xml.attribute(null, "enabled", String.valueOf(n.isEnabled()));
    Rect r = new Rect();
    n.getBoundsInScreen(r);
    xml.attribute(
        null, "bounds", "[" + r.left + "," + r.top + "][" + r.right + "," + r.bottom + "]");
    for (int i = 0; i < n.getChildCount(); i++) {
      AccessibilityNodeInfo c = n.getChild(i);
      if (c != null) {
        node(xml, c);
        c.recycle();
      }
    }
    xml.endTag(null, "node");
  }

  private String value(Object s) {
    return s == null ? "" : s.toString();
  }
}
