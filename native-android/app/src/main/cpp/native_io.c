#include <jni.h>
#include <fcntl.h>

/* O_DIRECT is part of the Android NDK, but not the Java SDK. Set it before
 * any plaintext read and fail closed if the filesystem cannot support it. */
JNIEXPORT void JNICALL Java_app_securenote_vault_NativeIo_makeUncached(
        JNIEnv *env, jobject self, jint fd) {
    (void)self;
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_DIRECT) < 0) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"),
                        "Uncached native viewing is unavailable");
        return;
    }
    flags = fcntl(fd, F_GETFL);
    if (flags < 0 || !(flags & O_DIRECT)) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/io/IOException"),
                        "Uncached native viewing is unavailable");
    }
}
