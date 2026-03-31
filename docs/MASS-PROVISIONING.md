# Mass Provisioning — Nexus

## Problem
Provisjonering av skjermer én og én er tregt. Ved utrulling hos ny forhandler (10-50 skjermer) eller migrering av 700 Fully-skjermer trenger vi en "koble strøm og alt skjer fra én plass"-løsning.

## Alternativ A: BLE Broadcast (foretrukket)

### Flyt
1. Skjermer booter uten config → ProvisioningActivity går i BLE-lyttemodus
2. Tekniker åpner /nexus på mobil (eller Nexus-app)
3. Velger: kunde, forhandler, WiFi SSID + passord, start-URL
4. Trykker "Provisjoner i nærheten"
5. Mobilen broadcaster config-pakke via BLE
6. Alle skjermer innenfor rekkevidde (~10-15m) mottar config
7. Skjermene kobler til WiFi → registrerer seg i cloud via provision-pair → viser nettsiden

### Fordeler
- Null kabler, null manuell input per skjerm
- Skalerer til alle skjermer i rommet
- BLE-hardware og permissions allerede i skjermene
- Funker uten eksisterende WiFi-nettverk (WiFi-credentials sendes via BLE)

### Utfordringer
- BLE payload er begrenset (~512 bytes per melding) — config-pakke må være kompakt
- Trenger enkel kryptering (AES med delt nøkkel?) så ikke naboer kan provisjonere
- Mobil-app eller PWA med Web Bluetooth API
- Må håndtere at noen skjermer er utenfor rekkevidde → retry/status per skjerm

### Teknisk skisse
**Skjerm (Android/Kotlin):**
- ProvisioningActivity starter BLE GATT server med service UUID
- Eksponerer "config" characteristic (write)
- Ved mottatt config: lagre WiFi + provisioner via API

**Mobil (/nexus eller app):**
- Scanner etter Nexus BLE service UUID
- Viser liste: "8 skjermer funnet"
- Bruker velger config → skriver til alle enheter

### MVP
- BLE GATT server i ProvisioningActivity
- Enkel Web Bluetooth-side i /nexus for å sende config
- Ingen kryptering i MVP (legges til før prod)

---

## Alternativ B: USB-provisioning (backup/felt)

### Flyt
1. Lag config-fil i /nexus → last ned `nexus-config.json` til USB-pinne
2. Plugg USB i skjerm → Nexus leser filen automatisk → provisjonerer seg
3. Flytt pinnen til neste skjerm

### Config-filformat
```json
{
  "version": 1,
  "wifi_ssid": "ForhandlerNett",
  "wifi_password": "passord123",
  "setup_code": "2222",
  "provisioning_url": "https://shortshift-provisioning.netlify.app/.netlify/functions/provision-pair"
}
```

### Fordeler
- Fungerer overalt, ingen nettverkskrav
- Ekstremt pålitelig — ingen trådløs-problematikk
- Lett å forstå for tekniker i felt

### Utfordringer
- Manuelt per skjerm (plugg inn/ut)
- USB OTG-støtte må verifiseres på rk3588
- Sikkerhet: config-fil på pinne inneholder WiFi-passord i klartekst

### Teknisk skisse
- BroadcastReceiver for USB_DEVICE_ATTACHED
- Les `nexus-config.json` fra USB-root
- Parse → lagre WiFi → provisioner → vis bekreftelse på skjerm
- Eject USB-melding: "Flytt pinnen til neste skjerm"

---

## Prioritering
1. **BLE** — hovedløsning for masse-provisjonering
2. **USB** — backup for felt/situasjoner uten app tilgjengelig
3. Begge kan sameksistere — ProvisioningActivity lytter på BLE OG USB samtidig

## Avhengigheter
- Nexus-appen må være pre-installert (custom firmware eller manuell install)
- Device owner må være satt (custom firmware eller ADB)
- BLE krever mobil-app eller Web Bluetooth-kompatibel nettleser
