package com.broadcast.adpcm.codec;

/**
 * G726Codec.java
 *
 * Implementasi encoder ADPCM ITU-T G.726 mode 32 kbps (4 bit per sampel).
 * Dibangun dari nol mengacu pada spesifikasi resmi ITU-T G.726 dan
 * referensi publik Sun Microsystems g72x.c (CCITT 1988).
 *
 * Seluruh operasi menggunakan aritmetika integer fixed-point — tidak ada
 * floating-point — sesuai cara kerja hardware DSP pada implementasi nyata.
 *
 * Blok fungsional yang diimplementasikan (sesuai diagram ITU-T G.726):
 *   Blok 1 : Input PCM format conversion     (scale 16-bit → 13-bit)
 *   Blok 2 : Difference signal computation   (d = sl - se)
 *   Blok 3 : Adaptive quantizer              (quantize)
 *   Blok 4 : Inverse adaptive quantizer      (reconstruct) ← jalur feedback
 *   Blok 5 : Reconstructed signal            (sr = se + dq) ← jalur feedback
 *   Blok 6 : Adaptive predictor              (predictorZero + predictorPole)
 *   Blok 7 : Quantizer scale factor adapt.   (stepSize + update)
 *   Blok 8 : Adaptation speed control        (update)
 *   Blok 9 : Tone and transition detector    (update)
 */
public final class G726Codec {

    // =========================================================
    // ENCODE — PCM 16-bit → kode ADPCM 4-bit
    // =========================================================

    /**
     * Mengodekan satu sampel PCM 16-bit menjadi kode ADPCM 4-bit.
     *
     * @param pcm16  sampel PCM 16-bit signed input [-32768, 32767]
     * @param state  state codec yang diperbarui setiap sampel
     * @return       kode ADPCM 4-bit [0-15]
     */
    public static int encode(int pcm16, G726State state) {

        // Blok 1: scale input 16-bit → 14-bit
        // Referensi Sun g72x.c/g721.c: "sl >>= 2" (14-bit dynamic range),
        // BUKAN >>3 (versi sebelumnya salah skala ke 13-bit)
        int sl = pcm16 >> 2;

        // Blok 6: hitung sinyal prediksi se dari state sebelumnya
        int sezi = predictorZero(state);   // kontribusi filter FIR (b[])
        int sez  = sezi >> 1;
        int sei  = sezi + predictorPole(state); // tambah filter IIR (a[])
        int se   = sei >> 1;

        // Blok 7: hitung step size y (gabungan fast dan slow scale factor)
        int y = stepSize(state);

        // Blok 2: hitung selisih sinyal
        int d = sl - se;

        // Blok 3: kuantisasi adaptif → kode 4-bit
        int I = quantize(d, y);

        // Blok 4: inverse quantizer (jalur feedback encoder)
        int dq = ssxreconstruct(I & 8, G726Tables.DQLN_TABLE[I], y);

        // Blok 5: sinyal rekonstruksi (jalur feedback encoder)
        int sr = (dq < 0) ? se - (dq & 0x3FFF) : se + dq;

        // Blok 6,7,8,9: update seluruh state untuk sampel berikutnya
        int dqsez = sr + sez - se;
        update(y, G726Tables.WI_TABLE[I] << 5, G726Tables.FI_TABLE[I],
               dq, sr, dqsez, state);

        return I;
    }

    // =========================================================
    // DECODE — kode ADPCM 4-bit → PCM 16-bit
    // =========================================================

    /**
     * Mendekodekan satu kode ADPCM 4-bit menjadi sampel PCM 16-bit.
     *
     * Menggunakan PERSIS jalur feedback yang sama dengan encode()
     * (Blok 4,5,6,7,8,9) — bedanya di sini kode I datang dari jaringan,
     * bukan dari hasil quantize() atas sinyal asli. State HARUS
     * diinisialisasi terpisah dari state encoder manapun, dan dipanggil
     * berurutan sesuai urutan kode yang diterima agar tetap sinkron
     * (implicit synchronization, prinsip standar ADPCM).
     *
     * @param code   kode ADPCM 4-bit [0-15] hasil terima dari jaringan
     * @param state  state codec (terpisah dari state encoder manapun)
     * @return       sampel PCM 16-bit hasil rekonstruksi
     */
    public static int decode(int code, G726State state) {
        int I = code & 0xF;
        // (skala output nanti di baris "pcm16 = sr << 2" — simetris dengan
        // "sl = pcm16 >> 2" di encode())

        // Blok 6: prediksi (identik dengan encode)
        int sezi = predictorZero(state);
        int sez  = sezi >> 1;
        int sei  = sezi + predictorPole(state);
        int se   = sei >> 1;

        // Blok 7: step size (identik dengan encode)
        int y = stepSize(state);

        // Blok 4: inverse quantizer — di sinilah bedanya dengan encode:
        // I datang langsung dari kode diterima, bukan dari quantize(d, y)
        int dq = ssxreconstruct(I & 8, G726Tables.DQLN_TABLE[I], y);

        // Blok 5: sinyal rekonstruksi
        int sr = (dq < 0) ? se - (dq & 0x3FFF) : se + dq;

        // Blok 6,7,8,9: update state — WAJIB identik urutannya dengan encode
        int dqsez = sr + sez - se;
        update(y, G726Tables.WI_TABLE[I] << 5, G726Tables.FI_TABLE[I],
               dq, sr, dqsez, state);

        // Inverse Blok 1: skala 14-bit kembali ke 16-bit
        // Referensi: "return (sr << 2);" — simetris dengan encode "sl = pcm16 >> 2"
        int pcm16 = sr << 2;
        return Math.max(-32768, Math.min(32767, pcm16));
    }

    // =========================================================
    // BLOK 6A — Predictor Zero (filter FIR, koefisien b[])
    // =========================================================

    /**
     * Menghitung kontribusi filter zeros:
     *   SEZ = b[0]*dq[0] + b[1]*dq[1] + ... + b[5]*dq[5]
     */
    private static int predictorZero(G726State s) {
        int sezi = fmult(s.b[0] >> 2, s.dq[0]);
        for (int i = 1; i < 6; i++) {
            sezi += fmult(s.b[i] >> 2, s.dq[i]);
        }
        return sezi;
    }

    // =========================================================
    // BLOK 6B — Predictor Pole (filter IIR, koefisien a[])
    // =========================================================

    /**
     * Menghitung kontribusi filter poles:
     *   SEP = a[0]*sr[0] + a[1]*sr[1]
     */
    private static int predictorPole(G726State s) {
        return fmult(s.a[0] >> 2, s.sr[0])
             + fmult(s.a[1] >> 2, s.sr[1]);
    }

    // =========================================================
    // BLOK 7 — Step Size
    // =========================================================

    /**
     * Menghitung step size y sebagai blending antara:
     *   yu  = fast scale factor (reaktif terhadap perubahan amplitudo)
     *   yl  = slow scale factor (stabil, konvergen lambat)
     *
     * Jika ap >= 256 (mode cepat penuh), langsung pakai yu.
     * Selain itu, interpolasi linear berdasarkan nilai ap.
     */
    private static int stepSize(G726State s) {
        if (s.ap >= 256) return s.yu;
        int y   = (int)(s.yl >> 6);
        int dif = s.yu - y;
        int al  = s.ap >> 2;
        if      (dif > 0) y += (dif * al) >> 6;
        else if (dif < 0) y += (dif * al + 0x3F) >> 6;
        return y;
    }

    // =========================================================
    // BLOK 3 — Adaptive Quantizer
    // =========================================================

    /**
     * Mengkuantisasi selisih d menjadi kode 4-bit I.
     *
     * Langkah:
     *   1. Hitung log2(|d|) dalam format Q7 → dl
     *   2. Normalisasi: dln = dl - (y >> 2)
     *   3. Cari level kuantisasi via QTAB_32
     *   4. Terapkan bit tanda (bit-3 dari I)
     *
     * Untuk G.726 4-bit:
     *   I = 0..7  → kode positif (bit-3 = 0)
     *   I = 8..15 → kode negatif (bit-3 = 1)
     *   Kode negatif: I = (15 - level_magnitude)
     */
    private static int quantize(int d, int y) {
        int dqm  = Math.abs(d);
        int exp  = search(dqm >> 1, G726Tables.POWER2, 15);
        int mant = ((dqm << 7) >> exp) & 0x7F;
        int dl   = (exp << 7) + mant;
        int dln  = dl - (y >> 2);
        int i    = search(dln, G726Tables.QTAB_32, 7);
        // Referensi Sun g72x.c (revisi 1988): magnitude granular terkecil
        // (i==0) untuk d>=0 WAJIB dipetakan ke kode 15, bukan 0 - kode 0
        // secara desain tidak pernah dihasilkan quantize() untuk sinyal nyata.
        if (d < 0)       return 15 - i;
        else if (i == 0) return 15;
        else             return i;
    }

    // =========================================================
    // BLOK 4 — Inverse Adaptive Quantizer
    // =========================================================

    /**
     * Merekonstruksi nilai selisih dq dari kode I dan step size y.
     *
     * Langkah:
     *   1. De-normalisasi: dql = dqln + (y >> 2)
     *   2. Antilog: dq = dqt * 2^(dex-7)
     *   3. Terapkan tanda
     *
     * Nilai negatif dikodekan sebagai (magnitude - 0x8000)
     * sehingga magnitude dapat diekstrak dengan (dq & 0x3FFF).
     *
     * @param sign  bit tanda — 0 = positif, non-zero = negatif
     * @param dqln  nilai dari DQLN_TABLE[I]
     * @param y     step size
     */
    private static int ssxreconstruct(int sign, int dqln, int y) {
        int dql = dqln + (y >> 2);
        if (dql < 0) return (sign != 0) ? -0x8000 : 0;
        int dex = (dql >> 7) & 15;
        int dqt = 128 + (dql & 127);
        int dq  = (dqt << 7) >> (14 - dex);
        return (sign != 0) ? (dq - 0x8000) : dq;
    }

    // =========================================================
    // BLOK 6,7,8,9 — Update State
    // =========================================================

    /**
     * Memperbarui seluruh variabel state G.726 setelah setiap sampel.
     * Urutan update ini WAJIB identik antara encoder dan decoder
     * untuk menjaga implicit synchronization.
     *
     * Yang diperbarui:
     *   (a) Scale factor  : yu (fast), yl (slow)
     *   (b) Speed control : dms, dml, ap
     *   (c) Tone detector : td
     *   (d) Predictor     : a[], b[], dq[], sr[], pk[]
     *
     * @param y      step size saat ini
     * @param wi     G726Tables.WI_TABLE[I]
     * @param fi     G726Tables.FI_TABLE[I]
     * @param dq     output reconstruct()
     * @param sr     sinyal rekonstruksi (se + dq)
     * @param dqsez  sr + sez - se
     * @param s      state yang diperbarui
     */
    private static void update(int y, int wi, int fi,
                                int dq, int sr, int dqsez,
                                G726State s) {

        int pk0 = (dqsez < 0) ? 1 : 0;
        int mag = dq & 0x7FFF;

        // (TRANS) Deteksi transisi data/modem vs suara — dipakai untuk
        // memutuskan apakah predictor harus di-reset paksa ke nol.
        // Referensi: Sun g72x.c update(), blok TRANS.
        int ylint  = (int) (s.yl >> 15);
        int ylfrac = (int) ((s.yl >> 10) & 0x1F);
        int thr1   = (32 + ylfrac) << ylint;
        int thr2   = (ylint > 9) ? (31 << 10) : thr1;
        int dqthr  = (thr2 + (thr2 >> 1)) >> 1;
        boolean tr;
        if      (s.td == 0)   tr = false;
        else if (mag <= dqthr) tr = false;
        else                   tr = true;

        // (a) Scale factor adaptation
        // yu: fast — (1 - 2^-5)*yu + 2^-5*W(I)
        int yuv = y + ((wi - y) >> 5);
        s.yu = Math.max(544, Math.min(5120, yuv));
        // yl: slow — konvergen ke 64*yu
        s.yl += s.yu + ((-s.yl) >> 6);

        // (d) Adaptive predictor — koefisien a[] (poles / IIR)
        // Port setia dari Sun g72x.c: blok UPA2 (a[1]) WAJIB pakai logika
        // LIMC (pembatas kestabilan 3-cabang) di bawah ini — versi
        // sebelumnya memakai rumus leaky-integrator sederhana yang TIDAK
        // punya mekanisme kestabilan ini, menyebabkan predictor runaway
        // tak terkendali untuk sinyal nyata (voice) sampai saturasi penuh.
        int pks1 = pk0 ^ s.pk[0];
        int a2p;
        if (tr) {
            // Sinyal terdeteksi sebagai data/modem: reset predictor
            s.a[0] = 0;
            s.a[1] = 0;
            for (int i = 0; i < 6; i++) s.b[i] = 0;
            a2p = 0;
        } else {
            // UPA2: update a[1]
            a2p = s.a[1] - (s.a[1] >> 7);
            if (dqsez != 0) {
                int fa1 = (pks1 != 0) ? s.a[0] : -s.a[0];
                if      (fa1 < -8191) a2p -= 0x100;
                else if (fa1 >  8191) a2p += 0xFF;
                else                  a2p += fa1 >> 5;

                // LIMC — pembatas kestabilan pole a[1]
                if ((pk0 ^ s.pk[1]) != 0) {
                    if      (a2p <= -12160) a2p = -12288;
                    else if (a2p >=  12416) a2p =  12288;
                    else                    a2p -= 0x80;
                } else {
                    if      (a2p <= -12416) a2p = -12288;
                    else if (a2p >=  12160) a2p =  12288;
                    else                    a2p += 0x80;
                }
            }
            s.a[1] = a2p;

            // UPA1: update a[0]
            s.a[0] -= s.a[0] >> 8;
            if (dqsez != 0) {
                if (pks1 == 0) s.a[0] += 192;
                else           s.a[0] -= 192;
            }
            // LIMD — batas a[0] bergantung pada a[1] yang baru diperbarui
            int a1ul = 15360 - a2p;
            s.a[0] = Math.max(-a1ul, Math.min(a1ul, s.a[0]));

            // UPB: update koefisien b[] (zeros / FIR)
            for (int i = 0; i < 6; i++) {
                s.b[i] -= s.b[i] >> 8;
                if ((dq & 0x7FFF) != 0) {
                    if ((dq ^ s.dq[i]) >= 0) s.b[i] += 128;
                    else                     s.b[i] -= 128;
                }
            }
        }

        // Shift register: simpan riwayat dq, sr, pk
        System.arraycopy(s.dq, 0, s.dq, 1, 5);
        s.dq[0] = floatConv(dq);
        s.sr[1] = s.sr[0];
        s.sr[0] = floatConv(sr);
        s.pk[1] = s.pk[0];
        s.pk[0] = pk0;

        // (c) Tone detector — dievaluasi SETELAH predictor diperbarui,
        // dan dilewati (dipaksa 0) saat mode data/modem terdeteksi
        if      (tr)             s.td = 0;
        else if (a2p < -11776)   s.td = 1;
        else                     s.td = 0;

        // (b) Adaptation speed control
        s.dms += (fi - s.dms) >> 5;          // short-term average F(I)
        s.dml += ((fi << 2) - s.dml) >> 7;   // long-term average F(I)
        if      (tr)                                                  s.ap += (0x200 - s.ap) >> 4;
        else if (y < 1536)                                            s.ap += (0x200 - s.ap) >> 4;
        else if (s.td == 1)                                           s.ap += (0x200 - s.ap) >> 4;
        else if (Math.abs((s.dms << 2) - s.dml) >= (s.dml >> 3))      s.ap += (0x200 - s.ap) >> 4;
        else                                                           s.ap += (-s.ap) >> 4;
        s.ap = Math.max(0, Math.min(256, s.ap));
    }

    // =========================================================
    // FUNGSI PEMBANTU INTERNAL
    // =========================================================

    /**
     * fmult: perkalian koefisien prediktor dengan sinyal.
     *
     * Kedua operand dalam format internal floating-point Sun g72x:
     *   an  = koefisien (a[i]>>2 atau b[i]>>2), Q13
     *   srn = sinyal riwayat, format:
     *         bit[5:0]  mantissa [32-63]
     *         bit[9:6]  eksponent [0-15]
     *         bit[10]   tanda (0=positif, 1=negatif)
     */
    private static int fmult(int an, int srn) {
        int anmag  = (an >= 0) ? an : ((-an) & 0x1FFF);
        int anexp  = search(anmag, G726Tables.POWER2, 15) - 6;
        int anmant;
        if      (anmag == 0) anmant = 32;
        else if (anexp >= 0) anmant = Math.min(63, anmag >> anexp);
        else                 anmant = Math.min(63, anmag << (-anexp));
        anmant = Math.max(32, anmant);

        int srexp  = (srn >> 6) & 0xF;
        int srmant = srn & 0x3F;
        int wexp   = anexp + srexp - 13;
        int wmant  = (anmant * srmant + 0x30) >> 4;
        int retval = (wexp >= 0)
                   ? Math.min(0x7FFF, wmant << wexp)
                   : (wmant >> (-wexp));

        // tanda hasil = XOR tanda(an) dan tanda(srn)
        int asign = (an < 0) ? 1 : 0;
        int ssign = (srn >> 10) & 1;
        return ((asign ^ ssign) != 0) ? -retval : retval;
    }

    /**
     * floatConv: konversi nilai linear ke format internal floating-point.
     *
     * Format output (11-bit dalam int):
     *   bit[5:0]  mantissa [32-63], nilai 32 = nol
     *   bit[9:6]  eksponent [0-15]
     *   bit[10]   tanda (0=positif, 1=negatif)
     */
    private static int floatConv(int val) {
        int mag  = (val >= 0) ? val : (~val);
        int exp  = search(mag, G726Tables.POWER2, 15);
        int mant = (exp == 0) ? ((mag << 1) & 0x3F)
                              : (0x20 | ((mag >> (exp - 1)) & 0x1F));
        mant = Math.max(32, Math.min(63, mant));
        return ((val < 0) ? 0x400 : 0) | (exp << 6) | mant;
    }

    /**
     * search: cari indeks pertama di mana val < tab[i].
     * Mengembalikan size jika val >= semua elemen tabel.
     */
    private static int search(int val, int[] tab, int size) {
        for (int i = 0; i < size; i++) if (val < tab[i]) return i;
        return size;
    }

    private G726Codec() {}
}