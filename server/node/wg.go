package main

import (
	"bufio"
	"bytes"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// WG — всё, что узлу нужно от WireGuard. В тестах подменяется фиктивной реализацией.
type WG interface {
	AddPeer(pub, ip string) error
	RemovePeer(pub string) error
	// Handshakes возвращает время последнего рукопожатия по ключам (нулевое время — рукопожатий не было).
	Handshakes() (map[string]time.Time, error)
	PublicKey() (string, error)
}

type execWG struct{ iface string }

func (w execWG) run(args ...string) ([]byte, error) {
	cmd := exec.Command("wg", args...)
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("wg %s: %v: %s", strings.Join(args, " "), err, strings.TrimSpace(errb.String()))
	}
	return out.Bytes(), nil
}

func (w execWG) AddPeer(pub, ip string) error {
	_, err := w.run("set", w.iface, "peer", pub, "allowed-ips", ip+"/32")
	return err
}

func (w execWG) RemovePeer(pub string) error {
	_, err := w.run("set", w.iface, "peer", pub, "remove")
	return err
}

func (w execWG) PublicKey() (string, error) {
	b, err := w.run("show", w.iface, "public-key")
	return strings.TrimSpace(string(b)), err
}

func (w execWG) Handshakes() (map[string]time.Time, error) {
	b, err := w.run("show", w.iface, "latest-handshakes")
	if err != nil {
		return nil, err
	}
	return parseHandshakes(b), nil
}

func parseHandshakes(b []byte) map[string]time.Time {
	res := map[string]time.Time{}
	sc := bufio.NewScanner(bytes.NewReader(b))
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) != 2 {
			continue
		}
		sec, err := strconv.ParseInt(f[1], 10, 64)
		if err != nil {
			continue // не строка формата «ключ время»
		}
		if sec <= 0 {
			res[f[0]] = time.Time{} // рукопожатий ещё не было
			continue
		}
		res[f[0]] = time.Unix(sec, 0)
	}
	return res
}
