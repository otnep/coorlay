#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <time.h>

#define JNI_NAME(name) Java_com_coorlay_transport_LegacyUsbNative_##name
static int result(int value) { return value < 0 ? -errno : value; }

static int detach_kernel_driver(int fd, int iface) {
    struct usbdevfs_getdriver owner = { .interface = (unsigned int)iface };
    if (ioctl(fd, USBDEVFS_GETDRIVER, &owner) < 0) return errno == ENODATA ? 0 : -errno;
    owner.driver[sizeof(owner.driver) - 1] = '\0';
    // Another userspace session must release its own claim. DISCONNECT must not steal it.
    if (strcmp(owner.driver, "usbfs") == 0) return -EBUSY;
    struct usbdevfs_ioctl detach = { .ifno = iface, .ioctl_code = USBDEVFS_DISCONNECT, .data = NULL };
    if (ioctl(fd, USBDEVFS_IOCTL, &detach) < 0) return errno == ENODATA ? 0 : -errno;
    return 1;
}
JNIEXPORT jstring JNICALL JNI_NAME(driver)(JNIEnv *env, jobject self, jint fd, jint iface) {
    (void)self;
    struct usbdevfs_getdriver owner = { .interface = (unsigned int)iface };
    if (ioctl(fd, USBDEVFS_GETDRIVER, &owner) < 0) {
        if (errno == ENODATA) return NULL;
        char message[96];
        snprintf(message, sizeof(message), "GETDRIVER interface %d: errno=%d", iface, errno);
        jclass exception = (*env)->FindClass(env, "java/io/IOException");
        if (exception != NULL) (*env)->ThrowNew(env, exception, message);
        return NULL;
    }
    owner.driver[sizeof(owner.driver) - 1] = '\0';
    return (*env)->NewStringUTF(env, owner.driver);
}
JNIEXPORT jint JNICALL JNI_NAME(detach)(JNIEnv *env, jobject self, jint fd, jint iface) {
    (void)env; (void)self;
    return detach_kernel_driver(fd, iface);
}
JNIEXPORT jint JNICALL JNI_NAME(attach)(JNIEnv *env, jobject self, jint fd, jint iface) {
    (void)env; (void)self;
    struct usbdevfs_ioctl attach = { .ifno = iface, .ioctl_code = USBDEVFS_CONNECT, .data = NULL };
    return result(ioctl(fd, USBDEVFS_IOCTL, &attach));
}

JNIEXPORT jint JNICALL JNI_NAME(claim)(JNIEnv *env, jobject self, jint fd, jint iface) {
    (void)env; (void)self;
    unsigned int number = (unsigned int)iface;
    int value = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &number);
    if (value < 0 && errno == EBUSY) {
        int detached = detach_kernel_driver(fd, iface);
        if (detached >= 0) {
            value = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &number);
            if (value >= 0) return detached;
            int failure = errno;
            if (detached == 1) {
                struct usbdevfs_ioctl attach = { .ifno = iface, .ioctl_code = USBDEVFS_CONNECT, .data = NULL };
                (void)ioctl(fd, USBDEVFS_IOCTL, &attach);
            }
            return -failure;
        }
        return detached;
    }
    return result(value);
}
JNIEXPORT jint JNICALL JNI_NAME(release)(JNIEnv *env, jobject self, jint fd, jint iface, jboolean restore) {
    (void)env; (void)self;
    unsigned int number = (unsigned int)iface;
    int value = result(ioctl(fd, USBDEVFS_RELEASEINTERFACE, &number));
    if (restore) {
        struct usbdevfs_ioctl attach = { .ifno = iface, .ioctl_code = USBDEVFS_CONNECT, .data = NULL };
        (void)ioctl(fd, USBDEVFS_IOCTL, &attach);
    }
    return value;
}
JNIEXPORT jint JNICALL JNI_NAME(configuration)(JNIEnv *env, jobject self, jint fd, jint config) {
    (void)env; (void)self;
    unsigned int number = (unsigned int)config;
    return result(ioctl(fd, USBDEVFS_SETCONFIGURATION, &number));
}
JNIEXPORT jint JNICALL JNI_NAME(alternate)(JNIEnv *env, jobject self, jint fd, jint iface, jint alt) {
    (void)env; (void)self;
    struct usbdevfs_setinterface setting = { .interface = (unsigned int)iface, .altsetting = (unsigned int)alt };
    return result(ioctl(fd, USBDEVFS_SETINTERFACE, &setting));
}
JNIEXPORT jint JNICALL JNI_NAME(transfer)(JNIEnv *env, jobject self, jint fd, jint endpoint,
    jbyteArray data, jint offset, jint length, jint timeout) {
    (void)self;
    if (fd < 0) return -EBADF;
    if (data == NULL) return -EINVAL;
    jsize capacity = (*env)->GetArrayLength(env, data);
    if (offset < 0 || length < 1 || offset > capacity - length || timeout < 1) return -EINVAL;
    // Old USBfs kernels limit synchronous bulk operations to 16 KiB. Never pin a Java array
    // across a blocking ioctl: that would stall garbage collection and other USB streams.
    int chunk = length > 16384 ? 16384 : length;
    unsigned char *buffer = malloc((size_t)chunk);
    if (buffer == NULL) return -ENOMEM;
    int transferred = 0;
    int failure = 0;
    struct timespec now;
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) { free(buffer); return -errno; }
    long long deadline = now.tv_sec * 1000LL + now.tv_nsec / 1000000LL + timeout;
    do {
        if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) { failure = -errno; break; }
        long long time_left = deadline - (now.tv_sec * 1000LL + now.tv_nsec / 1000000LL);
        if (time_left <= 0) { failure = -ETIMEDOUT; break; }
        int remaining = length - transferred;
        int amount = remaining > 16384 ? 16384 : remaining;
        if (!(endpoint & 0x80)) {
            (*env)->GetByteArrayRegion(env, data, offset + transferred, amount, (jbyte *)buffer);
            if ((*env)->ExceptionCheck(env)) { failure = -EINVAL; break; }
        }
        struct usbdevfs_bulktransfer bulk = { .ep = (unsigned int)endpoint,
            .len = (unsigned int)amount, .timeout = (unsigned int)time_left, .data = buffer };
        int count = ioctl(fd, USBDEVFS_BULK, &bulk);
        if (count < 0) { failure = -errno; break; }
        if (endpoint & 0x80) (*env)->SetByteArrayRegion(env, data, offset, count, (jbyte *)buffer);
        transferred += count;
        if ((endpoint & 0x80) || count < amount) break;
    } while (transferred < length);
    free(buffer);
    return transferred > 0 ? transferred : failure;
}
JNIEXPORT jint JNICALL JNI_NAME(setBlocking)(JNIEnv *env, jobject self, jint fd) {
    (void)env; (void)self;
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0) return -errno;
    return result(fcntl(fd, F_SETFL, flags & ~O_NONBLOCK));
}
