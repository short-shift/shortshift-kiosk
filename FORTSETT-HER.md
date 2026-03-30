# ShortShift Kiosk — Fortsett her

## Hva er dette?
Android kiosk-app som erstatter Fully Kiosk Browser for ShortShift sine showroom-skjermer.
Én app som håndterer alt: WiFi-oppsett, provisioning, kiosk-browser med lockdown.

## Status (2026-03-30)

### Fungerer (testet på fysisk Rockchip rk3588 skjerm):
- **WiFi-picker**: Viser tilgjengelige nettverk, kunde velger og taster passord
- **Provisioning-kode**: Kunde taster kode → appen henter config fra ShortShift Provisioning API
- **WebView kiosk**: Laster URL i fullskjerm med device owner lockdown (setLockTaskPackages)
- **Provisioning API**: Live på https://shortshift-provisioning.netlify.app
- **Setup codes i Supabase**: Tabell opprettet, fungerer end-to-end
- **Full flyt**: Boot → WiFi → kode → nettside vises

### Gjenstår:
1. ~~**demo.shortshift.io forventer Fully Kiosk**~~ — **LØST (2026-03-30)**: `FullyKioskApi.kt` injiserer fake `window.fully` i WebView via `@JavascriptInterface`. Alle 11 metoder brukt av finn-bruktbil er implementert. `setStartUrl()` lagrer ny URL i SharedPreferences → overlever reboot.
2. **WiFi-skanning tom** — Noen ganger viser WiFi-listen ingen nettverk. Mulig Android 12 throttling på wifiManager.startScan(). Kan trenge NEARBY_WIFI_DEVICES permission.
3. **Tastatur** — Kan være trøbbel med on-screen keyboard i fullskjerm-modus. Husk: ikke skjul navigation bars.
4. **Heartbeat** — Ikke implementert ennå. API-endepunktet finnes, appen trenger en WorkManager-jobb.
5. **Produksjons-hardening** — Aktiver device restrictions (factory reset, USB debugging) når dev er ferdig.

## Arkitektur

```
ShortShift Kiosk (device owner) — ÉN app som gjør ALT:
├── ProvisioningActivity     — WiFi-picker (startes ved boot)
├── ProvisioningCodeActivity — Kode-input → hent config fra API
├── KioskActivity            — WebView i fullskjerm med lockdown
├── DeviceOwnerReceiver      — Device admin, auto-grant permissions
└── BootReceiver             — Starter appen ved boot
```

Fully Kiosk Browser er IKKE involvert. Ingen device owner-konflikt.

## Repoer og infrastruktur

| Repo | Hva | Lokasjon |
|------|-----|----------|
| short-shift/shortshift-kiosk | Android kiosk-app (Kotlin) | /Users/vemund/RiderProjects/shortshift-kiosk |
| short-shift/shortshift-provisioning | Provisioning API (Netlify Functions) | /Users/vemund/RiderProjects/shortshift-provisioning |
| short-shift/shortshift-backoffice | Admin UI for kodegenerering (branch: feature/ztp-provisioning) | /Users/vemund/RiderProjects/shortshift-backoffice |

- **Provisioning API**: https://shortshift-provisioning.netlify.app
- **Supabase**: Tabeller setup_codes, device_heartbeats, device_events opprettet
- **Blueprint**: /Users/vemund/.claude/plans/polymorphic-petting-cookie.md

## Viktig kontekst

- Skjermene er egenutviklede Rockchip rk3588 med Android 12 (SDK 32)
- Ingen fysiske knapper — kun touchskjerm
- Fabrikken i Kina kan pre-installere APK og sette device owner via `adb shell dpm set-device-owner`
- Fully Kiosk EMM pakkenavn: `com.fullykiosk.emm` (activity: `de.ozerov.fully.MainActivity`)
- Fully Kiosk sin DeviceOwnerReceiver mangler device_admin meta-data — KAN IKKE settes som device owner via ADB eller transferOwnership
- ALDRI kjør `pm clear` på Fully Kiosk — ødelegger admin-registrering
- ALDRI sett DISALLOW_DEBUGGING_FEATURES under utvikling — bricker skjermen
- WiFi-nettverk hos ShortShift: SSID "shortshift"

## Bygg og test

```bash
# Bygg APK
export JAVA_HOME="/Users/vemund/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd /Users/vemund/RiderProjects/shortshift-kiosk
./gradlew assembleDebug

# Installer via ADB
export PATH=$PATH:~/Library/Android/sdk/platform-tools
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Sett device owner (kun på fersk enhet uten kontoer)
adb shell dpm set-device-owner com.shortshift.kiosk/.DeviceOwnerReceiver

# Reset for ny test (slett config + WiFi)
adb shell run-as com.shortshift.kiosk rm -rf /data/data/com.shortshift.kiosk/shared_prefs/
adb shell cmd wifi forget-network 0
adb reboot
```

## Neste steg prioritert
1. Løs demo.shortshift.io-integrasjonen (fake Fully API eller egen konfig-side)
2. Test full flyt fra factory reset til fungerende skjerm
3. Legg til heartbeat-rapportering
4. Send firmware-instruksjoner til fabrikken
