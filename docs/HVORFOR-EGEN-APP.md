# Hvorfor ShortShift bygger egen kiosk-app

## TL;DR

Fully Kiosk er en nettleser. Nexus er en intelligent showroom-node. Forskjellen er at vi eier hele stakken fra hardware til sky — og det er det som gjør oss umulig å kopiere.

---

## Problemet med Fully Kiosk

| Problem | Konsekvens |
|---|---|
| Kan ikke settes som device owner via ADB | Zero-touch provisioning er umulig |
| Ingen tilgang til sensorer | Skjermen er blind — vet ikke om noen står foran |
| Ingen JavaScript bridge vi kontrollerer | Avhengig av Fully sitt API som kan endre seg |
| Lisenskostnad per enhet | Løpende kostnad som skalerer lineært |
| Ingen OTA-oppdateringer av logikk | Kan ikke rulle ut nye features remote |
| Lukket kildekode | Kan ikke debugge, tilpasse, eller utvide |
| `pm clear` ødelegger admin permanent | Skjørt system som bricker ved feilkonfig |

## Hva Nexus gir oss

### 1. Full kontroll over enheten

Nexus er device owner. Det betyr:
- Lockdown uten avhengighet av tredjepart
- Programmatisk WiFi-konfig (ingen Android-UI)
- Auto-grant av alle permissions
- Remote factory reset
- OTA app-oppdateringer via heartbeat

### 2. Zero-touch provisioning

Chromecast-style oppsett: skjerm booter → forhandler taster kode → ferdig på 2 minutter.

- Halverer onboarding-tid → flere skjermer ut raskere
- Fjerner behov for teknisk personell ved installasjon
- Muliggjør selvbetjent bestilling (skjerm → post → forhandler setter opp selv)
- Kritisk for Sverige-ekspansjon (ingen trenger å reise dit)

### 3. Sensor-plattform

Med egen app kan vi koble til sensorer via USB Host API:
- **Nærhetssensor (ToF/mmWave):** Vet om noen står foran skjermen
- **Trigger-basert innhold:** Skjermen "våkner" når noen nærmer seg
- **Trafikkdata:** Approaches, engasjement, dwell time — per skjerm, per dag
- Se [SENSOR-STRATEGI.md](SENSOR-STRATEGI.md) for full spesifikasjon

### 4. JavaScript bridge vi eier (ShowroomNexus)

`window.fully`-kompatibel bridge med utvidelser ingen konkurrent har:
- GPS-lokasjon for skjermen
- Touch-statistikk (klikk + bevegelser)
- Sensor-events (presence, engagement)
- Fremtidig: kundeidentitet, multi-skjerm-sync

### 5. Data-flyhjulet

```
Skjerminteraksjon → aggregert data → bedre AI/anbefalinger
→ mer engasjement → mer data → ...
```

Fully Kiosk gir oss klikk-tall. Nexus gir oss:
- Hvilke biler får oppmerksomhet vs. klikk (presence uten touch)
- Tidsbruk per bil, per skjerm, per showroom
- Konverteringsattribusjon: online → skjerm → selger → salg
- A/B-testing av innhold
- Regional etterspørsel fra 500+ skjermer

---

## Strategisk moat

### Hva en konkurrent må bygge for å kopiere oss

1. Kiosk-app med device owner og provisioning
2. BLE provisioning-pipeline med telefon-app
3. 500+ fysiske skjermer hos forhandlere
4. Integrasjon med bildata-systemer
5. Sensor-lag med presence detection
6. AI og gamification

Det tar år og krever fysisk distribusjon. Software-konkurrenter kan ikke gjøre det. Hardware-konkurrenter har ikke softwaren.

### Fra "skjermleverandør" til "infrastruktur for bilsalg"

| | Med Fully Kiosk | Med Nexus |
|---|---|---|
| Produkt | Skjerm som viser en nettside | Intelligent showroom-node |
| Verdi | Visuelt oppsalg | Data + konvertering + AI |
| Moat | Ingen — kopierbart på en dag | Fysisk + software + data |
| Prising | Fast per skjerm | Fast + per-transaksjon + data |
| Skalerbarhet | Lineær (manuelt oppsett) | Eksponentiell (selvbetjent) |

---

## Importør-salg: Det strategiske våpenet

Aggregert data fra skjermene er unikt i markedet. Ingen av de 27 internasjonale aktørene vi har researched har showroom-data.

> "Frydenbø: Skjermene deres viser at 65% av trafikken ved elbil-skjermene er i aldersgruppa 30-45. Kampanjen retter seg mot 25-35. Vil dere justere?"

Importører betaler i dag for dårligere markedsdata fra tradisjonelle kilder. ShortShift sitter på sanntids etterspørselsdata fra det fysiske showrommet.

---

## Risiko og mitigering

| Risiko | Mitigering |
|---|---|
| Android-quirks (Rockchip, OS-versjoner) | Kontrollert hardware — kun én chipset å støtte |
| Feil i lockdown-kode bricker skjerm | DISALLOW_DEBUGGING_FEATURES aldri i dev. Remote reset via heartbeat |
| Vedlikeholdskostnad for Kotlin-app | Liten overflate — WebView gjør det meste, appen er thin wrapper |
| Fully har features vi mangler (crash recovery, etc.) | Bygges inn gradvis — WebView watchdog, scheduled restart |

## Tidshorisont

| Fase | Tidslinje | Leveranse |
|---|---|---|
| Infrastruktur-moat | Nå → 6 mnd | ZTP, heartbeat, remote management, sensor MVP |
| Intelligent showroom | 6-12 mnd | Kundeidentitet, selger-varsel, innbyttepris på skjerm, analytics |
| AI-showroom | 12-24 mnd | Bip på skjerm, multi-skjerm-orkestrering, importør-dashboard |
