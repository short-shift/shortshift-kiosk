# Nexus — Fortsett her

## Hva er dette?
Nexus erstatter Fully Kiosk Browser for ShortShift sine showroom-skjermer.
Én app som håndterer alt: WiFi-oppsett, provisioning, kiosk-browser, fjernkontroll.

## Status (2026-03-30 kveld)

### ✅ Fungerer og testet end-to-end:
- **ShowroomNexus** (`window.fully` bridge) — GPS, touch-statistikk, alle 11 metoder
- **Provisioning** — WiFi → kode → forhandlervalg → live
- **Admin-meny** — 2x oppe-venstre + 2x nede-høyre + PIN → WiFi/URL/status
- **ShortShift keyboard** tvunget som default, Gboard deaktivert
- **Cloud-fjernkontroll** — Supabase Realtime WebSocket, instant kommandoer
- **Backoffice Nexus-side** — /nexus med søk, alias, refresh/reboot/set_url, historikk
- **On-demand heartbeat** — skjermen sender full device-status når dashboardet ber om det
- **Dashboard-integrasjon** — plingplong, kart, klikk-statistikk fungerer via Azure

### ⏳ Gjenstår:
1. **Nexus-UI** — vis all device-status (WiFi, GPS, IP, RAM, uptime) som Fully Cloud gjør
2. **Screenshot** — ta bilde av skjermen remotely
3. **Boot-heartbeat** — send status én gang ved oppstart automatisk
4. **Produksjons-hardening** — disable USB, factory reset, safe boot
5. **WiFi pre-config** — pre-laste kunders WiFi fra fabrikken
6. **OTA app-oppdatering**

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
