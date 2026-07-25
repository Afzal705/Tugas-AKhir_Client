package com.broadcast.adpcm.client;

import com.broadcast.adpcm.audio.AudioConfig;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;

/**
 * BroadcastClient - Entry point utama sisi penerima (client) sistem
 * broadcast ADPCM. Parsing argumen CLI lalu menjalankan ClientEngine
 * sampai dihentikan (Ctrl+C).
 *
 * Pola strukturnya sengaja dibuat simetris dengan BroadcastServer di repo
 * server (shutdown hook, parseArguments/printUsage/waitForShutdown) supaya
 * mudah dibandingkan/dirawat bersamaan.
 */
public final class BroadcastClient {

    private static ClientEngine engine;
    private static ClientConfig config;
    private static volatile boolean shutdownRequested = false;

    static {
        // Shutdown hook - dipanggil saat Ctrl+C (SIGINT) atau kill biasa (SIGTERM)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown hook triggered");
            shutdownRequested = true;
            if (engine != null) {
                engine.stop();
            }
        }));
    }

    public static void main(String[] args) {
        System.out.println("=== ADPCM Broadcast Client ===");
        System.out.println("Version: 1.0");
        System.out.println();

        config = parseArguments(args);

        if (config == null) {
            printUsage();
            System.exit(1);
        }

        System.out.println("Configuration: " + config);
        System.out.println();

        try {
            engine = new ClientEngine(config);
            engine.start();

            System.out.println();
            System.out.println("Client is running. Press Ctrl+C to stop...");
            System.out.println();

            waitForShutdown();

        } catch (LineUnavailableException e) {
            System.err.println("ERROR: Gagal membuka speaker untuk playback "
                + "(perangkat audio output tidak tersedia/dipakai aplikasi lain). "
                + "Jalankan tanpa --playback kalau hanya butuh simpan PCM/metrik.");
            System.err.println("Detail: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        } catch (IOException e) {
            System.err.println("ERROR: Failed to initialize client: " + e.getMessage());
            e.printStackTrace();
            System.exit(3);
        }
    }

    /**
     * Parse command line arguments. Return null kalau argumen tidak valid
     * atau user minta --help (supaya main() mencetak usage & keluar).
     */
    private static ClientConfig parseArguments(String[] args) {
        ClientConfig.Builder builder = new ClientConfig.Builder();

        Integer sampleRate = null;
        Integer frameSizeMs = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "-p":
                case "--port":
                    if (i + 1 < args.length) {
                        builder.udpPort(Integer.parseInt(args[++i]));
                    }
                    break;

                case "-m":
                case "--multicast":
                    builder.useMulticast(true);
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        builder.multicastAddress(args[++i]);
                    }
                    break;

                case "-u":
                case "--unicast":
                    builder.useMulticast(false);
                    break;

                case "-c":
                case "--codec":
                    if (i + 1 < args.length) {
                        String codecArg = args[++i].toLowerCase();
                        if (codecArg.equals("dpcm")) {
                            builder.codecType(ClientConfig.CodecType.DPCM);
                        } else if (codecArg.equals("adpcm") || codecArg.equals("g726")) {
                            builder.codecType(ClientConfig.CodecType.ADPCM_G726);
                        } else {
                            System.err.println("Unknown codec: " + codecArg + " (gunakan 'adpcm' atau 'dpcm')");
                            return null;
                        }
                    }
                    break;

                case "-r":
                case "--samplerate":
                    if (i + 1 < args.length) {
                        sampleRate = Integer.parseInt(args[++i]);
                    }
                    break;

                case "-f":
                case "--frame":
                    if (i + 1 < args.length) {
                        frameSizeMs = Integer.parseInt(args[++i]);
                    }
                    break;

                case "--playback":
                    builder.enablePlayback(true);
                    break;

                case "--save-pcm":
                    if (i + 1 < args.length) {
                        builder.outputPcmPath(args[++i]);
                    }
                    break;

                case "--metrics":
                    if (i + 1 < args.length) {
                        builder.metricsLogPath(args[++i]);
                    }
                    break;

                case "-v":
                case "--verbose":
                    builder.verboseLogging(true);
                    break;

                case "-h":
                case "--help":
                    return null;

                default:
                    System.err.println("Unknown argument: " + arg);
                    return null;
            }
        }

        // AudioConfig hanya perlu dibangun manual kalau ada override
        // samplerate/frame - ClientConfig.Builder tidak punya setter
        // sampleRate/frameSizeMs langsung (beda dengan ServerConfig.Builder),
        // jadi kita rakit AudioConfig di sini lalu suntikkan lewat
        // builder.audioConfig(...).
        if (sampleRate != null || frameSizeMs != null) {
            int rate  = sampleRate != null ? sampleRate : AudioConfig.DEFAULT_SAMPLE_RATE;
            int frame = frameSizeMs != null ? frameSizeMs : AudioConfig.DEFAULT_FRAME_MS;
            try {
                builder.audioConfig(new AudioConfig(rate, AudioConfig.DEFAULT_BIT_DEPTH,
                    AudioConfig.DEFAULT_CHANNELS, frame));
            } catch (IllegalArgumentException e) {
                System.err.println("Konfigurasi audio tidak valid: " + e.getMessage());
                return null;
            }
        }

        try {
            return builder.build();
        } catch (IllegalStateException e) {
            System.err.println("Konfigurasi client tidak valid: " + e.getMessage());
            return null;
        }
    }

    /**
     * Print usage information.
     */
    private static void printUsage() {
        System.out.println("Usage: java -jar adpcm-client.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -p, --port <port>           UDP port (default: 50005, HARUS sama dengan server)");
        System.out.println("  -m, --multicast [addr]      Mode multicast (default: 239.1.2.3, HARUS sama dengan server)");
        System.out.println("  -u, --unicast                Mode broadcast/unicast (default)");
        System.out.println("  -c, --codec <adpcm|dpcm>     Codec (default: adpcm, HARUS sama dengan server)");
        System.out.println("  -r, --samplerate <hz>       Sample rate (default: 8000, HARUS sama dengan server)");
        System.out.println("  -f, --frame <ms>            Frame size in ms (default: 10, HARUS sama dengan server)");
        System.out.println("  --playback                   Putar hasil decode langsung ke speaker");
        System.out.println("  --save-pcm <file>            Simpan hasil decode PCM ke file (untuk analisis SNR/MSE)");
        System.out.println("  --metrics <file>              Simpan metrik QoS per-paket ke file CSV");
        System.out.println("  -v, --verbose                Enable verbose logging");
        System.out.println("  -h, --help                    Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Mode default (broadcast/unicast, ADPCM, tanpa playback/simpan file)");
        System.out.println("  java -jar adpcm-client.jar");
        System.out.println();
        System.out.println("  # Playback langsung ke speaker");
        System.out.println("  java -jar adpcm-client.jar --playback");
        System.out.println();
        System.out.println("  # DPCM sebagai metode pembanding (Sub-bab 3.3.3), simpan PCM + metrik");
        System.out.println("  java -jar adpcm-client.jar -c dpcm --save-pcm hasil.pcm --metrics qos.csv");
        System.out.println();
        System.out.println("  # Mode multicast, port custom");
        System.out.println("  java -jar adpcm-client.jar -m 239.1.2.3 -p 8888");
    }

    /**
     * Wait for shutdown signal (Ctrl+C).
     */
    private static void waitForShutdown() {
        while (!shutdownRequested) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Shutting down...");
    }

    /** Programmatic API untuk ambil instance engine yang sedang berjalan. */
    public static ClientEngine getEngine() {
        return engine;
    }

    /** Programmatic API untuk ambil config yang sedang dipakai. */
    public static ClientConfig getConfig() {
        return config;
    }
}