# Hvorfor ShortShift bygger egen kiosk-app

## TL;DR

Fully Kiosk er en moden, feature-rik kiosk-browser med 600+ skjermer i drift for oss. Vi bygger Nexus fordi vi trenger å bygge forretningslogikk inn i skjermen som Fully ikke er designet for.

---

## Hva Fully Kiosk gjør bra

Fully er et solid produkt med mye vi ikke trenger å gjenskape:

- Device owner provisioning (QR, NFC, Android Enterprise)
- Remote-kontroll via Fully Cloud (worldwide)
- 30+ JavaScript API-metoder (device info, screen, brightness, QR, Bluetooth, iBeacon, kamera, TTS, screenshots)
- Motion detection (kamera + lyd + proximity + akselerometer)
- Face detection (`getFaceNumber()`)
- NFC-lesing og Bluetooth-kommunikasjon
- 300+ konfigurerbare innstillinger via REST/MQTT
- Scheduled sleep/wake, crash recovery, auto-restart
- Samsung KNOX-støtte
- Daglig bruksstatistikk

Vi har 600+ skjermer som kjører Fully i produksjon. Det fungerer.

---

## Hva vi trenger som Fully ikke er designet for

### Selger-modus
Selger taster sin kode → skjermen blir et salgsverktøy med sammenligning, totaløkonomi, innbytte, cast til storskjerm. Dette er forretningslogikk som hører til ShortShift, ikke til en generisk kiosk-browser.

### Skjerm-til-skjerm kommunikasjon
Cast innhold fra liten skjerm til storskjerm i sanntid. Selger styrer fra én, kunden ser på en annen. Supabase Realtime mellom skjermer i samme showroom. Fully ser hver skjerm som en isolert enhet.

### Egen JavaScript-bridge vi utvider fritt
ShowroomNexus er `window.fully`-kompatibel (eksisterende kode fungerer uten endring), men vi kan legge til sesjoner, selger-modus, cast-events, og hva vi ellers trenger — uten å vente på Fully sin roadmap.

### Provisioning tilpasset vår flyt
Kode-basert oppsett der forhandler taster en kode → skjermen henter config fra vårt API og er klar. Tilpasset ShortShifts kunder og forhandlerstruktur, ikke en generisk provisioning.

### Integrasjon med våre systemer
Direkte kobling til innbyttepris-motoren, SalesZilla, konfiguratoren — fra skjermen. Fully sin JS-bridge gir oss device-info og hardware-tilgang, men forretningslogikk må vi bygge selv uansett.

---

## Hva vi mister ved å bytte

- Fullys modne crash recovery og auto-restart (må bygges)
- Scheduled sleep/wake (må bygges)
- MQTT-integrasjon (trenger vi ikke per nå)
- Motion detection med kamera/lyd/akselerometer (Fully har dette innebygd)
- Face detection (Fully har `getFaceNumber()`)
- Samsung KNOX-støtte (ikke relevant — vi bruker Rockchip)
- Battle-tested stabilitet over tusenvis av installasjoner
- Fully Cloud sitt dashboard og verktøy

---

## Hva det fjerner

- **Lisenskostnad** per enhet (~€8 engangslisens eller ~€1.18/mnd per enhet)
- **Avhengighet** av tredjeparts prioriteringer for nye features
- **Begrensningen** at vi kun kan gjøre det Fully eksponerer

---

## Hva det åpner

### Kort sikt
- Selger-modus med predefinerte salgsverktøy
- Cast til storskjerm
- "Send til kunde" via QR
- Provisioning tilpasset vår flyt

### Lengre sikt
- Skjerm-til-skjerm sync via Supabase Realtime
- Integrasjon med innbyttepris, SalesZilla, konfigurator direkte i skjermen
- Sensor-data (USB-sensorer for trafikk/dwell time)
- Data til forhandler- og importør-dashboards

---

## Kjerneargumentet

Fully er en generisk kiosk-browser. Den er god til å vise en nettside i fullskjerm, låse ned enheten, og gi remote-kontroll.

Men vi trenger skjermen til å bli et **salgsverktøy** — med modusbytte, sesjoner, skjerm-sync, og integrasjon med våre egne systemer. Det er forretningslogikk, ikke kiosk-funksjonalitet. Og det bygger vi best selv.

---

## Status

Fungerende prototype testet på fysisk skjerm. WiFi-oppsett, provisioning, WebView med lockdown, ShowroomNexus-bridge, cloud-fjernkontroll via Supabase Realtime — alt virker. Fully Kiosk er fortsatt installert men inaktiv på testskjermen.
