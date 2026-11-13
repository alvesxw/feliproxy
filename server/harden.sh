#!/usr/bin/env bash
# FeliProxy - deixa a VPS mais segura (Debian/Ubuntu). Pode rodar de novo sem problema.
#
#   - SSH so com chave (senha desligada), root so por chave, so o root entra por SSH
#   - Firewall (ufw): so SSH, a porta do proxy (TCP) e UDP alto (voz do Discord)
#   - fail2ban: bane IPs que erram o login do SSH
#   - Atualizacoes de seguranca automaticas (reinicia as 04:00 se precisar)
#   - Proxy (Dante) aceita so os logins do grupo "feliproxy" (nao o root nem outros usuarios)
#
# Uso (como root), DEPOIS de colocar sua chave em /root/.ssh/authorized_keys:
#   bash harden.sh
set -euo pipefail

GROUP=feliproxy
DANTE_CONF=/etc/danted.conf

if [ "$(id -u)" -ne 0 ]; then echo "Rode como root:  sudo bash $0"; exit 1; fi
if ! command -v apt-get >/dev/null 2>&1; then echo "Este script e para Debian/Ubuntu."; exit 1; fi

# Sem chave instalada, desligar a senha trancaria voce para fora.
if ! grep -qE '^(ssh-(ed25519|rsa)|ecdsa-sha2-|sk-)' /root/.ssh/authorized_keys 2>/dev/null; then
    echo "Nenhuma chave SSH em /root/.ssh/authorized_keys. Instale sua chave antes de rodar isto."
    exit 1
fi

echo "== Pacotes"
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a
apt-get update -y >/dev/null
apt-get install -y -o Dpkg::Options::=--force-confold ufw fail2ban unattended-upgrades >/dev/null

echo "== SSH: so chave"
# 00- para vir antes do 50-cloud-init.conf (o sshd usa o primeiro valor que encontra)
cat > /etc/ssh/sshd_config.d/00-feliproxy.conf <<'EOF'
# Gerado pelo harden.sh do FeliProxy
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitEmptyPasswords no
PermitRootLogin prohibit-password
# Logins do proxy sao usuarios sem shell; mesmo assim, so o root entra por SSH.
AllowUsers root
MaxAuthTries 3
LoginGraceTime 20
X11Forwarding no
AllowAgentForwarding no
EOF
sshd -t
systemctl reload ssh 2>/dev/null || systemctl reload sshd
SSH_PORT=$(sshd -T | awk '$1=="port" && !p {p=$2} END {print p}')

echo "== Proxy: so o grupo ${GROUP}"
groupadd -f "$GROUP"
# Logins do proxy ja existentes: usuarios sem shell (nologin) que tem senha definida
while IFS=: read -r user hash _; do
    case "$hash" in ''|'!'*|'*'*) continue ;; esac
    if [ "$(getent passwd "$user" | cut -d: -f7)" = "/usr/sbin/nologin" ]; then
        usermod -aG "$GROUP" "$user"
        echo "   login do proxy: $user"
    fi
done < /etc/shadow
PROXY_PORT=""
if [ -f "$DANTE_CONF" ]; then
    PROXY_PORT=$(sed -n 's/^internal:.*port *= *\([0-9]*\).*/\1/p' "$DANTE_CONF" | head -n 1)
    if ! grep -qE "^\s*group:\s*${GROUP}\s*$" "$DANTE_CONF"; then
        cp "$DANTE_CONF" "${DANTE_CONF}.bak.$(date +%s)"
        sed -i "/^\s*command:.*udpassociate/a\    group: ${GROUP}" "$DANTE_CONF"
    fi
    if ! danted -V -f "$DANTE_CONF" >/dev/null 2>&1; then
        echo "A configuracao do Dante ficou invalida; voltando o backup."
        cp "$(ls -t ${DANTE_CONF}.bak.* | head -n 1)" "$DANTE_CONF"
        exit 1
    fi
    systemctl restart danted
fi

echo "== Firewall (ufw)"
ufw default deny incoming >/dev/null
ufw default allow outgoing >/dev/null
ufw limit "${SSH_PORT}/tcp" comment 'SSH' >/dev/null
if [ -n "$PROXY_PORT" ]; then
    ufw allow "${PROXY_PORT}/tcp" comment 'FeliProxy SOCKS5' >/dev/null
    ufw allow 1024:65535/udp comment 'FeliProxy voz (UDP)' >/dev/null
fi
ufw --force enable >/dev/null

echo "== fail2ban"
cat > /etc/fail2ban/jail.d/feliproxy.local <<'EOF'
# Gerado pelo harden.sh do FeliProxy
[sshd]
enabled = true
maxretry = 3
findtime = 10m
bantime = 1h
bantime.increment = true
bantime.maxtime = 1w
EOF
systemctl enable fail2ban >/dev/null 2>&1
systemctl restart fail2ban

echo "== Atualizacoes automaticas"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF
cat > /etc/apt/apt.conf.d/52feliproxy-unattended <<'EOF'
// Gerado pelo harden.sh do FeliProxy
Unattended-Upgrade::Remove-Unused-Kernel-Packages "true";
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "04:00";
EOF
systemctl enable --now unattended-upgrades >/dev/null 2>&1

echo "== Atualizando o sistema agora"
apt-get -y -o Dpkg::Options::=--force-confold upgrade >/dev/null
apt-get -y autoremove >/dev/null

echo
echo "Pronto."
ufw status | sed 's/^/   /'
if [ -f /var/run/reboot-required ]; then echo "O sistema pede reinicio (reboot) para usar o kernel novo."; fi
