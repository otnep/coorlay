/*
 * libopus for com.coorlay.media.NativeOpus. Packets and 16-bit little-endian PCM stay in the caller's
 * byte arrays (pinned, not copied), so a frame allocates nothing on either side. A negative return is
 * an OPUS_* error code.
 */
#include <jni.h>
#include <stdint.h>
#include "opus.h"

#define JNI_FN(name) Java_com_coorlay_media_NativeOpus_##name

JNIEXPORT jlong JNICALL JNI_FN(decoderCreate)(JNIEnv *env, jclass type, jint rate, jint channels) {
    (void) env; (void) type;
    int error = OPUS_OK;
    OpusDecoder *decoder = opus_decoder_create(rate, channels, &error);
    return error == OPUS_OK ? (jlong) (intptr_t) decoder : 0;
}

/* Decodes one packet, or conceals a lost one when packet is null; returns samples per channel. */
JNIEXPORT jint JNICALL JNI_FN(decode)(JNIEnv *env, jclass type, jlong handle, jbyteArray packet, jint offset,
                                      jint length, jbyteArray pcm, jint maxSamples) {
    (void) type;
    OpusDecoder *decoder = (OpusDecoder *) (intptr_t) handle;
    jbyte *in = packet ? (*env)->GetPrimitiveArrayCritical(env, packet, NULL) : NULL;
    if (packet && !in) return OPUS_ALLOC_FAIL;
    jbyte *out = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    int result = OPUS_ALLOC_FAIL;
    if (out) {
        result = opus_decode(decoder, in ? (const unsigned char *) in + offset : NULL, in ? length : 0,
                             (opus_int16 *) out, maxSamples, 0);
        (*env)->ReleasePrimitiveArrayCritical(env, pcm, out, 0);
    }
    if (in) (*env)->ReleasePrimitiveArrayCritical(env, packet, in, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(decoderDestroy)(JNIEnv *env, jclass type, jlong handle) {
    (void) env; (void) type;
    opus_decoder_destroy((OpusDecoder *) (intptr_t) handle);
}

/* A mono or stereo VoIP encoder tuned for voice, as the Concentus one was. */
JNIEXPORT jlong JNICALL JNI_FN(encoderCreate)(JNIEnv *env, jclass type, jint rate, jint channels, jint bitrate,
                                              jint complexity) {
    (void) env; (void) type;
    int error = OPUS_OK;
    OpusEncoder *encoder = opus_encoder_create(rate, channels, OPUS_APPLICATION_VOIP, &error);
    if (error != OPUS_OK) return 0;
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(complexity));
    opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    return (jlong) (intptr_t) encoder;
}

/* Encodes frameSamples samples per channel from pcm into out; returns the packet length. */
JNIEXPORT jint JNICALL JNI_FN(encode)(JNIEnv *env, jclass type, jlong handle, jbyteArray pcm, jint frameSamples,
                                      jbyteArray out) {
    (void) type;
    OpusEncoder *encoder = (OpusEncoder *) (intptr_t) handle;
    jint capacity = (*env)->GetArrayLength(env, out);
    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, pcm, NULL);
    if (!in) return OPUS_ALLOC_FAIL;
    jbyte *packet = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    int result = OPUS_ALLOC_FAIL;
    if (packet) {
        result = opus_encode(encoder, (const opus_int16 *) in, frameSamples, (unsigned char *) packet, capacity);
        (*env)->ReleasePrimitiveArrayCritical(env, out, packet, 0);
    }
    (*env)->ReleasePrimitiveArrayCritical(env, pcm, in, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL JNI_FN(encoderDestroy)(JNIEnv *env, jclass type, jlong handle) {
    (void) env; (void) type;
    opus_encoder_destroy((OpusEncoder *) (intptr_t) handle);
}
