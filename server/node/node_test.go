package main

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

type fakeWG struct {
	mu    sync.Mutex
	peers map[string]string
	hs    map[string]time.Time
	fail  bool
}

func newFakeWG() *fakeWG { return &fakeWG{peers: map[string]string{}, hs: map[string]time.Time{}} }

func (f *fakeWG) AddPeer(pub, ip string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.fail {
		return errors.New("wg недоступен")
	}
	f.peers[pub] = ip
	return nil
}
func (f *fakeWG) RemovePeer(pub string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.peers, pub)
	return nil
}
func (f *fakeWG) Handshakes() (map[string]time.Time, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	out := map[string]time.Time{}
	for k := range f.peers {
		out[k] = f.hs[k]
	}
	return out, nil
}
func (f *fakeWG) PublicKey() (string, error) { return strings.Repeat("A", 43) + "=", nil }

func key(n byte) string {
	b := make([]byte, 32)
	b[0] = n
	return base64.StdEncoding.EncodeToString(b)
}

func testServer(t *testing.T, subnet string) (*Server, *fakeWG, *Store) {
	t.Helper()
	st, err := NewStore(filepath.Join(t.TempDir(), "peers.json"), subnet)
	if err != nil {
		t.Fatal(err)
	}
	fw := newFakeWG()
	cfg := Config{Endpoint: "203.0.113.9:51820", DNS: []string{"1.1.1.1", "9.9.9.9"}, MTU: 1380, Keepalive: 25,
		ActiveCapacity: 10, RegPerHour: 3, StaleAfter: 14 * 24 * time.Hour, Version: "t"}
	return NewServer(cfg, st, fw, strings.Repeat("B", 43)+"="), fw, st
}

func post(h http.Handler, addr, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodPost, "/v1/register", strings.NewReader(body))
	r.RemoteAddr = addr
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestRegisterIssuesAddressAndIsIdempotent(t *testing.T) {
	s, fw, _ := testServer(t, "10.8.0.0/24")
	h := s.Handler()
	body := `{"public_key":"` + key(1) + `"}`
	w1 := post(h, "1.2.3.4:5000", body)
	if w1.Code != 200 {
		t.Fatalf("код %d: %s", w1.Code, w1.Body)
	}
	var r1 map[string]any
	_ = json.Unmarshal(w1.Body.Bytes(), &r1)
	if r1["address"] != "10.8.0.2/32" || r1["endpoint"] != "203.0.113.9:51820" || r1["server_public_key"] == "" {
		t.Fatalf("неожиданный ответ: %v", r1)
	}
	if fw.peers[key(1)] != "10.8.0.2" {
		t.Fatal("клиент не добавлен в WireGuard")
	}
	w2 := post(h, "1.2.3.4:5000", body)
	var r2 map[string]any
	_ = json.Unmarshal(w2.Body.Bytes(), &r2)
	if r2["address"] != r1["address"] {
		t.Fatalf("повторная регистрация выдала другой адрес: %v vs %v", r2["address"], r1["address"])
	}
	w3 := post(h, "5.6.7.8:1", `{"public_key":"`+key(2)+`"}`)
	var r3 map[string]any
	_ = json.Unmarshal(w3.Body.Bytes(), &r3)
	if r3["address"] != "10.8.0.3/32" {
		t.Fatalf("второй клиент: %v", r3["address"])
	}
}

func TestRegisterRejectsBadInput(t *testing.T) {
	s, _, _ := testServer(t, "10.8.0.0/24")
	h := s.Handler()
	for _, body := range []string{``, `{}`, `{"public_key":"abc"}`, `{"public_key":"` + key(1) + `","x":1}`,
		`{"public_key":"` + strings.Repeat("A", 44) + `"}`, `{"public_key":"` + key(1) + "\n[Interface]" + `"}`} {
		if w := post(h, "1.1.1.1:1", body); w.Code != 400 && w.Code != 429 {
			t.Errorf("тело %q: ожидался 400, получен %d", body, w.Code)
		}
	}
	r := httptest.NewRequest(http.MethodGet, "/v1/register", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusMethodNotAllowed {
		t.Errorf("GET register: %d", w.Code)
	}
}

func TestRateLimitPerIP(t *testing.T) {
	s, _, _ := testServer(t, "10.8.0.0/24")
	h := s.Handler()
	now := time.Now()
	s.now = func() time.Time { return now }
	for i := 0; i < 3; i++ {
		if w := post(h, "9.9.9.9:1", `{"public_key":"`+key(byte(i))+`"}`); w.Code != 200 {
			t.Fatalf("запрос %d: %d", i, w.Code)
		}
	}
	if w := post(h, "9.9.9.9:2", `{"public_key":"`+key(10)+`"}`); w.Code != 429 {
		t.Fatalf("ожидался 429, получен %d", w.Code)
	}
	if w := post(h, "8.8.8.8:2", `{"public_key":"`+key(10)+`"}`); w.Code != 200 {
		t.Fatalf("другой IP не должен быть ограничен: %d", w.Code)
	}
	s.now = func() time.Time { return now.Add(61 * time.Minute) }
	if w := post(h, "9.9.9.9:3", `{"public_key":"`+key(11)+`"}`); w.Code != 200 {
		t.Fatalf("после часа лимит должен сняться: %d", w.Code)
	}
}

func TestFullServerEvictsStalePeersThenRefuses(t *testing.T) {
	s, fw, st := testServer(t, "10.8.0.0/29") // 5 адресов для клиентов
	s.cfg.RegPerHour = 1000
	h := s.Handler()
	now := time.Now()
	s.now = func() time.Time { return now }
	if st.Capacity() != 5 {
		t.Fatalf("ёмкость %d", st.Capacity())
	}
	for i := 0; i < 5; i++ {
		if w := post(h, "1.1.1.1:1", `{"public_key":"`+key(byte(i))+`"}`); w.Code != 200 {
			t.Fatalf("клиент %d: %d", i, w.Code)
		}
	}
	if w := post(h, "1.1.1.1:1", `{"public_key":"`+key(50)+`"}`); w.Code != 503 {
		t.Fatalf("при заполнении ожидался 503, получен %d", w.Code)
	}
	// Один клиент «состарился»: рукопожатие 20 дней назад. Остальные недавно.
	fw.hs[key(0)] = now.Add(-20 * 24 * time.Hour)
	for i := 1; i < 5; i++ {
		fw.hs[key(byte(i))] = now.Add(-time.Hour)
	}
	if w := post(h, "1.1.1.1:1", `{"public_key":"`+key(50)+`"}`); w.Code != 200 {
		t.Fatalf("после очистки место должно появиться: %d %s", w.Code, w.Body)
	}
	if _, ok := fw.peers[key(0)]; ok {
		t.Fatal("неактивный клиент не удалён из WireGuard")
	}
}

func TestCleanupKeepsFreshAndNeverHandshakedRecent(t *testing.T) {
	s, fw, st := testServer(t, "10.8.0.0/24")
	now := time.Now()
	s.now = func() time.Time { return now }
	_, _, _ = st.Allocate(key(1), now.Add(-time.Hour)) // зарегистрирован час назад, рукопожатий нет
	_, _, _ = st.Allocate(key(2), now.Add(-30*24*time.Hour))
	_ = fw.AddPeer(key(1), "10.8.0.2")
	_ = fw.AddPeer(key(2), "10.8.0.3")
	if n := s.Cleanup(); n != 1 {
		t.Fatalf("удалено %d, ожидался 1", n)
	}
	if st.Count() != 1 {
		t.Fatal("остаться должен один клиент")
	}
}

func TestWGFailureRollsBackAllocation(t *testing.T) {
	s, fw, st := testServer(t, "10.8.0.0/24")
	fw.fail = true
	if w := post(s.Handler(), "1.1.1.1:1", `{"public_key":"`+key(1)+`"}`); w.Code != 500 {
		t.Fatalf("ожидался 500, получен %d", w.Code)
	}
	if st.Count() != 0 {
		t.Fatal("адрес не должен остаться занятым после ошибки WireGuard")
	}
}

func TestStorePersistsAndReapplies(t *testing.T) {
	path := filepath.Join(t.TempDir(), "peers.json")
	st, _ := NewStore(path, "10.8.0.0/24")
	_, _, _ = st.Allocate(key(1), time.Now())
	st2, err := NewStore(path, "10.8.0.0/24")
	if err != nil || st2.Count() != 1 {
		t.Fatalf("состояние не восстановилось: %v %d", err, st2.Count())
	}
	fw := newFakeWG()
	cfg := Config{RegPerHour: 1}
	s := NewServer(cfg, st2, fw, "x")
	if err := s.Reapply(); err != nil || fw.peers[key(1)] != "10.8.0.2" {
		t.Fatalf("клиент не возвращён в WireGuard: %v %v", err, fw.peers)
	}
}

func TestHealthAndMetrics(t *testing.T) {
	s, fw, _ := testServer(t, "10.8.0.0/24")
	h := s.Handler()
	_ = post(h, "1.1.1.1:1", `{"public_key":"`+key(1)+`"}`)
	fw.hs[key(1)] = time.Now()
	r := httptest.NewRequest(http.MethodGet, "/v1/health", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	var d map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &d)
	if d["ok"] != true || d["peers"].(float64) != 1 || d["load"].(float64) != 10 {
		t.Fatalf("health: %v", d)
	}
	w = httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	if !strings.Contains(w.Body.String(), "freevpn_peers 1") || !strings.Contains(w.Body.String(), "freevpn_active_peers 1") {
		t.Fatalf("metrics: %s", w.Body)
	}
}

func TestParseHandshakes(t *testing.T) {
	got := parseHandshakes([]byte("AAA=\t1700000000\nBBB=\t0\nбитая строка\n"))
	if got["AAA="].Unix() != 1700000000 || !got["BBB="].IsZero() || len(got) != 2 {
		t.Fatalf("%v", got)
	}
}

// Сквозная проверка: настоящий TLS-сервер с самоподписанным сертификатом и клиент,
// который доверяет только отпечатку — ровно так работает приложение.
func TestPinnedTLSEndToEnd(t *testing.T) {
	dir := t.TempDir()
	cert, pin, err := LoadOrCreateCert(dir, []string{"127.0.0.1"})
	if err != nil {
		t.Fatal(err)
	}
	cert2, pin2, _ := LoadOrCreateCert(dir, []string{"127.0.0.1"})
	if pin != pin2 || len(cert2.Certificate) != 1 {
		t.Fatal("сертификат должен сохраняться между запусками")
	}
	s, _, _ := testServer(t, "10.8.0.0/24")
	ts := httptest.NewUnstartedServer(s.Handler())
	ts.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	ts.StartTLS()
	defer ts.Close()

	client := func(wantPin string) *http.Client {
		return &http.Client{Timeout: 3 * time.Second, Transport: &http.Transport{TLSClientConfig: &tls.Config{
			InsecureSkipVerify: true, // проверку делает VerifyPeerCertificate по отпечатку
			VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
				leaf, err := x509.ParseCertificate(raw[0])
				if err != nil {
					return err
				}
				if SPKIPin(leaf) != wantPin {
					return errors.New("отпечаток не совпал")
				}
				return nil
			}}}}
	}
	resp, err := client(pin).Get(ts.URL + "/v1/health")
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("с верным отпечатком запрос должен пройти: %v", err)
	}
	resp.Body.Close()
	resp, err = client(pin).Post(ts.URL+"/v1/register", "application/json", bytes.NewBufferString(`{"public_key":"`+key(7)+`"}`))
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("регистрация по TLS: %v", err)
	}
	resp.Body.Close()
	if _, err := client("неверный").Get(ts.URL + "/v1/health"); err == nil {
		t.Fatal("с неверным отпечатком соединение должно отклоняться")
	}
}

func TestStoreRejectsBadSubnet(t *testing.T) {
	for _, sn := range []string{"abc", "10.8.0.0/30", "fd00::/64"} {
		if _, err := NewStore(filepath.Join(t.TempDir(), "p.json"), sn); err == nil {
			t.Errorf("подсеть %q должна быть отклонена", sn)
		}
	}
}
