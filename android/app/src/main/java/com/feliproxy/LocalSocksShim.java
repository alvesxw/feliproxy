package com.feliproxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servidor SOCKS5 local (127.0.0.1, sem login) usado quando a voz NÃO deve passar pelo proxy:
 *  - TCP CONNECT: repassa para o proxy de verdade (com login), ou seja, texto/imagens vão pelo proxy;
 *  - UDP ASSOCIATE: manda os pacotes direto pela conexão normal do celular (voz direta).
 * O próprio app não está dentro da VPN (só o Discord está), então estes sockets saem direto.
 */
final class LocalSocksShim {
    private final InetAddress upIp;
    private final int upPort;
    private final String user, pass;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<java.io.Closeable> open = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, InetAddress> dnsCache = new ConcurrentHashMap<>();
    private ServerSocket server;
    private volatile boolean running;

    LocalSocksShim(InetAddress upIp, int upPort, String user, String pass) {
        this.upIp = upIp;
        this.upPort = upPort;
        this.user = user;
        this.pass = pass;
    }

    /** Inicia e retorna a porta local. */
    int start() throws IOException {
        server = new ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"));
        running = true;
        pool.execute(this::acceptLoop);
        return server.getLocalPort();
    }

    void stop() {
        running = false;
        Socks5.closeQuietly(server);
        for (java.io.Closeable c : open) Socks5.closeQuietly(c);
        open.clear();
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket c = server.accept();
                track(c);
                pool.execute(() -> handle(c));
            } catch (IOException e) {
                if (!running) return;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return;
            }
        }
    }

    private <T extends java.io.Closeable> T track(T c) {
        open.add(c);
        return c;
    }

    private void release(java.io.Closeable c) {
        open.remove(c);
        Socks5.closeQuietly(c);
    }

    private void handle(Socket c) {
        try {
            c.setTcpNoDelay(true);
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();

            // Saudação do cliente (hev-socks5-tunnel): só aceitamos "sem autenticação"
            byte[] g = Socks5.readN(in, 2);
            if (g[0] != 5) return;
            Socks5.readN(in, g[1] & 0xFF);
            out.write(new byte[]{5, 0});
            out.flush();

            byte[] h = Socks5.readN(in, 4);
            if (h[0] != 5) return;
            int cmd = h[1], atyp = h[3];
            byte[] addr;
            if (atyp == 1) addr = Socks5.readN(in, 4);
            else if (atyp == 4) addr = Socks5.readN(in, 16);
            else if (atyp == 3) {
                int l = in.read();
                if (l < 0) return;
                byte[] name = Socks5.readN(in, l);
                addr = new byte[l + 1];
                addr[0] = (byte) l;
                System.arraycopy(name, 0, addr, 1, l);
            } else {
                fail(out, 8);
                return;
            }
            byte[] port = Socks5.readN(in, 2);

            if (cmd == 1) connect(c, in, out, atyp, addr, port);
            else if (cmd == 3) udpAssociate(c, in, out);
            else fail(out, 7);
        } catch (IOException ignored) {
        } finally {
            release(c);
        }
    }

    private static void fail(OutputStream out, int rep) throws IOException {
        out.write(new byte[]{5, (byte) rep, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    // ---------------- TCP: encadeia no proxy de verdade ----------------
    private void connect(Socket c, InputStream in, OutputStream out, int atyp, byte[] addr, byte[] port) throws IOException {
        Socket up;
        try {
            up = track(Socks5.open(upIp, upPort, user, pass, 10000));
        } catch (IOException e) {
            fail(out, 1);
            return;
        }
        try {
            OutputStream upOut = up.getOutputStream();
            InputStream upIn = up.getInputStream();
            byte[] req = new byte[4 + addr.length + 2];
            req[0] = 5; req[1] = 1; req[2] = 0; req[3] = (byte) atyp;
            System.arraycopy(addr, 0, req, 4, addr.length);
            System.arraycopy(port, 0, req, 4 + addr.length, 2);
            upOut.write(req);
            upOut.flush();
            byte[] rep = Socks5.readReply(upIn);
            if (rep[1] != 0) {
                fail(out, rep[1] & 0xFF);
                return;
            }
            out.write(rep);
            out.flush();
            up.setSoTimeout(0);
            c.setSoTimeout(0);

            // proxy -> Discord em outra thread; Discord -> proxy nesta
            java.util.concurrent.Future<?> down = pool.submit(() -> {
                pipe(upIn, out);
                shutdownOutput(c);
            });
            pipe(in, upOut);
            shutdownOutput(up);
            try {
                down.get(); // espera o proxy terminar de mandar o que falta
            } catch (Exception ignored) {
            }
        } finally {
            release(up);
        }
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static void shutdownOutput(Socket s) {
        try {
            s.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    // ---------------- UDP: direto pela conexão normal ----------------
    private void udpAssociate(Socket c, InputStream in, OutputStream out) throws IOException {
        final DatagramSocket relay = track(new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)));
        final DatagramSocket direct = track(new DatagramSocket());
        final SocketAddress[] client = new SocketAddress[1];
        try {
            int rp = relay.getLocalPort();
            out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, (byte) (rp >> 8), (byte) rp});
            out.flush();

            // hev -> internet
            pool.execute(() -> {
                byte[] buf = new byte[65535];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                while (running && !relay.isClosed()) {
                    try {
                        p.setLength(buf.length);
                        relay.receive(p);
                        client[0] = p.getSocketAddress();
                        int len = p.getLength();
                        if (len < 10 || buf[2] != 0) continue; // fragmentação não suportada
                        int atyp = buf[3], off;
                        InetAddress dst;
                        if (atyp == 1) {
                            dst = InetAddress.getByAddress(java.util.Arrays.copyOfRange(buf, 4, 8));
                            off = 8;
                        } else if (atyp == 4) {
                            if (len < 22) continue;
                            dst = InetAddress.getByAddress(java.util.Arrays.copyOfRange(buf, 4, 20));
                            off = 20;
                        } else if (atyp == 3) {
                            int l = buf[4] & 0xFF;
                            if (len < 7 + l) continue;
                            String name = new String(buf, 5, l, StandardCharsets.US_ASCII);
                            dst = dnsCache.get(name);
                            if (dst == null) {
                                dst = Socks5.resolve(name);
                                dnsCache.put(name, dst);
                            }
                            off = 5 + l;
                        } else continue;
                        int port = ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
                        off += 2;
                        direct.send(new DatagramPacket(buf, off, len - off, dst, port));
                    } catch (IOException e) {
                        if (relay.isClosed()) return;
                    }
                }
            });

            // internet -> hev
            pool.execute(() -> {
                byte[] buf = new byte[65535];
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                byte[] outBuf = new byte[65535 + 22];
                while (running && !direct.isClosed()) {
                    try {
                        p.setLength(buf.length);
                        direct.receive(p);
                        SocketAddress cl = client[0];
                        if (cl == null) continue;
                        InetAddress src = p.getAddress();
                        byte[] a = src.getAddress();
                        int hl = 4 + a.length + 2;
                        if (p.getLength() > outBuf.length - hl) continue;
                        outBuf[0] = 0; outBuf[1] = 0; outBuf[2] = 0;
                        outBuf[3] = (byte) (src instanceof Inet4Address ? 1 : 4);
                        System.arraycopy(a, 0, outBuf, 4, a.length);
                        outBuf[4 + a.length] = (byte) (p.getPort() >> 8);
                        outBuf[5 + a.length] = (byte) p.getPort();
                        System.arraycopy(buf, 0, outBuf, hl, p.getLength());
                        relay.send(new DatagramPacket(outBuf, hl + p.getLength(), cl));
                    } catch (IOException e) {
                        if (direct.isClosed()) return;
                    }
                }
            });

            // A associação vive enquanto a conexão TCP de controle estiver aberta
            c.setSoTimeout(0);
            byte[] sink = new byte[256];
            while (in.read(sink) >= 0) {
                // ignora
            }
        } finally {
            release(relay);
            release(direct);
        }
    }
}
