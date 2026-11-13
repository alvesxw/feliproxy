package com.feliproxy;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Botão nas configurações rápidas (barra de notificações) para ligar/desligar. */
public class ToggleTile extends TileService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            if (TunnelService.state == TunnelService.CONNECTING) handler.postDelayed(this, 500);
        }
    };

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onStopListening() {
        handler.removeCallbacks(refresh);
    }

    @Override
    public void onClick() {
        if (TunnelService.state != TunnelService.OFF) {
            TunnelService.stop(this);
        } else if (!Prefs.load(this).configured() || VpnService.prepare(this) != null
                || !TunnelService.isDiscordInstalled(this)) {
            openApp(); // falta configurar ou dar a permissão de VPN
            return;
        } else {
            TunnelService.start(this);
        }
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 300);
    }

    @SuppressWarnings("deprecation")
    private void openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        int s = TunnelService.state;
        t.setState(s == TunnelService.OFF ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        t.setLabel("FeliProxy");
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(s == TunnelService.ON ? "Ativado" : s == TunnelService.CONNECTING ? "Conectando..." : "Desativado");
        }
        t.updateTile();
    }
}
