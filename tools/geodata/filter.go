// Generation-only tool; run through generate.py, not as an app dependency.
package main

import (
    "encoding/json"
    "fmt"
    "net/netip"
    "os"
    "sort"
    "strings"

    geodata "geodata-generator/official"
    "google.golang.org/protobuf/proto"
)

// ISO 3166-1 alpha-2 assignments; explicit allowlist excludes synthetic upstream lists.
const isoCountries = "AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW"

type countryStats struct {
    IPv4 int `json:"ipv4_cidrs"`
    IPv6 int `json:"ipv6_cidrs"`
}

func must(err error) { if err != nil { panic(err) } }
func load(path string, message proto.Message) {
    data, err := os.ReadFile(path); must(err)
    must(proto.Unmarshal(data, message))
}
func save(path string, message proto.Message) {
    data, err := (proto.MarshalOptions{Deterministic: true}).Marshal(message); must(err)
    must(os.WriteFile(path, data, 0644))
    // Validate the actual serialized output with the official generated message type.
    decoded := message.ProtoReflect().Type().New().Interface()
    must(proto.Unmarshal(data, decoded))
    if !proto.Equal(message, decoded) { panic("protobuf round-trip mismatch") }
}

func sortPrefixes(prefixes []netip.Prefix) {
    sort.Slice(prefixes, func(i, j int) bool {
        if order := prefixes[i].Addr().Compare(prefixes[j].Addr()); order != 0 { return order < 0 }
        return prefixes[i].Bits() < prefixes[j].Bits()
    })
}

// Inputs are canonical, sorted by address with enclosing prefixes first.
func collapse(prefixes []netip.Prefix) []netip.Prefix {
    output := make([]netip.Prefix, 0, len(prefixes))
    for _, prefix := range prefixes {
        if len(output) > 0 {
            previous := output[len(output)-1]
            if previous.Addr().BitLen() == prefix.Addr().BitLen() &&
                previous.Bits() <= prefix.Bits() && previous.Contains(prefix.Addr()) { continue }
        }
        for len(output) > 0 && prefix.Bits() > 0 {
            previous := output[len(output)-1]
            if previous.Addr().BitLen() != prefix.Addr().BitLen() || previous.Bits() != prefix.Bits() { break }
            parent := netip.PrefixFrom(prefix.Addr(), prefix.Bits()-1).Masked()
            if parent != netip.PrefixFrom(previous.Addr(), previous.Bits()-1).Masked() { break }
            output = output[:len(output)-1]
            prefix = parent
        }
        output = append(output, prefix)
    }
    return output
}

type addressRange struct { first, last netip.Addr }

// An independent interval-union representation proves equality in both directions,
// including merged output prefixes that span multiple original source prefixes.
func union(prefixes []netip.Prefix) []addressRange {
    ranges := []addressRange{}
    for _, prefix := range prefixes {
        bytes := prefix.Addr().AsSlice()
        for bit := prefix.Bits(); bit < len(bytes)*8; bit++ {
            bytes[bit/8] |= byte(1 << (7 - bit%8))
        }
        last, ok := netip.AddrFromSlice(bytes)
        if !ok { panic("invalid range endpoint") }
        if len(ranges) > 0 {
            previous := &ranges[len(ranges)-1]
            if previous.first.BitLen() == prefix.Addr().BitLen() &&
                (prefix.Addr().Compare(previous.last) <= 0 || previous.last.Next() == prefix.Addr()) {
                if last.Compare(previous.last) > 0 { previous.last = last }
                continue
            }
        }
        ranges = append(ranges, addressRange{prefix.Addr(), last})
    }
    return ranges
}

func verifyEqualCoverage(original, collapsed []netip.Prefix) {
    before, after := union(original), union(collapsed)
    if len(before) != len(after) { panic("CIDR collapse changed address union") }
    for i := range before {
        if before[i] != after[i] { panic("CIDR collapse changed address union") }
    }
}
func main() {
    if len(os.Args) != 4 { panic("usage: filter INPUT_GEOIP INPUT_GEOSITE OUTPUT_DIR") }
    wanted := map[string]bool{"PRIVATE": true}
    for _, code := range strings.Fields(isoCountries) { wanted[code] = true }
    ipInput := new(geodata.GeoIPList); load(os.Args[1], ipInput)
    ipOutput := new(geodata.GeoIPList)
    stats := map[string]countryStats{}
    originalStats := map[string]countryStats{}
    excluded := []string{}
    for _, entry := range ipInput.Entry {
        code := strings.ToUpper(entry.Code)
        if !wanted[code] { excluded = append(excluded, code); continue }
        if _, exists := stats[code]; exists { panic("duplicate GeoIP code: " + code) }
        if entry.ReverseMatch || len(entry.Cidr) == 0 { panic("unexpected empty/reversed GeoIP: " + code) }
        clean := &geodata.GeoIP{Code: code}
        originalCount := countryStats{}
        prefixes := make([]netip.Prefix, 0, len(entry.Cidr))
        for _, cidr := range entry.Cidr {
            ip, ok := netip.AddrFromSlice(cidr.Ip)
            if !ok || cidr.Prefix > uint32(ip.BitLen()) { panic("invalid CIDR: " + code) }
            prefix := netip.PrefixFrom(ip, int(cidr.Prefix))
            if prefix != prefix.Masked() { panic("noncanonical CIDR: " + code) }
            if ip.Is4() { originalCount.IPv4++ } else { originalCount.IPv6++ }
            prefixes = append(prefixes, prefix)
        }
        sortPrefixes(prefixes)
        collapsed := collapse(prefixes)
        verifyEqualCoverage(prefixes, collapsed)
        originalStats[code] = originalCount
        count := countryStats{}
        for _, prefix := range collapsed {
            if prefix.Addr().Is4() { count.IPv4++ } else { count.IPv6++ }
            clean.Cidr = append(clean.Cidr, &geodata.CIDR{Ip: prefix.Addr().AsSlice(), Prefix: uint32(prefix.Bits())})
        }
        stats[code] = count
        ipOutput.Entry = append(ipOutput.Entry, clean)
    }
    if _, ok := stats["PRIVATE"]; !ok { panic("missing PRIVATE") }
    missing := []string{}
    for _, code := range strings.Fields(isoCountries) { if _, ok := stats[code]; !ok { missing = append(missing, code) } }
    sort.Strings(excluded)
    sort.Slice(ipOutput.Entry, func(i, j int) bool { return ipOutput.Entry[i].Code < ipOutput.Entry[j].Code })
    sites := new(geodata.GeoSiteList); load(os.Args[2], sites)
    ads := &geodata.GeoSite{Code: "CATEGORY-ADS-ALL"}
    domainTypes := map[string]int{}
    seen := map[string]bool{}
    found := false
    for _, entry := range sites.Entry {
        if strings.ToUpper(entry.Code) != ads.Code { continue }
        if found { panic("duplicate ads category") }; found = true
        for _, domain := range entry.Domain {
            if domain.Value == "" || domain.Type < geodata.Domain_Substr || domain.Type > geodata.Domain_Full { panic("invalid ad domain") }
            key := fmt.Sprintf("%d:%s", domain.Type, domain.Value)
            if seen[key] { continue }; seen[key] = true
            // Attributes support optional @ filters only, not the unqualified category used by this app.
            ads.Domain = append(ads.Domain, &geodata.Domain{Type: domain.Type, Value: domain.Value})
            domainTypes[domain.Type.String()]++
        }
    }
    if !found || len(ads.Domain) == 0 { panic("missing ads category") }
    sort.Slice(ads.Domain, func(i,j int) bool {
        if ads.Domain[i].Type != ads.Domain[j].Type { return ads.Domain[i].Type < ads.Domain[j].Type }
        return ads.Domain[i].Value < ads.Domain[j].Value
    })
    must(os.MkdirAll(os.Args[3], 0755))
    save(os.Args[3]+"/geoip.dat", ipOutput)
    save(os.Args[3]+"/geosite.dat", &geodata.GeoSiteList{Entry: []*geodata.GeoSite{ads}})
    summary := map[string]any{
        "iso3166_1_alpha2_requested": len(strings.Fields(isoCountries)),
        "iso3166_1_reference": "https://www.iso.org/obp/ui/#search/code/",
        "geoip_entries": len(ipOutput.Entry), "countries": stats,
        "original_countries": originalStats,
        "unsupported_iso3166_1_alpha2": missing, "excluded_upstream_geoip_codes": excluded,
        "geosite_categories": []string{ads.Code}, "ad_domains": len(ads.Domain), "ad_domain_types": domainTypes,
        "validation": "Official Xray v26.11.9 protobuf decode/encode round-trip; all CIDRs canonical IPv4/IPv6; all domains have known types and nonempty values. Each country's original and collapsed CIDRs independently converted to canonical interval unions and compared exactly, proving bidirectional address-set equality. No invented ranges or fallback countries.",
    }
    report, err := json.MarshalIndent(summary, "", "  "); must(err)
    must(os.WriteFile(os.Args[3]+"/counts.json", append(report, '\n'), 0644))
}
