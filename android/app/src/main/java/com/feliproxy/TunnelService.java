package com.feliproxy;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.service.quicksettings.TileService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import hev.htproxy.TProxyService;

/**
 * VPN local que pega SÓ o tráfego do Discord (addAllowedApplication) e entrega para o
 * hev-socks5-tunnel, que repassa tudo para o proxy SOCKS5.
 */
public class TunnelService extends VpnService {
    static final String ACTION_START = "com.feliproxy.START";
    static final String ACTION_STOP = "com.feliproxy.STOP";
    static final String[] DISCORD_PACKAGES = {"com.discord"};

    static final int OFF = 0, CONNECTING = 1, ON = 2;
    static volatile int state = OFF;
    static volatile String lastError;
    static volatile String activeInfo;

    private static final int MTU = 8500;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ParcelFileDescriptor tun;
    private LocalSocksShim shim;

    static void start(Context c) {
        c.startService(new Intent(c, TunnelService.class).setAction(ACTION_START));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, TunnelService.class).setAction(ACTION_STOP));
    }

    static boolean isDiscordInstalled(Context c) {
        for (String pkg : DISCORD_PACKAGES) {
            try {
                c.getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            worker.execute(() -> {
                doStop();
                updateTile();
                stopSelf();
            });
        } else if (ACTION_START.equals(action)) {
            state = CONNECTING;
            lastError = null;
            updateTile();
            worker.execute(this::doStart);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        // outra VPN foi ligada ou o usuário desligou pelas configurações
        worker.execute(() -> {
            doStop();
            updateTile();
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        worker.execute(() -> {
            doStop();
            updateTile();
        });
        worker.shutdown();
        super.onDestroy();
    }

    private void doStart() {
        doStop();
        state = CONNECTING;
        try {
            Prefs p = Prefs.load(this);
            Socks5.Endpoint ep = Socks5.parse(p.endpoint);
            InetAddress ip = Socks5.resolve(ep.host);

            String socksHost, user = p.user, pass = p.pass;
            int socksPort;
            if (p.udp) {
                // tudo (TCP e UDP) direto para o proxy
                socksHost = ip.getHostAddress();
                socksPort = ep.port;
            } else {
                // TCP pelo proxy, UDP (voz) direto: passa pelo mini-proxy local
                shim = new LocalSocksShim(ip, ep.port, p.user, p.pass);
                socksPort = shim.start();
                socksHost = "127.0.0.1";
                user = "";
                pass = "";
            }

            Builder b = new Builder()
                    .setSession("FeliProxy")
                    .setMtu(MTU)
                    .addAddress("198.18.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fc00::1", 128)
                    .addRoute("::", 0)
                    .addDnsServer("198.18.0.2");
            int added = 0;
            for (String pkg : DISCORD_PACKAGES) {
                try {
                    b.addAllowedApplication(pkg);
                    added++;
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
            if (added == 0) throw new IOException("o Discord não está instalado neste celular");
            b.setConfigureIntent(PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));

            tun = b.establish();
            if (tun == null) throw new IOException("permissão de VPN não concedida");

            File cfg = writeConfig(socksHost, socksPort, user, pass);
            if (!TProxyService.TProxyStartService(cfg.getAbsolutePath(), tun.getFd()))
                throw new IOException("não foi possível iniciar o túnel");

            activeInfo = ep.host + ":" + ep.port + (p.udp ? "  (TCP + UDP)" : "  (só TCP, voz direta)");
            state = ON;
        } catch (Exception e) {
            String m = e.getMessage();
            lastError = (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
            doStop();
            stopSelf();
        }
        updateTile();
    }

    private void doStop() {
        try {
            TProxyService.TProxyStopService();
        } catch (Throwable ignored) {
        }
        if (shim != null) {
            shim.stop();
            shim = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (IOException ignored) {
            }
            tun = null;
        }
        state = OFF;
        activeInfo = null;
    }

    private static String q(String s) {
        return "'" + (s == null ? "" : s).replace("'", "''") + "'";
    }

    private File writeConfig(String host, int port, String user, String pass) throws IOException {
        StringBuilder y = new StringBuilder();
        y.append("tunnel:\n");
        y.append("  mtu: ").append(MTU).append('\n');
        y.append("  ipv4: 198.18.0.1\n");
        y.append("  ipv6: 'fc00::1'\n");
        y.append("socks5:\n");
        y.append("  port: ").append(port).append('\n');
        y.append("  address: ").append(q(host)).append('\n');
        y.append("  udp: 'udp'\n");
        if (user != null && !user.isEmpty()) {
            y.append("  username: ").append(q(user)).append('\n');
            y.append("  password: ").append(q(pass)).append('\n');
        }
        // DNS "mapeado": responde o DNS do Discord com IPs falsos e manda o nome
        // do domínio para o proxy, assim o DNS não depende de UDP no proxy.
        y.append("mapdns:\n");
        y.append("  address: 198.18.0.2\n");
        y.append("  port: 53\n");
        y.append("  network: 100.64.0.0\n");
        y.append("  netmask: 255.192.0.0\n");
        y.append("  cache-size: 10000\n");
        y.append("misc:\n");
        y.append("  task-stack-size: 81920\n");
        y.append("  log-file: ").append(q(new File(getCacheDir(), "tunnel.log").getAbsolutePath())).append('\n');
        y.append("  log-level: warn\n");

        File f = new File(getFilesDir(), "tunnel.yml");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(y.toString());
        }
        return f;
    }

    private void updateTile() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, ToggleTile.class));
        } catch (Exception ignored) {
        }
    }
}
