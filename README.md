# 🔗 PeerLink — Secure Hybrid File Sharing

PeerLink is a secure file sharing system with a **hybrid architecture** supporting three transfer modes: **S3 Relay** for asynchronous cloud-based sharing, **WebSocket Relay** for real-time P2P streaming (zero server storage), and **Socket P2P** for direct TCP transfers. Built with Java (backend) and Next.js (frontend).

![Java](https://img.shields.io/badge/Java-17-orange?style=flat)
![NextJS](https://img.shields.io/badge/Next.js-14-black?style=flat)
![Maven](https://img.shields.io/badge/Maven-3.9-red?style=flat)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat)

---

## ✨ Features

- **⚡ WebSocket P2P Relay**: Real-time file streaming — file passes through server memory, never touches disk
- **☁️ S3 Cloud Relay**: Upload once, download anytime — supports asynchronous sharing
- **🔐 PIN-Based Access**: 12-character cryptographically secure tokens (72-bit entropy)
- **📡 Dual-Port Architecture**: HTTP API (`:8080`) + WebSocket relay (`:8081`)
- **🛡️ Defense-in-Depth Security**: Rate limiting, file validation, filename sanitization, path traversal prevention
- **📁 Streaming Architecture**: Constant memory usage regardless of file size (8KB–64KB buffers)
- **🔒 Thread-Safe**: `ConcurrentHashMap`, `LinkedBlockingQueue`, atomic operations
- **🎨 Modern UI**: Mode selection toggle, real-time transfer progress, responsive design

---

## 🏗️ Architecture

### Three Transfer Modes

| Mode | User Selects | How It Works | File Stored On |
|------|-------------|-------------|----------------|
| **WebSocket Relay** | ⚡ Share Now | File streams from sender → server (memory) → receiver in real-time | **Nowhere** (zero disk) |
| **S3 Relay** | ☁️ Upload & Share Later | File uploaded to AWS S3, downloaded later via pre-signed URL | AWS S3 |
| **Socket P2P** | CLI: `X-Transfer-Mode: socket` | File saved to temp dir, served via ephemeral TCP port | Local disk |

### System Diagram

```
┌──────────────────────────────────────────────────────────────┐
│                     FRONTEND (Port 3000)                      │
│  Next.js + React + TypeScript                                 │
│                                                               │
│  ⚡ Share Now          ☁️ Upload & Share Later                │
│  POST /register        POST /upload (multipart)               │
│  + WebSocket conn      + axios progress tracking              │
└──────────────┬─────────────────┬──────────────────────────────┘
               │                 │
               ▼                 ▼
┌──────────────────────────────────────────────────────────────┐
│              BACKEND (HTTP :8080 + WS :8081)                  │
│  Java 17 + com.sun.net.httpserver + org.java_websocket        │
│                                                               │
│  RegisterHandler    UploadHandler    DownloadHandler           │
│  POST /register     POST /upload     GET /download            │
│  (metadata only)    (multipart/S3)   (routes by mode)         │
│       │                  │                 │                   │
│       ▼                  ▼                 ▼                   │
│  ┌─────────────── FileSharer (Service) ──────────────────┐    │
│  │  Token generation (SecureRandom)                       │    │
│  │  Mode routing: S3_RELAY / WEBSOCKET_RELAY / SOCKET_P2P │    │
│  │  Cleanup after download (mode-aware)                   │    │
│  └────────┬──────────────┬──────────────┬────────────────┘    │
│           │              │              │                      │
│     S3Service      RelayServer     ServerSocket                │
│     (AWS SDK)      (WS :8081)      (ephemeral)                │
│                    BlockingQueue                               │
│                    relay bridge                                │
└───────────────────────────────────────────────────────────────┘
```

### WebSocket Relay Flow

```
Sender                        Server                       Receiver
  │                              │                              │
  ├─ POST /register ────────────►│ Creates token+RelaySession   │
  │◄─ {token:"aBcDeFgHiJkL"} ──┤                              │
  │                              │                              │
  ├─ WebSocket connect ─────────►│ Registers WS ↔ session      │
  │  ws://server:8081/relay      │                              │
  │  ?token=aBcDeFgHiJkL         │                              │
  │                              │                              │
  │  [Sender shares PIN]         │                              │
  │                              │◄── GET /download?token=... ──┤
  │                              │                              │
  │◄─ {type:"SEND_FILE"} ──────┤                              │
  │                              │                              │
  ├─ Binary WS frames ─────────►│── BlockingQueue ────────────►│
  │  (64KB chunks)               │   poll() → HTTP response     │
  │                              │                              │
  ├─ {TRANSFER_COMPLETE} ──────►│── END_SENTINEL ─────────────►│
  │                              │   Cleanup                    │ ✅ Done
```

---

## 🛠️ Technology Stack

### Backend
- **Java 17** — `com.sun.net.httpserver` for HTTP, no framework (hand-rolled routing, CORS, multipart parsing)
- **org.java-websocket** — WebSocket server for real-time relay
- **AWS SDK v2** — S3 file storage with server-side encryption (AES-256)
- **Maven** — Build automation with shade plugin for fat JAR

### Frontend
- **Next.js 14** + **TypeScript** — React framework
- **Axios** — HTTP client with upload progress tracking
- **WebSocket API** — Browser-native WS for real-time file streaming
- **Framer Motion** — Animations
- **Lucide/React Icons** — UI icons

---

## 🔒 Security

| Layer | Protection | Implementation |
|-------|-----------|----------------|
| **Rate Limiting** | 10 req/min per IP | `FixedWindowRateLimiter` with `ConcurrentHashMap.compute()` |
| **Token Security** | 72-bit entropy, unguessable | `SecureRandom`, 12-char from 64-char alphabet |
| **File Validation** | Whitelist extensions + MIME check | `.txt .pdf .jpg .png .gif .zip .doc .docx .csv` |
| **Size Limits** | 500MB max, streaming enforcement | Checked at header level + during streaming |
| **Path Traversal** | `Path.normalize()` + base dir check | `normalizeAndValidatePath()` in FileSharer |
| **Filename Sanitization** | Strips `../`, `"`, `;`, CR/LF | `HeaderUtils.sanitizeFilename()` |
| **CORS** | Cross-origin browser requests | Manual headers on every handler |
| **WS Auth** | Token validated on connect | `RelayServer.onOpen()` validates token |

---

## 🚀 Getting Started

### Prerequisites
- Java 17+
- Node.js 18+ and npm
- Maven 3.9+
- AWS credentials (optional — WebSocket relay works without S3)

### Installation

```bash
# Clone
git clone https://github.com/Techtronics21/P2P-File-Share.git
cd P2P-File-Share

# Build backend
mvn clean package -DskipTests

# Install frontend
cd ui && npm install && cd ..
```

### Running

**Terminal 1 — Backend:**
```bash
java -jar target/p2p-1.0-SNAPSHOT-shaded.jar
# 🚀 API server started on port 8080
# 🔌 WebSocket relay on port 8081
```

**Terminal 2 — Frontend:**
```bash
cd ui && npm run dev
# ▲ Next.js on http://localhost:3000
```

### LAN Access (from other devices)

Update `ui/.env.local`:
```
NEXT_PUBLIC_API_URL=http://YOUR_IP:8080
NEXT_PUBLIC_WS_URL=ws://YOUR_IP:8081
```
Then restart the frontend. Others can access at `http://YOUR_IP:3000`.

---

## 📁 Project Structure

```
PeerLink/
├── src/main/java/org/arnavthakur/
│   ├── App.java                        # Entry point
│   ├── controller/
│   │   └── FileController.java         # HTTP + WS server startup, route registration
│   ├── handler/
│   │   ├── UploadHandler.java          # POST /upload (multipart, S3 mode)
│   │   ├── DownloadHandler.java        # GET /download (routes by transfer mode)
│   │   ├── RegisterHandler.java        # POST /register (WebSocket relay metadata)
│   │   ├── RelayServer.java            # WebSocket server (port 8081, binary relay)
│   │   ├── HealthHandler.java          # GET /health (monitoring)
│   │   └── CORSHandler.java            # CORS + 404 fallback
│   ├── service/
│   │   ├── FileSharer.java             # Core service: tokens, modes, cleanup
│   │   ├── RelaySession.java           # WS relay session (BlockingQueue bridge)
│   │   └── S3Service.java              # AWS S3 operations (upload, download, delete)
│   └── utils/
│       ├── StreamingMultipartParser.java # Streaming multipart parser (constant memory)
│       ├── FixedWindowRateLimiter.java  # Per-IP rate limiter (ConcurrentHashMap)
│       ├── HeaderUtils.java            # Filename sanitization, Content-Disposition
│       └── UploadUtils.java            # Ephemeral port generation
├── ui/
│   ├── src/
│   │   ├── app/
│   │   │   └── page.tsx                # Main page (mode toggle, WS logic, S3 upload)
│   │   └── components/
│   │       ├── FileUpload.tsx           # Drag-and-drop file selector
│   │       ├── FileDownload.tsx         # PIN input + download trigger
│   │       └── InviteCode.tsx           # PIN display + copy
│   └── .env.local                       # API + WS URLs
├── pom.xml                              # Maven config (shade plugin, dependencies)
└── README.md
```

---

## 📡 API Reference

### `POST /register` — Register a WebSocket relay share
```json
// Request
{ "filename": "photo.jpg", "size": 5242880, "contentType": "image/jpeg" }

// Response
{ "token": "aBcDeFgHiJkL" }
```

### `POST /upload` — Upload file to S3
```
Content-Type: multipart/form-data
Body: file=@photo.jpg

Response: { "token": "aBcDeFgHiJkL" }
```

### `GET /download?token={PIN}` — Download file
Routes automatically based on transfer mode:
- **WEBSOCKET_RELAY** → Signals uploader's WS, relays binary frames to response
- **S3_RELAY** → Streams from S3 with Content-Disposition header
- **SOCKET_P2P** → Streams from local disk

### `ws://server:8081/relay?token={PIN}` — WebSocket relay
Protocol:
- Server → Client: `{"type":"REGISTERED"}`, `{"type":"SEND_FILE"}`
- Client → Server: Binary frames (64KB file chunks)
- Client → Server: `{"type":"TRANSFER_COMPLETE"}`

---

## 🧠 Key Design Decisions

| Decision | Why | Alternative |
|----------|-----|-------------|
| `com.sun.net.httpserver` over Spring Boot | Learn what frameworks abstract away | Spring Boot, Netty, Micronaut |
| WebSocket relay over raw TCP sockets | Browsers can't open TCP; WS passes through firewalls | WebRTC DataChannels (true P2P) |
| `LinkedBlockingQueue` for relay bridge | Thread-safe producer-consumer with backpressure | `SynchronousQueue`, NIO Pipe |
| `SecureRandom` over `Random` | Tokens must be unguessable (72-bit entropy) | UUID, HMAC tokens |
| Separate WS port (8081) | `HttpServer` doesn't support WS upgrade | Implement WS handshake manually |
| Custom multipart parser | Streaming (constant memory) vs library (loads all) | Apache Commons FileUpload |

---

## Author

**Arnav Thakur**
- GitHub: [@Techtronics21](https://github.com/Techtronics21)
- LinkedIn: [Arnav Thakur](https://www.linkedin.com/in/arnav-thakur-788700189/)
