# libopus (vendored)

The Opus reference library, release 1.5.2 from
[downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz](https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz)
(SHA-256 `65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1`, as in xiph's `SHA256SUMS.txt`).

Kept unmodified: `include/`, `celt/`, `silk/`, `src/`, the `*_sources.mk` lists, `COPYING` and `AUTHORS`.
Left out: `dnn/` (deep PLC, DRED, OSCE: never enabled here), `silk/float/`, the tests, demos, the build
systems, and the multistream, projection and float analysis sources coorlay does not compile.

`../Android.mk` builds it fixed point as `libcoorlay_opus.so` together with `../opus_jni.c`; ARMv7 adds
the NEON intrinsics, picked at run time. `com.coorlay.media.SoftwareOpus.kt` uses it for the wireless
microphone and the iPhone's Opus voice streams, with Concentus (`org.concentus`) as the fallback.

To update: unpack the new release over these directories, delete what is listed as left out above, and
rebuild; `Android.mk` takes the file lists from the `*_sources.mk` files.

Licensed under the BSD-style licence in `COPYING`.
