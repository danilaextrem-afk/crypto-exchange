package main

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type Config struct {
	Endpoint       string   // host:port, который клиент использует для WireGuard
	DNS            []string // DNS, выдаваемый клиентам (работает через туннель)
	MTU            int
	Keepalive      int
	ActiveCapacity int           // сколько одновременно активных клиентов считаем 100% нагрузки
	RegPerHour     int           // регистраций в час с одного IP
	StaleAfter     time.Duration // через сколько без рукопожатий удалять клиента
	Version        string
}

type Server struct {
	cfg   Config
	store *Store
	wg    WG
	now   func() time.Time

	serverPub   string
	regTotal    atomic.Int64
	regRejected atomic.Int64

	rlMu sync.Mutex
	rl   map[string][]time.Time
}

func NewServer(cfg Config, store *Store, wg WG, serverPub string) *Server {
	return &Server{cfg: cfg, store: store, wg: wg, now: time.Now, serverPub: serverPub, rl: map[string][]time.Time{}}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", s.handleHealth)
	mux.HandleFunc("/v1/register", s.handleRegister)
	mux.HandleFunc("/metrics", s.handleMetrics)
	return mux
}

// Reapply возвращает всех сохранённых клиентов в интерфейс WireGuard (после перезапуска контейнера).
func (s *Server) Reapply() error {
	var firstErr error
	for _, p := range s.store.List() {
		if err := s.wg.AddPeer(p.PublicKey, p.IP); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return firstErr
}

func (s *Server) activePeers() int {
	hs, err := s.wg.Handshakes()
	if err != nil {
		return 0
	}
	n := 0
	for _, t := range hs {
		if !t.IsZero() && s.now().Sub(t) < 3*time.Minute {
			n++
		}
	}
	return n
}

func (s *Server) load() int {
	if s.cfg.ActiveCapacity <= 0 {
		return 0
	}
	l := s.activePeers() * 100 / s.cfg.ActiveCapacity
	if l > 100 {
		l = 100
	}
	return l
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":       true,
		"load":     s.load(),
		"peers":    s.store.Count(),
		"capacity": s.store.Capacity(),
		"version":  s.cfg.Version,
	})
}

type registerRequest struct {
	PublicKey string `json:"public_key"`
}

func validKey(k string) bool {
	b, err := base64.StdEncoding.DecodeString(k)
	return err == nil && len(b) == 32 && len(k) == 44
}

func (s *Server) allowRegister(ip string) bool {
	s.rlMu.Lock()
	defer s.rlMu.Unlock()
	cutoff := s.now().Add(-time.Hour)
	kept := s.rl[ip][:0]
	for _, t := range s.rl[ip] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= s.cfg.RegPerHour {
		s.rl[ip] = kept
		return false
	}
	s.rl[ip] = append(kept, s.now())
	if len(s.rl) > 10000 { // защита памяти
		for k, v := range s.rl {
			if len(v) == 0 || v[len(v)-1].Before(cutoff) {
				delete(s.rl, k)
			}
		}
	}
	return true
}

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method"})
		return
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if !s.allowRegister(host) {
		s.regRejected.Add(1)
		w.Header().Set("Retry-After", "3600")
		writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "rate_limited"})
		return
	}
	var req registerRequest
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil || !validKey(req.PublicKey) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "bad_request"})
		return
	}

	peer, created, err := s.store.Allocate(req.PublicKey, s.now())
	if errors.Is(err, ErrFull) {
		s.Cleanup() // освобождаем места от давно неактивных клиентов и пробуем ещё раз
		peer, created, err = s.store.Allocate(req.PublicKey, s.now())
	}
	if errors.Is(err, ErrFull) {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "full"})
		return
	}
	if err != nil {
		log.Printf("register: store: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "internal"})
		return
	}
	// AddPeer идемпотентен: повторная регистрация чинит потерянного клиента.
	if err := s.wg.AddPeer(peer.PublicKey, peer.IP); err != nil {
		log.Printf("register: wg: %v", err)
		if created {
			_ = s.store.Remove(peer.PublicKey)
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "internal"})
		return
	}
	s.regTotal.Add(1)
	writeJSON(w, http.StatusOK, map[string]any{
		"address":           fmt.Sprintf("%s/32", peer.IP),
		"server_public_key": s.serverPub,
		"endpoint":          s.cfg.Endpoint,
		"dns":               s.cfg.DNS,
		"mtu":               s.cfg.MTU,
		"keepalive":         s.cfg.Keepalive,
	})
}

// Cleanup удаляет клиентов, у которых давно не было рукопожатий. Возвращает число удалённых.
func (s *Server) Cleanup() int {
	hs, err := s.wg.Handshakes()
	if err != nil {
		log.Printf("cleanup: %v", err)
		return 0
	}
	removed := 0
	now := s.now()
	for _, p := range s.store.List() {
		last := hs[p.PublicKey]
		ref := last
		if ref.IsZero() {
			ref = p.Created // рукопожатий не было — считаем от момента регистрации
		}
		if now.Sub(ref) < s.cfg.StaleAfter {
			continue
		}
		if err := s.wg.RemovePeer(p.PublicKey); err != nil {
			log.Printf("cleanup: remove peer: %v", err)
			continue
		}
		if err := s.store.Remove(p.PublicKey); err == nil {
			removed++
		}
	}
	return removed
}

func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	var b strings.Builder
	fmt.Fprintf(&b, "# TYPE freevpn_up gauge\nfreevpn_up 1\n")
	fmt.Fprintf(&b, "# TYPE freevpn_peers gauge\nfreevpn_peers %d\n", s.store.Count())
	fmt.Fprintf(&b, "# TYPE freevpn_active_peers gauge\nfreevpn_active_peers %d\n", s.activePeers())
	fmt.Fprintf(&b, "# TYPE freevpn_capacity gauge\nfreevpn_capacity %d\n", s.store.Capacity())
	fmt.Fprintf(&b, "# TYPE freevpn_registrations_total counter\nfreevpn_registrations_total %d\n", s.regTotal.Load())
	fmt.Fprintf(&b, "# TYPE freevpn_registrations_rejected_total counter\nfreevpn_registrations_rejected_total %d\n", s.regRejected.Load())
	w.Header().Set("Content-Type", "text/plain; version=0.0.4")
	_, _ = w.Write([]byte(b.String()))
}
