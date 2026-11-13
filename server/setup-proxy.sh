#!/usr/bin/env bash
# FeliProxy - servidor SOCKS5 com UDP (Dante) para Debian/Ubuntu.
#
# Uso (como root):
#   bash setup-proxy.sh                  instala na porta 31080 e cria o login "discord"
#   bash setup-proxy.sh install 40000    instala numa porta escolhida
#   bash setup-proxy.sh add-user amigo   cria outro login (ex.: para um amigo) ou troca a senha dele
#   bash setup-proxy.sh del-user amigo   apaga um login
#   bash setup-proxy.sh list-users       lista os logins do proxy
#   bash setup-proxy.sh status           mostra se o proxy esta rodando
# Depois do install, rode tambem o harden.sh (SSH so com chave, firewall, fail2ban...).
set -euo pipefail

MODE="${1:-install}"
CONF=/etc/danted.conf
GROUP=feliproxy   # so usuarios deste grupo podem usar o proxy

if [ "$(id -u)" -ne 0 ]; then echo "Rode como root:  sudo bash $0 $*"; exit 1; fi
if ! command -v apt-get >/dev/null 2>&1; then echo "Este script e para Debian/Ubuntu."; exit 1; fi

gen_pass() {
    local p
    p=$(head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')
    echo "${p:0:20}"
}

public_ip() {
    local ip
    ip=$(curl -4 -s --max-time 5 https://api.ipify.org || true)
    if [ -z "$ip" ]; then
        ip=$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')
    fi
    echo "$ip"
}

current_port() {
    sed -n 's/^internal:.*port *= *\([0-9]*\).*/\1/p' "$CONF" | head -n 1
}

# Cria (ou troca a senha de) um login. Os logins sao usuarios do sistema sem shell.
add_user() {
    local name="$1" pass
    if ! [[ "$name" =~ ^[a-z][a-z0-9_-]{2,30}$ ]]; then
        echo "Nome invalido: use 3 a 31 letras minusculas, numeros, _ ou -"; exit 1
    fi
    if id "$name" >/dev/null 2>&1; then
        if [ "$(getent passwd "$name" | cut -d: -f7)" != "/usr/sbin/nologin" ]; then
            echo "O usuario '$name' ja existe no sistema e nao e um login do proxy. Escolha outro nome."; exit 1
        fi
    else
        useradd --system --no-create-home --shell /usr/sbin/nologin "$name"
    fi
    groupadd -f "$GROUP"
    usermod -aG "$GROUP" "$name"
    pass=$(gen_pass)
    echo "$name:$pass" | chpasswd
    PROXY_USER="$name"
    PROXY_PASS="$pass"
}

list_users() {
    getent group "$GROUP" | cut -d: -f4 | tr ',' '\n' | sed '/^$/d'
}

del_user() {
    local name="$1"
    if ! list_users | grep -qx "$name"; then echo "'$name' nao e um login do proxy."; exit 1; fi
    pkill -u "$name" 2>/dev/null || true
    userdel "$name"
    rm -f "/root/feliproxy-login-${name}.txt"
    # Derruba conexoes abertas desse login
    systemctl restart danted
    echo "Login '$name' apagado."
}

open_firewall() {
    local port="$1" ufw_out="" ipt_out=""
    if command -v ufw >/dev/null 2>&1; then ufw_out=$(ufw status 2>/dev/null || true); fi
    if command -v iptables >/dev/null 2>&1; then ipt_out=$(iptables -S INPUT 2>/dev/null || true); fi
    if [[ "$ufw_out" == *"Status: active"* ]]; then
        ufw allow "${port}/tcp" >/dev/null
        ufw allow 1024:65535/udp >/dev/null
        echo "Firewall (ufw): liberado ${port}/tcp e UDP 1024-65535"
    elif echo "$ipt_out" | grep -E '^-P INPUT (DROP|REJECT)|-j (DROP|REJECT)' >/dev/null; then
        iptables -I INPUT -p tcp --dport "$port" -j ACCEPT
        iptables -I INPUT -p udp --dport 1024:65535 -j ACCEPT
        if command -v netfilter-persistent >/dev/null 2>&1; then netfilter-persistent save >/dev/null 2>&1 || true; fi
        echo "Firewall (iptables): liberado ${port}/tcp e UDP 1024-65535"
    else
        echo "Firewall: nenhum bloqueio local encontrado"
    fi
}

print_summary() {
    local port
    port=$(current_port)
    # Tambem salva o login num arquivo so do root (para copiar depois)
    umask 077
    printf 'endereco=%s:%s\nusuario=%s\nsenha=%s\n' "$(public_ip)" "$port" "$PROXY_USER" "$PROXY_PASS" \
        > "/root/feliproxy-login-${PROXY_USER}.txt"
    if [ "${QUIET_SUMMARY:-0}" = "1" ]; then
        echo "Login salvo em /root/feliproxy-login-${PROXY_USER}.txt"
        return
    fi
    echo
    echo "=================== PRONTO ==================="
    echo "  Endereco : $(public_ip):${port}"
    echo "  Usuario  : ${PROXY_USER}"
    echo "  Senha    : ${PROXY_PASS}"
    echo "==============================================="
    echo "Coloque isso no FeliProxy, clique em 'Testar proxy'"
    echo "e veja se aparece 'UDP: OK'. Guarde a senha: ela nao e mostrada de novo."
    echo "Se o painel da hospedagem tiver firewall, libere la tambem:"
    echo "  TCP ${port} e UDP 1024-65535."
}

case "$MODE" in
install)
    PORT="${2:-31080}"
    if ! [[ "$PORT" =~ ^[0-9]+$ ]] || [ "$PORT" -lt 1024 ] || [ "$PORT" -gt 65535 ]; then
        echo "Porta invalida (use 1024-65535)"; exit 1
    fi
    IFACE=$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')
    if [ -z "$IFACE" ]; then echo "Nao encontrei a interface de rede de saida."; exit 1; fi

    # A configuracao e escrita ANTES de instalar: assim o Dante ja sobe certo
    # (com a configuracao padrao ele falha ao iniciar e o apt pode dar erro).
    if [ -f "$CONF" ]; then cp "$CONF" "${CONF}.bak.$(date +%s)"; fi
    cat > "$CONF" <<EOF
# Gerado pelo setup-proxy.sh do FeliProxy
logoutput: syslog
user.privileged: root
user.unprivileged: nobody

internal: 0.0.0.0 port = ${PORT}
external: ${IFACE}

clientmethod: none
socksmethod: username

client pass {
    from: 0.0.0.0/0 to: 0.0.0.0/0
    log: error
}

# Nao deixa o proxy acessar a rede interna do proprio servidor
socks block {
    from: 0.0.0.0/0 to: 127.0.0.0/8
    log: error
}
socks block {
    from: 0.0.0.0/0 to: 10.0.0.0/8
    log: error
}
socks block {
    from: 0.0.0.0/0 to: 172.16.0.0/12
    log: error
}
socks block {
    from: 0.0.0.0/0 to: 192.168.0.0/16
    log: error
}
socks block {
    from: 0.0.0.0/0 to: 169.254.0.0/16
    log: error
}

# TCP (texto/imagens) e UDP (voz), so para os logins do grupo ${GROUP}
# (sem isso qualquer usuario do sistema com senha, inclusive o root, entraria no proxy)
socks pass {
    from: 0.0.0.0/0 to: 0.0.0.0/0
    command: connect udpassociate udpreply
    group: ${GROUP}
    log: error
}
EOF

    echo "Instalando o Dante (servidor SOCKS5)..."
    apt-get update -y >/dev/null
    DEBIAN_FRONTEND=noninteractive apt-get install -y -o Dpkg::Options::=--force-confold \
        dante-server curl >/dev/null || true
    if ! command -v danted >/dev/null 2>&1; then
        echo "A instalacao do dante-server falhou. Rode 'apt-get install dante-server' para ver o erro."; exit 1
    fi

    add_user discord
    open_firewall "$PORT"
    systemctl enable danted >/dev/null 2>&1 || true
    systemctl restart danted
    sleep 1
    if ! systemctl is-active --quiet danted; then
        echo "O Dante nao iniciou. Ultimas linhas do log:"
        journalctl -u danted -n 20 --no-pager || true
        exit 1
    fi
    print_summary
    ;;
add-user)
    if [ -z "${2:-}" ]; then echo "Uso: bash $0 add-user NOME"; exit 1; fi
    if [ ! -f "$CONF" ]; then echo "Rode primeiro: bash $0 install"; exit 1; fi
    add_user "$2"
    print_summary
    ;;
del-user)
    if [ -z "${2:-}" ]; then echo "Uso: bash $0 del-user NOME"; exit 1; fi
    del_user "$2"
    ;;
list-users)
    list_users
    ;;
status)
    systemctl --no-pager status danted | head -n 5 || true
    echo "Porta: $(current_port)"
    ;;
*)
    echo "Uso: bash $0 [install [porta] | add-user NOME | del-user NOME | list-users | status]"; exit 1
    ;;
esac
