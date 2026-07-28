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
 */
public final class G726Codec {

    public static int encode(int pcm16, G726State state) {
        int sl = pcm16 >> 2;

        int sezi = predictorZero(state);
        int sez  = sezi >> 1;
        int sei  = sezi + predictorPole(state);
        int se   = sei >> 1;

        int y = stepSize(state);
        int d = sl - se;
        int I = quantize(d, y);

        int dq = ssxreconstruct(I & 8, G726Tables.DQLN_TABLE[I], y);
        int sr = (dq < 0) ? se - (dq & 0x3FFF) : se + dq;

        int dqsez = sr + sez - se;
        update(y, G726Tables.WI_TABLE[I] << 5, G726Tables.FI_TABLE[I],
               dq, sr, dqsez, state);

        return I;
    }

    public static int decode(int code, G726State state) {
        int I = code & 0xF;

        int sezi = predictorZero(state);
        int sez  = sezi >> 1;
        int sei  = sezi + predictorPole(state);
        int se   = sei >> 1;

        int y = stepSize(state);

        int dq = ssxreconstruct(I & 8, G726Tables.DQLN_TABLE[I], y);
        int sr = (dq < 0) ? se - (dq & 0x3FFF) : se + dq;

        int dqsez = sr + sez - se;
        update(y, G726Tables.WI_TABLE[I] << 5, G726Tables.FI_TABLE[I],
               dq, sr, dqsez, state);

        int pcm16 = sr << 2;
        return Math.max(-32768, Math.min(32767, pcm16));
    }

    private static int predictorZero(G726State s) {
        int sezi = fmult(s.b[0] >> 2, s.dq[0]);
        for (int i = 1; i < 6; i++) {
            sezi += fmult(s.b[i] >> 2, s.dq[i]);
        }
        return sezi;
    }

    private static int predictorPole(G726State s) {
        return fmult(s.a[0] >> 2, s.sr[0])
             + fmult(s.a[1] >> 2, s.sr[1]);
    }

    private static int stepSize(G726State s) {
        if (s.ap >= 256) return s.yu;
        int y   = (int)(s.yl >> 6);
        int dif = s.yu - y;
        int al  = s.ap >> 2;
        if      (dif > 0) y += (dif * al) >> 6;
        else if (dif < 0) y += (dif * al + 0x3F) >> 6;
        return y;
    }

    private static int quantize(int d, int y) {
        int dqm  = Math.abs(d);
        int exp  = search(dqm >> 1, G726Tables.POWER2, 15);
        int mant = ((dqm << 7) >> exp) & 0x7F;
        int dl   = (exp << 7) + mant;
        int dln  = dl - (y >> 2);
        int i    = search(dln, G726Tables.QTAB_32, 7);
        if (d < 0)       return 15 - i;
        else if (i == 0) return 15;
        else             return i;
    }

    private static int ssxreconstruct(int sign, int dqln, int y) {
        int dql = dqln + (y >> 2);
        if (dql < 0) return (sign != 0) ? -0x8000 : 0;
        int dex = (dql >> 7) & 15;
        int dqt = 128 + (dql & 127);
        int dq  = (dqt << 7) >> (14 - dex);
        return (sign != 0) ? (dq - 0x8000) : dq;
    }

    private static void update(int y, int wi, int fi,
                                int dq, int sr, int dqsez,
                                G726State s) {

        int pk0 = (dqsez < 0) ? 1 : 0;
        int mag = dq & 0x7FFF;

        int ylint  = (int) (s.yl >> 15);
        int ylfrac = (int) ((s.yl >> 10) & 0x1F);
        int thr1   = (32 + ylfrac) << ylint;
        int thr2   = (ylint > 9) ? (31 << 10) : thr1;
        int dqthr  = (thr2 + (thr2 >> 1)) >> 1;
        boolean tr;
        if      (s.td == 0)   tr = false;
        else if (mag <= dqthr) tr = false;
        else                   tr = true;

        int yuv = y + ((wi - y) >> 5);
        s.yu = Math.max(544, Math.min(5120, yuv));
        s.yl += s.yu + ((-s.yl) >> 6);

        int pks1 = pk0 ^ s.pk[0];
        int a2p;
        if (tr) {
            s.a[0] = 0;
            s.a[1] = 0;
            for (int i = 0; i < 6; i++) s.b[i] = 0;
            a2p = 0;
        } else {
            a2p = s.a[1] - (s.a[1] >> 7);
            if (dqsez != 0) {
                int fa1 = (pks1 != 0) ? s.a[0] : -s.a[0];
                if      (fa1 < -8191) a2p -= 0x100;
                else if (fa1 >  8191) a2p += 0xFF;
                else                  a2p += fa1 >> 5;

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

            s.a[0] -= s.a[0] >> 8;
            if (dqsez != 0) {
                if (pks1 == 0) s.a[0] += 192;
                else           s.a[0] -= 192;
            }
            int a1ul = 15360 - a2p;
            s.a[0] = Math.max(-a1ul, Math.min(a1ul, s.a[0]));

            for (int i = 0; i < 6; i++) {
                s.b[i] -= s.b[i] >> 8;
                if ((dq & 0x7FFF) != 0) {
                    if ((dq ^ s.dq[i]) >= 0) s.b[i] += 128;
                    else                     s.b[i] -= 128;
                }
            }
        }

        System.arraycopy(s.dq, 0, s.dq, 1, 5);
        s.dq[0] = floatConvDQ(dq);
        s.sr[1] = s.sr[0];
        s.sr[0] = floatConvSR(sr);
        s.pk[1] = s.pk[0];
        s.pk[0] = pk0;

        if      (tr)             s.td = 0;
        else if (a2p < -11776)   s.td = 1;
        else                     s.td = 0;

        s.dms += (fi - s.dms) >> 5;
        s.dml += ((fi << 2) - s.dml) >> 7;
        if (tr) {
            s.ap = 256;
        } else if (y < 1536) {
            s.ap += (0x200 - s.ap) >> 4;
        } else if (s.td == 1) {
            s.ap += (0x200 - s.ap) >> 4;
        } else if (Math.abs((s.dms << 2) - s.dml) >= (s.dml >> 3)) {
            s.ap += (0x200 - s.ap) >> 4;
        } else {
            s.ap += (-s.ap) >> 4;
        }
    }

    private static int fmult(int an, int srn) {
        int anmag  = (an > 0) ? an : ((-an) & 0x1FFF);
        int anexp  = search(anmag, G726Tables.POWER2, 15) - 6;
        int anmant = (anmag == 0) ? 32
                   : (anexp >= 0) ? (anmag >> anexp) : (anmag << -anexp);

        int wanexp  = anexp + ((srn >> 6) & 0xF) - 13;
        int wanmant = (anmant * (srn & 0x3F) + 0x30) >> 4;
        int retval  = (wanexp >= 0)
                    ? ((wanmant << wanexp) & 0x7FFF)
                    : (wanmant >> (-wanexp));

        return ((an ^ srn) < 0) ? -retval : retval;
    }

    private static int floatConvDQ(int dq) {
        int mag = dq & 0x7FFF;
        if (mag == 0) {
            return (dq >= 0) ? 0x20 : (0x20 - 0x400);
        }
        int exp = search(mag, G726Tables.POWER2, 15);
        int val = (exp << 6) + ((mag << 6) >> exp);
        return (dq >= 0) ? val : (val - 0x400);
    }

    private static int floatConvSR(int sr) {
        if (sr == 0) {
            return 0x20;
        } else if (sr > 0) {
            int exp = search(sr, G726Tables.POWER2, 15);
            return (exp << 6) + ((sr << 6) >> exp);
        } else if (sr > -0x8000) {
            int mag = -sr;
            int exp = search(mag, G726Tables.POWER2, 15);
            return (exp << 6) + ((mag << 6) >> exp) - 0x400;
        } else {
            return 0x20 - 0x400;
        }
    }

    private static int search(int val, int[] tab, int size) {
        for (int i = 0; i < size; i++) if (val < tab[i]) return i;
        return size;
    }

    private G726Codec() {}
}