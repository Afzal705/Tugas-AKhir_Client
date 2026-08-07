package com.broadcast.adpcm.tools;

import com.broadcast.adpcm.codec.AudioCodec;
import com.broadcast.adpcm.codec.DPCMCodecAdapter;
import com.broadcast.adpcm.codec.G726CodecAdapter;

import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * OfflineDecoder — alat bantu pengujian bertahap (BUKAN bagian dari
 * aplikasi utama). Men-decode file .adpcm mentah (hasil --save-adpcm di
 * server, format sama persis dengan payload UDP: 2 nibble/byte, nibble
 * tinggi = sampel pertama) menjadi .pcm, TANPA melibatkan jaringan atau
 * ClientEngine sama sekali.
 *
 * Usage: java com.broadcast.adpcm.tools.OfflineDecoder <input.adpcm> <output.pcm> <adpcm|dpcm>
 */
public class OfflineDecoder {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: OfflineDecoder <input.adpcm> <output.pcm> <adpcm|dpcm>");
            System.exit(1);
        }

        String inputPath  = args[0];
        String outputPath = args[1];
        String codecType  = args[2].toLowerCase();

        AudioCodec codec = codecType.equals("dpcm")
            ? new DPCMCodecAdapter()
            : new G726CodecAdapter();

        System.out.println("Codec: " + codec.getName());
        System.out.println("Input : " + inputPath);
        System.out.println("Output: " + outputPath);

        byte[] adpcmData;
        try (FileInputStream fis = new FileInputStream(inputPath)) {
            adpcmData = fis.readAllBytes();
        }
        System.out.println("Ukuran input: " + adpcmData.length + " bytes ("
            + (adpcmData.length * 2) + " nibble/sampel)");

        int sampleCount = adpcmData.length * 2;
        short[] pcm = new short[sampleCount];
        int idx = 0;

        for (byte b : adpcmData) {
            int hi = (b >> 4) & 0x0F;
            int lo = b & 0x0F;
            pcm[idx++] = (short) codec.decode(hi);
            pcm[idx++] = (short) codec.decode(lo);
        }

        byte[] pcmBytes = new byte[sampleCount * 2];
        for (int i = 0; i < sampleCount; i++) {
            pcmBytes[i * 2]     = (byte) (pcm[i] & 0xFF);
            pcmBytes[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xFF);
        }

        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            fos.write(pcmBytes);
        }

        System.out.println("Selesai: " + sampleCount + " sampel PCM ditulis ke " + outputPath);
        System.out.println("Durasi: " + String.format("%.2f", sampleCount / 8000.0) + " detik (asumsi 8000Hz)");
    }
}