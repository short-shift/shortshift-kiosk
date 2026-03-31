# Nexus — Fortsett her

## Hva er dette?
Nexus erstatter Fully Kiosk Browser for ShortShift sine showroom-skjermer.
Én app som håndterer alt: WiFi-oppsett, provisioning, kiosk-browser, fjernkontroll.

## Status (2026-03-31)

### ✅ Fungerer og testet end-to-end:
- **ShowroomNexus** (`window.fully` bridge) — GPS, touch-statistikk, alle 11 metoder
- **Provisioning** — WiFi → kode → forhandlervalg → live
- **Admin-meny** — 2x oppe-venstre + 2x nede-høyre + PIN → WiFi/URL/status
- **ShortShift keyboard** tvunget som default, Gboard deaktivert
- **Cloud-fjernkontroll** — Supabase Realtime WebSocket, instant kommandoer
- **Backoffice Nexus-side** — /nexus med søk, alias, refresh/reboot/set_url, historikk
- **On-demand heartbeat** — skjermen sender full device-status når dashboardet ber om det
- **Dashboard-integrasjon** — plingplong, kart, klikk-statistikk fungerer via Azure

### 🔒 Sikkerhetsoppgradering (branch: feature/secure-command-channel)
CommandChannel omskrevet fra direkte Supabase WebSocket → sikker API-polling:
- Fjernet hardkodet Supabase anon key og WebSocket
- Polling hvert 30s via `commands-pending` med Bearer token
- ACK via `commands-ack` med eierskapsverifisering
- Heartbeat hvert 5 min via `provision-heartbeat` med full device-status
- Piggyback: ventende kommandoer returneres i heartbeat-respons
- **Provisioning-API** har nye endepunkter (branch: `feature/secure-device-api` i shortshift-provisioning)

### ⏳ Neste steg (Sprint 1 gjenstår):
1. **Factory reset-beskyttelse** — hardware_id-gjenkjenning ved re-provisioning
2. **minSdk 28 → 24** — støtte Android 7-skjermer (verifiser WebView på rk3399 først)
3. **RLS-migrasjon** — stram inn `device_commands` (KUN etter alle skjermer er oppdatert)

### ⏳ Sprint 2: Push + skalering
4. **FCM-integrasjon** — instant kommandoer via Firebase push (erstatter polling-kostnad)
5. **Heartbeat piggyback** — kommandoer i heartbeat-respons (allerede implementert server-side)
6. **Lasttest** — simuler 700 skjermer

### ⏳ Sprint 3: SSH-infrastruktur
7. **Headscale-server** — self-hosted VPN for SSH-nødtilgang til alle 700+ skjermer
8. **Tailscale APK** preinstallert i fabrikk-image
9. **SSH-daemon** (dropbear) i fabrikk-image

### ⏳ Sprint 4: Flåtemigrering
10. Pilot (10-20 skjermer) → validere → gradvis migrering av 700 skjermer fra Fully → Nexus

### ⏳ Øvrig gjenstår:
- **Nexus-UI** — vis all device-status (WiFi, GPS, IP, RAM, uptime)
- **Screenshot** — ta bilde av skjermen remotely
- **Produksjons-hardening** — disable USB, factory reset, safe boot
- **OTA app-oppdatering**

## Repoer

| Repo | Branch | Hva |
|------|--------|-----|
| `short-shift/nexus` | main | Android kiosk-app (Kotlin) |
| `short-shift/shortshift-provisioning` | main | Provisioning API (Netlify) |
| `short-shift/shortshift-backoffice` | feature/ztp-provisioning | Nexus fjernkontroll-side |

## Supabase-endringer (2026-03-30)
- `device_commands`-tabell med Realtime + RLS
- `screens`: nye kolonner (alias, device_type, android_version, wifi_ssid, wifi_signal_dbm, ip_address, latitude, longitude, current_url, screen_on, uptime_seconds, free_memory_mb, mac_address)

## Test-skjerm
- Screen ID: `0de63926-f472-4f35-83d5-6d503564adfb`
- Setup-kode: `1111` → demo.shortshift.io (utløper 2. april)

## Bygg og test
```bash
export JAVA_HOME="/Users/vemund/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/vemund/RiderProjects/shortshift-kiosk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reboot
```

## Roadmap

### Fase 1: Cloud-fjernkontroll ← PÅGÅR
- [x] WebSocket kommando-kanal
- [x] Backoffice Nexus-side
- [x] On-demand heartbeat
- [ ] Komplett device-status i UI
- [ ] Screenshot

### Fase 2: Selger-verktøy
Se [docs/PERSONLIG-SHOWROOM.md](docs/PERSONLIG-SHOWROOM.md)

### Fase 3: Presence Detection
Se [docs/SENSOR-STRATEGI.md](docs/SENSOR-STRATEGI.md)

### Fase 4: Intelligent Showroom
Bip AI, demografi, importør-dashboard

**Full plan:** `.claude/plans/atomic-tickling-thunder.md`
**Strategidokumenter:** [docs/HVORFOR-EGEN-APP.md](docs/HVORFOR-EGEN-APP.md)
