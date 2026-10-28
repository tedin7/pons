/*
 * Zero-upload frames: Android graphics buffers (AHardwareBuffer) shared with Linux.
 *
 * The app allocates linear, CPU-writable buffers, hands their dma-buf file
 * descriptors to Mesa inside Linux, and draws them as GL textures through
 * EGLImage. Mesa writes each finished frame straight into one of them, so the
 * app no longer uploads 7 MB per frame with glTexSubImage2D.
 */
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#define TAG "Pinguplay"

/* Layout of a native_handle_t (system/core/libcutils/include/cutils/native_handle.h). */
struct native_handle {
    int version;
    int numFds;
    int numInts;
    int data[];
};

typedef const struct native_handle *(*get_native_handle_fn)(const AHardwareBuffer *);

static get_native_handle_fn get_native_handle(void)
{
    static get_native_handle_fn fn;
    if (!fn) {
        void *lib = dlopen("libnativewindow.so", RTLD_NOW);
        if (lib) fn = (get_native_handle_fn)dlsym(lib, "AHardwareBuffer_getNativeHandle");
        if (!fn) __android_log_print(ANDROID_LOG_WARN, TAG, "AHardwareBuffer_getNativeHandle unavailable");
    }
    return fn;
}

/* Returns the AHardwareBuffer pointer (0 on failure); stride in pixels goes to out[0]. */
JNIEXPORT jlong JNICALL
Java_io_github_pinguplay_display_AhbFrames_nativeAllocate(JNIEnv *env, jclass cls, jint width,
                                                           jint height, jintArray out)
{
    (void)cls;
    AHardwareBuffer_Desc desc = {
        .width = (uint32_t)width,
        .height = (uint32_t)height,
        .layers = 1,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
        /* CPU-writable keeps the buffer linear and mappable from Linux. */
        .usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
    };
    AHardwareBuffer *buffer = NULL;
    if (AHardwareBuffer_allocate(&desc, &buffer) != 0 || !buffer) return 0;
    AHardwareBuffer_describe(buffer, &desc);
    jint stride = (jint)desc.stride;
    (*env)->SetIntArrayRegion(env, out, 0, 1, &stride);
    return (jlong)(uintptr_t)buffer;
}

/* The buffer's first file descriptor (its dma-buf), or -1. Owned by the buffer. */
JNIEXPORT jint JNICALL
Java_io_github_pinguplay_display_AhbFrames_nativeFd(JNIEnv *env, jclass cls, jlong buffer)
{
    (void)env;
    (void)cls;
    get_native_handle_fn fn = get_native_handle();
    if (!fn) return -1;
    const struct native_handle *handle = fn((const AHardwareBuffer *)(uintptr_t)buffer);
    return (handle && handle->numFds > 0) ? handle->data[0] : -1;
}

/* Binds the buffer to the currently bound GL_TEXTURE_2D; returns the EGLImage (0 on failure). */
JNIEXPORT jlong JNICALL
Java_io_github_pinguplay_display_AhbFrames_nativeBindTexture(JNIEnv *env, jclass cls, jlong buffer)
{
    (void)env;
    (void)cls;
    EGLClientBuffer client = eglGetNativeClientBufferANDROID((const AHardwareBuffer *)(uintptr_t)buffer);
    if (!client) return 0;
    const EGLint attrs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
    EGLImageKHR image = eglCreateImageKHR(eglGetCurrentDisplay(), EGL_NO_CONTEXT,
                                          EGL_NATIVE_BUFFER_ANDROID, client, attrs);
    if (image == EGL_NO_IMAGE_KHR) return 0;
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, (GLeglImageOES)image);
    return (jlong)(uintptr_t)image;
}

JNIEXPORT void JNICALL
Java_io_github_pinguplay_display_AhbFrames_nativeRelease(JNIEnv *env, jclass cls, jlong buffer, jlong image)
{
    (void)env;
    (void)cls;
    if (image) eglDestroyImageKHR(eglGetCurrentDisplay(), (EGLImageKHR)(uintptr_t)image);
    if (buffer) AHardwareBuffer_release((AHardwareBuffer *)(uintptr_t)buffer);
}
