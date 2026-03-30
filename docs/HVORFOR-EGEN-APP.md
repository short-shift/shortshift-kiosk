# Hvorfor ShortShift bygger egen kiosk-app

## Hva vi har i dag

Fully Kiosk Browser + Fully Cloud kjører på 600+ skjermer i produksjon. Det gir oss:

- Kiosk-browser med device owner lockdown
- Remote-kontroll via Fully Cloud (URL, settings, reboot, screenshot)
- Fleet management (grupper, provisioning, monitoring, app-management)
- JavaScript-bridge med ~30 metoder (device info, screen, brightness, QR, Bluetooth, iBeacon, kamera, TTS)
- Motion/face detection, NFC, Bluetooth-kommunikasjon
- REST API og MQTT for automatisering
- 300+ konfigurerbare settings
- Crash recovery, scheduled restart, auto-wake
- Daglig bruksstatistikk

Det fungerer.

---

## Hva Nexus gir i tillegg

### Egen kode i app-laget
Fully er en konfigurerbar nettleser. Du peker den på en URL og stiller inn settings. Nexus er en app vi skriver Kotlin-kode i. WebView-en viser nettside akkurat som Fully, men rundt den kan vi bygge native features som ikke er en nettside:

- Selger-modus: native overlay med egen UI, logikk og tilstand
- Cast til storskjerm: native kommando mellom to enheter
- "Send til kunde": QR-generering med sesjonsdata
- Provisioning tilpasset vår flyt (kode → config fra vårt API)

### Skjerm-til-skjerm kommunikasjon
Fully ser hver skjerm som en isolert enhet. Med Nexus kan vi åpne en Supabase Realtime-kanal der alle skjermer i et showroom er på samme kanal. Det er kode som må skrives — ikke noe Fully kan konfigureres til.

### JavaScript-bridge vi utvider selv
Fully eksponerer ~30 metoder via `window.fully`. Vi kan ikke legge til nr. 31. Med ShowroomNexus (`window.fully`-kompatibel) kan vi legge til `getActiveSession()`, `castToScreen()`, `enterSellerMode()` — hva enn vi trenger.

### Ingen lisenskostnad
~€8 engangslisens per enhet. Ved 600+ enheter er det ~€5000 spart. Ikke enormt, men reelt.

### Vi bestemmer roadmap
Trenger vi en ny feature i skjerm-laget? Vi bygger den selv.

---

## Hva vi gir opp

- **Fully Cloud** — fleet management, grupper, monitoring. Må bygge eget dashboard.
- **Modenhet** — Fullys stabilitet over tusenvis av installasjoner. Vi starter med én testskjerm.
- **Features vi ikke har bygget ennå** — crash recovery, scheduled restart, motion detection, face detection, MQTT, NFC.
- **Support** — Fully har dokumentasjon og community. Nexus har oss.

---

## Kjerneargumentet

Fully er et ferdig produkt du konfigurerer. Nexus er en kodebase vi utvikler. Forskjellen er relevant når du trenger ting som ikke finnes i Fully — og det gjør vi med selger-verktøy, cast, skjerm-sync, og integrasjon med egne systemer (innbyttepris, SalesZilla, konfigurator).

---

## Status

Fungerende prototype testet på fysisk skjerm. WiFi-oppsett, provisioning, WebView med lockdown, ShowroomNexus-bridge, cloud-fjernkontroll via Supabase Realtime — alt virker.
