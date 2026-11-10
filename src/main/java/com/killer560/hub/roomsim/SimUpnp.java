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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Co-op sim, phase 2: asks the home router to forward the host's sim port (UPnP IGD, SSDP + SOAP), so a friend on
 * another network can join without router setup. No library: one M-SEARCH, one description fetch, three SOAP calls.
 * Every call runs on its own daemon thread, never on the render or server thread. Only ever talks to the LAN router
 * that answered the M-SEARCH (or a test override), never to the internet.
 *
 * <p>A testkit or offline client ({@code killer560.net.offline}) never sends the M-SEARCH unless
 * {@code killer560.simcoop.ssdp} (host:port) points it at a fake gateway: a test must never open a port on the real
 * router.
 */
public final class SimUpnp {

    private static final String SSDP_PROPERTY = "killer560.simcoop.ssdp";
    private static final int WAIT_MS = 2500;
    private static final int HTTP_TIMEOUT_MS = 3000;
    private static final Pattern LOCATION = Pattern.compile("(?im)^location:\\s*(\\S+)\\s*$");

    /** What the router said: {@code ok} with the public address, or why not (shown to the host as is). */
    public record Result(boolean ok, String externalIp, String message) {
    }

    /** The gateway that mapped a port, kept so the mapping can be removed. */
    private record Gateway(String controlUrl, String serviceType, String localIp) {
    }

    private static volatile Gateway mapped;
    private static volatile int mappedPort = -1;

    private SimUpnp() {
    }

    /** Maps TCP {@code port} on the router to this machine, off the calling thread. Never completes exceptionally. */
    public static CompletableFuture<Result> map(int port) {
        CompletableFuture<Result> out = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            Result r;
            try {
                r = mapNow(port);
            } catch (Exception e) {
                r = new Result(false, null, noPort(port, "your router did not answer (" + e.getClass().getSimpleName() + ")"));
            }
            SimCoop.LOGGER.info("[SimCoop] UPnP port {}: {} {}", port, r.ok() ? "mapped" : "not mapped", r.message());
            out.complete(r);
        }, "killer560smod-upnp");
        t.setDaemon(true);
        t.start();
        return out;
    }

    /** Removes the mapping made by {@link #map}, if any, off the calling thread. */
    public static void unmap() {
        Gateway g = mapped;
        int port = mappedPort;
        mapped = null;
        mappedPort = -1;
        if (g == null || port < 0) return;
        Thread t = new Thread(() -> {
            try {
                soap(g, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port
                        + "</NewExternalPort><NewProtocol>TCP</NewProtocol>");
                SimCoop.LOGGER.info("[SimCoop] UPnP port {} removed", port);
            } catch (Exception e) {
                SimCoop.LOGGER.warn("[SimCoop] UPnP port {} could not be removed: {}", port, e.toString());
            }
        }, "killer560smod-upnp");
        t.setDaemon(true);
        t.start();
    }

    /** The port currently mapped, or -1. For tests. */
    public static int mappedPortForTest() { return mappedPort; }

    static String noPort(int port, String why) {
        return "Your router did not open the port (" + why + "): friends on your network can still join; others need"
                + " port forwarding of TCP port " + port + ".";
    }

    private static Result mapNow(int port) throws Exception {
        InetSocketAddress target = ssdpTarget();
        if (target == null) {
            return new Result(false, null, noPort(port, "UPnP is off in this test client"));
        }
        String location = discover(target);
        if (location == null) {
            return new Result(false, null, noPort(port, "no router answered UPnP"));
        }
        Gateway g = describe(location);
        if (g == null) {
            return new Result(false, null, noPort(port, "the router offers no port mapping service"));
        }
        String body = "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort><NewProtocol>TCP"
                + "</NewProtocol><NewInternalPort>" + port + "</NewInternalPort><NewInternalClient>" + g.localIp
                + "</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>killer560smod sim"
                + "</NewPortMappingDescription><NewLeaseDuration>";
        String[] reply = soap(g, "AddPortMapping", body + "0</NewLeaseDuration>");
        if (reply[0] == null) {
            return new Result(false, null, noPort(port, "it refused: " + reply[1]));
        }
        mapped = g;
        mappedPort = port;
        String[] ip = soap(g, "GetExternalIPAddress", "");
        String external = ip[0] == null ? null : tag(ip[0], "NewExternalIPAddress");
        if (external == null || external.isBlank()) {
            return new Result(false, null, noPort(port, "it would not say its public address"));
        }
        InetAddress a = InetAddress.getByName(external.trim());
        if (SimGuest.privateAddress(a) || carrierNat(a)) {
            return new Result(false, external.trim(), noPort(port, "your router is itself behind another network, "
                    + external.trim()));
        }
        return new Result(true, external.trim(), "Port " + port + " is open on " + external.trim());
    }

    /** 100.64.0.0/10, the carrier-grade NAT range: a mapping there never reaches the internet. */
    private static boolean carrierNat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
    }

    private static InetSocketAddress ssdpTarget() {
        String o = System.getProperty(SSDP_PROPERTY);
        if (o != null && !o.isBlank()) {
            int c = o.lastIndexOf(':');
            return new InetSocketAddress(o.substring(0, c), Integer.parseInt(o.substring(c + 1).trim()));
        }
        if (ModNet.offline() || System.getProperty("fabric.client.gametest") != null) return null;
        return new InetSocketAddress("239.255.255.250", 1900);
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
                String local;
                try (DatagramSocket probe = new DatagramSocket()) {
                    probe.connect(InetAddress.getByName(url.getHost()), url.getPort() < 0 ? 80 : url.getPort());
                    local = probe.getLocalAddress().getHostAddress();
                }
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
}
