package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Peer — зарегистрированное устройство. Хранится только публичный ключ и выданный адрес:
// никакого содержимого трафика, никаких журналов подключений.
type Peer struct {
	PublicKey string    `json:"public_key"`
	IP        string    `json:"ip"`
	Created   time.Time `json:"created"`
}

var ErrFull = errors.New("нет свободных адресов")

// Store выдаёт адреса из подсети и сохраняет их на диск атомарно.
type Store struct {
	mu     sync.Mutex
	path   string
	prefix netip.Prefix
	peers  map[string]Peer // по публичному ключу
}

func NewStore(path string, subnet string) (*Store, error) {
	p, err := netip.ParsePrefix(subnet)
	if err != nil {
		return nil, fmt.Errorf("подсеть %q: %w", subnet, err)
	}
	if !p.Addr().Is4() || p.Bits() > 29 {
		return nil, fmt.Errorf("нужна IPv4-подсеть не меньше /29, получено %s", subnet)
	}
	s := &Store{path: path, prefix: p.Masked(), peers: map[string]Peer{}}
	if b, err := os.ReadFile(path); err == nil {
		var list []Peer
		if err := json.Unmarshal(b, &list); err != nil {
			return nil, fmt.Errorf("файл состояния повреждён: %w", err)
		}
		for _, pe := range list {
			s.peers[pe.PublicKey] = pe
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	return s, nil
}

// ServerAddr — адрес самого сервера в туннеле (первый хост подсети).
func (s *Store) ServerAddr() netip.Addr { return s.prefix.Addr().Next() }

func (s *Store) Bits() int { return s.prefix.Bits() }

func (s *Store) Count() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.peers)
}

func (s *Store) Capacity() int {
	hosts := 1 << (32 - s.prefix.Bits())
	return hosts - 3 // сеть, сервер, broadcast
}

func (s *Store) List() []Peer {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Peer, 0, len(s.peers))
	for _, p := range s.peers {
		out = append(out, p)
	}
	return out
}

// Allocate возвращает адрес для ключа. Повторный вызов с тем же ключом возвращает тот же адрес.
func (s *Store) Allocate(pub string, now time.Time) (Peer, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if p, ok := s.peers[pub]; ok {
		return p, false, nil
	}
	used := make(map[netip.Addr]bool, len(s.peers))
	for _, p := range s.peers {
		if a, err := netip.ParseAddr(p.IP); err == nil {
			used[a] = true
		}
	}
	server := s.ServerAddr()
	for a := server.Next(); s.prefix.Contains(a); a = a.Next() {
		if a == server || used[a] || !s.prefix.Contains(a.Next()) { // последний адрес — broadcast
			continue
		}
		p := Peer{PublicKey: pub, IP: a.String(), Created: now.UTC()}
		s.peers[pub] = p
		if err := s.saveLocked(); err != nil {
			delete(s.peers, pub)
			return Peer{}, false, err
		}
		return p, true, nil
	}
	return Peer{}, false, ErrFull
}

func (s *Store) Remove(pub string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.peers[pub]; !ok {
		return nil
	}
	old := s.peers[pub]
	delete(s.peers, pub)
	if err := s.saveLocked(); err != nil {
		s.peers[pub] = old
		return err
	}
	return nil
}

func (s *Store) saveLocked() error {
	list := make([]Peer, 0, len(s.peers))
	for _, p := range s.peers {
		list = append(list, p)
	}
	b, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}
