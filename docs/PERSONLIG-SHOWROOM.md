# Personlig Showroom — Skjermen blir kundens

## Konsept

En kunde går inn i showroom. Skjermene viser kampanjer. Kunden stopper ved en bil, skjermen våkner. Kunden scanner QR — skjermen blir personlig. Selger kobler seg på. Kunden går videre til neste bil — konteksten følger med. Kunden drar hjem — alt ligger på telefonen.

---

## Tre moduser per skjerm

### 1. STANDBY (ingen foran)
- Kampanjebilder, store tall, eye-catching
- Roterer mellom predefinerte visninger
- Presence-sensor: zone = NONE

### 2. SHOWROOM (noen foran)
- Vanlig bruktbil/nybil-visning for bilen skjermen står ved
- Presence-sensor trigger: zone = INTERESTED
- Etter 5 sek uten touch: "Trykk for å utforske" CTA

### 3. PERSONLIG (kunde identifisert)
- Kundens sesjon — tatt over via QR/NFC/Vipps
- Viser kundens kontekst: sette biler, innbyttepris, finansiering
- Selger kan entre sesjonen
- Alt synces til kundens telefon

---

## Brukerreise: Kunden

```
1. Kunde går inn i showroom
   └─ Skjermer viser kampanjer (STANDBY)

2. Stopper ved fellesskjerm
   └─ Skjermen våkner, viser hele utvalget (SHOWROOM)
   └─ Kunden browser, finner Volvo XC40

3. Trykker "Vis meg denne bilen"
   └─ Skjermen ved XC40 lyser opp: "Gå til Volvo XC40 →"
   └─ Veivisning i showroom via skjermene

4. Ved XC40-skjermen: scanner QR-kode
   └─ Skjermen blir PERSONLIG
   └─ Viser: bilens detaljer + finansiering + "Har du bil å bytte inn?"
   └─ Kunden taster reg.nr → innbyttepris vises umiddelbart

5. Selger ser varsel i SalesZilla
   └─ "Kunde ved XC40, interessert i innbytte, 3 min dwell time"
   └─ Selger går bort, parrer seg med sesjonen (NFC badge)
   └─ Skjermen blir felles verktøy

6. Selger og kunde sammen
   └─ Sammenligning: XC40 vs XC60 side om side
   └─ Totaløkonomi med kundens innbytte trukket fra
   └─ Ekstrautstyr, serviceavtale, forsikring
   └─ "Send til telefonen min" → alt ligger i kundens app

7. Kunden drar hjem
   └─ Skjermen tilbake til STANDBY
   └─ Kunden fortsetter på telefon: justerer finansiering, deler med partner
   └─ Selger følger opp neste dag med full kontekst
```

---

## Brukerreise: Selger

```
1. Morgen: sjekker SalesZilla-dashboard
   └─ Ser hvilke skjermer som har mest trafikk i dag

2. Varsel: "Kunde ved XC40, engasjert 2+ min"
   └─ SalesZilla viser: kunden har sett 3 biler, startet innbyttesjekk
   └─ Selger bestemmer: "Denne er varm, jeg går bort"

3. Ved skjermen: parrer seg med NFC badge
   └─ Skjermen viser selger-kontekst: kundens reise så langt
   └─ Predefinerte sammenligninger: "Vis XC40 vs XC60"
   └─ Quick-actions: "Legg til tilhenger", "Vis servicepriser"

4. Close: "Send tilbud til kunden"
   └─ Tilbudet ligger på kundens telefon
   └─ Selger får XP i SalesZilla for assist
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

## Kundeidentifisering

### Alternativ 1: Anonym QR (MVP)
- Skjermen viser QR med unik session-URL
- Kunden scanner → nettleser åpnes → sesjon opprettet
- Ingen innlogging, ingen persondata
- Begrensning: mister kontekst hvis kunden lukker nettleseren

### Alternativ 2: Vipps Login (fase 2)
- QR-kode trigger Vipps-innlogging
- Full identitet: navn, telefon, e-post
- Kunden kan fortsette hjemmefra med full kontekst
- Kobles til kundeportal-visjonen

### Alternativ 3: ShortShift-app (fase 3)
- Kunden laster ned appen (eller PWA)
- NFC-tap eller BLE proximity for å ta over skjerm
- Persistent profil med alle besøk, preferanser, tilbud
- Pusher tilbud og oppfølging

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

Alle handlinger er predefinerte, ikke fritekst. Selger velger fra en meny. Dette sikrer konsistent opplevelse og hindrer feil.

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

- **Anonym QR:** Ingen persondata lagres. Session-token er engangs.
- **Vipps Login:** GDPR Art. 6(1)(b) — nødvendig for å levere tjenesten kunden ber om
- **Sesjonsdata:** Slettes automatisk etter 30 dager
- **Selger ser aldri persondata** uten at kunden aktivt gir tilgang via QR/Vipps
