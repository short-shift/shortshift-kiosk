# ShortShift Kiosk

Android kiosk provisioning-app som kjører som Device Owner på tablets. Håndterer BLE advertising, WiFi provisioning, og Fully Kiosk-konfigurasjon.

## Oppsett

1. Åpne prosjektet i Android Studio
2. Synkroniser Gradle
3. Bygg APK: `Build > Build Bundle(s) / APK(s) > Build APK(s)`

## Fabrikk-installasjon

### Sett som Device Owner (på ny enhet eller etter factory reset)

```bash
# Installer APK
adb install app/build/outputs/apk/release/app-release.apk

# Sett som device owner
adb shell dpm set-device-owner com.shortshift.kiosk/.DeviceOwnerReceiver
```

### NFC-provisioning (alternativ)

For masseproduksjon kan enheter provisjoneres via NFC med Android Enterprise zero-touch enrollment.

## Provisioning-flyt

1. Tableten starter og annonserer via BLE som "ShortShift-XXXX"
2. Bruker åpner ShortShift-appen på telefonen og finner tableten
3. Telefonen sender WiFi-konfigurasjon og setup-kode via BLE
4. Tableten kobler til WiFi og registrerer seg mot provisioning-API-et
5. API-et returnerer token, dealer-info og Fully Kiosk-konfigurasjon
6. Tableten konfigurerer og starter Fully Kiosk i kiosk-modus
7. Heartbeat sendes hvert 15. minutt

## Arkitektur

- **BLE GATT Server**: Annonserer og mottar WiFi/kode fra telefon-appen
- **WiFi Provisioner**: Kobler til WiFi via DevicePolicyManager (ingen brukerinteraksjon)
- **Provisioning API Client**: Registrerer enhet og henter konfigurasjon
- **Fully Kiosk Configurator**: Setter opp og starter Fully Kiosk Browser
- **Heartbeat Worker**: WorkManager-jobb som sender status hvert 15. minutt
- **Secure Storage**: EncryptedSharedPreferences for token og config
