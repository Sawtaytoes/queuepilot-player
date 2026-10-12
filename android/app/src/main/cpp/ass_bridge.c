#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdarg.h>
#include <ass/ass.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    ASS_Library *library;
    ASS_Renderer *renderer;
    ASS_Track *track;
    int width, height, force;
} Context;
#define JNI(name) Java_dev_queuepilot_player_AssNative_##name
static void message(int level, const char *format, va_list arguments, void *data) {
    if (level <= 5) __android_log_vprint(ANDROID_LOG_DEBUG, "QueuePilotLibass", format, arguments);
}
static Context *context(jlong handle) { return (Context *)(intptr_t)handle; }
static void destroy(Context *ctx) {
    if (!ctx) return;
    if (ctx->track) ass_free_track(ctx->track);
    if (ctx->renderer) ass_renderer_done(ctx->renderer);
    if (ctx->library) ass_library_done(ctx->library);
    free(ctx);
}
JNIEXPORT jlong JNICALL JNI(create)(JNIEnv *env, jclass type) {
    Context *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) return 0;
    ctx->library = ass_library_init();
    if (!ctx->library) { destroy(ctx); return 0; }
    ass_set_message_cb(ctx->library, message, NULL);
    ctx->renderer = ass_renderer_init(ctx->library);
    if (!ctx->renderer) { destroy(ctx); return 0; }
    ass_set_cache_limits(ctx->renderer, 1000, 48);
    ass_set_fonts(ctx->renderer, "/system/fonts/Roboto-Regular.ttf", "sans-serif", ASS_FONTPROVIDER_NONE, NULL, 1);
    ctx->track = ass_new_track(ctx->library);
    if (!ctx->track) { destroy(ctx); return 0; }
    ctx->force = 1;
    return (jlong)(intptr_t)ctx;
}
JNIEXPORT void JNICALL JNI(destroy)(JNIEnv *env, jclass type, jlong handle) { destroy(context(handle)); }
JNIEXPORT void JNICALL JNI(font)(JNIEnv *env, jclass type, jlong handle, jstring name, jbyteArray bytes) {
    Context *ctx = context(handle);
    const char *font_name = (*env)->GetStringUTFChars(env, name, NULL);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (font_name && data) {
        ass_add_font(ctx->library, font_name, (char *)data, (*env)->GetArrayLength(env, bytes));
        ass_set_fonts(ctx->renderer, "/system/fonts/Roboto-Regular.ttf", "sans-serif", ASS_FONTPROVIDER_NONE, NULL, 1);
        ctx->force = 1;
    }
    if (data) (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    if (font_name) (*env)->ReleaseStringUTFChars(env, name, font_name);
}
JNIEXPORT void JNICALL JNI(header)(JNIEnv *env, jclass type, jlong handle, jbyteArray bytes) {
    Context *ctx = context(handle);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (data) {
        ass_process_codec_private(ctx->track, (char *)data, (*env)->GetArrayLength(env, bytes));
        (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    }
    ctx->force = 1;
}
JNIEXPORT void JNICALL JNI(packet)(JNIEnv *env, jclass type, jlong handle, jbyteArray bytes, jlong time, jlong duration) {
    Context *ctx = context(handle);
    jbyte *data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (data) {
        ass_process_chunk(ctx->track, (char *)data, (*env)->GetArrayLength(env, bytes), time, duration);
        (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
    }
}
JNIEXPORT jobject JNICALL JNI(render)(JNIEnv *env, jclass type, jlong handle, jlong time, jint width, jint height, jintArray rectangle) {
    Context *ctx = context(handle);
    jint geometry[4] = {-2, 0, 0, 0};
    if (width < 1 || height < 1 || width > 3840 || height > 3840) return NULL;
    if (ctx->width != width || ctx->height != height) {
        ctx->width = width; ctx->height = height;
        ass_set_frame_size(ctx->renderer, width, height);
        ass_set_storage_size(ctx->renderer, width, height);
        ctx->force = 1;
    }
    ass_prune_events(ctx->track, time - 60000);
    int changed = 0;
    ASS_Image *images = ass_render_frame(ctx->renderer, ctx->track, time, &changed);
    if (!changed && !ctx->force) { (*env)->SetIntArrayRegion(env, rectangle, 0, 4, geometry); return NULL; }
    ctx->force = 0;
    int left = width, top = height, right = 0, bottom = 0;
    for (ASS_Image *image = images; image; image = image->next) {
        if (!image->w || !image->h) continue;
        int x = image->dst_x < 0 ? 0 : image->dst_x;
        int y = image->dst_y < 0 ? 0 : image->dst_y;
        int x2 = image->dst_x + image->w; if (x2 > width) x2 = width;
        int y2 = image->dst_y + image->h; if (y2 > height) y2 = height;
        if (x < left) left = x; if (y < top) top = y;
        if (x2 > right) right = x2; if (y2 > bottom) bottom = y2;
    }
    geometry[0] = left; geometry[1] = top;
    geometry[2] = right-left; geometry[3] = bottom-top;
    (*env)->SetIntArrayRegion(env, rectangle, 0, 4, geometry);
    if (right <= left || bottom <= top) return NULL;
    jclass bitmap_class = (*env)->FindClass(env, "android/graphics/Bitmap");
    jclass config_class = (*env)->FindClass(env, "android/graphics/Bitmap$Config");
    jfieldID config_id = (*env)->GetStaticFieldID(env, config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = (*env)->GetStaticObjectField(env, config_class, config_id);
    jmethodID create = (*env)->GetStaticMethodID(env, bitmap_class, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = (*env)->CallStaticObjectMethod(env, bitmap_class, create, right-left, bottom-top, config);
    if (!bitmap || (*env)->ExceptionCheck(env)) return NULL;
    AndroidBitmapInfo info; void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return NULL;
    memset(pixels, 0, info.stride * info.height);
    for (ASS_Image *image = images; image; image = image->next) {
        unsigned opacity = 255 - (image->color & 255);
        unsigned red = image->color >> 24, green = (image->color >> 16) & 255, blue = (image->color >> 8) & 255;
        for (int y = 0; y < image->h; y++) {
            int target_y = image->dst_y + y - top;
            if (target_y < 0 || target_y >= (int)info.height) continue;
            uint8_t *row = (uint8_t *)pixels + target_y * info.stride;
            for (int x = 0; x < image->w; x++) {
                int target_x = image->dst_x + x - left;
                if (target_x < 0 || target_x >= (int)info.width) continue;
                unsigned alpha = (image->bitmap[y * image->stride + x] * opacity + 127) / 255;
                uint8_t *pixel = row + target_x * 4;
                pixel[0] = (red*alpha + pixel[0]*(255-alpha) +127)/255;
                pixel[1] = (green*alpha + pixel[1]*(255-alpha) +127)/255;
                pixel[2] = (blue*alpha + pixel[2]*(255-alpha) +127)/255;
                pixel[3] = alpha + (pixel[3]*(255-alpha) +127)/255;
            }
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}
