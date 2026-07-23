package com.broadcast.adpcm.codec;

/**
 * DPCMCodecAdapter.java
 *
 * Membungkus DPCMCodec + DPCMState ke antarmuka AudioCodec, sebagai
 * metode pembanding (baseline) sesuai Sub-bab 3.3.3.
 */
public final class DPCMCodecAdapter implements AudioCodec {

    private final DPCMState state = new DPCMState();

    @Override
    public int encode(int pcm16) {
        return DPCMCodec.encode(pcm16, state);
    }

    @Override
    public int decode(int code) {
        // code diterima sebagai nibble mentah unsigned [0-15] hasil unpack
        // byte. DPCM pakai representasi two's complement 4-bit (signed),
        // jadi perlu di-sign-extend dulu ke rentang [-8,7] sebelum dipakai
        // (lihat catatan bit-packing di BroadcastEngine.processAudioChunk).
        int signExtended = (code >= 8) ? (code - 16) : code;
        return DPCMCodec.decode(signExtended, state);
    }

    @Override
    public int getBitsPerSample() {
        return 4;
    }

    @Override
    public String getName() {
        return "DPCM";
    }
}