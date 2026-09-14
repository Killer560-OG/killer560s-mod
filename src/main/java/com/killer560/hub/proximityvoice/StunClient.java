package com.killer560.hub.proximityvoice;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;

/**
 * A minimal STUN (RFC 5389) client - just enough to ask a public STUN server "what's my public IP and
 * port for this UDP socket", which {@link ProximityVoiceFeature} needs so party members behind
 * different home routers can send audio directly to each other without either of them configuring port
 * forwarding. STUN itself (not a Minecraft-specific thing) is a small, fixed, decades-stable binary
 * protocol - hand-implemented here rather than pulling in a dependency for ~80 lines of well-documented
 * wire format. Uses Google's own public STUN server (stun.l.google.com:19302), the same free, no-signup
 * server countless real applications (including Chrome's own WebRTC) already rely on.
 */
final class StunClient {

    private static final String STUN_HOST = "stun.l.google.com";
    private static final int STUN_PORT = 19302;
    private static final int MAGIC_COOKIE = 0x2112A442;

    private StunClient() {
    }

    record Result(String ip, int port) {
    }

    /** Sends one STUN Binding Request over the given (already-bound) socket and parses the response's
     *  XOR-MAPPED-ADDRESS attribute. Blocks until a response arrives or the socket's timeout elapses. */
    static Result discoverPublicAddress(DatagramSocket socket) throws IOException {
        byte[] transactionId = new byte[12];
        new Random().nextBytes(transactionId);

        byte[] request = new byte[20];
        request[0] = 0x00;
        request[1] = 0x01; // Binding Request
        request[2] = 0x00;
        request[3] = 0x00; // message length: 0 (no attributes)
        writeInt(request, 4, MAGIC_COOKIE);
        System.arraycopy(transactionId, 0, request, 8, 12);

        InetAddress stunAddress = InetAddress.getByName(STUN_HOST);
        socket.send(new DatagramPacket(request, request.length, stunAddress, STUN_PORT));

        byte[] responseBuf = new byte[512];
        DatagramPacket responsePacket = new DatagramPacket(responseBuf, responseBuf.length);
        socket.receive(responsePacket);

        return parseBindingResponse(responseBuf, responsePacket.getLength(), transactionId);
    }

    private static Result parseBindingResponse(byte[] data, int length, byte[] expectedTransactionId) throws IOException {
        if (length < 20 || (data[0] != 0x01 || data[1] != 0x01)) {
            throw new IOException("Not a STUN Binding Success Response");
        }
        int attributesLength = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        int offset = 20;
        int end = Math.min(length, 20 + attributesLength);

        while (offset + 4 <= end) {
            int type = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            int attrLen = ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
            int valueStart = offset + 4;
            if (valueStart + attrLen > end) {
                break;
            }
            // XOR-MAPPED-ADDRESS (0x0020) - preferred; fall back to MAPPED-ADDRESS (0x0001) if that's
            // all the server sent.
            if (type == 0x0020 && attrLen >= 8) {
                return parseXorMappedAddress(data, valueStart);
            }
            if (type == 0x0001 && attrLen >= 8) {
                return parseMappedAddress(data, valueStart);
            }
            offset = valueStart + attrLen + (attrLen % 4 == 0 ? 0 : 4 - attrLen % 4);
        }
        throw new IOException("STUN response had no mapped-address attribute");
    }

    private static Result parseXorMappedAddress(byte[] data, int start) {
        int port = (((data[start + 2] & 0xFF) << 8) | (data[start + 3] & 0xFF)) ^ (MAGIC_COOKIE >>> 16);
        int ip = readInt(data, start + 4) ^ MAGIC_COOKIE;
        return new Result(intToIp(ip), port & 0xFFFF);
    }

    private static Result parseMappedAddress(byte[] data, int start) {
        int port = ((data[start + 2] & 0xFF) << 8) | (data[start + 3] & 0xFF);
        int ip = readInt(data, start + 4);
        return new Result(intToIp(ip), port);
    }

    private static String intToIp(int ip) {
        return ((ip >>> 24) & 0xFF) + "." + ((ip >>> 16) & 0xFF) + "." + ((ip >>> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    private static void writeInt(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >>> 24);
        buf[offset + 1] = (byte) (value >>> 16);
        buf[offset + 2] = (byte) (value >>> 8);
        buf[offset + 3] = (byte) value;
    }

    private static int readInt(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24) | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8) | (buf[offset + 3] & 0xFF);
    }
}
