package main

import (
	"crypto/tls"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const version = "1"

func env(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func main() {
	stateDir := env("STATE_DIR", "/var/lib/freevpn")
	listen := env("LISTEN", ":443")
	iface := env("WG_IF", "wg0")
	subnet := env("SUBNET", "10.8.0.0/22")
	publicHost := os.Getenv("PUBLIC_HOST") // внешний IP или имя узла (обязательно)
	wgPort := env("WG_PORT", "51820")

	if len(os.Args) > 1 && os.Args[1] == "serveraddr" {
		// Адрес сервера внутри туннеля вида 10.8.0.1/22 (нужен entrypoint.sh).
		st, err := NewStore(filepath.Join(os.TempDir(), "freevpn-addr.json"), subnet)
		if err != nil {
			log.Fatal(err)
		}
		fmt.Printf("%s/%d\n", st.ServerAddr(), st.Bits())
		return
	}
	if len(os.Args) > 1 && os.Args[1] == "fingerprint" {
		_, pin, err := LoadOrCreateCert(stateDir, []string{publicHost})
		if err != nil {
			log.Fatal(err)
		}
		fmt.Println(pin)
		return
	}
	if publicHost == "" {
		log.Fatal("не задан PUBLIC_HOST (внешний IP или имя этого сервера)")
	}

	cert, pin, err := LoadOrCreateCert(stateDir, []string{publicHost})
	if err != nil {
		log.Fatalf("сертификат: %v", err)
	}
	// Отпечаток нужен каталогу узлов: приложение доверяет только ему.
	if err := os.WriteFile(filepath.Join(stateDir, "cert.pin"), []byte(pin+"\n"), 0o644); err != nil {
		log.Fatal(err)
	}

	store, err := NewStore(filepath.Join(stateDir, "peers.json"), subnet)
	if err != nil {
		log.Fatal(err)
	}
	wg := execWG{iface: iface}
	pub, err := wg.PublicKey()
	if err != nil || pub == "" {
		log.Fatalf("интерфейс %s не поднят или без ключа: %v", iface, err)
	}

	cfg := Config{
		Endpoint:       publicHost + ":" + wgPort,
		DNS:            strings.Split(env("CLIENT_DNS", "1.1.1.1,9.9.9.9"), ","),
		MTU:            envInt("CLIENT_MTU", 1380),
		Keepalive:      25,
		ActiveCapacity: envInt("ACTIVE_CAPACITY", 100),
		RegPerHour:     envInt("REGISTRATIONS_PER_HOUR", 10),
		StaleAfter:     time.Duration(envInt("STALE_DAYS", 14)) * 24 * time.Hour,
		Version:        version,
	}
	srv := NewServer(cfg, store, wg, pub)
	if err := srv.Reapply(); err != nil {
		log.Printf("предупреждение: не все клиенты восстановлены: %v", err)
	}

	go func() {
		for range time.Tick(time.Hour) {
			if n := srv.Cleanup(); n > 0 {
				log.Printf("удалено неактивных клиентов: %d", n)
			}
		}
	}()

	hs := &http.Server{
		Addr:              listen,
		Handler:           srv.Handler(),
		TLSConfig:         &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12},
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	log.Printf("freevpn-node v%s: API %s, WireGuard %s, клиентов в базе: %d, pin=%s", version, listen, cfg.Endpoint, store.Count(), pin)
	log.Fatal(hs.ListenAndServeTLS("", ""))
}
