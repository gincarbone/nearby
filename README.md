# NearBy

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="120" alt="NearBy Logo"/>
</p>

<p align="center">
  <strong>Messaggistica P2P senza Internet</strong><br>
  Connettiti con le persone intorno a te, ovunque tu sia.
</p>

---

## Panoramica

**NearBy** è un'applicazione di messaggistica peer-to-peer che funziona **senza connessione Internet**. Utilizza tecnologie di prossimità (Bluetooth, WiFi Direct) per creare una rete mesh tra dispositivi vicini, permettendo la comunicazione anche in assenza di infrastruttura di rete.

### Scenari d'Uso

- **Festival e concerti** - Comunica con gli amici nella folla senza rete mobile sovraccarica
- **Viaggi e escursioni** - Resta in contatto in zone senza copertura
- **Emergenze** - Comunicazione quando le reti tradizionali sono fuori servizio
- **Privacy** - Messaggi che non passano da server esterni
- **Edifici e sotterranei** - Funziona dove il segnale non arriva

---

## Vantaggi Principali

### 🌐 Zero Dipendenza da Internet
- Funziona completamente offline
- Nessun server centrale
- Nessun costo di dati mobili

### 🔒 Privacy by Design
- Crittografia end-to-end (AES-256-GCM)
- I messaggi non transitano su server esterni
- Nessuna registrazione con email o telefono
- I dati restano sul dispositivo

### 🕸️ Rete Mesh Intelligente
- Ogni dispositivo è un nodo della rete
- I messaggi possono "saltare" tra dispositivi
- Maggiore è il numero di utenti, migliore è la copertura

### 🔋 Ottimizzato per la Batteria
- Discovery adattivo basato su contesto
- Heartbeat intelligente invece di scanning continuo
- Modalità risparmio energetico automatica

### 🌍 Multilingua
- Italiano
- English

---

## Architettura Tecnica

### Stack Tecnologico

| Componente | Tecnologia |
|------------|------------|
| Linguaggio | Kotlin |
| UI Framework | Jetpack Compose |
| Connettività | Google Nearby Connections API |
| Database | Room (SQLite) |
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |
| Crittografia | Android Keystore + AES-256-GCM |

### Google Nearby Connections

L'app utilizza la [Nearby Connections API](https://developers.google.com/nearby/connections/overview) di Google con strategia **P2P_CLUSTER**, che permette:

- Connessioni multiple simultanee
- Switching automatico tra Bluetooth, BLE, WiFi Direct e WiFi LAN
- Throughput ottimizzato in base alla distanza

```
┌─────────────┐         ┌─────────────┐
│  Device A   │◄───────►│  Device B   │
└─────────────┘         └─────────────┘
       ▲                       ▲
       │                       │
       ▼                       ▼
┌─────────────┐         ┌─────────────┐
│  Device C   │◄───────►│  Device D   │
└─────────────┘         └─────────────┘
```

---

## Rete Mesh

### Concetto Base

Ogni utente NearBy funziona simultaneamente come:

1. **USER** - Invia e riceve i propri messaggi
2. **RELAY** - Inoltra messaggi di altri utenti
3. **STORE & FORWARD** - Memorizza messaggi per utenti offline

### Topologia Distribuita

```
┌────────────────────────────────────────────────────────────┐
│                    MESH NETWORK                             │
├────────────────────────────────────────────────────────────┤
│                                                            │
│    [Alice]───────[Bob]───────[Carol]                       │
│        │           │            │                          │
│        │           │            │                          │
│    [Dave]────────[Eve]────────[Frank]                      │
│                    │                                       │
│                    │                                       │
│                 [Grace]                                    │
│                                                            │
│  Se Alice vuole inviare a Grace:                          │
│  Alice → Bob → Eve → Grace                                 │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### Routing Table

Ogni nodo mantiene una **tabella di routing distribuita**:

```kotlin
data class RouteEntry(
    val destinationId: String,    // Nodo destinazione
    val nextHop: String,          // Prossimo salto
    val hopCount: Int,            // Numero di salti
    val lastUpdated: Long         // Timestamp
)
```

La tabella viene aggiornata tramite **TOPOLOGY_ANNOUNCE** periodici:

```
Ogni 30 secondi:
  Nodo → broadcast ai vicini:
    - Il mio ID
    - I miei vicini diretti
    - Le mie capacità (storage, uptime)
```

### Store & Forward

Quando un destinatario è **offline**, il messaggio viene memorizzato:

```
┌─────────────────────────────────────────────────────┐
│              STORE & FORWARD FLOW                    │
├─────────────────────────────────────────────────────┤
│                                                     │
│  1. Alice invia messaggio a Bob (offline)           │
│                    │                                │
│                    ▼                                │
│  2. Nessuna route verso Bob                         │
│                    │                                │
│                    ▼                                │
│  3. Messaggio memorizzato su nodo con capacità      │
│     (preferenza: WiFi + in carica)                  │
│                    │                                │
│                    ▼                                │
│  4. Bob torna online, si connette alla mesh         │
│                    │                                │
│                    ▼                                │
│  5. Messaggio consegnato automaticamente            │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**Retention Policy** (basata su contesto dispositivo):

| Condizione | Retention | Storage |
|------------|-----------|---------|
| WiFi + In carica | 7 giorni | 100 MB |
| Solo WiFi | 36 ore | 50 MB |
| Batteria > 30% | 18 ore | 20 MB |
| Batteria < 30% | Disabilitato | - |

---

## Heartbeat System

### Problema
Discovery continuo = consumo batteria eccessivo

### Soluzione
**Heartbeat periodico**: brevi burst di discovery a intervalli adattivi

```
┌─────────────────────────────────────────────────────┐
│              HEARTBEAT CYCLE                         │
├─────────────────────────────────────────────────────┤
│                                                     │
│  ┌─────────┐    ┌─────────────┐    ┌─────────┐     │
│  │  IDLE   │───►│  DISCOVERY  │───►│  SYNC   │     │
│  └─────────┘    │  (6-15 sec) │    └─────────┘     │
│       ▲         └─────────────┘          │         │
│       │                                  │         │
│       └──────────────────────────────────┘         │
│              Wait (2-10 min)                       │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### Configurazione Adattiva

| Contesto | Discovery | Intervallo | % Attivo |
|----------|-----------|------------|----------|
| WiFi + Charging | 15 sec | 2 min | 12.5% |
| WiFi | 6 sec | 2 min | 5% |
| Battery > 50% | 6 sec | 5 min | 2% |
| Battery 30-50% | 6 sec | 10 min | 1% |
| Battery < 30% | Disabilitato | - | 0% |

---

## Protocollo Messaggi

### Tipi di Messaggio

```kotlin
// Handshake (connessione)
HANDSHAKE_INIT     = 0x01  // Richiesta connessione
HANDSHAKE_RESPONSE = 0x02  // Risposta

// Messaggi utente
PLAIN_MESSAGE      = 0x10  // Messaggio in chiaro
ENCRYPTED_MESSAGE  = 0x11  // Messaggio cifrato

// Ricevute
DELIVERY_RECEIPT   = 0x20  // Consegnato
READ_RECEIPT       = 0x21  // Letto

// Mesh protocol
TOPOLOGY_ANNOUNCE  = 0x30  // Annuncio topologia
ROUTED_MESSAGE     = 0x31  // Messaggio instradato
ROUTE_ACK          = 0x32  // Conferma routing
STORE_CONFIRM      = 0x33  // Conferma storage
```

### Formato Messaggio

```
┌────────────────────────────────────────┐
│  HEADER (1 byte)                       │
│  ├── Type (4 bit)                      │
│  └── Flags (4 bit)                     │
├────────────────────────────────────────┤
│  MESSAGE ID (16 bytes - UUID)          │
├────────────────────────────────────────┤
│  SENDER ID (16 bytes - UUID)           │
├────────────────────────────────────────┤
│  TIMESTAMP (8 bytes - Long)            │
├────────────────────────────────────────┤
│  PAYLOAD LENGTH (4 bytes - Int)        │
├────────────────────────────────────────┤
│  PAYLOAD (variable)                    │
└────────────────────────────────────────┘
```

---

## Sicurezza

### Crittografia End-to-End

```
┌─────────────────────────────────────────────────────┐
│              E2E ENCRYPTION                          │
├─────────────────────────────────────────────────────┤
│                                                     │
│  1. Ogni utente genera coppia chiavi EC (P-256)     │
│     - Chiave privata in Android Keystore            │
│     - Chiave pubblica condivisa durante handshake   │
│                                                     │
│  2. Scambio chiavi (ECDH)                           │
│     SharedSecret = ECDH(myPrivate, peerPublic)      │
│                                                     │
│  3. Derivazione chiave simmetrica (HKDF)            │
│     AESKey = HKDF(SharedSecret, salt, info)         │
│                                                     │
│  4. Cifratura messaggi (AES-256-GCM)                │
│     Ciphertext = AES-GCM(AESKey, nonce, plaintext)  │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### Fingerprint Verifica

Ogni utente ha un **fingerprint** derivato dalla chiave pubblica:

```
Esempio: "🔵🟢🔴🟡 🟣🟠⚪🟤"

Verifica: confronta il fingerprint mostrato sui due dispositivi
```

---

## Struttura Progetto

```
app/src/main/java/com/nearby/
├── core/
│   ├── crypto/          # Crittografia (CryptoManager)
│   ├── di/              # Dependency Injection (Hilt modules)
│   └── util/            # Utility (UUID, Date, Locale)
│
├── data/
│   ├── local/
│   │   ├── dao/         # Data Access Objects (Room)
│   │   ├── db/          # Database configuration
│   │   └── entity/      # Database entities
│   │
│   ├── nearby/
│   │   ├── mesh/        # Mesh networking
│   │   │   ├── HeartbeatManager.kt
│   │   │   ├── MeshManager.kt
│   │   │   ├── MeshProtocol.kt
│   │   │   └── RoutingTable.kt
│   │   │
│   │   ├── protocol/    # Message protocol
│   │   ├── MessageHandler.kt
│   │   ├── NearbyManager.kt
│   │   └── NearbyService.kt
│   │
│   └── repository/      # Repository implementations
│
├── domain/
│   ├── model/           # Domain models
│   └── repository/      # Repository interfaces
│
└── presentation/
    ├── components/      # Reusable UI components
    ├── navigation/      # Navigation graph
    ├── screens/         # Screen composables
    │   ├── chat/
    │   ├── connected/
    │   ├── discover/
    │   ├── home/
    │   ├── onboarding/
    │   └── settings/
    └── theme/           # Material Theme
```

---

## Requisiti

- Android 8.0 (API 26) o superiore
- Bluetooth attivo
- Permessi posizione (richiesti da Nearby Connections)
- WiFi attivo (opzionale, migliora le performance)

---

## Permessi

| Permesso | Motivo |
|----------|--------|
| `ACCESS_FINE_LOCATION` | Richiesto da Nearby Connections |
| `BLUETOOTH_*` | Comunicazione Bluetooth |
| `NEARBY_WIFI_DEVICES` | WiFi Direct (Android 13+) |
| `POST_NOTIFICATIONS` | Notifiche messaggi |

---

## Build

```bash
# Clone
git clone https://github.com/user/nearby.git
cd nearby

# Build debug
./gradlew assembleDebug

# Build release
./gradlew assembleRelease

# Run tests
./gradlew test
```

---

## Roadmap

- [ ] Messaggi vocali
- [ ] Condivisione file
- [ ] Gruppi di chat
- [ ] Messaggi che si autodistruggono
- [ ] Backup crittografato
- [ ] Widget home screen

---

## Contribuire

Le pull request sono benvenute! Per modifiche importanti, apri prima una issue per discutere cosa vorresti cambiare.

---

## Licenza

[MIT](LICENSE)

---

<p align="center">
  Made with ❤️ for offline communication
</p>
