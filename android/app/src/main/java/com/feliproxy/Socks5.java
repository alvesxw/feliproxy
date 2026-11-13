package com.feliproxy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** Cliente SOCKS5 mínimo (RFC 1928 / 1929) + teste do proxy. */
final class Socks5 {
    private Socks5() {
    }

    static final String BAD_ENDPOINT = "Endereço inválido. Use IP:porta, por exemplo 1.2.3.4:1080";

    static final class Endpoint {
        final String host;
        final int port;

        Endpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    static Endpoint parse(String ep) {
        ep = ep == null ? "" : ep.trim();
        int i = ep.lastIndexOf(':');
        if (i <= 0) throw new IllegalArgumentException(BAD_ENDPOINT);
        String host = ep.substring(0, i).trim();
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        int port;
        try {
            port = Integer.parseInt(ep.substring(i + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(BAD_ENDPOINT);
        }
        if (host.isEmpty() || port < 1 || port > 65535) throw new IllegalArgumentException(BAD_ENDPOINT);
        return new Endpoint(host, port);
    }

    static InetAddress resolve(String host) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        for (InetAddress a : all) if (a instanceof Inet4Address) return a;
        if (all.length > 0) return all[0];
        throw new IOException("não foi possível encontrar o endereço de " + host);
    }

    static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int o = 0;
        while (o < n) {
            int r = in.read(b, o, n - o);
            if (r < 0) throw new EOFException("o proxy fechou a conexão");
            o += r;
        }
        return b;
    }

    static String replyText(int rep) {
        switch (rep) {
            case 1: return "falha geral do servidor";
            case 2: return "não permitido pelas regras do proxy";
            case 3: return "rede inacessível";
            case 4: return "host inacessível";
            case 5: return "conexão recusada";
            case 6: return "tempo esgotado";
            case 7: return "comando não suportado";
            case 8: return "tipo de endereço não suportado";
            default: return String.format("código 0x%02X", rep);
        }
    }

    /** Conecta no proxy e faz a negociação (com login se user não for vazio). */
    static Socket open(InetAddress ip, int port, String user, String pass, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            boolean auth = user != null && !user.isEmpty();
            out.write(auth ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
            out.flush();
            byte[] r = readN(in, 2);
            if (r[0] != 5) throw new IOException("o servidor não é um proxy SOCKS5");
            int m = r[1] & 0xFF;
            if (m == 0xFF) throw new IOException(auth ? "o proxy recusou o login" : "o proxy exige usuário e senha");
            if (m == 2) {
                if (!auth) throw new IOException("o proxy exige usuário e senha");
                byte[] u = user.getBytes(StandardCharsets.UTF_8);
                byte[] p = (pass == null ? "" : pass).getBytes(StandardCharsets.UTF_8);
                if (u.length > 255 || p.length > 255) throw new IOException("usuário ou senha longos demais");
                ByteArrayOutputStream a = new ByteArrayOutputStream();
                a.write(1);
                a.write(u.length);
                a.write(u, 0, u.length);
                a.write(p.length);
                a.write(p, 0, p.length);
                out.write(a.toByteArray());
                out.flush();
                r = readN(in, 2);
                if (r[1] != 0) throw new IOException("usuário ou senha incorretos");
            } else if (m != 0) {
                throw new IOException("método de autenticação não suportado");
            }
            return s;
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
    }

    /**
     * Lê a resposta de um comando SOCKS5. Retorna os bytes crus (VER REP RSV ATYP ADDR PORT).
     * Se REP != 0 retorna só os 4 primeiros bytes.
     */
    static byte[] readReply(InputStream in) throws IOException {
        byte[] h = readN(in, 4);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(h, 0, 4);
        if (h[1] != 0) return raw.toByteArray();
        int len;
        if (h[3] == 1) len = 4;
        else if (h[3] == 4) len = 16;
        else if (h[3] == 3) {
            int l = in.read();
            if (l < 0) throw new EOFException("o proxy fechou a conexão");
            raw.write(l);
            len = l;
        } else throw new IOException("resposta inválida do proxy");
        byte[] rest = readN(in, len + 2);
        raw.write(rest, 0, rest.length);
        return raw.toByteArray();
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Teste do proxy
    // ------------------------------------------------------------------
    static final class Result {
        boolean tcpOk, udpOk;
        String tcp = "", udp = "";
    }

    static Result test(String endpoint, String user, String pass) throws IOException {
        Result res = new Result();
        Endpoint ep = parse(endpoint);
        InetAddress ip = resolve(ep.host);

        // TCP: CONNECT em api.ipify.org:80 e lê o IP de saída
        Socket s = null;
        try {
            s = open(ip, ep.port, user, pass, 10000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] hb = "api.ipify.org".getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[]{5, 1, 0, 3, (byte) hb.length});
            req.write(hb);
            req.write(new byte[]{0, 80});
            out.write(req.toByteArray());
            out.flush();
            byte[] rep = readReply(in);
            if (rep[1] != 0) throw new IOException("conexão recusada pelo proxy (" + replyText(rep[1] & 0xFF) + ")");
            out.write("GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = in.read(buf)) > 0 && body.size() < 65536) body.write(buf, 0, n);
            } catch (IOException ignored) {
            }
            String text = new String(body.toByteArray(), StandardCharsets.US_ASCII);
            String exitIp = null;
            int b = text.indexOf("\r\n\r\n");
            if (b >= 0) {
                for (String line : text.substring(b + 4).split("\n")) {
                    String t = line.trim();
                    if (t.matches("[0-9a-fA-F:.]{3,45}") && (t.contains(".") || t.contains(":"))) {
                        exitIp = t;
                        break;
                    }
                }
            }
            res.tcpOk = true;
            res.tcp = "TCP: OK" + (exitIp != null ? " (IP de saída " + exitIp + ")" : "");
        } catch (IOException e) {
            res.tcpOk = false;
            res.tcp = "TCP: falhou (" + e.getMessage() + ")";
            res.udp = "UDP: não testado";
            return res;
        } finally {
            closeQuietly(s);
        }

        // UDP: UDP ASSOCIATE + consulta DNS para 1.1.1.1 pelo relay
        DatagramSocket u = null;
        try {
            s = open(ip, ep.port, user, pass, 10000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            out.write(new byte[]{5, 3, 0, 1, 0, 0, 0, 0, 0, 0});
            out.flush();
            byte[] rep = readReply(in);
            if (rep[1] != 0) {
                res.udpOk = false;
                res.udp = "UDP: não suportado por este proxy (" + replyText(rep[1] & 0xFF) + "). A voz não passa por ele.";
                return res;
            }
            InetAddress relayIp;
            int relayPort = ((rep[rep.length - 2] & 0xFF) << 8) | (rep[rep.length - 1] & 0xFF);
            if (rep[3] == 1 || rep[3] == 4) {
                byte[] a = new byte[rep[3] == 1 ? 4 : 16];
                System.arraycopy(rep, 4, a, 0, a.length);
                relayIp = InetAddress.getByAddress(a);
                if (relayIp.isAnyLocalAddress()) relayIp = ip;
            } else {
                relayIp = ip;
            }

            byte[] dns = dnsQuery("discord.com");
            byte[] pkt = new byte[10 + dns.length];
            pkt[3] = 1;
            pkt[4] = 1; pkt[5] = 1; pkt[6] = 1; pkt[7] = 1;
            pkt[8] = 0; pkt[9] = 53;
            System.arraycopy(dns, 0, pkt, 10, dns.length);

            u = new DatagramSocket();
            u.setSoTimeout(4000);
            boolean got = false;
            for (int i = 0; i < 2 && !got; i++) {
                u.send(new DatagramPacket(pkt, pkt.length, relayIp, relayPort));
                try {
                    DatagramPacket resp = new DatagramPacket(new byte[2048], 2048);
                    u.receive(resp);
                    got = resp.getLength() > 10;
                } catch (SocketTimeoutException ignored) {
                }
            }
            res.udpOk = got;
            res.udp = got ? "UDP: OK, a voz deve funcionar pelo proxy."
                    : "UDP: o proxy aceitou, mas nenhuma resposta voltou. A voz pode não funcionar.";
        } catch (IOException e) {
            res.udpOk = false;
            res.udp = "UDP: falhou (" + e.getMessage() + ")";
        } finally {
            if (u != null) u.close();
            closeQuietly(s);
        }
        return res;
    }

    private static byte[] dnsQuery(String name) {
        ByteArrayOutputStream ms = new ByteArrayOutputStream();
        ms.write(new byte[]{0x44, 0x54, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0}, 0, 12);
        for (String label : name.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            ms.write(b.length);
            ms.write(b, 0, b.length);
        }
        ms.write(0);
        ms.write(new byte[]{0, 1, 0, 1}, 0, 4);
        return ms.toByteArray();
    }
}
