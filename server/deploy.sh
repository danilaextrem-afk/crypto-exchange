#!/usr/bin/env bash
# Разворачивает узел FreeVPN на VPS (Debian/Ubuntu, root). Запускается из папки server/.
#   NODE_ID=de-fra-1 NODE_NAME=Frankfurt NODE_COUNTRY=DE bash deploy.sh
# В конце печатает строку FREEVPN_NODE_JSON=... — запись для каталога узлов.
set -euo pipefail
cd "$(dirname "$0")"

[ "$(id -u)" -eq 0 ] || { echo "Запустите от root (sudo)"; exit 1; }

NODE_ID="${NODE_ID:?задайте NODE_ID, например de-fra-1}"
NODE_NAME="${NODE_NAME:?задайте NODE_NAME, например Frankfurt}"
NODE_COUNTRY="${NODE_COUNTRY:?задайте NODE_COUNTRY, например DE}"
WG_PORT="${WG_PORT:-51820}"
API_PORT="${API_PORT:-443}"

PUBLIC_HOST="${PUBLIC_HOST:-$(curl -4 -fsS --max-time 10 https://api.ipify.org || true)}"
[ -n "$PUBLIC_HOST" ] || { echo "Не удалось определить внешний IP: задайте PUBLIC_HOST=..."; exit 1; }

if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sh
fi
docker compose version >/dev/null 2>&1 || { echo "Нужен плагин docker compose"; exit 1; }

# Пересылка пакетов — навсегда.
echo "net.ipv4.ip_forward=1" > /etc/sysctl.d/99-freevpn.conf
sysctl -p /etc/sysctl.d/99-freevpn.conf >/dev/null

# Порты. У облачных провайдеров их нужно открыть ещё и в панели (security list / firewall).
if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
  ufw allow "${API_PORT}/tcp" >/dev/null
  ufw allow "${WG_PORT}/udp" >/dev/null
fi
iptables -C INPUT -p tcp --dport "$API_PORT" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p tcp --dport "$API_PORT" -j ACCEPT
iptables -C INPUT -p udp --dport "$WG_PORT" -j ACCEPT 2>/dev/null || iptables -I INPUT 1 -p udp --dport "$WG_PORT" -j ACCEPT
command -v netfilter-persistent >/dev/null 2>&1 && netfilter-persistent save >/dev/null 2>&1 || true

cat > .env <<EOF
PUBLIC_HOST=${PUBLIC_HOST}
WG_PORT=${WG_PORT}
LISTEN=:${API_PORT}
EOF

mkdir -p state
docker compose up -d --build

echo "Жду запуска узла…"
for _ in $(seq 1 60); do
  if curl -fsk --max-time 3 "https://127.0.0.1:${API_PORT}/v1/health" >/dev/null 2>&1 && [ -s state/cert.pin ]; then
    PIN="$(tr -d '\n' < state/cert.pin)"
    URL="https://${PUBLIC_HOST}"
    [ "$API_PORT" = "443" ] || URL="https://${PUBLIC_HOST}:${API_PORT}"
    echo
    echo "Узел работает. Откройте в облачном файрволе: TCP ${API_PORT} и UDP ${WG_PORT}."
    printf 'FREEVPN_NODE_JSON={"id":"%s","name":"%s","country":"%s","api":"%s","pin":"%s"}\n' \
      "$NODE_ID" "$NODE_NAME" "$NODE_COUNTRY" "$URL" "$PIN"
    exit 0
  fi
  sleep 2
done
echo "Узел не запустился. Журнал:" >&2
docker compose logs --tail 40 >&2
exit 1
