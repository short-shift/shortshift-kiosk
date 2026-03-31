# Nexus — Fortsett her

## Hva er dette?
Nexus erstatter Fully Kiosk Browser for ShortShift sine showroom-skjermer.
Én app som håndterer alt: WiFi-oppsett, provisioning, kiosk-browser, fjernkontroll.

## Status (2026-03-31 kveld)

### Testet og verifisert på rk3588 Android 12:
- **Provisioning** — WiFi → kode 2222 → demo.shortshift.io → fungerer
- **Cloud reboot** — fra /nexus i backoffice → fungerer (~30 sek)
- **Admin-gest** — 2x oppe-venstre + 2x nede-høyre + PIN 3023 → fungerer
- **"Gå til Android"** — åpner Android home, appen forblir lukket
- **Branded feilside** — svart skjerm med "ShortShift"-logo, nedtelling, WiFi-knapp
- **WiFi-knapp i feilside** — åpner admin-meny med WiFi-fanen aktiv (DRY)
- **Vis/Skjul passord** — toggle-knapp i WiFi-passordfeltet
- **Tastatur-logikk** — autofokus på PIN (eneste input), ingen autofokus på URL-editor
- **WebView svart bakgrunn** — ingen hvit blink ved retry
- **Setup-koder** — forblir aktive etter bruk, gjenbrukbare for mange skjermer
- **CATEGORY_HOME fjernet** — ingen velger-dialog, BootReceiver starter appen

### Deployet til prod:
- **shortshift-provisioning.netlify.app** — `feature/secure-device-api` med:
  - Setup-koder brukes ikke opp (attempt_count inkrementeres)
  - expires_at valgfri (null = uendelig)

### Kjente problemer å fikse:
1. **Heartbeat thread-bug** — FIKSET i kode (currentWebViewUrl), men commands-pending gir 404 (endepunktet mangler på deployet API)
2. **PIN-tastatur** — SHOW_IMPLICIT fungerer ikke alltid i lock task mode, tastatur dukker ikke alltid opp automatisk
3. **Vis/Skjul passord** — fungerer men kan fryse visuelt ved rask klikking

### Ucommittede endringer (MÅ COMMITTES):
- `AndroidManifest.xml` — CATEGORY_HOME fjernet
- `BootReceiver.kt` — forenklet, ingen claimHomeActivity
- `KioskActivity.kt` — branded feilside, tastatur-fixes, "Gå til Android" via HOME intent, WiFi vis/skjul passord
- `docs/MASS-PROVISIONING.md` — plan for BLE + USB masse-provisjonering

## Test-skjerm
- **Ny skjerm (23.8"):** Serial `2FD2534003843838`, rk3588 Android 12
- **Gammel skjerm (32"):** Serial `2FD2523012756961` — DEFEKT backlight, ikke bruk
- **Viktig:** ANDROID_ID er app-scoped på Android 8+, ADB gir annen verdi enn appen sender

## Fjerne Fully som device owner
1. Åpne Fully sin innstillingsmeny PÅ SKJERMEN
2. Søk etter "owner"
3. Skru av device owner-funksjonen
4. ADB: `adb shell pm disable-user --user 0 com.fullykiosk.emm`
5. ADB: `adb install -r app-debug.apk`
6. ADB: `adb shell dpm set-device-owner com.shortshift.kiosk/.DeviceOwnerReceiver`

## Provisioning-koder
- Kode **2222** — aktiv, demo.shortshift.io, utløper 7. april
- Koder forblir aktive etter bruk (ny logikk deployet)
- Forhandler: Bilia Hamar Volvo & XPENG

## Neste steg
1. **Commit ucommittede endringer**
2. **Deploy commands-pending** — mangler på provisioning-API (gir 404)
3. **Factory reset-beskyttelse** — hardware_id-gjenkjenning ved re-provisioning
4. **Mass provisioning** — BLE (primær) + USB (backup), se `docs/MASS-PROVISIONING.md`
5. **Branded feilside polish** — logo som bilde, animasjon, bedre layout
6. **Custom firmware** — pre-install Nexus + keyboard, fjern Fully/bloatware

## Bygg og test
```bash
export JAVA_HOME="/Users/vemund/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/vemund/RiderProjects/shortshift-kiosk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reboot
```

## Repoer

| Repo | Branch | Hva |
|------|--------|-----|
| `short-shift/nexus` | feature/secure-command-channel | Android kiosk-app (Kotlin) |
| `short-shift/shortshift-provisioning` | feature/secure-device-api | Provisioning API (Netlify) — DEPLOYET |
| `short-shift/shortshift-backoffice` | feature/ztp-provisioning | Nexus fjernkontroll-side |

## Roadmap

### Fase 1: Cloud-fjernkontroll ← PÅGÅR
- [x] Provisioning (WiFi → kode → URL)
- [x] Cloud reboot fra /nexus
- [x] Admin-meny med PIN
- [x] Branded feilsider
- [x] "Gå til Android"
- [ ] commands-pending endepunkt (deployes)
- [ ] Komplett device-status i UI
- [ ] Screenshot-funksjon

### Fase 2: Mass Provisioning
- [ ] BLE broadcast fra mobil/web
- [ ] USB-config backup
- Se `docs/MASS-PROVISIONING.md`

### Fase 3: SSH-infrastruktur
- [ ] Headscale-server
- [ ] Tailscale APK i firmware

### Fase 4: Flåtemigrering
- [ ] Pilot 10-20 skjermer → gradvis migrering av 700 fra Fully

**Full plan:** `.claude/plans/atomic-tickling-thunder.md`
