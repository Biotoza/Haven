package ocbridge

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"net/textproto"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// --- pure helpers -----------------------------------------------------------

func TestTOTP_RFC6238Vectors(t *testing.T) {
	// RFC 6238 Appendix B, SHA-1 seed "12345678901234567890", 8 digits.
	tt, err := newTOTP("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&digits=8")
	if err != nil {
		t.Fatal(err)
	}
	for ts, want := range map[int64]string{
		59: "94287082", 1111111109: "07081804", 1234567890: "89005924", 2000000000: "69279037",
	} {
		if got := tt.codeAt(time.Unix(ts, 0)); got != want {
			t.Errorf("t=%d: got %s want %s", ts, got, want)
		}
	}
	// Bare secret, lower case, spaces: 6-digit default is the low 6 digits.
	t6, err := newTOTP("gezd gnbv gy3t qojq gezd gnbv gy3t qojq")
	if err != nil {
		t.Fatal(err)
	}
	if got := t6.codeAt(time.Unix(59, 0)); got != "287082" {
		t.Errorf("6-digit: got %s", got)
	}
	if _, err := newTOTP("not base32 !!"); err == nil {
		t.Error("expected error for invalid secret")
	}
}

func TestParsePin(t *testing.T) {
	good := []string{
		"pin-sha256:" + base64.StdEncoding.EncodeToString(make([]byte, 32)),
		"sha256:" + strings.Repeat("ab", 32),
		strings.Repeat("AB:", 31) + "AB",
		"sha1:" + strings.Repeat("0f", 20),
	}
	for _, s := range good {
		if _, err := parsePin(s); err != nil {
			t.Errorf("%q: %v", s, err)
		}
	}
	for _, s := range []string{"pin-sha256:short", "sha256:abc", "hello"} {
		if _, err := parsePin(s); err == nil {
			t.Errorf("%q: expected error", s)
		}
	}
}

func TestNormalizeServer(t *testing.T) {
	cases := map[string]string{
		"vpn.example.com":                 "https://vpn.example.com/",
		"vpn.example.com:8443":            "https://vpn.example.com:8443/",
		"https://vpn.example.com/eng?x=1": "https://vpn.example.com/eng",
	}
	for in, want := range cases {
		u, err := normalizeServer(in)
		if err != nil || u.String() != want {
			t.Errorf("%q: got %v %v want %s", in, u, err, want)
		}
	}
	if _, err := normalizeServer("http://vpn.example.com"); err == nil {
		t.Error("plain http must be refused")
	}
}

func TestParseTunnelHeaders(t *testing.T) {
	h := textproto.MIMEHeader{}
	h.Set("X-CSTP-Address", "10.10.0.5")
	h.Set("X-CSTP-Address-IP6", "fd00::5/64")
	h.Add("X-CSTP-DNS", "10.10.0.1")
	h.Add("X-CSTP-DNS", "fd00::1")
	h.Set("X-CSTP-MTU", "1300")
	h.Set("X-CSTP-DPD", "20")
	p, err := parseTunnelHeaders(h)
	if err != nil {
		t.Fatal(err)
	}
	if p.addr4.String() != "10.10.0.5" || p.addr6.String() != "fd00::5" || len(p.dns) != 2 ||
		p.mtu != 1300 || p.dpd != 20*time.Second {
		t.Fatalf("unexpected params %+v", p)
	}
	if _, err := parseTunnelHeaders(textproto.MIMEHeader{}); err == nil {
		t.Error("expected error without an address")
	}
}

func TestReplyXMLEscapesAndDropsBadNames(t *testing.T) {
	x := replyXML("<opaque is-for=\"sg\">x</opaque>",
		[][2]string{{"username", "a<b&c"}, {"bad name><evil", "x"}}, "Eng")
	if !strings.Contains(x, "<username>a&lt;b&amp;c</username>") {
		t.Error("value not escaped:", x)
	}
	if strings.Contains(x, "evil") {
		t.Error("hostile field name echoed:", x)
	}
	if !strings.Contains(x, "<group-select>Eng</group-select>") || !strings.Contains(x, `<opaque is-for="sg">x</opaque>`) {
		t.Error("missing group-select or opaque:", x)
	}
}

// --- fake gateway -----------------------------------------------------------

const (
	gwAddr     = "10.77.0.1"
	clientAddr = "10.77.0.2"
	otpSecret  = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
)

// fakeGateway imitates an ASA: an init POST gets a form with a group list;
// selecting group "Eng" yields a username/password form; correct
// credentials yield an OTP challenge; a valid TOTP completes. CONNECT then
// bridges CSTP frames to a server-side gVisor netstack with a TCP echo
// service, which is the "corporate network" the client reaches.
type fakeGateway struct {
	t   *testing.T
	srv *httptest.Server

	sdev tun.Device
	snet *netstack.Net

	mu          sync.Mutex
	tunnelConns []net.Conn
	connects    atomic.Int32
	logouts     atomic.Int32
	authRounds  []string
	rejectNext  atomic.Bool // force the next CONNECT to 401 (expired cookie)
	wmu         sync.Mutex  // guards cur and serialises frame writes
	cur         net.Conn    // tunnel connection packets are forwarded to
	issued      atomic.Int32
}

func newFakeGateway(t *testing.T) *fakeGateway {
	g := &fakeGateway{t: t}
	var err error
	g.sdev, g.snet, err = netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr(gwAddr)}, nil, 1300)
	if err != nil {
		t.Fatal(err)
	}
	ln, err := g.snet.ListenTCPAddrPort(netip.MustParseAddrPort(gwAddr + ":7"))
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	go g.pumpDown()
	g.srv = httptest.NewTLSServer(http.HandlerFunc(g.handle))
	t.Cleanup(func() {
		g.srv.Close()
		g.sdev.Close()
	})
	return g
}

func (g *fakeGateway) pin() string {
	h := sha256.Sum256(g.srv.Certificate().RawSubjectPublicKeyInfo)
	return "pin-sha256:" + base64.StdEncoding.EncodeToString(h[:])
}

func (g *fakeGateway) handle(w http.ResponseWriter, r *http.Request) {
	switch {
	case r.Method == http.MethodConnect && r.RequestURI == "/CSCOSSLC/tunnel":
		g.handleConnect(w, r)
		return
	case r.URL.Path == "/+webvpn+/webvpn_logout.html":
		g.logouts.Add(1)
		return
	}
	body, _ := io.ReadAll(r.Body)
	s := string(body)
	g.mu.Lock()
	g.authRounds = append(g.authRounds, s)
	g.mu.Unlock()
	w.Header().Set("Content-Type", "text/xml")

	opaque := `<opaque is-for="sg"><tunnel-group>X</tunnel-group></opaque>`
	switch {
	case strings.Contains(s, `type="init"`):
		fmt.Fprint(w, authRequest(opaque, "", groupForm("Default")))
	case strings.Contains(s, "<group-select>Eng</group-select>") && !strings.Contains(s, "<username>"):
		fmt.Fprint(w, authRequest(opaque, "", groupForm("Eng")))
	case strings.Contains(s, "<username>alice</username>") && strings.Contains(s, "<password>s3cret</password>"):
		if !strings.Contains(s, opaque) {
			fmt.Fprint(w, authRequest(opaque, "opaque not echoed", ""))
			return
		}
		fmt.Fprint(w, authRequest(opaque, "", `<form method="post" action="/">
<input type="password" name="secondary_password" label="Passcode:"></input></form>`))
	case strings.Contains(s, "<secondary_password>"):
		tt, _ := newTOTP(otpSecret)
		if !strings.Contains(s, "<secondary_password>"+tt.codeAt(time.Now())+"</secondary_password>") {
			fmt.Fprint(w, authRequest(opaque, "Invalid passcode", ""))
			return
		}
		n := g.issued.Add(1)
		fmt.Fprintf(w, `<?xml version="1.0" encoding="UTF-8"?>
<config-auth client="vpn" type="complete" aggregate-auth-version="2">
<session-id>1</session-id><session-token>COOKIE%d</session-token>
<auth id="success"><message id="0" param1="" param2=""></message></auth>
</config-auth>`, n)
	default:
		fmt.Fprint(w, authRequest(opaque, "Login failed.", groupForm("Eng")))
	}
}

func groupForm(selected string) string {
	sel := func(v string) string {
		if v == selected {
			return ` selected="true"`
		}
		return ""
	}
	return fmt.Sprintf(`<form method="post" action="/">
<input type="text" name="username" label="Username:"></input>
<input type="password" name="password" label="Password:"></input>
<select name="group_list" label="GROUP:">
<option value="Default"%s>Default</option><option value="Eng"%s>Engineering</option>
</select></form>`, sel("Default"), sel("Eng"))
}

func authRequest(opaque, errText, form string) string {
	e := ""
	if errText != "" {
		e = `<error id="88" param1="" param2="">` + errText + `</error>`
	}
	return `<?xml version="1.0" encoding="UTF-8"?>
<config-auth client="vpn" type="auth-request" aggregate-auth-version="2">` + opaque +
		`<auth id="main"><title>Login</title>` + e + form + `</auth></config-auth>`
}

func (g *fakeGateway) handleConnect(w http.ResponseWriter, r *http.Request) {
	g.connects.Add(1)
	cookie := ""
	if c, err := r.Cookie("webvpn"); err == nil {
		cookie = c.Value
	}
	hj, _ := w.(http.Hijacker)
	conn, rw, err := hj.Hijack()
	if err != nil {
		return
	}
	if g.rejectNext.Swap(false) || !strings.HasPrefix(cookie, "COOKIE") {
		fmt.Fprint(conn, "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n")
		conn.Close()
		return
	}
	fmt.Fprintf(conn, "HTTP/1.1 200 CONNECTED\r\nX-CSTP-Version: 1\r\nX-CSTP-Address: %s\r\n"+
		"X-CSTP-Netmask: 255.255.255.0\r\nX-CSTP-DNS: %s\r\nX-CSTP-MTU: 1300\r\nX-CSTP-DPD: 30\r\n"+
		"X-CSTP-Keepalive: 20\r\n\r\n", clientAddr, gwAddr)
	g.mu.Lock()
	g.tunnelConns = append(g.tunnelConns, conn)
	g.mu.Unlock()

	write := func(f []byte) error {
		g.wmu.Lock()
		defer g.wmu.Unlock()
		_, err := conn.Write(f)
		return err
	}
	g.wmu.Lock()
	g.cur = conn
	g.wmu.Unlock()
	// client → server netstack
	br := rw.Reader
	var hdr [hdrLen]byte
	for {
		if _, err := io.ReadFull(br, hdr[:]); err != nil {
			break
		}
		n := int(binary.BigEndian.Uint16(hdr[4:6]))
		p := make([]byte, n)
		if _, err := io.ReadFull(br, p); err != nil {
			break
		}
		switch hdr[6] {
		case pktData:
			g.sdev.Write([][]byte{p}, 0)
		case pktDPDReq:
			write(control(pktDPDResp, p))
		case pktDisconnect:
			conn.Close()
		}
	}
	g.wmu.Lock()
	if g.cur == conn {
		g.cur = nil
	}
	g.wmu.Unlock()
	conn.Close()
}

// pumpDown is the gateway's single reader of its netstack: it must keep
// draining the device for the life of the test (gVisor blocks on an
// undrained TUN, which would deadlock Close), forwarding to whichever
// tunnel connection is current and dropping packets in between.
func (g *fakeGateway) pumpDown() {
	buf := make([]byte, hdrLen+maxPayload)
	sizes := []int{0}
	for {
		n, err := g.sdev.Read([][]byte{buf}, sizes, hdrLen)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		frameHeader(buf, pktData, sizes[0])
		g.wmu.Lock()
		if g.cur != nil {
			g.cur.Write(buf[:hdrLen+sizes[0]])
		}
		g.wmu.Unlock()
	}
}

func (g *fakeGateway) dropTunnels() {
	g.mu.Lock()
	defer g.mu.Unlock()
	for _, c := range g.tunnelConns {
		c.Close()
	}
	g.tunnelConns = nil
}

func (g *fakeGateway) config(mod func(*Config)) string {
	c := Config{
		Server:     g.srv.URL + "/eng",
		Username:   "alice",
		Password:   "s3cret",
		AuthGroup:  "Eng",
		OTPSecret:  otpSecret,
		ServerCert: g.pin(),
	}
	if mod != nil {
		mod(&c)
	}
	b, _ := json.Marshal(c)
	return string(b)
}

func echo(t *testing.T, c *Conn, msg string) {
	t.Helper()
	if err := c.Write([]byte(msg)); err != nil {
		t.Fatalf("write: %v", err)
	}
	var got bytes.Buffer
	for got.Len() < len(msg) {
		b, err := c.Read(len(msg) - got.Len())
		if err != nil {
			t.Fatalf("read after %q: %v", got.String(), err)
		}
		got.Write(b)
	}
	if got.String() != msg {
		t.Fatalf("echo: got %q want %q", got.String(), msg)
	}
}

// --- end-to-end against the fake gateway -------------------------------------

func TestEndToEnd_AuthTunnelEchoAndLogout(t *testing.T) {
	g := newFakeGateway(t)
	h, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	if h.BindAddr() != clientAddr {
		t.Errorf("BindAddr = %q", h.BindAddr())
	}
	// init, group switch, credentials, OTP
	if len(g.authRounds) != 4 {
		t.Errorf("expected 4 auth rounds, got %d", len(g.authRounds))
	}

	c, err := h.Dial(gwAddr, 7, 10_000)
	if err != nil {
		t.Fatal(err)
	}
	echo(t, c, "hello through anyconnect")
	big := strings.Repeat("0123456789abcdef", 64*1024/16) // > MTU, many segments
	echo(t, c, big)
	if c.RemoteAddrHost() != gwAddr {
		t.Errorf("RemoteAddrHost = %q", c.RemoteAddrHost())
	}
	c.Close()

	// SOCKS5 front door
	port, err := h.StartSocksListener()
	if err != nil || port == 0 {
		t.Fatalf("socks: %d %v", port, err)
	}

	h.Close()
	h.Close() // idempotent
	if _, err := h.Dial(gwAddr, 7, 1000); err == nil {
		t.Error("dial after close should fail")
	}
	deadline := time.Now().Add(3 * time.Second)
	for g.logouts.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	if g.logouts.Load() == 0 {
		t.Error("expected a logout request on close")
	}
}

func TestEndToEnd_SurvivesConnectionDrop(t *testing.T) {
	g := newFakeGateway(t)
	h, err := StartTunnel(g.config(nil))
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	c, err := h.Dial(gwAddr, 7, 10_000)
	if err != nil {
		t.Fatal(err)
	}
	echo(t, c, "before")

	// Network change: the TLS connection dies under an open TCP stream.
	g.dropTunnels()
	echo(t, c, "after reconnect, same stream") // gVisor retransmits over the new connection
	if g.connects.Load() < 2 {
		t.Errorf("expected a reconnect, connects=%d", g.connects.Load())
	}

	// Expired cookie: gateway 401s the reconnect; client re-auths with a fresh TOTP.
	issuedBefore := g.issued.Load()
	g.rejectNext.Store(true)
	g.dropTunnels()
	echo(t, c, "after re-auth")
	if g.issued.Load() <= issuedBefore {
		t.Error("expected re-authentication after cookie rejection")
	}
	c.Close()
}

func TestAuthFailures(t *testing.T) {
	g := newFakeGateway(t)
	cases := []struct {
		name string
		mod  func(*Config)
		want string
	}{
		{"bad password", func(c *Config) { c.Password = "nope" }, "Login failed"},
		{"unknown group", func(c *Config) { c.AuthGroup = "Sales" }, "not offered"},
		{"missing second factor", func(c *Config) { c.OTPSecret = "" }, "second factor"},
		{"wrong static second factor", func(c *Config) { c.OTPSecret = ""; c.SecondaryPassword = "push" }, "Invalid passcode"},
		{"wrong pin", func(c *Config) { c.ServerCert = "sha256:" + strings.Repeat("00", 32) }, "does not match"},
		{"no pin, self-signed", func(c *Config) { c.ServerCert = "" }, "pin-sha256:"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := StartTunnel(g.config(tc.mod))
			if err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("got %v, want error containing %q", err, tc.want)
			}
		})
	}
}

func TestStartTunnelRejectsBadConfig(t *testing.T) {
	for _, s := range []string{"", "{}", `{"server":"x"}`, `{"server":"x","username":"u","otpSecret":"!!"}`} {
		if _, err := StartTunnel(s); err == nil {
			t.Errorf("%q: expected error", s)
		}
	}
}

// TestLive runs against a real gateway when OCBRIDGE_LIVE_CONFIG holds a
// config JSON and OCBRIDGE_LIVE_ECHO a host:port TCP echo service reachable
// inside the VPN. Skipped otherwise (CI has no gateway).
func TestLive(t *testing.T) {
	cfg, target := os.Getenv("OCBRIDGE_LIVE_CONFIG"), os.Getenv("OCBRIDGE_LIVE_ECHO")
	if cfg == "" || target == "" {
		t.Skip("OCBRIDGE_LIVE_CONFIG / OCBRIDGE_LIVE_ECHO not set")
	}
	h, err := StartTunnel(cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	t.Logf("tunnel up, assigned %s", h.BindAddr())
	host, portStr, _ := net.SplitHostPort(target)
	port, _ := strconv.Atoi(portStr)
	c, err := h.Dial(host, port, 15_000)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	echo(t, c, "hello from haven over a real gateway")
	echo(t, c, strings.Repeat("x", 200_000))
}
