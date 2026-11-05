#!/usr/bin/env bash
# Установка WireGuard-сервера на Debian/Ubuntu и генерация клиентских конфигов для FreeVPN.
# Запуск от root:  bash setup-wireguard.sh [порт_UDP] [число_клиентов]
# Пример:          bash setup-wireguard.sh 51820 3
set -euo pipefail

PORT="${1:-51820}"
CLIENTS="${2:-3}"
NET="10.8.0"
OUT="/root/freevpn-clients"
SERVER_NAME="${SERVER_NAME:-My Server}"
SERVER_COUNTRY="${SERVER_COUNTRY:-DE}"

[ "$(id -u)" -eq 0 ] || { echo "Запустите от root (sudo)"; exit 1; }

apt-get update -y
DEBIAN_FRONTEND=noninteractive apt-get install -y wireguard qrencode iptables curl

PUBLIC_IP="${PUBLIC_IP:-$(curl -4 -s https://api.ipify.org)}"
[ -n "$PUBLIC_IP" ] || { echo "Не удалось определить внешний IP: задайте PUBLIC_IP=..."; exit 1; }
IFACE="$(ip -4 route show default | awk '{print $5; exit}')"

umask 077
mkdir -p /etc/wireguard "$OUT"
SERVER_PRIV="$(wg genkey)"
SERVER_PUB="$(echo "$SERVER_PRIV" | wg pubkey)"

cat > /etc/wireguard/wg0.conf <<EOF
[Interface]
Address = ${NET}.1/24
ListenPort = ${PORT}
PrivateKey = ${SERVER_PRIV}
PostUp = iptables -A FORWARD -i wg0 -j ACCEPT; iptables -A FORWARD -o wg0 -j ACCEPT; iptables -t nat -A POSTROUTING -s ${NET}.0/24 -o ${IFACE} -j MASQUERADE
PostDown = iptables -D FORWARD -i wg0 -j ACCEPT; iptables -D FORWARD -o wg0 -j ACCEPT; iptables -t nat -D POSTROUTING -s ${NET}.0/24 -o ${IFACE} -j MASQUERADE
EOF

for i in $(seq 1 "$CLIENTS"); do
  CPRIV="$(wg genkey)"; CPUB="$(echo "$CPRIV" | wg pubkey)"; PSK="$(wg genpsk)"
  cat >> /etc/wireguard/wg0.conf <<EOF

[Peer]
# client${i}
PublicKey = ${CPUB}
PresharedKey = ${PSK}
AllowedIPs = ${NET}.$((i + 1))/32
EOF
  cat > "${OUT}/client${i}.conf" <<EOF
# Name: ${SERVER_NAME} #${i}
# Country: ${SERVER_COUNTRY}
[Interface]
PrivateKey = ${CPRIV}
Address = ${NET}.$((i + 1))/32
DNS = 1.1.1.1, 9.9.9.9
MTU = 1380

[Peer]
PublicKey = ${SERVER_PUB}
PresharedKey = ${PSK}
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = ${PUBLIC_IP}:${PORT}
PersistentKeepalive = 25
EOF
done

echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-wireguard.conf
sysctl --system >/dev/null

systemctl enable --now wg-quick@wg0
command -v ufw >/dev/null && ufw allow "${PORT}/udp" || true

echo
echo "Готово. Клиентские конфиги: ${OUT}/client*.conf"
echo "Откройте UDP-порт ${PORT} в файрволе облачного провайдера (security list / firewall)."
echo "Для телефона можно показать QR:  qrencode -t ansiutf8 < ${OUT}/client1.conf"
echo "Один конфиг = одно устройство. Не используйте один конфиг на нескольких устройствах одновременно."
