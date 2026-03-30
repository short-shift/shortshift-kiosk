# Sensor-strategi: Presence Detection

## Mål

Gjøre showroom-skjermen kontekst-aware. Den skal vite om noen står foran, hvor lenge, og reagere på det — uten kamera, uten persondata.

---

## Fase 1: ToF nærhetssensor (MVP)

### Hardware

**VL53L1X Time-of-Flight sensor** (eller tilsvarende)
- Pris: ~150 kr/enhet (USB-modul fra AliExpress/Adafruit)
- Rekkevidde: 0–400 cm, millimeterpresisjon
- Oppdateringsfrekvens: 50 Hz
- Strøm: USB-drevet
- Størrelse: ~15×15mm sensor, USB-modul ~30×20mm

**Alternativ:** VL53L4CD (nyere, billigere, 0-130cm) — nok for showroom.

### Tilkobling

USB-modul med FTDI/CH340-chip → Android USB Host API.

```
[ToF sensor] --USB--> [rk3588 USB-port] --USB Host API--> [Nexus-appen]
```

Montering: limes/skrus under skjermkanten, peker utover i showroom.

### Tre soner

```
[SKJERM]
  │
  │  0–60 cm   → ENGASJERT   (leser, vurderer touch)
  │ 60–150 cm  → INTERESSERT (stoppet, ser på)
  │ 150 cm+    → PASSERER    (gikk forbi, ignoreres)
  │
```

Terskelverdier konfigurerbare per skjerm (via provisioning config).

### Begrensninger ToF

- Teller kun én person (nærmeste objekt)
- Smal strålevinkel (~27°) — fanger ikke folk som står til siden
- Kan trigges av objekter (handlevogn, barnevogn)

Godt nok for MVP. Oppgraderes til mmWave i fase 2 for multi-person.

---

## Fase 2: mmWave radar (etter validering)

### Hardware

**HLK-LD2410 / LD2450** (Hi-Link mmWave sensor)
- Pris: ~200–400 kr/enhet
- Detekterer: antall personer, avstand, bevegelsesretning, sitter/står
- Rekkevidde: 0–600 cm
- Null personvern-issues (radar, ikke kamera)
- Brukes i smart home (Aqara FP2, etc.)

### Fordeler over ToF

| | ToF | mmWave |
|---|---|---|
| Antall personer | 1 (nærmeste) | Flere (opptil 3-5) |
| Bevegelsesretning | Nei | Ja |
| Vinkel | ~27° | ~60-120° |
| Sitter/står | Nei | Ja |
| Pris | ~150 kr | ~300 kr |

---

## Android-implementasjon

### USB Host API sensor-driver

```kotlin
// SensorManager.kt — ny fil i com.shortshift.kiosk

class PresenceSensor(private val context: Context) {

    enum class Zone { NONE, PASSING, INTERESTED, ENGAGED }

    data class PresenceState(
        val zone: Zone,
        val distanceMm: Int,
        val dwellTimeMs: Long
    )

    // Konfigurerbare terskler (hentes fra provisioning config)
    var engagedThresholdMm = 600    // 60 cm
    var interestedThresholdMm = 1500 // 150 cm

    private var approachTimestamp: Long = 0
    private var currentZone: Zone = Zone.NONE

    fun onDistanceReading(distanceMm: Int) {
        val newZone = when {
            distanceMm <= engagedThresholdMm -> Zone.ENGAGED
            distanceMm <= interestedThresholdMm -> Zone.INTERESTED
            else -> Zone.PASSING
        }

        if (newZone != Zone.PASSING && currentZone == Zone.PASSING) {
            // Noen stoppet foran skjermen
            approachTimestamp = System.currentTimeMillis()
            onApproach(distanceMm)
        }

        if (newZone == Zone.ENGAGED && currentZone != Zone.ENGAGED) {
            // Engasjert — nært nok til å lese
            onEngaged(distanceMm)
        }

        if (newZone == Zone.PASSING && currentZone != Zone.PASSING) {
            // Personen gikk
            val dwellMs = System.currentTimeMillis() - approachTimestamp
            onLeft(dwellMs)
        }

        currentZone = newZone
    }
}
```

### JavaScript bridge (utvidelse av ShowroomNexus)

```kotlin
// I ShowroomNexus.kt — nye metoder

@JavascriptInterface
fun getPresenceZone(): String {
    // "none", "interested", "engaged"
    return presenceSensor.currentZone.name.lowercase()
}

@JavascriptInterface
fun getPresenceDistance(): Int {
    return presenceSensor.lastDistanceMm
}

@JavascriptInterface
fun getPresenceDwellTime(): Long {
    return presenceSensor.currentDwellTimeMs
}

// Event-pushing til WebView (fra Kotlin-siden)
fun pushPresenceEvent(event: String, data: String) {
    webView.evaluateJavascript(
        "window.dispatchEvent(new CustomEvent('nexus-presence', " +
        "{detail: {event: '$event', data: $data}}))", null
    )
}
```

### WebView-siden (finn-bruktbil / konfigurator)

```javascript
// Lytt på presence-events fra Nexus
window.addEventListener('nexus-presence', (e) => {
  const { event, data } = e.detail

  switch (event) {
    case 'approached':
      // Skjermen "våkner" — velkommen-animasjon
      showWelcomeOverlay()
      break

    case 'engaged':
      // Personen er nært — vis CTA
      showCallToAction('Trykk for å utforske denne bilen')
      break

    case 'left':
      // Personen gikk — logg dwell time, tilbake til standby
      logInteraction({ dwellMs: data.dwellTimeMs, touched: data.hadTouch })
      resetToStandbyMode()
      break
  }
})
```

---

## Trigger-plattform

Presence-eventen er fundamentet for alle fremtidige features:

| Trigger | Handling | Verdi |
|---|---|---|
| Person nærmer seg | Våkne fra standby, animasjon | Bedre førsteinntrykk |
| Engasjert >5 sek, ingen touch | "Trykk her for å starte" | Høyere konvertering |
| Engasjert >30 sek | Push til selger: "Kunde ved XC40-skjermen" | Selger-assist |
| Person gikk uten touch | Logg "missed opportunity" | Optimere innhold |
| Flere approaches etter kl 17 | Auto-bytt til kveldskampanje | Kontekst-aware |
| Person + kundeidentitet (fremtid) | "Velkommen tilbake, du så på XC40 sist" | Personalisering |

---

## Data som samles inn

### Per skjerm, per dag (sendes via heartbeat)

```json
{
  "screen_id": "uuid",
  "date": "2026-03-30",
  "presence_summary": {
    "total_approaches": 47,
    "total_engagements": 23,
    "touches_after_approach": 15,
    "avg_dwell_sec": 34,
    "max_dwell_sec": 180,
    "approach_to_touch_rate": 0.32,
    "approach_to_engagement_rate": 0.49,
    "hourly_distribution": {
      "09": 3, "10": 5, "11": 8, "12": 4,
      "13": 6, "14": 9, "15": 7, "16": 3, "17": 2
    }
  }
}
```

### Personvern

- Ingen kamera, ingen bilder, ingen biometri
- Kun avstandsmåling (tall i millimeter)
- Aggregerte statistikker — ingen individuell sporing
- Ingen GDPR-relevans — tilsvarende en dørteller
- Ingen informasjonsplikt påkrevd

---

## Kalibrering

### Problem
Sensorplassering varierer mellom installasjoner:
- Avstand til gulv
- Vinkel (rett ut vs. nedover)
- Omgivelser (vegg bak, annen skjerm ved siden av)

### Løsning: Auto-kalibrering ved oppsett

Etter provisioning kjører appen 60 sekunder med kalibrering:
1. Måler bakgrunnsavstand (vegg/gulv) → setter baseline
2. Beregner soner relativt til baseline
3. Lagrer i config (synces via heartbeat)

Kan rekalibreres fra admin-meny (allerede eksisterer: 2x oppe-venstre + 2x nede-høyre + PIN).

---

## Implementeringsplan

### Steg 1: USB sensor-driver (~2 dager)
- USB Host API tilkobling for VL53L1X
- Lese avstands-verdier i loop
- Debounce og filtrering (ignorere <100ms readings)

### Steg 2: Presence-logikk (~1 dag)
- Sone-klassifisering (engaged/interested/passing)
- Approach/left event-deteksjon
- Dwell time tracking

### Steg 3: ShowroomNexus bridge (~0.5 dag)
- `getPresenceZone()`, `getPresenceDistance()`, `getPresenceDwellTime()`
- `nexus-presence` CustomEvent pushing
- Konfigurerbare terskler via provisioning config

### Steg 4: Heartbeat-utvidelse (~0.5 dag)
- Aggreger presence-data per time
- Send som del av heartbeat payload
- Ny tabell: `screen_presence_stats` i Supabase

### Steg 5: Auto-kalibrering (~1 dag)
- Kalibreringsfase ved første oppstart
- Rekalibrering fra admin-meny
- Config-sync via heartbeat

**Total: ~5 dager + ~150 kr/skjerm i hardware**

---

## Fremtid: Kamera-lag (fase 3)

Når presence-infrastrukturen er validert med ToF/mmWave, kan kamera legges til:
- Demografisk estimering (alder/kjønn) — on-device ML, ingen bilder lagres
- Krever DPIA, informasjonsplakater, og juridisk vurdering
- Se egen analyse i samtalehistorikk (2026-03-30)
- Enormt verdifullt for importør-dashboards

Presence-eventen og trigger-plattformen er den samme — kamera er bare en rikere sensor.
