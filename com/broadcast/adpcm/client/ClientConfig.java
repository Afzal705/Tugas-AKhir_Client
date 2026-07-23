package com.broadcast.adpcm.client;

import com.broadcast.adpcm.audio.AudioConfig;

/**
 * ClientConfig.java
 *
 * Konfigurasi client penerima broadcast - simetris dengan ServerConfig di
 * sisi server, tapi untuk sisi penerima.
 *
 * CATATAN PENTING: karena repo client ini TERPISAH dari repo server (bukan
 * satu project yang sama), enum CodecType di sini didefinisikan ULANG
 * (bukan reuse langsung dari ServerConfig, karena package server tidak ada
 * di repo ini). Isinya harus SELALU sinkron dengan ServerConfig.CodecType
 * di repo server - kalau server menambah algoritma baru, tambahkan juga di
 * sini.
 *
 * PENTING JUGA: --port dan --codec yang dipakai client HARUS SAMA PERSIS
 * dengan yang dipakai server saat itu, kalau tidak, hasil decode akan
 * rusak/berisik (lihat diskusi soal ini sebelumnya).
 */
public final class ClientConfig {

    // Harus sama persis dengan ServerConfig.DEFAULT_UDP_PORT di repo server
    public static final int DEFAULT_UDP_PORT = 50005;
    public static final String DEFAULT_MULTICAST_ADDRESS = "239.1.2.3";

    /** Harus SELALU sinkron dengan ServerConfig.CodecType di repo server. */
    public enum CodecType { ADPCM_G726, DPCM }

    public static final CodecType DEFAULT_CODEC_TYPE = CodecType.ADPCM_G726;
    public static final boolean DEFAULT_ENABLE_PLAYBACK = false;

    // Network settings
    private int udpPort;
    private boolean useMulticast;
    private String multicastAddress;

    // Audio settings
    private AudioConfig audioConfig;
    private CodecType codecType;

    // Output settings
    private boolean enablePlayback;
    private String outputPcmPath;
    private String metricsLogPath;

    // Control flags
    private boolean verboseLogging;

    private ClientConfig() {
        // Use builder
    }

    /**
     * Get default configuration.
     */
    public static ClientConfig getDefault() {
        return new Builder().build();
    }

    /**
     * Validate configuration.
     */
    public boolean validate() {
        if (udpPort < 1024 || udpPort > 65535) {
            System.err.println("Invalid UDP port: " + udpPort);
            return false;
        }

        if (useMulticast && (multicastAddress == null || multicastAddress.isEmpty())) {
            System.err.println("Multicast address required for multicast mode");
            return false;
        }

        return true;
    }

    /**
     * Builder pattern untuk ClientConfig.
     */
    public static class Builder {
        private final ClientConfig config;

        public Builder() {
            config = new ClientConfig();

            // Set defaults
            config.udpPort = DEFAULT_UDP_PORT;
            config.useMulticast = false;
            config.multicastAddress = DEFAULT_MULTICAST_ADDRESS;
            config.audioConfig = AudioConfig.getDefaultConfig();
            config.codecType = DEFAULT_CODEC_TYPE;
            config.enablePlayback = DEFAULT_ENABLE_PLAYBACK;
            config.outputPcmPath = null;
            config.metricsLogPath = null;
            config.verboseLogging = false;
        }

        public Builder udpPort(int port) {
            config.udpPort = port;
            return this;
        }

        public Builder useMulticast(boolean use) {
            config.useMulticast = use;
            return this;
        }

        public Builder multicastAddress(String address) {
            config.multicastAddress = address;
            return this;
        }

        public Builder audioConfig(AudioConfig audioConfig) {
            config.audioConfig = audioConfig;
            return this;
        }

        /** Pilih codec: ADPCM_G726 (default) atau DPCM. HARUS sama dengan server. */
        public Builder codecType(CodecType type) {
            config.codecType = type;
            return this;
        }

        /** Aktifkan playback real-time ke speaker. */
        public Builder enablePlayback(boolean enable) {
            config.enablePlayback = enable;
            return this;
        }

        /** Simpan hasil decode PCM ke file (untuk analisis SNR/MSE nanti). */
        public Builder outputPcmPath(String path) {
            config.outputPcmPath = path;
            return this;
        }

        /** Simpan metrik QoS per-paket ke file CSV. */
        public Builder metricsLogPath(String path) {
            config.metricsLogPath = path;
            return this;
        }

        public Builder verboseLogging(boolean verbose) {
            config.verboseLogging = verbose;
            return this;
        }

        public ClientConfig build() {
            if (!config.validate()) {
                throw new IllegalStateException("Invalid client configuration");
            }
            return config;
        }
    }

    // Getters
    public int getUdpPort() { return udpPort; }
    public boolean isUseMulticast() { return useMulticast; }
    public String getMulticastAddress() { return multicastAddress; }
    public AudioConfig getAudioConfig() { return audioConfig; }
    public CodecType getCodecType() { return codecType; }
    public boolean isEnablePlayback() { return enablePlayback; }
    public String getOutputPcmPath() { return outputPcmPath; }
    public String getMetricsLogPath() { return metricsLogPath; }
    public boolean isVerboseLogging() { return verboseLogging; }

    @Override
    public String toString() {
        return String.format(
            "ClientConfig[port=%d, mode=%s, codec=%s, playback=%s, savePcm=%s, metricsLog=%s]",
            udpPort,
            useMulticast ? "MULTICAST" : "BROADCAST/UNICAST",
            codecType,
            enablePlayback,
            outputPcmPath != null ? outputPcmPath : "off",
            metricsLogPath != null ? metricsLogPath : "off"
        );
    }
}