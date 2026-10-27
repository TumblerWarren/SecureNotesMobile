#include <jni.h>
#include <sodium.h>

JNIEXPORT jbyteArray JNICALL Java_app_securenote_vault_Sodium_deriveNative(
        JNIEnv *env, jobject self, jbyteArray password, jbyteArray salt) {
    (void) self;
    if (password == NULL || salt == NULL || (*env)->GetArrayLength(env, salt) != crypto_pwhash_SALTBYTES) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), "Invalid KDF input");
        return NULL;
    }
    if (sodium_init() < 0) return NULL;
    jsize length = (*env)->GetArrayLength(env, password);
    jbyte *pw = (*env)->GetByteArrayElements(env, password, NULL);
    if (pw == NULL) return NULL;
    unsigned char salt_copy[crypto_pwhash_SALTBYTES];
    (*env)->GetByteArrayRegion(env, salt, 0, sizeof salt_copy, (jbyte *)salt_copy);
    unsigned char key[32];
    int result = crypto_pwhash(key, sizeof key, (const char *)pw, (unsigned long long)length,
            salt_copy, 3, 64U * 1024U * 1024U, crypto_pwhash_ALG_ARGON2ID13);
    sodium_memzero(pw, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, password, pw, 0);
    sodium_memzero(salt_copy, sizeof salt_copy);
    jbyteArray output = NULL;
    if (result == 0) {
        output = (*env)->NewByteArray(env, sizeof key);
        if (output != NULL) (*env)->SetByteArrayRegion(env, output, 0, sizeof key, (jbyte *)key);
    } else {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "Insufficient memory to unlock vault");
    }
    sodium_memzero(key, sizeof key);
    return output;
}
