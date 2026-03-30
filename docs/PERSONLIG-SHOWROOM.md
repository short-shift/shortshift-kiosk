# Showroom Selger-verktøy — Skjermen som salgsassistent

## Konsept

Selgeren aktiverer skjermen som et presentasjons- og salgsverktøy. Kunden trenger ikke lære noe nytt — selgeren styrer, kunden ser. Når de er ferdige trykker selger "Send til kunden" → kunden scanner QR → alt ligger på telefonen.

---

## To moduser per skjerm

### 1. STANDBY (default)
- Kampanjebilder, store tall, eye-catching
- Roterer mellom predefinerte visninger
- Valgfritt tillegg: presence-sensor kan vekke fra dvale og vise CTA

### 2. SELGER-MODUS (selger aktivert)
- **Trigger:** Selger taster skjult gesture + personlig kode
- Skjermen blir et presentasjons- og salgsverktøy
- Selger styrer: sammenligning, totaløkonomi, innbytte, tilbud
- Kunden ser — trenger ikke forstå systemet
- "Send til kunden" → QR → alt på kundens telefon
- **Tilbake til STANDBY:** Selger avslutter / timeout

---

## Brukerreise

```
1. Kunde går inn i showroom
   └─ Skjermer viser kampanjer (STANDBY)
   └─ Kunden ser på biler, trykker på skjermer (som i dag)

2. Selger ser på mobilen (SalesZilla live showroom)
   └─ "Skjerm 2 ved XC40: noen trykker, aktiv 3 min"
   └─ Eller bare: selger ser kunden i butikken med øynene

3. Selger går bort: "Hei, skal jeg vise deg litt mer?"
   └─ Taster skjult gesture + personlig kode → SELGER-MODUS
   └─ Skjermen blir et salgsverktøy

4. Selger presenterer
   └─ Sammenligning: XC40 vs XC60 side om side
   └─ Innbyttepris: "Hva kjører du i dag?" → reg.nr → pris live
   └─ Totaløkonomi med innbytte trukket fra
   └─ Ekstrautstyr, serviceavtale, forsikring — priseffekt i sanntid
   └─ "Vis på storskjerm" → konfigurasjonen i stort format

5. Avslutt: "Vil du ha dette på telefonen?"
   └─ Selger trykker "Send til kunden" → QR vises
   └─ Kunden scanner → alt på telefon (biler, priser, tilbud)
   └─ Lav terskel — kunden scanner bare for å motta

6. Kunden drar hjem
   └─ Skjermen tilbake til STANDBY
   └─ Kunden har alt på telefon: deler med partner, justerer
   └─ Selger følger opp neste dag med full kontekst
```

---

## Brukerreise: Selger

```
1. Selger sjekker mobilen (SalesZilla live showroom)
   └─ Ser: "Kari ved XC40, innbytte aktiv, 3 min"
   └─ Ser: "Ola ved EX30, anonym, sammenligner, 1 min"
   └─ Ser: "Skjerm 1, 3, 5 — ledig"

2. Velger: "Kari er varm, jeg går bort"
   └─ Trykker 3x nede-høyre på skjermen → numerisk felt
   └─ Taster sin personlige selgerkode (f.eks. 4821)
   └─ Koblet til Karis sesjon

3. Skjermen blir felles verktøy
   └─ Selger ser kundens reise: hvilke biler, innbytte, finansiering
   └─ Predefinerte actions: "Sammenlign XC40 vs XC60"
   └─ Quick-actions: "Legg til tilhenger", "Vis servicepriser"
   └─ "Vis på storskjerm" → konfiguratoren i stort format

4. Close: "Send tilbud til kunden"
   └─ Tilbudet ligger på kundens telefon
   └─ Selger får XP i SalesZilla
```

---

## Multi-sesjon: Flere kunder samtidig

Showroomet tilhører aldri én kunde. Hver skjerm har sin egen tilstand:

```
Skjerm 1 (inngang):      STANDBY — kampanje
Skjerm 2 (XC40):         PERSONLIG — Kari, innbytte aktiv
Skjerm 3 (XC60):         STANDBY — kampanje
Skjerm 4 (EX30):         PERSONLIG — Ola, sammenligner
Skjerm 5 (fellesskjerm): PERSONLIG — Ahmed, browser utvalg
Storskjerm:               Cast — viser Karis konfig (styrt fra skjerm 2)
```

Selger ser alle aktive sesjoner på mobilen og velger hvem hun vil hjelpe.

---

## Cast til storskjerm

Storskjermen er en vanlig Nexus-skjerm i en spesiell rolle: **dum mottaker.**

- Selger/kunde trykker "Vis på storskjerm" → innhold castes
- All styring fra liten skjerm eller mobil
- Storskjermen bare renderer — som en Chromecast
- Konfigurator live: velg farge/utstyr på liten skjerm → oppdateres i stort format
- Familie/par: én styrer, alle ser — sofagruppen i showroom

```
Liten skjerm / mobil                    Storskjerm
───────────────────                     ──────────
session:cast { target, content }  →     Skifter til cast-modus
updateSessionState(color: blue)   →     Fargen endres live
session:endcast                   →     Tilbake til standby
```

---

## Skjerm-til-skjerm kommunikasjon

### Supabase Realtime channels

Alle skjermene i ett showroom abonnerer på samme kanal:

```
channel: showroom:{dealer_id}

Events:
  screen:wake
    → { target_screen_id, context: { car_id, session_id, message } }
    → Mottaker-skjermen skifter til invitasjonsmodus

  session:claim
    → { screen_id, customer_token, session_id }
    → Skjermen låses til kundens sesjon

  session:join
    → { screen_id, seller_id, session_id }
    → Selger entrer sesjonen

  session:transfer
    → { from_screen_id, to_screen_id, session_id }
    → Kontekst flyttes fra én skjerm til en annen

  session:release
    → { screen_id, session_id }
    → Skjermen tilbake til standby

  screen:navigate
    → { screen_id, route, params }
    → Fjernstyr hva en skjerm viser (fra selger-app eller annen skjerm)
```

### Sesjonsobjekt (Supabase)

```sql
CREATE TABLE showroom_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  dealer_id UUID NOT NULL REFERENCES dealers(id),
  customer_token TEXT,              -- anonym token fra QR-scan
  customer_id UUID REFERENCES customers(id),  -- etter Vipps-innlogging
  seller_id UUID,                   -- når selger kobler seg på
  active_screen_id UUID REFERENCES screens(id),
  state JSONB NOT NULL DEFAULT '{}',  -- { selected_cars, trade_in, financing }
  status TEXT DEFAULT 'active',     -- active / paused / completed
  started_at TIMESTAMPTZ DEFAULT NOW(),
  last_activity_at TIMESTAMPTZ DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  metadata JSONB                    -- dwell times, screens visited, etc.
);
```

### Sesjonsstate (JSONB)

```json
{
  "selected_cars": ["volvo-xc40-abc123"],
  "compared_cars": ["volvo-xc40-abc123", "volvo-xc60-def456"],
  "trade_in": {
    "reg_nr": "AB12345",
    "estimated_value": 185000,
    "source": "innbyttepris"
  },
  "financing": {
    "monthly_payment": 4500,
    "down_payment": 50000,
    "term_months": 60
  },
  "screens_visited": ["screen-1", "screen-3", "screen-7"],
  "total_dwell_sec": 340
}
```

---

## ShowroomNexus bridge — nye metoder

```kotlin
// Sesjonshåndtering
@JavascriptInterface
fun claimScreen(sessionToken: String): String
    // Kunde tar over skjermen
    // Sender session:claim til Realtime
    // Returnerer session state JSON

@JavascriptInterface
fun releaseScreen()
    // Tilbake til standby
    // Sender session:release

@JavascriptInterface
fun getActiveSession(): String?
    // Returnerer current session JSON eller null

// Skjerm-til-skjerm
@JavascriptInterface
fun wakeScreen(targetScreenId: String, carId: String)
    // "Vis meg denne bilen" → target lyser opp
    // Sender screen:wake med kontekst

@JavascriptInterface
fun transferSession(targetScreenId: String)
    // Flytt hele sesjonen til en annen skjerm
    // Sender session:transfer

// Selger-integrasjon
@JavascriptInterface
fun joinSession(sellerId: String)
    // Selger entrer sesjonen
    // Sender session:join
    // Returnerer utvidet session state

// Sesjonstate
@JavascriptInterface
fun updateSessionState(stateJson: String)
    // Oppdater valgte biler, innbytte, finansiering
    // Synces til alle enheter i sesjonen via Realtime
```

---

## Selger-aktivering: Skjult kode, ingen hardware

Selger aktiverer skjermen uten ekstra hardware:

1. Tapper skjult gesture (f.eks. 3x nede-høyre) → numerisk felt vises
2. Taster sin personlige selgerkode (fra SalesZilla, f.eks. 4821)
3. Skjermen skifter til selger-modus med alle verktøy tilgjengelig

Samme mønster som admin-menyen. Ingen NFC, ingen badges, ingen ekstra kostnad.

---

## "Send til kunde" — eneste kundeinteraksjon

Kunden trenger aldri styre systemet. Den eneste handlingen er å **motta**:

1. Selger trykker "Send til kunden" → QR-kode vises på skjermen
2. Kunden scanner med telefonen → nettleser åpnes med alt: biler, priser, tilbud, finansiering
3. Kunden kan dele med partner, justere, eller bare ha som referanse

Lav terskel. Kjent konsept (som å få kvittering på e-post).

---

## Predefinerte selger-verktøy

Når selger er koblet til sesjonen, viser skjermen quick-actions:

| Handling | Hva skjermen viser |
|---|---|
| **Sammenlign** | To biler side om side — pris, utstyr, mål, drivstoff |
| **Totaløkonomi** | Månedspris med innbytte, forsikring, service inkludert |
| **Tilhengerfeste** | "Denne bilen trekker 2100 kg — her er tilhengerpriser" |
| **Historikk** | Servicerapport, km-stand, antall eiere (fra innbyttepris) |
| **Tilbud** | Generer tilbud → sendes til kundens telefon |
| **Prøvekjøring** | Booker prøvekjøring direkte fra skjermen |
| **Ekstern vurdering** | Starter eksternkjøper-auksjon på kundens innbytte (live!) |
| **Vis på storskjerm** | Cast innhold til stor showroom-skjerm |

Alle handlinger er predefinerte, ikke fritekst. Selger velger fra en meny.

---

## Hva dette kobler sammen

```
┌─────────────────────────────────────────────────────────────┐
│                  Nexus Personlig Showroom                    │
│                                                             │
│  finn-bruktbil ─────── bildata, bilder, priser              │
│  Innbyttepris ──────── verdivurdering, defektprediksjon     │
│  Konfigurator ──────── nybil-config, opsjoner               │
│  SalesZilla ────────── selger-varsel, XP, gamification      │
│  Presence-sensor ───── trigger modusbytte                    │
│  Supabase Realtime ─── skjerm-sync, sesjonsdeling           │
│  Bip (AI-agent) ────── assistanse ved skjermen (fremtid)    │
│                                                             │
│  = Alt som i dag er frakoblet, bundet sammen                │
│    gjennom den fysiske skjermen                             │
└─────────────────────────────────────────────────────────────┘
```

---

## Personvern

- **Selger-modus:** Ingen kundedata lagres — sesjonen er selgerens verktøy
- **"Send til kunde":** Anonym lenke, ingen innlogging påkrevd
- **Sesjonsdata:** Slettes automatisk etter 30 dager

---

## Kanskje senere: Kunde-selvbetjening

> Arkivert — interessante ideer som vurderes når selger-verktøyet er validert.
> Forutsetning: data som viser at kunder faktisk vil og forstår dette.

- **Kunde tar over skjerm:** Scanner QR → Vipps/BankID → skjermen blir personlig
- **Sesjon følger kunden:** Fra skjerm til skjerm via Supabase Realtime
- **"Vis meg denne bilen":** Target-skjerm lyser opp med navigasjon i showroom
- **Vipps Login:** Persistent profil → "Velkommen tilbake, du så på XC40 sist"
- **Kunde styrer storskjerm:** Fra mobil, som Chromecast
- **Full selvbetjening:** Innbytte, finansiering, tilbud — uten å snakke med selger

Disse ideene er kraftige, men krever at kunden forstår et nytt konsept. Start med selger-verktøyet, observer hvordan kunder reagerer, og aktiver selvbetjening gradvis basert på reell adferd.
