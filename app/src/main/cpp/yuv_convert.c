#include <jni.h>
#include <stdint.h>

static inline int clamp255(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

/* RGBA(800x800, 12 字节头) -> 720x720 YUV420（planar=1 输出 I420，否则 NV12），整数采样降尺寸 */
JNIEXPORT void JNICALL
Java_com_magneo_compass_web_H264Streamer_rgbaToYuv(JNIEnv* env, jclass clazz,
        jbyteArray srcArr, jint sw, jint sh,
        jbyteArray dstArr, jint ew, jint eh, jint planar)
{
    jsize slen = (*env)->GetArrayLength(env, srcArr);
    jsize dlen = (*env)->GetArrayLength(env, dstArr);
    if (slen < 12 + sw * sh * 4 || dlen < ew * eh * 3 / 2) return;
    jbyte* s = (*env)->GetByteArrayElements(env, srcArr, NULL);
    jbyte* d = (*env)->GetByteArrayElements(env, dstArr, NULL);
    if (!s || !d) {
        if (s) (*env)->ReleaseByteArrayElements(env, srcArr, s, JNI_ABORT);
        if (d) (*env)->ReleaseByteArrayElements(env, dstArr, d, JNI_ABORT);
        return;
    }
    const uint8_t* src = (const uint8_t*)s;
    uint8_t* dst = (uint8_t*)d;
    int yLen = ew * eh;
    int p = 0;
    int y, x;
    for (y = 0; y < eh; y++) {
        int sy = (y * sh) / eh;
        const uint8_t* row = src + (size_t)sy * sw * 4 + 12;
        for (x = 0; x < ew; x++) {
            int sx = (x * sw) / ew;
            const uint8_t* px = row + (size_t)sx * 4;
            int r = px[0], g = px[1], b = px[2];
            int yv = (r * 19595 + g * 38470 + b * 7471) >> 16;
            dst[p++] = (uint8_t)clamp255(yv);
        }
    }
    int uvLen = yLen >> 2;
    int uPos = yLen;
    int vPos = planar ? yLen + uvLen : -1;
    for (y = 0; y < eh; y += 2) {
        int sy = (y * sh) / eh;
        int sy2 = ((y + 1) * sh) / eh;
        const uint8_t* row  = src + (size_t)sy  * sw * 4 + 12;
        const uint8_t* row2 = src + (size_t)sy2 * sw * 4 + 12;
        for (x = 0; x < ew; x += 2) {
            int sx = (x * sw) / ew;
            int sx2 = ((x + 1) * sw) / ew;
            const uint8_t* a = row  + (size_t)sx  * 4;
            const uint8_t* b2 = row  + (size_t)sx2 * 4;
            const uint8_t* c = row2 + (size_t)sx  * 4;
            const uint8_t* e = row2 + (size_t)sx2 * 4;
            int r = (a[0] + b2[0] + c[0] + e[0]) >> 2;
            int g = (a[1] + b2[1] + c[1] + e[1]) >> 2;
            int bl = (a[2] + b2[2] + c[2] + e[2]) >> 2;
            int u = (-r * 100 - g * 208 + bl * 308 + 32768) >> 8;
            int v = (r * 308 - g * 261 - bl * 47 + 32768) >> 8;
            if (planar) {
                dst[uPos++] = (uint8_t)clamp255(u);
                dst[vPos++] = (uint8_t)clamp255(v);
            } else {
                dst[uPos++] = (uint8_t)clamp255(u);
                dst[uPos++] = (uint8_t)clamp255(v);
            }
        }
    }
    (*env)->ReleaseByteArrayElements(env, srcArr, s, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, dstArr, d, 0);
}
