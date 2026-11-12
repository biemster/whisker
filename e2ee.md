# Whisker End-to-End Encryption (E2EE) Specification

## 1. Overview & Architecture

Whisker implements default-on End-to-End Encryption (E2EE) across all ecosystem clients (Home Server, Android Mobile App, CLI Client, MCP Server, Web Visitor Client, and Embed Widget). 

Communication between nodes uses peer-to-peer authenticated key agreement via Elliptic Curve Diffie-Hellman (ECDH) on Curve25519 / X25519, combined with HKDF-SHA256 key derivation and AES-256-GCM symmetric authenticated encryption.

```
+-----------------------------------------------------------------------------------+
|                                 Whisker E2EE                                      |
|                                                                                   |
|  [Sender Node]                                                    [Recipient Node]|
|  1. X25519 Private Key A         ECDH Shared Secret             1. X25519 Priv B |
|  2. Peer Public Key B    ------------------------------------>  2. Peer Pub A     |
|                                         |                                         |
|                                   HKDF-SHA256                                     |
|                             Salt: "whisker-e2ee-v1"                               |
|                          Info: "whisker-msg-aes256-gcm"                           |
|                                         |                                         |
|                                  32-byte AES Key                                  |
|                                         |                                         |
|  Plaintext Payload ---------------> AES-256-GCM ---------------> Decrypted Plain  |
|                               IV[12] + Ciphertext + Tag[16]                       |
+-----------------------------------------------------------------------------------+
```

---

## 2. Cryptographic Specifications

| Component | Standard / Algorithm | Details |
| :--- | :--- | :--- |
| **Asymmetric Key Exchange** | X25519 / Curve25519 (RFC 7748) | 32-byte scalar Montgomery curve arithmetic. Public key is 44-char Base64, used directly as `uuid` / server ID. |
| **Key Derivation Function** | HKDF-SHA256 (RFC 5869) | `salt = "whisker-e2ee-v1"`, `info = "whisker-msg-aes256-gcm"`. Produces 32-byte key. |
| **Authenticated Symmetric Cipher** | AES-256-GCM | 12-byte CSPRNG IV, 16-byte authentication tag. |
| **Wire Envelope Format** | Base64 String | `Base64( IV[12] || Ciphertext || AuthTag[16] )` |

---

## 3. Key Generation & Storage Model

1. **Working Directory Key Storage**:
   - Private key files are stored directly in the application working directory alongside database files.
   - File Naming: `.<username>_<server_name>.secret` (e.g. `.b100_main.secret`) for CLI and local clients. Home Servers act as zero-trust relays and store a persistent random server UUID in the database without maintaining a private key file.
   - File Permissions: POSIX mode `0600` (`-rw-------`) or `0400` (`-r--------`).
   - Version Control Safety: `.*.secret` pattern added to `.gitignore`.

2. **Mobile App Key Storage**:
   - Keys generated at first launch or pairing using `WhiskerCrypto.generateKeyPair()`.
   - Stored in Android app-private `SharedPreferences`.

3. **Web & Embed Widget Key Storage**:
   - Keypairs generated using WebCrypto / `WhiskerWebCrypto.generateKeyPair()`.
   - Stored in browser `localStorage`.

---

## 4. Message Storage Invariance & On-Demand Decryption

- **Storage Invariance**: Message payloads are stored in `messages.text` (SQLite database) **exactly as received** on the wire.
- **Database Schema**:
  - `is_encrypted`: `INTEGER` (`0` for plaintext, `1` for E2EE encrypted).
- **On-Demand Decryption**: Payloads are decrypted strictly on-demand when a user or authorized client renders/reads the message in the UI or CLI/MCP output.

---

## 5. Protocol Updates & Payload Compatibility

The REST API and WebSocket/SSE event stream preserve backward and forward compatibility via the explicit `is_encrypted` boolean flag:

```json
{
  "action": "message",
  "uuid": "44-char-Base64-Public-Key",
  "token": "auth-token",
  "name": "Sender Name",
  "text": "Base64-IV-Ciphertext-Tag-Envelope",
  "is_encrypted": true,
  "timestamp": 1794328981799
}
```
