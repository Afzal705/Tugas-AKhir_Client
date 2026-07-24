package com.broadcast.adpcm.client;

import com.broadcast.adpcm.network.packet.SimplePacketFormatter.PacketInfo;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QosMetrics.java
 *
 * Menghitung metrik QoS dari paket yang diterima: packet loss, jitter
 * (RFC 3550), estimasi delay, dan throughput. Dirancang untuk kebutuhan
 * data Bab 4 (perbandingan ADPCM vs DPCM di berbagai skenario).
 *
 * CATATAN PENTING soal delay: nilai delay di sini dihitung sebagai
 * (waktu terima lokal - timestamp pengirim yang tertanam di paket).
 * Ini HANYA akurat kalau jam server dan client tersinkron (mis. via NTP) -
 * kalau tidak, anggap nilai delay sebagai estimasi kasar saja, dan
 * fokuskan analisis pada JITTER (yang tetap valid TANPA sinkronisasi jam,
 * karena jitter dihitung dari SELISIH antar delay pada clock yang sama,
 * bukan dari nilai delay absolut).
 *
 * Method di kelas ini di-synchronized supaya aman dipanggil dari 1 thread
 * penerima paket, sederhana dan cukup untuk kebutuhan ini (bukan didesain
 * untuk dipanggil paralel dari banyak thread sekaligus).
 */
public final class QosMetrics {

    private final AtomicInteger expectedSeq       = new AtomicInteger(-1);
    private final AtomicInteger packetsOk         = new AtomicInteger(0);
    private final AtomicInteger packetsLost       = new AtomicInteger(0);
    private final AtomicInteger packetsOutOfOrder = new AtomicInteger(0);
    private final AtomicLong    bytesTotal        = new AtomicLong(0);

    // RFC 3550 interarrival jitter (dalam ms, running estimate)
    private double jitterMs      = 0.0;
    private long   prevTransitMs = Long.MIN_VALUE;

    private final long startTimeMillis;
    private BufferedWriter csvWriter;

    /**
     * @param csvOutputPath path file CSV untuk log per-paket, atau null
     *                      kalau tidak perlu logging detail per-paket
     *                      (cukup ringkasan lewat toString()/getter)
     */
    public QosMetrics(String csvOutputPath) {
        this.startTimeMillis = System.currentTimeMillis();
        if (csvOutputPath != null) {
            try {
                csvWriter = new BufferedWriter(new FileWriter(csvOutputPath));
                csvWriter.write("seq,receive_time_ms,send_timestamp_ms,delay_ms,jitter_ms,cumulative_loss,payload_bytes\n");
            } catch (IOException e) {
                System.err.println("Gagal membuka file metrik CSV: " + csvOutputPath + " - " + e.getMessage());
                csvWriter = null;
            }
        }
    }

    /**
     * Catat 1 paket yang baru diterima. Panggil ini SEKALI per paket,
     * sesegera mungkin setelah packet diterima dari socket (supaya
     * receiveTimeMillis paling akurat).
     */
    public synchronized void recordPacket(PacketInfo info, long receiveTimeMillis) {
        int seq = info.sequenceNumber;

        // --- Packet loss detection ---
        if (expectedSeq.get() == -1) {
            expectedSeq.set(seq); // paket pertama, inisialisasi baseline
        }

        int expected = expectedSeq.get();
        if (seq == expected) {
            packetsOk.incrementAndGet();
            expectedSeq.set(seq + 1);
        } else if (seq > expected) {
            int gap = seq - expected;
            packetsLost.addAndGet(gap);
            packetsOk.incrementAndGet();
            expectedSeq.set(seq + 1);
        } else {
            // seq < expected -> paket telat/out-of-order/duplikat
            packetsOutOfOrder.incrementAndGet();
        }

        bytesTotal.addAndGet(info.payload.length);

        // --- Delay & Jitter (RFC 3550) ---
        long delayMs   = receiveTimeMillis - info.timestamp;
        long transitMs = delayMs;
        if (prevTransitMs != Long.MIN_VALUE) {
            long d = Math.abs(transitMs - prevTransitMs);
            jitterMs += (d - jitterMs) / 16.0;
        }
        prevTransitMs = transitMs;

        if (csvWriter != null) {
            try {
                csvWriter.write(String.format("%d,%d,%d,%d,%.3f,%d,%d%n",
                    seq, receiveTimeMillis, info.timestamp, delayMs, jitterMs,
                    packetsLost.get(), info.payload.length));
            } catch (IOException e) {
                System.err.println("Gagal menulis baris metrik CSV: " + e.getMessage());
            }
        }
    }

    /** Tutup file CSV (kalau ada). Panggil ini saat client berhenti. */
    public void close() {
        if (csvWriter != null) {
            try {
                csvWriter.flush();
                csvWriter.close();
            } catch (IOException e) {
                System.err.println("Gagal menutup file metrik CSV: " + e.getMessage());
            }
        }
    }

    public double getThroughputKbps() {
        long elapsedSec = Math.max(1, (System.currentTimeMillis() - startTimeMillis) / 1000);
        return (bytesTotal.get() * 8.0 / 1000.0) / elapsedSec;
    }

    public int getPacketsOk()         { return packetsOk.get(); }
    public int getPacketsLost()       { return packetsLost.get(); }
    public int getPacketsOutOfOrder() { return packetsOutOfOrder.get(); }
    public double getJitterMs()       { return jitterMs; }

    public double getPacketLossPercent() {
        int total = packetsOk.get() + packetsLost.get();
        return total == 0 ? 0.0 : (100.0 * packetsLost.get() / total);
    }

    @Override
    public String toString() {
        return String.format(
            "QoS[ok=%d, lost=%d (%.2f%%), outOfOrder=%d, jitter=%.2fms, throughput=%.1fkbps]",
            packetsOk.get(), packetsLost.get(), getPacketLossPercent(),
            packetsOutOfOrder.get(), jitterMs, getThroughputKbps()
        );
    }
}