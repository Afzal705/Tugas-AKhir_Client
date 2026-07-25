package com.broadcast.adpcm.client;

import com.broadcast.adpcm.audio.AudioConfig;
import com.broadcast.adpcm.codec.AudioCodec;
import com.broadcast.adpcm.codec.DPCMCodecAdapter;
import com.broadcast.adpcm.codec.G726CodecAdapter;
import com.broadcast.adpcm.network.packet.SimplePacketFormatter;
import com.broadcast.adpcm.network.packet.SimplePacketFormatter.PacketInfo;
import com.broadcast.adpcm.network.udp.UDPBroadcastReceiver;
import com.broadcast.adpcm.util.AppLogger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ClientEngine.java
 *
 * Pasangan BroadcastEngine di sisi server, tapi untuk sisi penerima.
 * Pipeline utama: terima paket UDP -> parse -> catat QoS -> decode (skip
 * sync tone di awal sesi) -> (opsional) putar ke speaker & (opsional)
 * simpan PCM hasil decode.
 *
 * CATATAN KRITIS - SYNC TONE:
 * Server mengirim sync tone (1000Hz, 200ms) di awal SETIAP sesi memakai
 * instance codec TERPISAH dari codec audio utama (lihat
 * BroadcastEngine.processSyncTone() di repo server). Kalau nibble sync
 * tone ini ikut di-decode() lewat codec utama milik client, state
 * adaptif codec (predictor G.726 / delta DPCM) akan ter-kontaminasi
 * sebelum audio sungguhan dimulai, membuat awal audio hasil decode
 * rusak/berisik.
 *
 * Solusinya: ClientEngine menghitung total SAMPEL (bukan paket, bukan
 * frame) yang harus dibuang di awal sesi = SYNC_TONE_DURATION_MS/1000 *
 * sampleRate (default 200ms * 8000Hz = 1600 sampel = 20 frame @ 80
 * sampel/frame). Nibble-nibble pertama sejumlah itu TIDAK PERNAH
 * dilempar ke codec.decode() sama sekali (bukan sekadar dibuang hasilnya)
 * supaya state codec tidak tersentuh. Penghitungan berbasis sampel (bukan
 * paket) sengaja dipilih supaya tetap benar walau satu paket berisi lebih
 * dari satu frame, atau batas sync-tone jatuh di tengah suatu paket.
 *
 * CATATAN BIT-PACKING (harus sama persis dengan
 * BroadcastEngine.processAudioChunk() di server):
 * 1 byte payload = 2 nibble. Nibble TINGGI (>>4) = sampel PERTAMA,
 * nibble RENDAH (&0x0F) = sampel KEDUA.
 *
 * AudioCodec.decode(int) menerima nibble MENTAH unsigned [0-15] apa
 * adanya - untuk DPCM, sign-extend ke [-8,7] sudah ditangani otomatis DI
 * DALAM DPCMCodecAdapter.decode(), jadi ClientEngine tidak perlu (dan
 * tidak boleh) melakukan sign-extend sendiri di sini.
 *
 * Heartbeat packet (payload kosong, byte[0]) TIDAK didecode, tapi TETAP
 * dicatat ke QosMetrics karena tetap relevan untuk packet loss/jitter.
 */
public final class ClientEngine {

    // Harus sama persis dengan BroadcastEngine.SYNC_TONE_DURATION_MS di server
    private static final int SYNC_TONE_DURATION_MS = 200;

    private final ClientConfig config;
    private final AudioConfig audioConfig;
    private final AudioCodec codec;
    private final UDPBroadcastReceiver receiver;
    private final QosMetrics qosMetrics;

    // Jumlah sampel yang masih harus dibuang (sisa sync tone di awal sesi).
    // Hanya disentuh oleh receiveThread, tidak perlu atomic.
    private int samplesToSkip;

    private SourceDataLine playbackLine;
    private FileOutputStream pcmFileOut;

    private Thread receiveThread;
    private ScheduledExecutorService statsExecutor;

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Statistik
    private final AtomicInteger packetsReceived  = new AtomicInteger(0);
    private final AtomicInteger heartbeatsSeen   = new AtomicInteger(0);
    private final AtomicInteger malformedPackets = new AtomicInteger(0);
    private final AtomicLong    samplesDecoded   = new AtomicLong(0);
    private long startTime;

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public ClientEngine(ClientConfig config) throws IOException, LineUnavailableException {
        this.config      = config;
        this.audioConfig = config.getAudioConfig();
        this.codec       = createCodec(config.getCodecType());
        this.qosMetrics  = new QosMetrics(config.getMetricsLogPath());

        // Sama seperti UDPBroadcastSender di server: socket dibuat/di-bind di
        // constructor. start() nanti hanya menyalakan flag isRunning + thread.
        if (config.isUseMulticast()) {
            this.receiver = new UDPBroadcastReceiver(config.getMulticastAddress(), config.getUdpPort());
        } else {
            this.receiver = new UDPBroadcastReceiver(config.getUdpPort());
        }

        this.samplesToSkip = audioConfig.getSamplesForDuration(SYNC_TONE_DURATION_MS);
        AppLogger.info("Codec aktif: " + codec.getName());
        AppLogger.info(String.format(
            "Sync tone skip: %d sampel (%dms @ %dHz)",
            samplesToSkip, SYNC_TONE_DURATION_MS, audioConfig.getSampleRate()));

        if (config.isEnablePlayback()) {
            initializePlayback();
        }

        if (config.getOutputPcmPath() != null) {
            pcmFileOut = new FileOutputStream(config.getOutputPcmPath());
            AppLogger.info("PCM output file dibuka: " + config.getOutputPcmPath());
        }
    }

    /**
     * Factory codec berdasarkan pilihan di ClientConfig. HARUS sinkron dengan
     * BroadcastEngine.createCodec() di server - kalau server menambah
     * algoritma baru, tambahkan juga di sini.
     */
    private static AudioCodec createCodec(ClientConfig.CodecType type) {
        switch (type) {
            case DPCM:
                return new DPCMCodecAdapter();
            case ADPCM_G726:
            default:
                return new G726CodecAdapter();
        }
    }

    private void initializePlayback() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(
            audioConfig.getSampleRate(),
            audioConfig.getBitDepth(),
            audioConfig.getChannels(),
            true,   // signed
            false); // little-endian (sama dengan urutan byte yang dipakai saat menulis file PCM)

        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(lineInfo)) {
            throw new LineUnavailableException("Line audio output tidak didukung untuk format: " + format);
        }

        playbackLine = (SourceDataLine) AudioSystem.getLine(lineInfo);
        playbackLine.open(format);
        playbackLine.start();
        AppLogger.info("Playback speaker aktif: " + format);
    }

    // =========================================================================
    // START / STOP
    // =========================================================================

    public void start() {
        if (isRunning.get()) {
            AppLogger.warn("ClientEngine sudah berjalan");
            return;
        }

        isRunning.set(true);
        startTime = System.currentTimeMillis();

        receiver.start();

        receiveThread = new Thread(this::receiveLoop, "Client-Receiver");
        receiveThread.setDaemon(true);
        receiveThread.start();

        statsExecutor = Executors.newSingleThreadScheduledExecutor();
        statsExecutor.scheduleAtFixedRate(() -> {
            if (isRunning.get()) printStats();
        }, 10, 10, TimeUnit.SECONDS);

        AppLogger.info("ClientEngine started successfully");
        AppLogger.info("Audio format: " + audioConfig);
        AppLogger.info("Codec: " + codec.getName());
        AppLogger.info("UDP port: " + config.getUdpPort());
    }

    public void stop() {
        if (!isRunning.get()) return;

        AppLogger.info("Stopping ClientEngine...");
        isRunning.set(false);

        receiver.stop();

        if (receiveThread != null) {
            try {
                receiveThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (statsExecutor != null) {
            statsExecutor.shutdown();
        }

        if (playbackLine != null) {
            playbackLine.drain();
            playbackLine.stop();
            playbackLine.close();
        }

        if (pcmFileOut != null) {
            try {
                pcmFileOut.flush();
                pcmFileOut.close();
            } catch (IOException e) {
                AppLogger.error("Gagal menutup file PCM output", e);
            }
        }

        qosMetrics.close();

        printStats();
        AppLogger.info("ClientEngine stopped");
    }

    // =========================================================================
    // RECEIVE LOOP (jalan di receiveThread - 1 thread saja, aman untuk
    // codec.decode() yang stateful & tidak thread-safe)
    // =========================================================================

    private void receiveLoop() {
        AppLogger.info("Receive loop started");

        while (isRunning.get()) {
            byte[] raw = receiver.receivePacket();
            if (raw == null) {
                // timeout (SO_TIMEOUT di UDPBroadcastReceiver) - normal, loop
                // lagi supaya flag isRunning dicek berkala
                continue;
            }
            processPacket(raw);
        }

        AppLogger.info("Receive loop ended");
    }

    private void processPacket(byte[] raw) {
        long receiveTimeMillis = System.currentTimeMillis();

        PacketInfo info;
        try {
            info = SimplePacketFormatter.parsePacket(raw);
        } catch (IllegalArgumentException e) {
            malformedPackets.incrementAndGet();
            AppLogger.warn("Paket tidak valid, dilewati: " + e.getMessage());
            return;
        }

        // Catat ke QoS SELALU - termasuk heartbeat (payload kosong), karena
        // tetap relevan untuk packet loss/jitter tracking.
        qosMetrics.recordPacket(info, receiveTimeMillis);
        packetsReceived.incrementAndGet();

        if (info.payload.length == 0) {
            // Heartbeat - tidak ada audio untuk didecode
            heartbeatsSeen.incrementAndGet();
            if (config.isVerboseLogging()) {
                AppLogger.debug("Heartbeat diterima: seq=" + info.sequenceNumber);
            }
            return;
        }

        decodeAndOutput(info.payload);

        if (config.isVerboseLogging() && packetsReceived.get() % 100 == 0) {
            AppLogger.debug("Processed " + packetsReceived.get() + " packets, "
                + samplesDecoded.get() + " samples decoded");
        }
    }

    /**
     * Unpack payload (2 nibble/byte, nibble tinggi = sampel pertama), buang
     * nibble yang masih termasuk jatah sync tone tanpa memanggil
     * codec.decode() sama sekali, decode sisanya, lalu kirim hasilnya ke
     * speaker/file (kalau diaktifkan).
     */
    private void decodeAndOutput(byte[] payload) {
        short[] decoded = new short[payload.length * 2];
        int decodedCount = 0;

        for (byte b : payload) {
            int hi = (b >> 4) & 0x0F;
            int lo = b & 0x0F;
            decodedCount = handleNibble(hi, decoded, decodedCount);
            decodedCount = handleNibble(lo, decoded, decodedCount);
        }

        if (decodedCount == 0) {
            // Seluruh nibble di paket ini masih bagian dari sync tone
            return;
        }

        samplesDecoded.addAndGet(decodedCount);
        emitSamples(decoded, decodedCount);
    }

    /**
     * @return decodedCount baru setelah nibble ini diproses (bertambah 1
     *         kalau di-decode, tetap sama kalau masih dibuang sebagai sync tone)
     */
    private int handleNibble(int nibble, short[] out, int decodedCount) {
        if (samplesToSkip > 0) {
            samplesToSkip--;
            return decodedCount;
        }
        // nibble dikirim mentah [0-15] apa adanya - untuk DPCM, sign-extend
        // sudah ditangani otomatis di dalam DPCMCodecAdapter.decode()
        int pcm16 = codec.decode(nibble);
        out[decodedCount] = (short) pcm16;
        return decodedCount + 1;
    }

    private void emitSamples(short[] samples, int count) {
        byte[] pcmBytes = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            // little-endian, sama dengan cara BroadcastEngine menulis file PCM di server
            pcmBytes[i * 2]     = (byte) (samples[i] & 0xFF);
            pcmBytes[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }

        if (playbackLine != null) {
            playbackLine.write(pcmBytes, 0, pcmBytes.length);
        }

        if (pcmFileOut != null) {
            try {
                pcmFileOut.write(pcmBytes);
            } catch (IOException e) {
                AppLogger.error("Gagal menulis PCM ke file output", e);
            }
        }
    }

    // =========================================================================
    // STATS
    // =========================================================================

    private void printStats() {
        long uptime = System.currentTimeMillis() - startTime;
        AppLogger.info(String.format(
            "Stats: uptime=%ds, packets=%d, heartbeats=%d, malformed=%d, samples=%d | %s",
            uptime / 1000, packetsReceived.get(), heartbeatsSeen.get(),
            malformedPackets.get(), samplesDecoded.get(), qosMetrics.toString()));
    }

    // =========================================================================
    // GETTERS
    // =========================================================================

    public boolean isRunning()          { return isRunning.get();          }
    public int getPacketsReceived()     { return packetsReceived.get();    }
    public int getHeartbeatsSeen()      { return heartbeatsSeen.get();     }
    public int getMalformedPackets()    { return malformedPackets.get();   }
    public long getSamplesDecoded()     { return samplesDecoded.get();     }
    public QosMetrics getQosMetrics()   { return qosMetrics;               }
}