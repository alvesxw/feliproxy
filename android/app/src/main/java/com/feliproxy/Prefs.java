package com.feliproxy;

import android.content.Context;
import android.content.SharedPreferences;

/** Configuração do proxy, salva no armazenamento privado do app. */
final class Prefs {
    String endpoint = "";
    String user = "";
    String pass = "";
    /** true: voz (UDP) também pelo proxy. false: só TCP pelo proxy, voz pela conexão normal. */
    boolean udp = false;

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("proxy", Context.MODE_PRIVATE);
    }

    static Prefs load(Context c) {
        SharedPreferences s = sp(c);
        Prefs p = new Prefs();
        p.endpoint = s.getString("endpoint", "");
        p.user = s.getString("user", "");
        p.pass = s.getString("pass", "");
        p.udp = s.getBoolean("udp", false);
        return p;
    }

    void save(Context c) {
        sp(c).edit()
                .putString("endpoint", endpoint)
                .putString("user", user)
                .putString("pass", pass)
                .putBoolean("udp", udp)
                .apply();
    }

    boolean configured() {
        return endpoint != null && !endpoint.trim().isEmpty();
    }

    String describe() {
        if (!configured()) return "Nenhum proxy salvo ainda";
        return "Salvo: " + endpoint + "  (" + (udp ? "TCP + UDP" : "só TCP, voz direta")
                + (user.isEmpty() ? "" : ", com login") + ")";
    }
}
