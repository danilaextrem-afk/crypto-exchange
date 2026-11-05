#!/usr/bin/env python3
"""Собирает subscription.json из клиентских .conf, чтобы приложение обновляло список без переустановки.

Использование:
    python3 make-subscription.py /root/freevpn-clients/*.conf > subscription.json

Имя и страна берутся из строк "# Name:" и "# Country:" в конфиге. Необязательное поле нагрузки:
    LOAD=35 python3 make-subscription.py client1.conf > subscription.json

ВАЖНО: в подписке лежат приватные ключи клиентов. Публикуйте её только в закрытом месте
(приватный репозиторий/ссылка, известная лишь вам), а не в открытом доступе.
"""
import json
import os
import re
import sys


def main(paths):
    servers = []
    for p in paths:
        text = open(p, encoding="utf-8").read().strip()
        name = re.search(r"(?im)^#\s*name\s*[:=]\s*(.+)$", text)
        country = re.search(r"(?im)^#\s*country\s*[:=]\s*(\w{2})\s*$", text)
        item = {
            "name": name.group(1).strip() if name else os.path.basename(p),
            "country": country.group(1).upper() if country else "??",
            "config": text,
        }
        if os.environ.get("LOAD"):
            item["load"] = int(os.environ["LOAD"])
        servers.append(item)
    json.dump({"version": 1, "servers": servers}, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
