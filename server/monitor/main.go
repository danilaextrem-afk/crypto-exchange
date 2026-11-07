// freevpn-monitor проверяет все узлы из каталога так же, как это делает приложение:
// TCP-подключение, затем HTTPS-запрос /v1/health с проверкой отпечатка сертификата.
//
//	go run . ../../directory/directory.json [status.json]
//
// Код выхода 1, если хотя бы один узел недоступен (плановый workflow сообщит об этом письмом).
package main

import (
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"sort"
	"sync"
	"time"
)

type Node struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Country string `json:"country"`
	API     string `json:"api"`
	Pin     string `json:"pin"`
}

type Directory struct {
	Version int    `json:"version"`
	Updated string `json:"updated"`
	Nodes   []Node `json:"nodes"`
}

type Result struct {
	ID      string  `json:"id"`
	Name    string  `json:"name"`
	Country string  `json:"country"`
	Up      bool    `json:"up"`
	RTTms   float64 `json:"rtt_ms,omitempty"`
	Load    int     `json:"load,omitempty"`
	Peers   int     `json:"peers,omitempty"`
	Error   string  `json:"error,omitempty"`
}

func pinClient(pin string) *http.Client {
	return &http.Client{Timeout: 6 * time.Second, Transport: &http.Transport{
		DisableKeepAlives: true,
		TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true, // сертификат самоподписанный; доверие — только по отпечатку ниже
			VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
				if len(raw) == 0 {
					return errors.New("нет сертификата")
				}
				leaf, err := x509.ParseCertificate(raw[0])
				if err != nil {
					return err
				}
				sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
				if base64.StdEncoding.EncodeToString(sum[:]) != pin {
					return errors.New("отпечаток сертификата не совпал с каталогом")
				}
				return nil
			},
		},
	}}
}

func check(n Node) Result {
	r := Result{ID: n.ID, Name: n.Name, Country: n.Country}
	u, err := url.Parse(n.API)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		r.Error = "некорректный адрес api"
		return r
	}
	host := u.Host
	if u.Port() == "" {
		host = net.JoinHostPort(u.Hostname(), "443")
	}
	best := time.Duration(0)
	for i := 0; i < 3; i++ {
		t0 := time.Now()
		c, err := net.DialTimeout("tcp", host, 4*time.Second)
		if err != nil {
			continue
		}
		d := time.Since(t0)
		c.Close()
		if best == 0 || d < best {
			best = d
		}
	}
	if best == 0 {
		r.Error = "TCP-порт недоступен"
		return r
	}
	resp, err := pinClient(n.Pin).Get(n.API + "/v1/health")
	if err != nil {
		r.Error = err.Error()
		return r
	}
	defer resp.Body.Close()
	var h struct {
		OK    bool `json:"ok"`
		Load  int  `json:"load"`
		Peers int  `json:"peers"`
	}
	if resp.StatusCode != 200 || json.NewDecoder(resp.Body).Decode(&h) != nil || !h.OK {
		r.Error = fmt.Sprintf("health: HTTP %d", resp.StatusCode)
		return r
	}
	r.Up, r.RTTms, r.Load, r.Peers = true, float64(best.Microseconds())/1000, h.Load, h.Peers
	return r
}

func run(dir Directory) []Result {
	res := make([]Result, len(dir.Nodes))
	var wg sync.WaitGroup
	for i, n := range dir.Nodes {
		wg.Add(1)
		go func() { defer wg.Done(); res[i] = check(n) }()
	}
	wg.Wait()
	sort.SliceStable(res, func(i, j int) bool { return res[i].Country < res[j].Country })
	return res
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "использование: freevpn-monitor directory.json [status.json]")
		os.Exit(2)
	}
	b, err := os.ReadFile(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	var dir Directory
	if err := json.Unmarshal(b, &dir); err != nil {
		fmt.Fprintln(os.Stderr, "каталог повреждён:", err)
		os.Exit(2)
	}
	if len(dir.Nodes) == 0 {
		fmt.Println("В каталоге нет узлов — проверять нечего.")
		return
	}
	res := run(dir)
	down := 0
	for _, r := range res {
		if r.Up {
			fmt.Printf("UP    %-4s %-18s %6.1f мс  нагрузка %d%%  клиентов %d\n", r.Country, r.Name, r.RTTms, r.Load, r.Peers)
		} else {
			down++
			fmt.Printf("DOWN  %-4s %-18s %s\n", r.Country, r.Name, r.Error)
		}
	}
	if len(os.Args) > 2 {
		out, _ := json.MarshalIndent(map[string]any{"checked": time.Now().UTC().Format(time.RFC3339), "nodes": res}, "", "  ")
		_ = os.WriteFile(os.Args[2], out, 0o644)
	}
	if down > 0 {
		fmt.Printf("\nНедоступно узлов: %d из %d\n", down, len(res))
		os.Exit(1)
	}
}
