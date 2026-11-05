#!/usr/bin/env bash
# Запускается внутри reactivecircus/android-emulator-runner (эмулятор уже загружен).
# Хост — машина GitHub Actions с настоящим WireGuard-сервером (server/setup-wireguard.sh).
set -uo pipefail

OUT="${E2E_OUT:-e2e-out}"
mkdir -p "$OUT"
adb logcat -c || true

# 1. Хост выполняет команды теста: остановить/запустить сервер (тест «сервер упал»).
(
  adb logcat -v raw -s FreeVpnE2E:I | while read -r line; do
    case "$line" in
      *STOP_SERVER*)  echo "[host] $(date +%T) остановка WireGuard-сервера";  sudo systemctl stop wg-quick@wg0 ;;
      *START_SERVER*) echo "[host] $(date +%T) запуск WireGuard-сервера";     sudo systemctl start wg-quick@wg0 ;;
    esac
  done
) &
WATCHER=$!

# 2. Захват DNS-запросов, вышедших ИЗ туннеля на сервере (источник — адреса клиентов 10.8.0.0/24).
sudo tcpdump -i any -n -U -w "$OUT/dns-from-tunnel.pcap" \
  'src net 10.8.0.0/24 and (port 53 or port 853)' >/dev/null 2>&1 &
TCPDUMP=$!

# 3. Сквозные тесты (debug-сборка с инструментацией).
./gradlew --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.wgConf1="$(cat "$RUNNER_TEMP/c1.b64")" \
  -Pandroid.testInstrumentationRunnerArguments.wgConf2="$(cat "$RUNNER_TEMP/c2.b64")"
RC=$?

sleep 2
sudo kill "$TCPDUMP" 2>/dev/null || true
kill "$WATCHER" 2>/dev/null || true
sudo systemctl start wg-quick@wg0 || true

mkdir -p "$OUT/screens"
for f in $(adb shell ls /data/local/tmp/freevpn-e2e/ 2>/dev/null | tr -d '\r'); do
  adb pull "/data/local/tmp/freevpn-e2e/$f" "$OUT/screens/$f" >/dev/null || true
done
adb logcat -d > "$OUT/logcat-instrumented.txt" || true
sudo wg show > "$OUT/wg-show.txt" 2>&1 || true
sudo chmod a+r "$OUT"/*.pcap 2>/dev/null || true
echo "DNS-пакетов из туннеля: $(sudo tcpdump -r "$OUT/dns-from-tunnel.pcap" -n 2>/dev/null | wc -l)" | tee "$OUT/dns-summary.txt"
sudo tcpdump -r "$OUT/dns-from-tunnel.pcap" -n 2>/dev/null | head -20 >> "$OUT/dns-summary.txt" || true

# 4. Настоящий release-APK (тот, что получает пользователь): установка, запуск, падения.
if [ -f FreeVPN.apk ]; then
  adb uninstall app.freevpn >/dev/null 2>&1 || true
  adb install -r FreeVPN.apk | tee "$OUT/release-install.txt"
  adb logcat -c
  adb shell am start -W -n app.freevpn/.MainActivity | tee "$OUT/release-start.txt"
  sleep 6
  adb shell screencap -p /data/local/tmp/release-home.png
  adb pull /data/local/tmp/release-home.png "$OUT/screens/20-release-home.png" || true
  PID="$(adb shell pidof app.freevpn | tr -d '\r')"
  adb logcat -d > "$OUT/logcat-release.txt"
  if [ -z "$PID" ] || grep -q "FATAL EXCEPTION" "$OUT/logcat-release.txt"; then
    echo "::error::release-APK упал при запуске"
    RC=1
  else
    echo "release-APK запущен, процесс $PID, падений нет" | tee -a "$OUT/release-start.txt"
  fi
fi

exit $RC
