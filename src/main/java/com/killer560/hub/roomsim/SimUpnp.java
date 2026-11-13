package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModNet;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Co-op sim: asks the home router to forward the host's sim port, so a friend on another network joins straight to the
 * host and the relay carries nothing (coopfree-1111: co-op must never cost money). UPnP IGD first (SSDP + SOAP, a one-hour
 * lease, or a permanent one if the router only takes those, error 725), then NAT-PMP / PCP (UDP 5351 on the gateway,
 * x.y.z.1 of this PC's address) when no UPnP router answered. A lease is renewed every 30 minutes while hosting and
 * removed on close and at JVM exit. No library; every call runs on a daemon thread, never the render or server thread.
 * Only ever talks to the LAN router (or a test override), never to the internet.
 *
 * <p>A testkit or offline client ({@code killer560.net.offline}) never sends the M-SEARCH or a NAT-PMP request unless
 * {@code killer560.simcoop.ssdp} / {@code killer560.simcoop.natpmp} (host:port) point it at a fake gateway: a test must
 * never open a port on the real router.
 */
public final class SimUpnp {

    private static final String SSDP_PROPERTY = "killer560.simcoop.ssdp", PMP_PROPERTY = "killer560.simcoop.natpmp";
    private static final int WAIT_MS = 2500;
    private static final int HTTP_TIMEOUT_MS = 3000;
    private static final int LEASE_S = 3600, RENEW_MIN = 30;
    private static final Pattern LOCATION = Pattern.compile("(?im)^location:\\s*(\\S+)\\s*$");
    private static final ScheduledExecutorService RENEW = renewer();

    /** What the router said: {@code ok} with the public address and port, or why not (shown to the host as is). */
    public record Result(boolean ok, String externalIp, int externalPort, String message) {
    }

    /** The UPnP gateway that mapped a port, kept so the mapping can be renewed and removed. */
    private record Gateway(String controlUrl, String serviceType, String localIp) {
    }

    /** A NAT-PMP ({@code nonce == null}) or PCP gateway that mapped a port. */
    private record Pmp(InetSocketAddress at, InetAddress local, byte[] nonce) {
    }

    private static volatile Gateway mapped;
    private static volatile Pmp pmpMapped;
    private static volatile int mappedPort = -1, lease = LEASE_S;
    private static volatile ScheduledFuture<?> renewal;
    private static volatile boolean hooked;

    private SimUpnp() {
    }

    /** Maps TCP {@code port} on the router to this machine, off the calling thread. Never completes exceptionally. */
    public static CompletableFuture<Result> map(int port) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        SimSignal.daemon("killer560smod-upnp", () -> {
            Result r;
            try {
                r = mapNow(port);
            } catch (Exception e) {
                r = new Result(false, null, 0, noPort(port, "your router did not answer (" + e.getClass().getSimpleName() + ")"));
            }
            SimCoop.LOGGER.info("[SimCoop] port {}: {} {}", port, r.ok() ? "mapped" : "not mapped", r.message());
            if (r.ok()) keep(port);
            out.complete(r);
        });
        return out;
    }

    /** Removes the mapping made by {@link #map}, if any, off the calling thread. */
    public static void unmap() {
        SimSignal.daemon("killer560smod-upnp", SimUpnp::unmapNow);
    }

    /** The port currently mapped, or -1. For tests. */
    public static int mappedPortForTest() { return mappedPort; }

    /** The lease asked for last (seconds, 0 = permanent). For tests. */
    public static int leaseForTest() { return lease; }

    /** Renews the mapping now, as the 30-minute timer does. For tests. */
    public static void renewForTest() { renew(); }

    static String noPort(int port, String why) {
        return "Your router did not open the port (" + why + "): friends on your network can still join; others need"
                + " port forwarding of TCP port " + port + ".";
    }

    private static Result mapNow(int port) throws Exception {
        Result upnp = upnp(port);
        if (upnp.ok() || upnp.externalIp() != null) return upnp;
        InetSocketAddress pmp = pmpTarget();
        if (pmp == null) return upnp;
        Result r = natPmp(pmp, port);
        return r == null ? upnp : r;
    }

    private static Result upnp(int port) throws Exception {
        InetSocketAddress target = ssdpTarget();
        if (target == null) {
            return new Result(false, null, 0, noPort(port, "UPnP is off in this test client"));
        }
        String location = discover(target);
        if (location == null) {
            return new Result(false, null, 0, noPort(port, "no router answered UPnP"));
        }
        Gateway g = describe(location);
        if (g == null) {
            return new Result(false, null, 0, noPort(port, "the router offers no port mapping service"));
        }
        String[] reply = addMapping(g, port, LEASE_S);
        if (reply[0] == null && reply[1].startsWith("error 725")) reply = addMapping(g, port, 0); // permanent leases only
        if (reply[0] == null) {
            return new Result(false, null, 0, noPort(port, "it refused: " + reply[1]));
        }
        mapped = g;
        mappedPort = port;
        String[] ip = soap(g, "GetExternalIPAddress", "");
        String external = ip[0] == null ? null : tag(ip[0], "NewExternalIPAddress");
        if (external == null || external.isBlank()) {
            return new Result(false, null, 0, noPort(port, "it would not say its public address"));
        }
        return checked(InetAddress.getByName(external.trim()), port, port, SSDP_PROPERTY);
    }

    /** The public address as a Result: refused when it is private (double NAT) or carrier-grade NAT. */
    private static Result checked(InetAddress a, int port, int externalPort, String testProperty) {
        String ip = a.getHostAddress();
        // Loopback is taken only from a gateway named by a test property (the testkit's fakes, same PC).
        boolean testLoop = a.isLoopbackAddress() && System.getProperty(testProperty) != null;
        if ((SimGuest.privateAddress(a) && !testLoop) || carrierNat(a)) {
            return new Result(false, ip, 0, noPort(port, "your router is itself behind another network, " + ip));
        }
        return new Result(true, ip, externalPort, "Port " + externalPort + " is open on " + ip);
    }

    private static String[] addMapping(Gateway g, int port, int leaseS) throws IOException {
        lease = leaseS;
        return soap(g, "AddPortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol><NewInternalPort>" + port + "</NewInternalPort><NewInternalClient>"
                + g.localIp + "</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>killer560smod sim"
                + "</NewPortMappingDescription><NewLeaseDuration>" + leaseS + "</NewLeaseDuration>");
    }

    /** Renews every 30 minutes while mapped, and removes the mapping when the game exits. */
    private static void keep(int port) {
        ScheduledFuture<?> old = renewal;
        if (old != null) old.cancel(false);
        renewal = RENEW.scheduleWithFixedDelay(SimUpnp::renew, RENEW_MIN, RENEW_MIN, TimeUnit.MINUTES);
        if (!hooked) {
            hooked = true;
            Runtime.getRuntime().addShutdownHook(new Thread(SimUpnp::unmapNow, "killer560smod-upnp-exit"));
        }
    }

    private static void renew() {
        int port = mappedPort;
        Gateway g = mapped;
        Pmp p = pmpMapped;
        try {
            if (port < 0) return;
            if (g != null) {
                String[] r = addMapping(g, port, lease);
                SimCoop.LOGGER.info("[SimCoop] UPnP port {} renewed: {}", port, r[0] != null ? "ok" : r[1]);
            } else if (p != null) {
                SimCoop.LOGGER.info("[SimCoop] NAT-PMP port {} renewed: {}", port, pmpMap(p, port, LEASE_S) != null ? "ok" : "no answer");
            }
        } catch (Exception e) {
            SimCoop.LOGGER.warn("[SimCoop] port {} renew failed: {}", port, e.toString());
        }
    }

    private static synchronized void unmapNow() {
        ScheduledFuture<?> r = renewal;
        if (r != null) r.cancel(false);
        renewal = null;
        Gateway g = mapped;
        Pmp p = pmpMapped;
        int port = mappedPort;
        mapped = null;
        pmpMapped = null;
        mappedPort = -1;
        if (port < 0) return;
        try {
            if (g != null) {
                soap(g, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port
                        + "</NewExternalPort><NewProtocol>TCP</NewProtocol>");
            } else if (p != null) {
                pmpMap(p, port, 0);
            }
            SimCoop.LOGGER.info("[SimCoop] port {} removed", port);
        } catch (Exception e) {
            SimCoop.LOGGER.warn("[SimCoop] port {} could not be removed: {}", port, e.toString());
        }
    }

    // ---- NAT-PMP (RFC 6886) and PCP (RFC 6887) ----

    /** NAT-PMP, or PCP when the router answers that it speaks only PCP; null when nothing answered. */
    private static Result natPmp(InetSocketAddress at, int port) throws IOException {
        InetAddress local = localFor(at.getAddress());
        Pmp p = new Pmp(at, local, null);
        byte[] r = pmpMap(p, port, LEASE_S);
        if (r != null && (r[0] == 2 || (r.length >= 4 && r[3] == 1))) { // UNSUPP_VERSION: a PCP-only router
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            p = new Pmp(at, local, nonce);
            r = pmpMap(p, port, LEASE_S);
        }
        if (r == null) return null;
        boolean pcp = p.nonce() != null;
        int result = pcp ? r[3] & 0xff : ((r[2] & 0xff) << 8) | (r[3] & 0xff);
        if (result != 0) {
            return new Result(false, null, 0, noPort(port, "it refused " + (pcp ? "PCP" : "NAT-PMP") + ": code " + result));
        }
        InetAddress external;
        int externalPort;
        if (pcp) {
            externalPort = ((r[42] & 0xff) << 8) | (r[43] & 0xff);
            external = InetAddress.getByAddress(Arrays.copyOfRange(r, 56, 60));
        } else {
            externalPort = ((r[10] & 0xff) << 8) | (r[11] & 0xff);
            byte[] a = exchange(at, new byte[]{0, 0}, 12);
            if (a == null || a[3] != 0) return new Result(false, null, 0, noPort(port, "it would not say its public address"));
            external = InetAddress.getByAddress(Arrays.copyOfRange(a, 8, 12));
        }
        pmpMapped = p;
        mappedPort = port;
        lease = LEASE_S;
        return checked(external, port, externalPort, PMP_PROPERTY);
    }

    /** One NAT-PMP or PCP MAP request for TCP {@code port} (lifetime 0 deletes); the reply, or null. */
    private static byte[] pmpMap(Pmp p, int port, int lifetime) throws IOException {
        if (p.nonce() == null) {
            ByteBuffer b = ByteBuffer.allocate(12).put((byte) 0).put((byte) 2).putShort((short) 0)
                    .putShort((short) port).putShort((short) (lifetime == 0 ? 0 : port)).putInt(lifetime);
            return exchange(p.at(), b.array(), 16);
        }
        ByteBuffer b = ByteBuffer.allocate(60).put((byte) 2).put((byte) 1).putShort((short) 0).putInt(lifetime)
                .put(mapped6(p.local())).put(p.nonce()).put((byte) 6).put(new byte[3])
                .putShort((short) port).putShort((short) port).put(mapped6(null));
        return exchange(p.at(), b.array(), 60);
    }

    /** An IPv4 address as an IPv4-mapped IPv6 address (null: 0.0.0.0, "any"). */
    private static byte[] mapped6(InetAddress a) {
        byte[] out = new byte[16];
        out[10] = (byte) 0xff;
        out[11] = (byte) 0xff;
        if (a instanceof Inet4Address) System.arraycopy(a.getAddress(), 0, out, 12, 4);
        return out;
    }

    /** Sends {@code req} up to three times (250, 500, 1000 ms); the first reply of at least {@code min} bytes or a version error. */
    private static byte[] exchange(InetSocketAddress at, byte[] req, int min) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] buf = new byte[1100];
            for (int wait = 250; wait <= 1000; wait *= 2) {
                s.send(new DatagramPacket(req, req.length, at));
                s.setSoTimeout(wait);
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    if (p.getLength() >= min || (p.getLength() >= 4 && (buf[0] == 2 || buf[3] == 1))) {
                        return Arrays.copyOf(buf, p.getLength());
                    }
                } catch (SocketTimeoutException e) {
                    // next try
                }
            }
        }
        return null;
    }

    private static InetSocketAddress pmpTarget() throws IOException {
        InetSocketAddress o = override(PMP_PROPERTY);
        if (o != null) return o;
        if (testClient()) return null;
        InetAddress local = localFor(InetAddress.getByName("192.0.2.1")); // TEST-NET: picks the default route, sends nothing
        if (!(local instanceof Inet4Address) || !SimGuest.privateAddress(local)) return null;
        byte[] g = local.getAddress();
        g[3] = 1;
        return new InetSocketAddress(InetAddress.getByAddress(g), 5351);
    }

    private static InetAddress localFor(InetAddress peer) throws IOException {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(peer, 9);
            return probe.getLocalAddress();
        }
    }

    // ---- UPnP ----

    /** 100.64.0.0/10, the carrier-grade NAT range: a mapping there never reaches the internet. */
    private static boolean carrierNat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    private static InetSocketAddress ssdpTarget() {
        InetSocketAddress o = override(SSDP_PROPERTY);
        if (o != null) return o;
        return testClient() ? null : new InetSocketAddress("239.255.255.250", 1900);
    }

    private static boolean testClient() {
        return ModNet.offline() || System.getProperty("fabric.client.gametest") != null;
    }

    private static InetSocketAddress override(String property) {
        String o = System.getProperty(property);
        if (o == null || o.isBlank()) return null;
        int c = o.lastIndexOf(':');
        return new InetSocketAddress(o.substring(0, c), Integer.parseInt(o.substring(c + 1).trim()));
    }

    /** One M-SEARCH for an internet gateway; the first LOCATION that answers, or null. */
    private static String discover(InetSocketAddress target) throws IOException {
        String search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                + "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n";
        byte[] out = search.getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(500);
            s.send(new DatagramPacket(out, out.length, target));
            long end = System.currentTimeMillis() + WAIT_MS;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Matcher m = LOCATION.matcher(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    /** The WANIPConnection / WANPPPConnection service of the description at {@code location}, or null. */
    private static Gateway describe(String location) throws Exception {
        URL url = URI.create(location).toURL();
        if (!"http".equals(url.getProtocol())) return null;
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(HTTP_TIMEOUT_MS);
        c.setReadTimeout(HTTP_TIMEOUT_MS);
        byte[] xml;
        try (InputStream in = c.getInputStream()) {
            xml = in.readNBytes(256 * 1024);
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setNamespaceAware(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        NodeList services = doc.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element s = (Element) services.item(i);
            String type = text(s, "serviceType");
            String control = text(s, "controlURL");
            if (type == null || control == null) continue;
            if (type.contains(":WANIPConnection:") || type.contains(":WANPPPConnection:")) {
                String local = localFor(InetAddress.getByName(url.getHost())).getHostAddress();
                return new Gateway(url.toURI().resolve(control.trim()).toString(), type.trim(), local);
            }
        }
        return null;
    }

    private static String text(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        return l.getLength() == 0 ? null : l.item(0).getTextContent();
    }

    private static String tag(String xml, String tag) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + tag + ">([^<]*)</").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /** {reply body, null} on HTTP 200, else {null, the UPnP error or HTTP status}. */
    private static String[] soap(Gateway g, String action, String args) throws IOException {
        String envelope = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\""
                + g.serviceType + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection c = (HttpURLConnection) URI.create(g.controlUrl).toURL().openConnection();
        c.setConnectTimeout(HTTP_TIMEOUT_MS);
        c.setReadTimeout(HTTP_TIMEOUT_MS);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
        c.setRequestProperty("SOAPAction", "\"" + g.serviceType + "#" + action + "\"");
        byte[] out = envelope.getBytes(StandardCharsets.UTF_8);
        try (OutputStream o = c.getOutputStream()) {
            o.write(out);
        }
        int status = c.getResponseCode();
        InputStream in = status == 200 ? c.getInputStream() : c.getErrorStream();
        String body = in == null ? "" : new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        if (status == 200) return new String[]{body, null};
        String code = tag(body, "errorCode");
        String desc = tag(body, "errorDescription");
        return new String[]{null, (code == null ? "HTTP " + status : "error " + code)
                + (desc == null ? "" : " " + desc.toLowerCase(Locale.ROOT))};
    }

    private static ScheduledExecutorService renewer() {
        ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "killer560smod-upnp-renew");
            t.setDaemon(true);
            return t;
        });
        e.setRemoveOnCancelPolicy(true);
        return e;
    }
}
