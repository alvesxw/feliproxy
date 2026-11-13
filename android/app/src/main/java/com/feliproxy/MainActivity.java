package com.feliproxy;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;

    private static final int BG = Color.rgb(49, 51, 56);
    private static final int INPUT_BG = Color.rgb(30, 31, 34);
    private static final int FG = Color.rgb(242, 243, 245);
    private static final int MUTED = Color.rgb(181, 186, 193);
    private static final int DIM = Color.rgb(128, 132, 142);
    private static final int GREEN = Color.rgb(35, 165, 90);
    private static final int RED = Color.rgb(218, 55, 60);
    private static final int YELLOW = Color.rgb(240, 178, 50);
    private static final int BLURPLE = Color.rgb(88, 101, 242);
    private static final int GRAY_BTN = Color.rgb(78, 80, 88);

    private TextView dot, status, info, result;
    private Button toggle, btnTest, btnSave;
    private EditText etEndpoint, etUser, etPass;
    private CheckBox cbUdp, cbShow;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean busy;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        Prefs p = Prefs.load(this);
        etEndpoint.setText(p.endpoint);
        etUser.setText(p.user);
        etPass.setText(p.pass);
        cbUdp.setChecked(p.udp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    // ------------------------------------------------------------------
    // Interface (feita em código, sem XML)
    // ------------------------------------------------------------------
    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(8));
        return g;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(round(color));
        b.setStateListAnimator(null);
        return b;
    }

    private EditText input(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(DIM);
        e.setTextColor(FG);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setBackground(round(INPUT_BG));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private CheckBox check(String text) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextColor(MUTED);
        if (Build.VERSION.SDK_INT >= 21) c.setButtonTintList(android.content.res.ColorStateList.valueOf(BLURPLE));
        return c;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(20), dp(20), dp(24));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        dot = label("●", 26, DIM, false);
        status = label("", 22, FG, true);
        status.setPadding(dp(10), 0, 0, 0);
        head.addView(dot);
        head.addView(status);
        col.addView(head, lp(0));

        info = label("", 14, MUTED, false);
        col.addView(info, lp(4));

        toggle = button("Ativar", BLURPLE);
        toggle.setMinHeight(dp(56));
        toggle.setOnClickListener(v -> onToggle());
        col.addView(toggle, lp(18));

        TextView hint = label("Só o Discord passa pela VPN. Se ele já estava aberto, feche e abra de novo depois de ativar.", 13, DIM, false);
        col.addView(hint, lp(10));

        col.addView(label("Proxy SOCKS5", 18, FG, true), lp(28));

        col.addView(label("Endereço (IP:porta)", 13, MUTED, false), lp(12));
        etEndpoint = input("1.2.3.4:1080", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(etEndpoint, lp(6));

        col.addView(label("Usuário (opcional)", 13, MUTED, false), lp(12));
        etUser = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(etUser, lp(6));

        col.addView(label("Senha (opcional)", 13, MUTED, false), lp(12));
        etPass = input("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(etPass, lp(6));
        cbShow = check("Mostrar senha");
        cbShow.setOnCheckedChangeListener((b, on) -> {
            int sel = etPass.getSelectionEnd();
            etPass.setInputType(InputType.TYPE_CLASS_TEXT | (on
                    ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            etPass.setSelection(Math.max(0, sel));
        });
        col.addView(cbShow, lp(2));

        cbUdp = check("Voz (UDP) também pelo proxy");
        col.addView(cbUdp, lp(8));
        col.addView(label("Desmarcado: mensagens e imagens vão pelo proxy, e a voz sai pela sua conexão normal. Use o teste para ver se o seu proxy aceita UDP.", 12, DIM, false), lp(0));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnTest = button("Testar proxy", GRAY_BTN);
        btnSave = button("Salvar", BLURPLE);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, dp(48), 1);
        half2.leftMargin = dp(12);
        row.addView(btnTest, half);
        row.addView(btnSave, half2);
        btnTest.setOnClickListener(v -> onTest());
        btnSave.setOnClickListener(v -> onSave());
        col.addView(row, lp(20));

        result = label("", 14, MUTED, false);
        col.addView(result, lp(16));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(col);

        // Android 15 desenha atrás das barras do sistema: compensa com padding
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = i.top; bottom = i.bottom; left = i.left; right = i.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        return scroll;
    }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------
    private void setResult(String text, int color) {
        result.setText(text);
        result.setTextColor(color);
    }

    private void setBusy(boolean on) {
        busy = on;
        btnTest.setEnabled(!on);
        btnSave.setEnabled(!on);
        toggle.setEnabled(!on);
        btnTest.setAlpha(on ? 0.5f : 1f);
        btnSave.setAlpha(on ? 0.5f : 1f);
    }

    private void refresh() {
        int s = TunnelService.state;
        info.setText(s == TunnelService.ON && TunnelService.activeInfo != null
                ? "Usando: " + TunnelService.activeInfo
                : Prefs.load(this).describe());
        if (s == TunnelService.ON) {
            dot.setTextColor(GREEN);
            status.setText("Ativado");
            toggle.setText("Desativar");
            toggle.setBackground(round(RED));
        } else if (s == TunnelService.CONNECTING) {
            dot.setTextColor(YELLOW);
            status.setText("Conectando...");
            toggle.setText("Desativar");
            toggle.setBackground(round(RED));
        } else {
            dot.setTextColor(DIM);
            status.setText("Desativado");
            toggle.setText("Ativar");
            toggle.setBackground(round(BLURPLE));
        }
        if (!busy) toggle.setEnabled(true);

        String err = TunnelService.lastError;
        if (err != null) {
            TunnelService.lastError = null;
            setResult("Erro: " + err, RED);
        }
    }

    private Prefs readForm(StringBuilder error) {
        Prefs p = new Prefs();
        p.endpoint = etEndpoint.getText().toString().trim();
        p.user = etUser.getText().toString().trim();
        p.pass = etPass.getText().toString();
        p.udp = cbUdp.isChecked();
        try {
            Socks5.parse(p.endpoint);
        } catch (IllegalArgumentException e) {
            error.append(e.getMessage());
            return null;
        }
        if (p.user.isEmpty() != p.pass.isEmpty()) {
            error.append("Preencha usuário e senha juntos, ou deixe os dois vazios.");
            return null;
        }
        return p;
    }

    // ------------------------------------------------------------------
    // Ações
    // ------------------------------------------------------------------
    private void onToggle() {
        if (TunnelService.state != TunnelService.OFF) {
            TunnelService.stop(this);
            setResult("Desativado.", MUTED);
            return;
        }
        if (!Prefs.load(this).configured()) {
            setResult("Preencha o proxy e toque em Salvar antes de ativar.", YELLOW);
            return;
        }
        if (!TunnelService.isDiscordInstalled(this)) {
            setResult("O Discord não está instalado neste celular.", RED);
            return;
        }
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, REQ_VPN);
        else onActivityResult(REQ_VPN, RESULT_OK, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        if (resultCode == RESULT_OK) {
            TunnelService.start(this);
            setResult("Ativando...", MUTED);
        } else {
            setResult("Sem a permissão de VPN o app não consegue funcionar.", YELLOW);
        }
    }

    private void onTest() {
        StringBuilder err = new StringBuilder();
        final Prefs p = readForm(err);
        if (p == null) {
            setResult(err.toString(), RED);
            return;
        }
        final boolean udpChecked = p.udp;
        setBusy(true);
        setResult("Testando o proxy...", MUTED);
        new Thread(() -> {
            Socks5.Result r = null;
            String e = null;
            try {
                r = Socks5.test(p.endpoint, p.user, p.pass);
            } catch (Exception ex) {
                e = ex.getMessage();
            }
            final Socks5.Result fr = r;
            final String fe = e;
            runOnUiThread(() -> {
                setBusy(false);
                if (fe != null || fr == null) {
                    setResult("Erro: " + fe, RED);
                    return;
                }
                String text = fr.tcp + "\n" + fr.udp;
                if (fr.tcpOk && !fr.udpOk && udpChecked)
                    text += "\nDica: desmarque a voz (UDP) e salve, para a voz sair pela sua conexão.";
                if (fr.tcpOk && fr.udpOk && !udpChecked)
                    text += "\nDica: marque a voz (UDP) e salve, para a voz também passar pelo proxy.";
                setResult(text, !fr.tcpOk ? RED : (fr.udpOk ? GREEN : YELLOW));
            });
        }).start();
    }

    private void onSave() {
        StringBuilder err = new StringBuilder();
        Prefs p = readForm(err);
        if (p == null) {
            setResult(err.toString(), RED);
            return;
        }
        p.save(this);
        if (TunnelService.state != TunnelService.OFF) {
            TunnelService.start(this); // reinicia com o proxy novo
            setResult("Salvo. Reconectando com o novo proxy...", GREEN);
        } else {
            setResult("Salvo. Toque em Ativar para usar.", GREEN);
        }
        refresh();
    }
}
