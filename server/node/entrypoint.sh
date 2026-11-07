#!/bin/sh
# Поднимает интерфейс WireGuard, правила NAT/файрвола и запускает API узла.
set -eu

IFACE="${WG_IF:-wg0}"
PORT="${WG_PORT:-51820}"
export SUBNET="${SUBNET:-10.8.0.0/22}"
export STATE_DIR="${STATE_DIR:-/var/lib/freevpn}"

mkdir -p "$STATE_DIR"
umask 077
[ -s "$STATE_DIR/server.key" ] || wg genkey > "$STATE_DIR/server.key"

if [ "$(cat /proc/sys/net/ipv4/ip_forward)" != "1" ]; then
  echo "ОШИБКА: на сервере выключен net.ipv4.ip_forward (его включает deploy.sh)" >&2
  exit 1
fi

ADDR="$(freevpn-node serveraddr)"
ip link del "$IFACE" 2>/dev/null || true
if ! ip link add dev "$IFACE" type wireguard; then
  echo "ОШИБКА: ядро сервера не поддерживает WireGuard (нужно Linux 5.6+ или модуль wireguard)" >&2
  exit 1
fi
wg set "$IFACE" private-key "$STATE_DIR/server.key" listen-port "$PORT"
ip addr add "$ADDR" dev "$IFACE"
ip link set mtu 1420 up dev "$IFACE"

WAN="$(ip -4 route show default | awk '{print $5; exit}')"
[ -n "$WAN" ] || { echo "ОШИБКА: не найден внешний интерфейс" >&2; exit 1; }

# Цепочка правил для трафика клиентов: клиенты видят только интернет.
iptables -N FREEVPN 2>/dev/null || iptables -F FREEVPN
for net in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 169.254.0.0/16 100.64.0.0/10 127.0.0.0/8; do
  iptables -A FREEVPN -d "$net" -j DROP        # локальные сети сервера и метаданные облака
done
iptables -A FREEVPN -o "$IFACE" -j DROP        # клиент -> клиент
iptables -A FREEVPN -p tcp --dport 25 -j DROP  # рассылка спама
iptables -A FREEVPN -j ACCEPT

iptables -C FORWARD -i "$IFACE" -j FREEVPN 2>/dev/null || iptables -I FORWARD 1 -i "$IFACE" -j FREEVPN
iptables -C FORWARD -o "$IFACE" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT 2>/dev/null ||
  iptables -I FORWARD 2 -o "$IFACE" -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
iptables -t nat -C POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE 2>/dev/null ||
  iptables -t nat -A POSTROUTING -s "$SUBNET" -o "$WAN" -j MASQUERADE
iptables -t mangle -C FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu 2>/dev/null ||
  iptables -t mangle -A FORWARD -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu

exec freevpn-node
