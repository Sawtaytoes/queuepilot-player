package dev.queuepilot.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Cropped premultiplied subtitle frame, positioned within the video's content rectangle. */
final class AssOverlay extends View {
  private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
  private Bitmap bitmap;
  private int left, top, frameWidth = 1920, frameHeight = 1080;
  AssOverlay(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
  void frame(Bitmap image, int[] rectangle, int width, int height) {
    bitmap = image; left = rectangle[0]; top = rectangle[1]; frameWidth = width; frameHeight = height; invalidate();
  }
  void clear() { bitmap = null; invalidate(); }
  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (bitmap == null) return;
    float scale = Math.min((float)getWidth()/frameWidth, (float)getHeight()/frameHeight);
    float x = (getWidth() - frameWidth * scale)/2 + left*scale;
    float y = (getHeight() - frameHeight * scale)/2 + top*scale;
    canvas.drawBitmap(bitmap, null, new RectF(x,y,x+bitmap.getWidth()*scale,y+bitmap.getHeight()*scale),paint);
  }
}
