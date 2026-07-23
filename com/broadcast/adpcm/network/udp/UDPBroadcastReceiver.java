package com.broadcast.adpcm.network.udp;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDPBroadcastReceiver.java
 *
 * Pasangan UDPBroadcastSender di sisi server, tapi untuk MENERIMA paket.
 * Mendukung mode broadcast/unicast (cukup bind ke port) dan multicast
 * (perlu join grup multicast).
 *
 * Socket di-set SO_TIMEOUT supaya receivePacket() tidak nge-block selamanya
 * kalau tidak ada paket masuk - ini penting supaya ClientEngine bisa cek
 * flag isRunning secara berkala dan berhenti dengan rapi saat Ctrl+C.
 */
public final class UDPBroadcastReceiver {

    private static final int SO_TIMEOUT_MS = 200;
    private static final int PACKET_BUFFER_SIZE = 65507; // ukuran maksimum payload UDP

    private final int port;
    private final boolean useMulticast;
    private final InetAddress multicastGroup;

    private DatagramSocket socket;
    private MulticastSocket multicastSocket;
    private volatile boolean isRunning;

    private final AtomicLong packetsReceived = new AtomicLong(0);
    private final AtomicLong bytesReceived   = new AtomicLong(0);
    private final AtomicLong errorsCount     = new AtomicLong(0);

    /** Constructor untuk mode broadcast/unicast - cukup bind ke port. */
    public UDPBroadcastReceiver(int port) throws IOException {
        this.port = port;
        this.useMulticast = false;
        this.multicastGroup = null;
        initializeSocket();
    }

    /** Constructor untuk mode multicast - perlu join grup multicast. */
    public UDPBroadcastReceiver(String multicastAddress, int port) throws IOException {
        this.port = port;
        this.useMulticast = true;
        this.multicastGroup = InetAddress.getByName(multicastAddress);
        initializeSocket();
    }

    private void initializeSocket() throws IOException {
        if (useMulticast) {
            multicastSocket = new MulticastSocket(port);
            multicastSocket.setReuseAddress(true);
            multicastSocket.setSoTimeout(SO_TIMEOUT_MS);
            multicastSocket.joinGroup(multicastGroup);
            System.out.println("UDPBroadcastReceiver initialized on port " + port
                + " (multicast mode, group=" + multicastGroup.getHostAddress() + ")");
        } else {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            socket.setSoTimeout(SO_TIMEOUT_MS);
            System.out.println("UDPBroadcastReceiver initialized on port " + port
                + " (broadcast/unicast mode)");
        }
    }

    public void start() {
        isRunning = true;
        System.out.println("UDPBroadcastReceiver started");
    }

    public void stop() {
        isRunning = false;
        try {
            if (useMulticast && multicastSocket != null) {
                multicastSocket.leaveGroup(multicastGroup);
                multicastSocket.close();
            } else if (socket != null) {
                socket.close();
            }
        } catch (IOException e) {
            // socket sudah ditutup / grup sudah ditinggalkan, aman diabaikan
        }
        System.out.println("UDPBroadcastReceiver stopped. Stats: packets=" + packetsReceived.get()
            + ", bytes=" + bytesReceived.get() + ", errors=" + errorsCount.get());
    }

    /**
     * Blocking receive satu paket. Return null kalau timeout (dipakai supaya
     * caller bisa cek flag isRunning secara berkala tanpa hang selamanya)
     * atau kalau terjadi error I/O selain timeout.
     */
    public byte[] receivePacket() {
        byte[] buffer = new byte[PACKET_BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        try {
            if (useMulticast) {
                multicastSocket.receive(packet);
            } else {
                socket.receive(packet);
            }

            byte[] data = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

            packetsReceived.incrementAndGet();
            bytesReceived.addAndGet(data.length);

            return data;

        } catch (SocketTimeoutException e) {
            return null; // normal, bukan error - caller akan loop lagi
        } catch (IOException e) {
            if (isRunning) {
                errorsCount.incrementAndGet();
            }
            return null;
        }
    }

    public boolean isRunning()       { return isRunning; }
    public long getPacketsReceived() { return packetsReceived.get(); }
    public long getBytesReceived()   { return bytesReceived.get(); }
    public long getErrorsCount()     { return errorsCount.get(); }
    public int getPort()             { return port; }
}