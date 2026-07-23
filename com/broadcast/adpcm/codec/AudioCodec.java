package com.broadcast.adpcm.codec;

/**
 * AudioCodec.java
 *
 * Abstraksi codec kompresi suara agar BroadcastEngine tidak perlu tahu
 * apakah yang dipakai ADPCM G.726 atau DPCM (pembanding, Sub-bab 3.3.3) -
 * cukup panggil encode() secara generik.
 *
 * Setiap implementasi menyimpan state internalnya sendiri (G726State atau
 * DPCMState), sehingga satu instance = satu sesi encoding yang stateful
 * dan TIDAK thread-safe (harus dipanggil dari satu thread saja).
 */
public interface AudioCodec {

    /**
     * @param pcm16 sampel PCM 16-bit signed [-32768, 32767]
     * @return kode hasil kompresi
     */
    int encode(int pcm16);

    /**
     * Mendekodekan satu kode (nibble mentah, unsigned [0-15] hasil unpack
     * byte) menjadi sampel PCM 16-bit hasil rekonstruksi. Setiap
     * implementasi bertanggung jawab menafsirkan representasi tandanya
     * sendiri (mis. DPCM perlu sign-extend nibble ke [-8,7] sebelum
     * diproses, ADPCM G.726 tidak perlu karena kodenya memang unsigned
     * 0-15).
     *
     * @param code nibble mentah [0-15] yang diterima dari jaringan
     * @return sampel PCM 16-bit hasil rekonstruksi
     */
    int decode(int code);

    /** Jumlah bit per sampel hasil kompresi (dipakai untuk packing byte). */
    int getBitsPerSample();

    /** Nama algoritma, untuk logging/penamaan file output. */
    String getName();
}