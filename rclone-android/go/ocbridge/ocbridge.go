// Package ocbridge is a userspace Cisco AnyConnect (and ocserv/OpenConnect
// server) client exposed via gomobile, so Haven can route a single
// connection profile through a corporate AnyConnect VPN without Cisco's app
// and without taking the system VPN slot — the same per-app model as
// wgbridge (WireGuard), tsbridge (Tailscale) and nbbridge (NetBird).
//
// Protocol: Cisco's "aggregate" XML authentication over HTTPS yields a
// session cookie; a CONNECT /CSCOSSLC/tunnel on a fresh TLS connection then
// carries IP packets as CSTP frames ("STF\x01" + length + type). This
// follows openconnect's anyconnect protocol driver. The IP packets are fed
// to wireguard-go's gVisor netstack (already linked into libgojni for
// wgbridge), which is what Dial / ListenUDP / ListenTCP talk to, so no
// kernel TUN or VpnService is involved.
//
// Scope, deliberately: TLS (CSTP) only — no DTLS, no compression, no
// client certificates, no SAML/SSO, no posture (CSD/HostScan). Username +
// password, auth-group selection, and a TOTP or fixed second factor are
// supported. Sessions reconnect on their own after network changes.
//
// The exported API mirrors wgbridge so the Kotlin wrapper is the same shape.
package ocbridge

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"

	"sh.haven/rcbridge/socks5"
)

// TunnelHandle is a live AnyConnect session with its userspace netstack.
// Dial is safe to call concurrently.
type TunnelHandle struct {
	sess     *session
	tnet     *netstack.Net
	bindAddr netip.Addr

	mu      sync.Mutex
	closed  bool
	failure error
	socksLn net.Listener
}

// Conn is a TCP connection through a [TunnelHandle]. Read returns a fresh
// slice because gomobile passes []byte arguments by copy.
type Conn struct {
	c net.Conn
}

// UDPConn is an unconnected UDP socket inside the tunnel's netstack.
type UDPConn struct {
	c net.PacketConn
}

// UDPRead packages a single ReadFrom result for gomobile.
type UDPRead struct {
	Data     []byte
	FromHost string
	FromPort int
}

// Listener accepts inbound TCP connections on the tunnel address.
type Listener struct {
	ln net.Listener
}

// StartTunnel authenticates against the gateway described by configJSON
// (see [Config]) and brings the tunnel up. Blocks for up to 60 seconds;
// call from a background thread. The handle must be closed with
// [TunnelHandle.Close], which also logs the session out on the gateway.
func StartTunnel(configJSON string) (*TunnelHandle, error) {
	cfg, err := parseConfig(configJSON)
	if err != nil {
		return nil, fmt.Errorf("parse config: %w", err)
	}
	base, err := normalizeServer(cfg.Server)
	if err != nil {
		return nil, err
	}
	tlsConf := tlsConfigFor(cfg, base)

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	cookie, err := newAuthClient(cfg, base, tlsConf).authenticate(ctx)
	if err != nil {
		return nil, fmt.Errorf("authenticate: %w", err)
	}
	conn, br, params, err := connectCSTP(ctx, cfg, base, tlsConf, cookie)
	if err != nil {
		go logout(cfg, base, tlsConf, cookie)
		return nil, fmt.Errorf("open tunnel: %w", err)
	}

	dev, tnet, err := netstack.CreateNetTUN(params.addresses(), params.dns, params.mtu)
	if err != nil {
		conn.Close()
		go logout(cfg, base, tlsConf, cookie)
		return nil, fmt.Errorf("create netstack: %w", err)
	}

	h := &TunnelHandle{tnet: tnet}
	if params.addr4.IsValid() {
		h.bindAddr = params.addr4
	} else {
		h.bindAddr = params.addr6
	}
	h.sess = newSession(cfg, base, tlsConf, cookie, conn, br, params, dev, func(err error) {
		h.closeWith(err)
	})
	h.sess.start()
	return h, nil
}

func (t *TunnelHandle) net() (*netstack.Net, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		if t.failure != nil {
			return nil, fmt.Errorf("tunnel closed: %w", t.failure)
		}
		return nil, errors.New("tunnel closed")
	}
	return t.tnet, nil
}

// Dial opens a TCP connection through the tunnel. Hostnames resolve via
// the DNS servers the gateway pushed. timeoutMs <= 0 means 30 s.
func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (*Conn, error) {
	tnet, err := t.net()
	if err != nil {
		return nil, err
	}
	if timeoutMs <= 0 {
		timeoutMs = 30_000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	c, err := tnet.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return nil, fmt.Errorf("dial %s:%d: %w", host, port, err)
	}
	return &Conn{c: c}, nil
}

// ListenUDP opens an unconnected UDP socket bound to the tunnel address
// (netstack can't route from an unspecified source; see wgbridge).
func (t *TunnelHandle) ListenUDP() (*UDPConn, error) {
	tnet, err := t.net()
	if err != nil {
		return nil, err
	}
	pc, err := tnet.ListenUDPAddrPort(netip.AddrPortFrom(t.bindAddr, 0))
	if err != nil {
		return nil, fmt.Errorf("netstack ListenUDP: %w", err)
	}
	return &UDPConn{c: pc}, nil
}

// StartSocksListener lazily binds a 127.0.0.1 SOCKS5 listener fronting the
// tunnel (for rclone / IronRDP) and returns its port. Idempotent.
func (t *TunnelHandle) StartSocksListener() (int, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return 0, errors.New("tunnel closed")
	}
	if t.socksLn != nil {
		return t.socksLn.Addr().(*net.TCPAddr).Port, nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("bind SOCKS5 listener: %w", err)
	}
	t.socksLn = ln
	tnet := t.tnet
	go socks5.Serve(ln, func(host string, port int) (net.Conn, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return tnet.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	})
	return ln.Addr().(*net.TCPAddr).Port, nil
}

// BindAddr is the address the gateway assigned (IPv4 preferred).
func (t *TunnelHandle) BindAddr() string {
	if t.bindAddr.IsValid() {
		return t.bindAddr.String()
	}
	return ""
}

// ListenTCP binds a TCP listener on the tunnel address. Most AnyConnect
// gateways filter inbound connections to clients, but where policy allows
// it this lets on-device servers (MCP) be reached over the VPN.
func (t *TunnelHandle) ListenTCP(port int) (*Listener, error) {
	tnet, err := t.net()
	if err != nil {
		return nil, err
	}
	ln, err := tnet.ListenTCPAddrPort(netip.AddrPortFrom(t.bindAddr, uint16(port)))
	if err != nil {
		return nil, fmt.Errorf("netstack ListenTCP: %w", err)
	}
	return &Listener{ln: ln}, nil
}

// LastError is why the tunnel went down on its own, or "" if it is up or
// was closed normally. For surfacing in the UI.
func (t *TunnelHandle) LastError() string {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.failure != nil {
		return t.failure.Error()
	}
	return ""
}

// Close disconnects, logs out of the gateway and tears down the netstack.
// Idempotent.
func (t *TunnelHandle) Close() {
	t.closeWith(nil)
}

func (t *TunnelHandle) closeWith(cause error) {
	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return
	}
	t.closed = true
	t.failure = cause
	ln := t.socksLn
	t.socksLn = nil
	t.mu.Unlock()

	if ln != nil {
		ln.Close()
	}
	t.sess.close()
	t.sess.dev.Close()
}

// --- Conn / UDPConn / Listener (same contract as wgbridge) ------------------

// RemoteAddrHost returns the literal IP this connection reached.
func (c *Conn) RemoteAddrHost() string {
	if addr, ok := c.c.RemoteAddr().(*net.TCPAddr); ok && addr.IP != nil {
		return addr.IP.String()
	}
	return ""
}

// Read returns up to size bytes; nil + error on EOF or failure.
func (c *Conn) Read(size int) ([]byte, error) {
	if size <= 0 {
		size = 4096
	}
	buf := make([]byte, size)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], err
	}
	return nil, err
}

// Write writes all of data.
func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

// Close closes the connection.
func (c *Conn) Close() error { return c.c.Close() }

// ReadFrom blocks for a datagram; timeoutMs > 0 sets a one-shot deadline
// and a timeout surfaces as an "i/o timeout" error.
func (u *UDPConn) ReadFrom(size int, timeoutMs int) (*UDPRead, error) {
	if size <= 0 {
		size = 2048
	}
	if timeoutMs > 0 {
		_ = u.c.SetReadDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond))
	} else {
		_ = u.c.SetReadDeadline(time.Time{})
	}
	buf := make([]byte, size)
	n, addr, err := u.c.ReadFrom(buf)
	if err != nil {
		return nil, err
	}
	if udp, ok := addr.(*net.UDPAddr); ok {
		return &UDPRead{Data: buf[:n], FromHost: udp.IP.String(), FromPort: udp.Port}, nil
	}
	return &UDPRead{Data: buf[:n], FromHost: addr.String()}, nil
}

// WriteTo sends data to an IP-literal host:port through the tunnel.
func (u *UDPConn) WriteTo(data []byte, host string, port int) error {
	ip := net.ParseIP(host)
	if ip == nil {
		return fmt.Errorf("WriteTo: host %q is not an IP literal", host)
	}
	_, err := u.c.WriteTo(data, &net.UDPAddr{IP: ip, Port: port})
	return err
}

// Close closes the UDP socket.
func (u *UDPConn) Close() error { return u.c.Close() }

// Accept blocks until a peer connects.
func (l *Listener) Accept() (*Conn, error) {
	c, err := l.ln.Accept()
	if err != nil {
		return nil, err
	}
	return &Conn{c: c}, nil
}

// Addr returns the bound "ip:port".
func (l *Listener) Addr() string { return l.ln.Addr().String() }

// Close stops accepting.
func (l *Listener) Close() error { return l.ln.Close() }
