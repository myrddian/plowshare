# Network Privacy Watch: services, capabilities and evidence buckets

Status: draft written 14 Nov 2026; awaiting review by the project owner.

## Why

Network Privacy Watch learns about devices from two external services, Pi-hole
and UniFi. Each one is wired in by name across the collector:

- `CollectionPlan` has fixed `pihole` and `unifi` fields
  (`contracts.py`).
- `collector_factory.py` builds exactly one of each.
- `NetworkCollector` takes `pihole_presence` and `pihole_activity` arguments.
  `confirm_identity` hard-codes "UniFi wins" (`collection.py`).
- `scope_fingerprint` has special rules for each vendor.
- Credential redaction (`system_view.py`), settings (`monitor.py`), health
  checks (`console.py`), setup (`bootstrap.py`, `setup.py`), the web UI
  (`web/app.js` has about 100 vendor references) and the dossier
  (`pihole_silent`) all name the vendor.

Adding another DNS server (AdGuard Home, Technitium), a hypervisor (Proxmox) or
a second Pi-hole means touching every one of those places.

Pi-hole is also only partly used. The collector reads its network table
(`network/devices`) and its query log (`queries`). It never asks whether Pi-hole
runs DHCP, and it never reads the DHCP leases, reservations or local DNS
records. UniFi is read for each client's `ipAddress` only, not whether the
client has a fixed IP.

As a result, Network Privacy Watch cannot tell how an address was assigned.
Real networks mix dynamic DHCP leases, DHCP reservations (fixed assignments)
and statically configured devices. That is worth keeping: a device that
changes from a reservation to a static address, or a reserved address held by
the wrong device, is something the operator should see.

The data the collector produces is already vendor-neutral: an identity record
does not care where it came from. What is missing is a model in which a
connector *declares* what it provides, so the collector, settings and UI work
from that declaration instead of from vendor names.

## The model

```
Service      one configured instance: an address, credentials, an id
              e.g. Pi-hole "home-dns" at https://pihole.lan
  └─ Capability   one kind of read the service can answer, checked at connect
                   e.g. "DNS query log", "DHCP leases"
        └─ Bucket     the vendor-neutral evidence it produces
                       e.g. Identity, DNS
```

- **A connector** is the code for one kind of service (`pihole`, `unifi`). It
  is registered once, in this repository.
- **A service** is one configured instance of a connector. The operator gives
  it an id, and can configure two Pi-holes as two services.
- **A capability** produces one record type into one bucket, with its own
  coverage gaps. The operator can switch each one on or off. Its status ("not
  offered by this service", "unavailable", "working") is shown separately.
- **A bucket** owns the record type, how records from several capabilities are
  merged, and what the rest of the collector reads.

## Buckets

These already exist in the code; this design names them and makes them the only
thing consumers depend on.

| Bucket | Record | In full collections (journaled) | In quick checks (local) | Merge rule |
|---|---|---|---|---|
| **Identity**: who holds this IP | `DeviceIdentity` | yes, `Snapshot.devices` | yes, via `Sighting` | Same MAC: highest-precedence record supplies name and vendor. Different MACs: the address is ambiguous and has no identity (current behaviour). |
| **Addressing**: how the address was assigned | `AddressAssignment` (new) | yes, `Snapshot.addressing` (new, omitted when empty) | no | Configured intent (reservation, fixed IP) → observed lease → inference from the DHCP range → `unknown`. Two configured sources naming different MACs: `unknown` plus a conflict. |
| **DNS**: what a device looked up | `DnsObservation`, `DnsWindow`, blocked counts | yes, `Snapshot.dns`, `dns_window` | yes, `QuerySample` | Stage 1: at most one DNS-query capability enabled. |
| **Open ports** | `TcpObservation` | yes | no | Collector's own probe only; not a connector bucket. |
| **Presence**: is it online now | `Sighting`, `PresenceSample` | no | yes | A device is present if any presence capability sees it. |
| **Traffic** | `TrafficCounter` | no | yes | Stage 1: at most one traffic capability enabled. |
| **Product**: what it is | `ManagedProduct` | no; added to device details | no | Highest precedence wins; disagreement is shown, not resolved. |

Self-descriptions (`DeviceFacts`, from SSDP/mDNS) and open ports stay the
collector's own work. They are not connector capabilities.

Every bucket keeps its coverage-gap codes (`identity_stale`, `dns_truncated`
and so on). A capability that the service does not offer, such as DHCP on a
Pi-hole where DHCP is off, is **not a gap**. It is reported as "not offered",
so the collection's coverage does not change because of it.

## Capability catalogue

Each capability has a fixed name, one bucket, and a typed contract. Connectors
implement capabilities; they do not invent buckets.

| Capability | Bucket | Contract (existing or new) | Evidence kind |
|---|---|---|---|
| `network_table` | Identity, Presence | `DeviceIdentitySource` (Pi-hole today) | `neighbour_table` |
| `dhcp_leases` | Identity, Addressing | `DeviceIdentitySource` and an addressing sample (**new for Pi-hole**) | `dhcp_lease` |
| `dhcp_reservations` | Addressing only | addressing sample (**new**: Pi-hole reservations, UniFi fixed IPs) | — |
| `dhcp_scope` | Addressing only | addressing sample (**new**: whether DHCP runs, its network and range) | — |
| `local_dns_names` | Identity (names only) | **new**: address → name, never a MAC | `local_dns` |
| `controller_clients` | Identity, Presence | `DeviceIdentitySource` (UniFi today) | `controller_client` |
| `managed_inventory` | Identity, Product | `ManagedInventory`, `ManagedProductLookup` (UniFi today) | `managed_device` |
| `dns_queries` | DNS, Presence | `DnsActivityCollector` and the collection read (Pi-hole today) | — |
| `traffic_counters` | Traffic | the current `traffic_source` callable, given a named contract | — |

The configured device labels and the DNS observation file are not services.
Labels stay part of the plan. The observation file stays the fixture-mode DNS
input, and keeps its rule that it cannot be combined with a live DNS-query
capability.

## Pi-hole as the first connector

Pi-hole is ported first and gains two capabilities. All calls stay read-only
`GET`s on one short-lived session, as the module docstring promises today.

| Capability | Pi-hole v6 call | Available when |
|---|---|---|
| `network_table` | `GET network/devices` (unchanged) | always |
| `dns_queries` | `GET queries` (unchanged) | always |
| `dhcp_scope` | `GET config/dhcp`: `active`, `start`, `end`, `router`, `netmask` | always (reports "DHCP off") |
| `dhcp_leases` | if DHCP is active, `GET dhcp/leases` | `dhcp.active` is true |
| `dhcp_reservations` | `GET config/dhcp`: the reservation list (`hosts`) | `dhcp.active` is true |
| `local_dns_names` | `GET config/dns/hosts` | always (may be empty) |

DHCP rules:

- **The DHCP check runs at connect, and again once per full collection.** If
  the operator turns DHCP on or off in Pi-hole, the status follows within one
  collection.
- **DHCP settings, leases and local names are read once per full collection**,
  for all its targets, never once per 32-address batch. Each batch's identities
  still merge the leases and names of their own addresses. The collection has
  exactly one Pi-hole DHCP scope, so one batch's failed read can never let
  another batch's scope decide its addresses.
- **The scope is complete only with `dhcp_reservations` enabled** and its read
  complete, as UniFi requires its fixed IPs. With `dhcp_scope` alone an unread
  reservation could hold any address, so no address is ever `static`.
- **A lease is identity and addressing evidence, not presence.** A lease proves
  an address was handed to a MAC. It does not prove the device is online now,
  so leases never create a `Sighting`.
- **A reservation is intent, not observation.** It says which address a MAC
  *should* get. It goes into Addressing only, never Identity, because it
  cannot show who holds the address now.
- **Only active leases count.** An expired lease is ignored. How Pi-hole marks a
  static or infinite lease is left to the builder to confirm against a real
  instance (see below).
- **Leases are scoped like everything else.** Only addresses in the selected
  targets enter evidence. Results are bounded, and a full page sets a
  `identity_partial` gap.
- **Lease and network table disagree:** if they name different MACs for one
  address, the address is ambiguous, as for any two sources today.

Local DNS names only ever supply a hostname. They cannot assert a MAC, so they
can never create or break an association.

## The Addressing bucket

For each selected address with a confirmed identity, one `AddressAssignment`
records:

- **`mode`**: `dhcp_dynamic`, `dhcp_reserved`, `static` or `unknown`
- **`basis`**: why. One of:
  - `dhcp_lease` (observed)
  - `dhcp_reservation`, `controller_fixed_ip` (configured intent)
  - `inside_dhcp_range`, `outside_dhcp_range`, `no_lease_or_reservation` (inferred)
- the MAC it applies to, the services that contributed, and a lease's expiry
  when there is one

Connectors only report facts: leases, reservations and DHCP scopes. One
function in the bucket decides the mode, in this order:

1. **A reservation or fixed IP for this address and this MAC** →
   `dhcp_reserved`.
2. **An active lease for this address and this MAC** → `dhcp_dynamic`.
3. **No lease and no reservation, and a DHCP scope was read completely for the
   subnet containing the address:**
   - outside the DHCP range → `static` (`outside_dhcp_range`)
   - inside the range, where that service's lease table was read completely →
     `static` (`no_lease_or_reservation`)
   - inside the range, where the service has no lease table (UniFi) →
     `dhcp_dynamic` (`inside_dhcp_range`)
4. **Anything else** → `unknown`. `static` is never a default.

Further rules:

- **A reservation naming a different MAC** from the one holding the address is
  an `addressing_conflict` issue. The holder's mode is still worked out by the
  rules above.
- **Two configured sources naming different MACs** for one address (a Pi-hole
  reservation and a UniFi fixed IP) give `unknown` plus `addressing_conflict`.
- **An address without a confirmed MAC** is `unknown`.
- **Several complete scopes covering one address** must all give the same mode
  and basis. If one of them cannot decide (an in-range address whose lease table
  was read incompletely), the address is `unknown`; if they decide differently
  (for example a Pi-hole range and a UniFi range that disagree), it is
  `unknown` plus one `addressing_conflict`. Sample order never picks a winner.
- **Scope inference is IPv4 only.** An IPv6 address can still be reserved or
  leased; without either it is `unknown`.
- **Coverage gaps:** `addressing_unavailable` and `addressing_partial` follow
  the same pattern as the other buckets.
- **The mode is compared between collections.** When the same address and MAC
  goes from one known mode to another, the change list gets "Address
  assignment at A changed: dhcp_reserved -> static". As with DNS, no
  comparison is made when either collection has an `addressing_` gap.
- **Mixed networks are normal.** The bucket keeps each address's mode. It never
  collapses the network into one mode.

## UniFi as the second connector

UniFi is ported onto the same model with no new behaviour: `controller_clients`,
`managed_inventory` and `traffic_counters`. Its gateway alias and
`trafficSite` remain UniFi settings. Porting it proves the registry handles a
connector with several capabilities and credentials of a different shape (an
environment variable plus a CA file or certificate pin).

Once the Addressing bucket exists, UniFi gains `dhcp_reservations` and
`dhcp_scope`. Reservations come from each known client's fixed-IP setting
(`use_fixedip`, `fixed_ip`) in `rest/user`, which lists offline clients too;
`stat/sta` lists only connected clients, so an offline device's fixed IP would
be missed and the address could wrongly look static. The DHCP scope comes from
each network's DHCP server setting and range (`rest/networkconf`). UniFi
scopes are complete only when fixed IPs were also read completely. Both use the controller's legacy endpoints, as
traffic does today, so they need the legacy site name (`trafficSite`). UniFi
has no lease table, which is why rule 3 treats an address inside its range as
`inside_dhcp_range`.

## The connector registration

Each connector registers one object. It describes:

- **`kind`**: a fixed name such as `pihole`, used in configuration.
- **Capabilities** it can offer, from the catalogue.
- **Config decode:** a function that validates the service's settings into a
  typed, frozen plan (as `PiHolePlan.decode` does today). Raw JSON never
  crosses into collector code.
- **Build:** a function that turns the plan into the working connector.
- **Capability check:** asks the service which capabilities it actually offers
  (for Pi-hole, reads `dhcp.active`).
- **Scope fields:** which plan fields count towards the scope fingerprint. For
  example, UniFi's `gateway` and `trafficSite` do not.
- **Credentials:** which fields name a secret (an environment variable or a
  private file), so `system_view` can redact them and setup can check they are
  distinct from every other credential.
- **Health check:** what `console.py` runs today for each vendor.
- **Settings fields:** a short list of field descriptions (label, type: URL,
  text, number, secret file, environment variable name, certificate pin; limits
  and help text). The web settings form and the CLI prompts are built from this
  list.

The registry is a fixed mapping in this package. There is no loading of code
from outside the package.

## Configuration

```json
"collection": {
  "sources": [
    {
      "kind": "pihole",
      "id": "home-dns",
      "capabilities": ["network_table", "dns_queries", "dhcp_leases"],
      "settings": { "origin": "https://pihole.lan", "passwordFile": "...", "queryLimit": 64 }
    },
    {
      "kind": "unifi",
      "id": "gateway",
      "capabilities": ["controller_clients", "managed_inventory", "traffic_counters"],
      "settings": { "apiBase": "https://...", "siteId": "...", "apiKeyEnvironment": "UNIFI_API_KEY" }
    }
  ]
}
```

- **`id`** is a short identifier, unique within the collector, chosen by the
  operator. It appears in provenance and the UI.
- **`capabilities`** lists the ones the operator has switched on. If it is
  omitted, every capability the connector offers is on, except the DHCP
  capabilities and local DNS names, which are always opted into. A capability that is
  switched on but not offered is shown as "not offered"; it is not an error.
- **Old configurations keep working.** Decoding accepts the existing
  `collection.pihole` and `collection.unifi` keys and turns each into one
  service with the capabilities it has today: Pi-hole gets `network_table` and
  `dns_queries`; UniFi gets `controller_clients`, `managed_inventory` and,
  if `trafficSite` is set, `traffic_counters`. A configuration may not use both
  forms at once.
- **The DHCP capabilities and local names stay off on an upgraded
  configuration** until the operator turns them on. They add new evidence, and
  so change scope.
- **The configuration on disk is rewritten to the new form only** by an
  explicit scope change (setup or settings), never silently at start-up.

Existing cross-field rules become bucket rules: `dnsProfiles` needs a working
DNS-query capability; a DNS observation file excludes live DNS-query
capabilities; every credential environment variable is distinct.

## Provenance

`DeviceIdentity.source` is a closed set today: `configured`, `pihole-v6`,
`unifi`. Stored snapshots, the dossier, the Java test fixtures
(`tv-dossier.json`, `preview-tv-run.json`) and the agent prompts depend on it.

- **`source` keeps its three existing values** for the capabilities that
  produce them today. Stored history and fixtures stay valid.
- **A new optional field, `evidence`,** records the evidence kind from the
  catalogue (`neighbour_table`, `dhcp_lease`, `controller_client`,
  `managed_device`, `local_dns`, or `configured`).
- **A new optional field, `service`,** records the service id.
- **New connectors add their own `source` value** to a fixed allowlist in
  `contracts.py`. A test checks that every registered connector's value is in
  it. Decoding still rejects anything else. A constant avoids an import cycle
  between the contracts and the connector modules.
- `DnsWindow.source` follows the same rule.

## Merging and precedence

- Each evidence kind has a fixed precedence, highest first:
  `configured` (label only) → `managed_device` → `controller_client` →
  `dhcp_lease` → `neighbour_table` → `local_dns` (name only). An adopted
  device ranks above a controller client because today's UniFi read already
  lets the adopted-inventory record replace the client record.
- This replaces the hard-coded "UniFi wins". With today's two sources it gives
  the same result, and a test must show that.
- **Precedence never overrides a disagreement.** If two records name different
  MACs for one address, the address is ambiguous and has no identity,
  whichever ranks higher. This is the current rule, and it is what protects
  attribution.
- `confirm_identity` (used by deep scans and the assistant) reads every enabled
  identity capability and applies the same merge.

## Scope fingerprint and the journal

`scope_fingerprint` decides whether two collections are comparable, and it is
part of the receipt journal's identity.

- **An upgraded configuration must produce exactly the same fingerprint** as
  before, whether it is written in the old keys or the new list form. A test
  pins today's fingerprints for: no sources, Pi-hole only, UniFi only, both,
  and UniFi with gateway and `trafficSite`.
- New fields are added to the fingerprint only when they are present. This is
  the pattern the code already uses for `enabled` and `labels`.
- Turning on `dhcp_leases` or `local_dns_names`, or adding a service, is a
  scope change. It goes through `prepare_scope_change` and
  `complete_scope_change` like `connect_pihole` does today.

## Setup, settings and UI

- **CLI:** `connect <kind>` replaces `connect-pihole` and `connect-unifi`, with
  prompts built from the settings fields. The old commands stay as aliases.
- **Web settings:** one "Sources" section lists each service with its
  capabilities, each showing on/off and its status. Adding a service shows a
  form built from the connector's settings fields. The existing
  `POST /api/pihole` route remains until the UI moves over.
- **Status page and console:** run each service's health check and show status
  per capability. This replaces the fixed "Pi-hole: …" and "UniFi: …" lines.
- **Dossier and agent prompts:** say "DNS source" or "network controller", and
  name the actual service as data. `pihole_silent` becomes `dns_silent`, with
  the old name kept as a read alias where dossiers are stored or compared.

## Goals

- Adding a connector for a new kind of service needs one new module, plus
  registering it. No change to the collector, settings, UI or prompts.
- Pi-hole runs as the first connector, with DHCP leases and local DNS names
  available.
- UniFi runs on the same model with no behaviour change.
- Existing configurations, journals, fingerprints and stored evidence are
  unaffected until the operator changes scope.
- Each address records how it was assigned (dynamic, reserved, static or
  unknown) and why, and changes to that are reported.
- Two services of one kind (two Pi-holes) can be configured for identity.
  This arrives in stage 3.

## Non-goals

- **No writes to services.** Connectors only read. Blocking a domain in Pi-hole
  (the `pihole` remediation mechanism in `privacy_issues.py`) stays an
  operator action and is out of scope.
- **No plugin loading.** Connectors live in this package.
- **No new connectors beyond Pi-hole and UniFi in this work.** AdGuard Home and
  Proxmox were used to check that the model fits; they are later work.
- **No merging of DNS or traffic from several services** in stage 1. Two
  Pi-holes answering DNS for the same clients is real, and needs its own rule
  for summing counts and coverage windows.
- **No change to the open-port probe or self-descriptions.**

## Anti-goals (a failure even if it works)

- A lease, a local DNS name or any single source overriding another source that
  names a different MAC.
- An address shown as `static` because no DHCP data was found, when no DHCP
  scope for its subnet was read completely.
- A reservation being used as evidence of who holds an address.
- A service that does not offer a capability showing up as a coverage gap, so
  the operator sees "coverage changed" after an upgrade with no config change.
- Any change in fingerprint or journal identity for an untouched configuration.
- Raw JSON or dictionaries passed from a connector into the collector or the
  buckets.
- A vendor name reappearing in collector, settings or UI code outside the
  connector module and its registration.

## Constraints

- **Integration boundary:** everything stays inside `integrations/network-privacy`.
  No change to the Plowshare server, protocol, SDKs or shared contracts
  (`docs/decisions/0001-integration-boundary.md`).
- **Read-only and bounded:** every call is a `GET` apart from opening and
  closing a session; every list read has a fixed limit; only targeted addresses
  enter evidence; error messages are fixed strings, with no response bodies,
  URLs or credentials.
- **No deployment values in code:** service addresses, credential names and
  file paths come only from configuration.
- **Tests use fakes.** Connector tests use recorded or hand-built API
  responses; collector tests use fake capabilities. No live Pi-hole or UniFi is
  needed to run them.

## Calls made in the design (check these)

- **How an address was assigned is its own bucket, Addressing.** Leases feed
  both Identity and Addressing; reservations and DHCP scopes feed Addressing
  only. (An earlier draft folded DHCP into Identity; the owner pointed out that
  mixed static, reserved and dynamic assignment is worth keeping.)
- **`static` is only ever inferred from a complete DHCP scope,** and
  `inside_dhcp_range` without a lease table counts as dynamic.
- **Leases never count as presence.**
- **The dossier and agent prompts are unchanged in stages 1–2.** Addressing and
  the new provenance fields reach the assessor in stage 3. This keeps the Java
  dossier contract out of these stages.
- **`source` keeps its existing values,** with new `evidence` and `service`
  fields, instead of renaming provenance. Renaming would invalidate stored
  history and the Java fixtures.
- **One DNS-query and one traffic capability at a time** in stage 1.
- **New capabilities stay off on upgrade.**

## Left to the builder

- Confirm the Pi-hole v6 response shapes for `config/dhcp` (including the
  reservation list and range fields), `dhcp/leases` and `config/dns/hosts`
  against a real instance and Pi-hole's API documentation, including how a
  lease for a reserved address is reported.
  Checked against FTL's OpenAPI spec (`src/api/docs/content/specs/config.yaml`
  and `dhcp.yaml`) and the setting descriptions in `src/config/config.c`, not
  against a live instance:
  - `GET config/dhcp` returns a subset of `GET config`:
    `{"config": {"dhcp": {active, start, end, router, netmask, leaseTime,
    ipv6, rapidCommit, multiDNS, logging, ignoreUnknownClients, hosts}},
    "took"}`. `start`, `end`, `router` and `netmask` are IPv4 strings or `""`;
    an empty netmask means "taken from the interface", which a read cannot see,
    so the scope is then not decodable.
  - A `dhcp.hosts` entry is a dnsmasq `dhcp-host` string, documented as
    `[<hwaddr>][,id:<client_id>|*][,set:<tag>][,tag:<tag>][,<ipaddr>][,<hostname>][,<lease_time>][,ignore]`,
    with examples `"MAC,IP"`, `"MAC,IP,NAME"` and `"MAC,IP,NAME,24h"`. The
    plan assumed `"MAC,IP[,NAME]"`; the reader accepts exactly
    `MAC,IPv4[,NAME[,LEASE_TIME]]` and reports every other form as
    `addressing_partial`.
  - `GET dhcp/leases` returns `leases[].{expires, name, hwaddr, ip, clientid}`.
    `expires` is documented only as "0 = infinite lease, never expires"; it is
    not documented as `0` for a reserved address (a reserved host gets its own
    or the default lease time), so a reservation cannot be inferred from it.
  - `config.dns.hosts[]` is `"IP HOSTNAME [HOSTNAME ...]"`, as assumed.
- Confirm that the UniFi API key can read the legacy client fixed-IP fields
  and network DHCP settings (`rest/user`, `rest/networkconf`) read-only.
  Checked against public references only, not against a controller:
  - `GET /api/s/{site}/stat/sta` lists connected clients; on UniFi OS the
    path is prefixed `/proxy/network` (ubntwiki "UniFi Controller API",
    Art-of-WiFi `UniFi-API-client` `list_clients`). Legacy replies are
    `{"meta": {"rc": "ok"|"error"}, "data": [...]}`.
  - Client objects carry `ip`, `mac`, `use_fixedip: bool` and
    `fixed_ip: str` (aiounifi `models/client.py` `TypedClient`, reading
    `/stat/sta`). `stat/sta` lists connected clients only. The known-client
    list `GET /api/s/{site}/rest/user` (aiounifi `AllClientListRequest`;
    Art-of-WiFi `edit_client_fixedip` writes `use_fixedip`/`fixed_ip` there)
    includes offline clients, so reservations are read from `rest/user`: a
    fixed IP held for an offline device is still seen, and another device
    holding that address is a conflict rather than `static`.
  - `GET /api/s/{site}/rest/networkconf` lists the site's networks
    (Art-of-WiFi `list_networkconf`). The fields `ip_subnet` (the gateway's
    CIDR, such as `192.168.1.1/24`), `dhcpd_enabled`, `dhcpd_start` and
    `dhcpd_stop` come from community references (create-network payloads and
    `networkconf` documents), not an official schema. WAN and VLAN-only
    entries carry no `ip_subnet`. The reader takes exactly these forms and
    reports anything else as `addressing_partial`. A network is accepted
    only as a private unicast IPv4 LAN from /8 to /30; overlapping networks
    are reported and leave the read's scopes incomplete.
  - No public document says whether the Integration API key (`X-API-Key`)
    is accepted on the legacy paths; the public clients log in with a
    session. The existing traffic read already depends on it. The owner
    should confirm on the controller (one read-only GET of each path with the
    key) before enabling these capabilities.
- Whether the Java side (`NetworkDeviceAssessmentTest` and the dossier port)
  tolerates the new optional `evidence` and `service` fields without changes.
  If it does not, that is a contract change and is reported before going ahead.
- The exact name and wording of each capability status in the UI.

## Stages

1. **Registry and configuration, no behaviour change.** Add the registry, the
   bucket merge and the `sources` list, with decoding of the old keys. Port
   Pi-hole and UniFi onto it. The existing tests pass with only their
   constructor calls updated, and new tests pin fingerprints and precedence.
   At most one service of each kind.
2. **Addressing and Pi-hole DHCP.** Add the Addressing bucket; Pi-hole's DHCP
   check, leases, reservations, scope and local DNS names; UniFi fixed IPs and
   DHCP scope; and turning capabilities on from the CLI.
3. **Settings and UI built from the settings fields.** The Sources section,
   `connect <kind>`, and vendor-neutral console, dossier and prompt wording.
