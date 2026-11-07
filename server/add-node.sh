#!/usr/bin/env bash
# Разворачивает новый узел на VPS по SSH и сам вносит его в каталог directory/directory.json.
#   ./server/add-node.sh root@203.0.113.10 DE "Frankfurt" de-fra-1
set -euo pipefail

HOST="${1:?использование: add-node.sh user@host СТРАНА \"Название\" [id]}"
COUNTRY="${2:?укажите код страны, например DE}"
NAME="${3:?укажите название, например Frankfurt}"
COUNTRY="$(echo "$COUNTRY" | tr '[:lower:]' '[:upper:]')"
ID="${4:-$(echo "${COUNTRY}-${NAME}" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9-')}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/directory/directory.json"

echo "→ копирую узел на $HOST"
tar -C "$ROOT/server" --exclude=state --exclude=.env -czf - . |
  ssh "$HOST" 'mkdir -p /opt/freevpn && tar -xzf - -C /opt/freevpn'

echo "→ запускаю установку (1–3 минуты)"
OUT="$(ssh "$HOST" "cd /opt/freevpn && NODE_ID='$ID' NODE_NAME='$NAME' NODE_COUNTRY='$COUNTRY' bash deploy.sh" | tee /dev/stderr)"
LINE="$(printf '%s\n' "$OUT" | grep '^FREEVPN_NODE_JSON=' | tail -1 | cut -d= -f2-)"
[ -n "$LINE" ] || { echo "Установка не вернула запись узла" >&2; exit 1; }

python3 -I - "$DIR" "$LINE" <<'PY'
import json, sys, datetime
path, entry = sys.argv[1], json.loads(sys.argv[2])
d = json.load(open(path, encoding="utf-8"))
d["nodes"] = [n for n in d["nodes"] if n["id"] != entry["id"]] + [entry]
d["updated"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
json.dump(d, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
open(path, "a").write("\n")
print("Каталог обновлён:", entry["id"])
PY

cat <<MSG

Готово. Осталось отправить изменение в репозиторий:
  git add directory/directory.json && git commit -m "Add node $ID" && git push
Сборка пересоберёт приложение со списком узлов. Если каталог опубликован по https-адресу,
уже установленные приложения подхватят узел без переустановки.
MSG
