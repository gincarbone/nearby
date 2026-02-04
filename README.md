# NearBy

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="120" alt="NearBy Logo"/>
</p>

<p align="center">
  <strong>Offline P2P Messaging</strong><br>
  Connect with people around you, wherever you are.
</p>

---

## Overview

**NearBy** is a peer-to-peer rebel messaging application that works **without an Internet connection**. It uses proximity technologies (Bluetooth, WiFi Direct) to create a mesh network between nearby devices, allowing communication even in the absence of network infrastructure. It democratizes communications and allows users to communicate at zero cost.

### Use Cases

- **Festivals and Concerts** - Communicate with friends in the crowd without overloaded mobile networks
- **Travel and Hiking** - Stay in touch in areas with no coverage
- **Emergencies** - Communication when traditional networks are down
- **Privacy** - Messages that do not pass through external servers
- **Buildings and Undergrounds** - Works where signal does not reach
- **Airplane**During flights you can communicate with other passengers
- **Global thermonuclear war** 🙂 - It works because there would be no more communications services


### Advanced use cases

- **Huge Diffusion Scenario** - With around 1000 devices in a medium-sized city of 50,000 - 100,000 inhabitants, users can communicate with each other(messages, chat, files ) without using any paid services.

---

## Key Benefits

### 🌐 Zero Internet Dependency
- Works completely offline
- No central server
- No mobile data costs

### 🔒 Privacy by Design
- End-to-end encryption (AES-256-GCM)
- Messages do not transit on external servers
- No registration with email or phone
- Data remains on the device

### 🕸️ Smart Mesh Network
- Each device is a network node
- Messages can "hop" between devices
- The more users, the better the coverage

### 🔋 Battery Optimized
- Adaptive discovery based on context
- Smart heartbeat instead of continuous scanning
- Automatic power-saving mode

### 🌍 Multilingual
- Italian
- English

---

## Technical Architecture

### Tech Stack

| Component | Technology |
|------------|------------|
| Language | Kotlin |
| UI Framework | Jetpack Compose |
| Connectivity | Google Nearby Connections API |
| Database | Room (SQLite) |
| DI | Hilt |
| Async | Kotlin Coroutines + Flow |
| Encryption | Android Keystore + AES-256-GCM |

### Google Nearby Connections

The app uses Google's [Nearby Connections API](https://developers.google.com/nearby/connections/overview) with **P2P_CLUSTER** strategy, which allows:

- Multiple simultaneous connections
- Automatic switching between Bluetooth, BLE, WiFi Direct, and WiFi LAN
- Throughput optimized based on distance

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

## Mesh Network

### Basic Concept

Each NearBy user functions simultaneously as:

1. **USER** - Sends and receives their own messages
2. **RELAY** - Forwards messages from other users
3. **STORE & FORWARD** - Stores messages for offline users

### Distributed Topology

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
│  If Alice wants to send to Grace:                          │
│  Alice → Bob → Eve → Grace                                 │
│                                                            │
└────────────────────────────────────────────────────────────┘
```

### Routing Table

Each node maintains a **distributed routing table**:

```kotlin
data class RouteEntry(
    val destinationId: String,    // Destination Node
    val nextHop: String,          // Next Hop
    val hopCount: Int,            // Number of Hops
    val lastUpdated: Long         // Timestamp
)
```

The table is updated via periodic **TOPOLOGY_ANNOUNCE**:

```
Every 30 seconds:
  Node → broadcast to neighbors:
    - My ID
    - My direct neighbors
    - My capabilities (storage, uptime)
```

### Store & Forward

When a recipient is **offline**, the message is stored:

```
┌─────────────────────────────────────────────────────┐
│              STORE & FORWARD FLOW                    │
├─────────────────────────────────────────────────────┤
│                                                     │
│  1. Alice sends message to Bob (offline)            │
│                    │                                │
│                    ▼                                │
│  2. No route to Bob                                 │
│                    │                                │
│                    ▼                                │
│  3. Message stored on node with capacity            │
│     (preference: WiFi + charging)                   │
│                    │                                │
│                    ▼                                │
│  4. Bob comes online, connects to mesh              │
│                    │                                │
│                    ▼                                │
│  5. Message delivered automatically                 │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**Retention Policy** (based on device context):

| Condition | Retention | Storage |
|------------|-----------|---------|
| WiFi + Charging | 7 days | 100 MB |
| Only WiFi | 36 hours | 50 MB |
| Battery > 30% | 18 hours | 20 MB |
| Battery < 30% | Disabled | - |

---

## Heartbeat System

### Problem
Continuous discovery = excessive battery consumption

### Solution
**Periodic Heartbeat**: short bursts of discovery at adaptive intervals

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

### Adaptive Configuration

| Context | Discovery | Interval | % Active |
|----------|-----------|------------|----------|
| WiFi + Charging | 15 sec | 2 min | 12.5% |
| WiFi | 6 sec | 2 min | 5% |
| Battery > 50% | 6 sec | 5 min | 2% |
| Battery 30-50% | 6 sec | 10 min | 1% |
| Battery < 30% | Disabled | - | 0% |

---

## Message Protocol

### Message Types

```kotlin
// Handshake (connection)
HANDSHAKE_INIT     = 0x01  // Connection request
HANDSHAKE_RESPONSE = 0x02  // Response

// User messages
PLAIN_MESSAGE      = 0x10  // Plain message
ENCRYPTED_MESSAGE  = 0x11  // Encrypted message

// Receipts
DELIVERY_RECEIPT   = 0x20  // Delivered
READ_RECEIPT       = 0x21  // Read

// Mesh protocol
TOPOLOGY_ANNOUNCE  = 0x30  // Topology announce
ROUTED_MESSAGE     = 0x31  // Routed message
ROUTE_ACK          = 0x32  // Routing confirmation
STORE_CONFIRM      = 0x33  // Storage confirmation
```

### Message Format

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

## Security

### End-to-End Encryption

```
┌─────────────────────────────────────────────────────┐
│              E2E ENCRYPTION                          │
├─────────────────────────────────────────────────────┤
│                                                     │
│  1. Each user generates EC key pair (P-256)         │
│     - Private key in Android Keystore               │
│     - Public key shared during handshake            │
│                                                     │
│  2. Key Exchange (ECDH)                             │
│     SharedSecret = ECDH(myPrivate, peerPublic)      │
│                                                     │
│  3. Symmetric Key Derivation (HKDF)                 │
│     AESKey = HKDF(SharedSecret, salt, info)         │
│                                                     │
│  4. Message Encryption (AES-256-GCM)                │
│     Ciphertext = AES-GCM(AESKey, nonce, plaintext)  │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### Fingerprint Verification

Each user has a **fingerprint** derived from their public key:

```
Example: "🔵🟢🔴🟡 🟣🟠⚪🟤"

Verification: compare the fingerprint shown on both devices
```

---

## Project Structure

```
app/src/main/java/com/nearby/
├── core/
│   ├── crypto/          # Cryptography (CryptoManager)
│   ├── di/              # Dependency Injection (Hilt modules)
│   └── util/            # Utilities (UUID, Date, Locale)
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
│   ├── components/      # Reusable UI components
│   ├── navigation/      # Navigation graph
│   ├── screens/         # Screen composables
│   │   ├── chat/
│   │   ├── connected/
│   │   ├── discover/
│   │   ├── home/
│   │   ├── onboarding/
│   │   └── settings/
│   └── theme/           # Material Theme
```

---

## Requirements

- Android 8.0 (API 26) or higher
- Bluetooth enabled
- Location permissions (required by Nearby Connections)
- WiFi enabled (optional, improves performance)

---

## Permissions

| Permission | Reason |
|------------|--------|
| `ACCESS_FINE_LOCATION` | Required by Nearby Connections |
| `BLUETOOTH_*` | Bluetooth communication |
| `NEARBY_WIFI_DEVICES` | WiFi Direct (Android 13+) |
| `POST_NOTIFICATIONS` | Message notifications |

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

- [ ] Voice messages
- [ ] File sharing
- [ ] Group chats
- [ ] Self-destructing messages
- [ ] Encrypted backup
- [ ] Home screen widget

---

## Contributing

Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change.

---

## License

[MIT](LICENSE)

---

<p align="center">
  Made with ❤️ for offline communication
</p>
