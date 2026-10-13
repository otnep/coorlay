# Concentus (vendored)

The Java port of the Opus reference library from
[lostromb/concentus](https://github.com/lostromb/concentus) at commit
`3885c4e46513ef0fc81fca100189e54f1714c6ca`: every file of `Java/Concentus/src/main/java/org/concentus`,
unmodified. Upstream publishes no release artifact of this port.

Copied from DiPlay 0.2.16 (`shared/src/main/java/org/concentus`). coorlay uses `OpusEncoder` for the
wireless CarPlay microphone and `OpusDecoder` for the iPhone's Opus voice streams, because Android 4.3
has no MediaCodec Opus codec. See `legacy-core/src/main/java/com/coorlay/media/SoftwareOpus.kt`.

Licensed under the BSD-style licence in `LICENSE`, which retains the Opus copyright notices.
