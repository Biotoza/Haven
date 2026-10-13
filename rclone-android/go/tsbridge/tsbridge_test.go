package tsbridge

import (
	"net/netip"
	"testing"

	dns "golang.org/x/net/dns/dnsmessage"
)

// buildAResponse builds a DNS response for qname carrying one A record for addr.
func buildAResponse(t *testing.T, qname string, addr netip.Addr) []byte {
	t.Helper()
	q := dns.Question{
		Name:  dns.MustNewName(qname),
		Type:  dns.TypeA,
		Class: dns.ClassINET,
	}
	b := dns.NewBuilder(nil, dns.Header{Response: true, RecursionDesired: true})
	b.StartQuestions()
	if err := b.Question(q); err != nil {
		t.Fatalf("question: %v", err)
	}
	b.StartAnswers()
	if err := b.AResource(dns.ResourceHeader{
		Name:  q.Name,
		Class: dns.ClassINET,
		TTL:   300,
	}, dns.AResource{A: addr.As4()}); err != nil {
		t.Fatalf("aresource: %v", err)
	}
	resp, err := b.Finish()
	if err != nil {
		t.Fatalf("finish: %v", err)
	}
	return resp
}

func TestParseFirstA(t *testing.T) {
	want := netip.MustParseAddr("100.101.102.103")
	resp := buildAResponse(t, "homelab.example.", want)
	got, ok := parseFirstA(resp)
	if !ok {
		t.Fatalf("parseFirstA ok=false, want true")
	}
	if got != want.String() {
		t.Errorf("parseFirstA = %q, want %q", got, want.String())
	}
}

func TestParseFirstANoAnswer(t *testing.T) {
	// A response with a question but no answer section (NXDOMAIN/NODATA)
	// must report ok=false so the caller falls back to the plain dial.
	b := dns.NewBuilder(nil, dns.Header{Response: true})
	b.StartQuestions()
	if err := b.Question(dns.Question{
		Name:  dns.MustNewName("missing.example."),
		Type:  dns.TypeA,
		Class: dns.ClassINET,
	}); err != nil {
		t.Fatalf("question: %v", err)
	}
	resp, err := b.Finish()
	if err != nil {
		t.Fatalf("finish: %v", err)
	}
	if got, ok := parseFirstA(resp); ok {
		t.Errorf("parseFirstA = (%q, true), want ok=false for a no-answer response", got)
	}
}

func TestParseFirstAGarbage(t *testing.T) {
	// Truncated / non-DNS bytes must not panic and must report ok=false.
	if got, ok := parseFirstA([]byte{0x00, 0x01}); ok {
		t.Errorf("parseFirstA = (%q, true), want ok=false for garbage", got)
	}
}
