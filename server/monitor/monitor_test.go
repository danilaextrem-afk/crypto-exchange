package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func selfSigned(t *testing.T) (tls.Certificate, string) {
	t.Helper()
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	tpl := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "t"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &k.PublicKey, k)
	if err != nil {
		t.Fatal(err)
	}
	leaf, _ := x509.ParseCertificate(der)
	sum := sha256.Sum256(leaf.RawSubjectPublicKeyInfo)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: k}, base64.StdEncoding.EncodeToString(sum[:])
}

func TestCheckUpDownAndPinMismatch(t *testing.T) {
	cert, pin := selfSigned(t)
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/health", func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"ok":true,"load":7,"peers":3}`))
	})
	ts := httptest.NewUnstartedServer(mux)
	ts.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	ts.StartTLS()
	defer ts.Close()

	r := check(Node{ID: "a", Name: "A", Country: "DE", API: ts.URL, Pin: pin})
	if !r.Up || r.Load != 7 || r.Peers != 3 || r.RTTms <= 0 {
		t.Fatalf("ожидался рабочий узел: %+v", r)
	}
	if r := check(Node{ID: "b", API: ts.URL, Pin: "чужой-отпечаток"}); r.Up || r.Error == "" {
		t.Fatalf("неверный отпечаток должен давать недоступность: %+v", r)
	}
	if r := check(Node{ID: "c", API: "https://127.0.0.1:1", Pin: pin}); r.Up {
		t.Fatalf("закрытый порт должен давать недоступность: %+v", r)
	}
	if r := check(Node{ID: "d", API: "http://127.0.0.1:1", Pin: pin}); r.Up {
		t.Fatalf("http без TLS должен отклоняться: %+v", r)
	}
}
