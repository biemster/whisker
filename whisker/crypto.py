"""
Whisker End-to-End Encryption (E2EE) Cryptographic Engine

Implements:
1. X25519 (RFC 7748 / Curve25519) key generation, public key derivation, and ECDH agreement.
2. HKDF-SHA256 (RFC 5869) key derivation (salt='whisker-e2ee-v1', info='whisker-msg-aes256-gcm').
3. AES-256-GCM authenticated encryption and on-demand decryption.
4. SSH-like private key storage in the working directory (filename: .<user>_<server>.secret, mode 0600/0400).
"""

import os
import sys
import base64
import hmac
import hashlib
import ctypes
import ctypes.util
import re

# Curve25519 Field Prime and Constant (RFC 7748)
P = 2**255 - 19
A24 = 121665
BASE_POINT = (9).to_bytes(32, 'little')

# HKDF Constants
HKDF_SALT = b"whisker-e2ee-v1"
HKDF_INFO = b"whisker-msg-aes256-gcm"

# Try loading libcrypto for fast OpenSSL acceleration
_libcrypto = None
try:
    _crypto_path = ctypes.util.find_library('crypto')
    if _crypto_path:
        _libcrypto = ctypes.CDLL(_crypto_path)
except Exception:
    _libcrypto = None


# ---------------------------------------------------------------------------
# 1. X25519 Montgomery Ladder Arithmetic (Pure Python RFC 7748)
# ---------------------------------------------------------------------------

def clamp_scalar(scalar_bytes: bytes) -> bytes:
    """Clamps a 32-byte scalar for Curve25519 per RFC 7748."""
    k = bytearray(scalar_bytes[:32])
    k[0] &= 248
    k[31] &= 127
    k[31] |= 64
    return bytes(k)


def x25519_pure(scalar_bytes: bytes, u_bytes: bytes) -> bytes:
    """Performs RFC 7748 X25519 scalar multiplication."""
    k = int.from_bytes(clamp_scalar(scalar_bytes), 'little')
    u = int.from_bytes(u_bytes[:32], 'little') % P
    x_1 = u
    x_2 = 1
    z_2 = 0
    x_3 = u
    z_3 = 1
    swap = 0

    for t in reversed(range(255)):
        k_t = (k >> t) & 1
        swap ^= k_t
        if swap:
            x_2, x_3 = x_3, x_2
            z_2, z_3 = z_3, z_2
        swap = k_t

        A = (x_2 + z_2) % P
        AA = (A * A) % P
        B = (x_2 - z_2) % P
        BB = (B * B) % P
        E = (AA - BB) % P
        C = (x_3 + z_3) % P
        D = (x_3 - z_3) % P
        DA = (D * A) % P
        CB = (C * B) % P
        x_3 = ((DA + CB) ** 2) % P
        z_3 = (x_1 * ((DA - CB) ** 2)) % P
        x_2 = (AA * BB) % P
        z_2 = (E * (AA + A24 * E)) % P

    if swap:
        x_2, x_3 = x_3, x_2
        z_2, z_3 = z_3, z_2

    return (x_2 * pow(z_2, P - 2, P) % P).to_bytes(32, 'little')


def x25519_mult(scalar_bytes: bytes, u_bytes: bytes) -> bytes:
    """Computes X25519 scalar multiplication with OpenSSL acceleration if available, falling back to pure Python."""
    if _libcrypto:
        try:
            # OpenSSL 3.0 EVP_PKEY raw API
            _libcrypto.EVP_PKEY_new_raw_private_key_ex.restype = ctypes.c_void_p
            _libcrypto.EVP_PKEY_new_raw_private_key_ex.argtypes = [
                ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t
            ]
            _libcrypto.EVP_PKEY_new_raw_public_key_ex.restype = ctypes.c_void_p
            _libcrypto.EVP_PKEY_new_raw_public_key_ex.argtypes = [
                ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_size_t
            ]
            _libcrypto.EVP_PKEY_CTX_new.restype = ctypes.c_void_p
            _libcrypto.EVP_PKEY_CTX_new.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
            _libcrypto.EVP_PKEY_derive_init.restype = ctypes.c_int
            _libcrypto.EVP_PKEY_derive_init.argtypes = [ctypes.c_void_p]
            _libcrypto.EVP_PKEY_derive_set_peer.restype = ctypes.c_int
            _libcrypto.EVP_PKEY_derive_set_peer.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
            _libcrypto.EVP_PKEY_derive.restype = ctypes.c_int
            _libcrypto.EVP_PKEY_derive.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_size_t)]
            _libcrypto.EVP_PKEY_get_raw_public_key.restype = ctypes.c_int
            _libcrypto.EVP_PKEY_get_raw_public_key.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_size_t)]
            _libcrypto.EVP_PKEY_free.argtypes = [ctypes.c_void_p]
            _libcrypto.EVP_PKEY_CTX_free.argtypes = [ctypes.c_void_p]

            priv_pkey = _libcrypto.EVP_PKEY_new_raw_private_key_ex(None, b'X25519', None, scalar_bytes, 32)
            if priv_pkey:
                try:
                    if u_bytes == BASE_POINT:
                        # Public key derivation
                        pub_buf = ctypes.create_string_buffer(32)
                        pub_len = ctypes.c_size_t(32)
                        if _libcrypto.EVP_PKEY_get_raw_public_key(priv_pkey, pub_buf, ctypes.byref(pub_len)) == 1:
                            return pub_buf.raw[:32]
                    else:
                        # ECDH key agreement
                        peer_pkey = _libcrypto.EVP_PKEY_new_raw_public_key_ex(None, b'X25519', None, u_bytes, 32)
                        if peer_pkey:
                            try:
                                ctx = _libcrypto.EVP_PKEY_CTX_new(priv_pkey, None)
                                if ctx:
                                    try:
                                        if _libcrypto.EVP_PKEY_derive_init(ctx) == 1:
                                            if _libcrypto.EVP_PKEY_derive_set_peer(ctx, peer_pkey) == 1:
                                                sec_buf = ctypes.create_string_buffer(32)
                                                sec_len = ctypes.c_size_t(32)
                                                if _libcrypto.EVP_PKEY_derive(ctx, sec_buf, ctypes.byref(sec_len)) == 1:
                                                    return sec_buf.raw[:sec_len.value]
                                    finally:
                                        _libcrypto.EVP_PKEY_CTX_free(ctx)
                            finally:
                                _libcrypto.EVP_PKEY_free(peer_pkey)
                finally:
                    _libcrypto.EVP_PKEY_free(priv_pkey)
        except Exception:
            pass

    return x25519_pure(scalar_bytes, u_bytes)


def generate_keypair() -> tuple[bytes, str]:
    """Generates an X25519 keypair.
    Returns (private_key_bytes [32 bytes], public_key_b64 [44 chars]).
    """
    priv_bytes = os.urandom(32)
    pub_bytes = x25519_mult(priv_bytes, BASE_POINT)
    pub_b64 = base64.b64encode(pub_bytes).decode('ascii')
    return priv_bytes, pub_b64


def get_public_key_b64(private_key_bytes: bytes) -> str:
    """Derives the Base64 public key from private key bytes."""
    pub_bytes = x25519_mult(private_key_bytes, BASE_POINT)
    return base64.b64encode(pub_bytes).decode('ascii')


def derive_shared_secret(private_key_bytes: bytes, peer_public_key_b64: str) -> bytes:
    """Computes X25519 ECDH shared secret (32 bytes) with peer public key."""
    clean_b64 = peer_public_key_b64.strip()
    peer_bytes = base64.b64decode(clean_b64)
    if len(peer_bytes) != 32:
        raise ValueError(f"Invalid X25519 public key length: {len(peer_bytes)} bytes (expected 32)")
    return x25519_mult(private_key_bytes, peer_bytes)


# ---------------------------------------------------------------------------
# 2. Key Derivation (HKDF-SHA256)
# ---------------------------------------------------------------------------

def hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    """RFC 5869 HKDF-Expand step using HMAC-SHA256."""
    okm = b""
    t = b""
    i = 1
    while len(okm) < length:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        okm += t
        i += 1
    return okm[:length]


def derive_aes_key(shared_secret: bytes) -> bytes:
    """Derives a 32-byte AES-256 symmetric key from an X25519 shared secret."""
    # HKDF-Extract
    prk = hmac.new(HKDF_SALT, shared_secret, hashlib.sha256).digest()
    # HKDF-Expand
    return hkdf_expand(prk, HKDF_INFO, 32)


# ---------------------------------------------------------------------------
# 3. Authenticated Encryption & Decryption (AES-256-GCM)
# ---------------------------------------------------------------------------

def _aes_gcm_encrypt(key: bytes, iv: bytes, plaintext: bytes) -> tuple[bytes, bytes]:
    """Encrypts plaintext with AES-256-GCM. Returns (ciphertext, tag_16_bytes)."""
    if not _libcrypto:
        raise RuntimeError("OpenSSL libcrypto is required for AES-256-GCM encryption")

    _libcrypto.EVP_CIPHER_CTX_new.restype = ctypes.c_void_p
    _libcrypto.EVP_CIPHER_CTX_free.argtypes = [ctypes.c_void_p]
    _libcrypto.EVP_aes_256_gcm.restype = ctypes.c_void_p

    _libcrypto.EVP_EncryptInit_ex.argtypes = [
        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p
    ]
    _libcrypto.EVP_EncryptInit_ex.restype = ctypes.c_int
    _libcrypto.EVP_EncryptUpdate.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int), ctypes.c_char_p, ctypes.c_int
    ]
    _libcrypto.EVP_EncryptUpdate.restype = ctypes.c_int
    _libcrypto.EVP_EncryptFinal_ex.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)
    ]
    _libcrypto.EVP_EncryptFinal_ex.restype = ctypes.c_int
    _libcrypto.EVP_CIPHER_CTX_ctrl.argtypes = [
        ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_void_p
    ]
    _libcrypto.EVP_CIPHER_CTX_ctrl.restype = ctypes.c_int

    EVP_CTRL_GCM_SET_IVLEN = 0x9
    EVP_CTRL_GCM_GET_TAG = 0x10

    ctx = _libcrypto.EVP_CIPHER_CTX_new()
    if not ctx:
        raise RuntimeError("Failed to create EVP_CIPHER_CTX")
    try:
        if _libcrypto.EVP_EncryptInit_ex(ctx, _libcrypto.EVP_aes_256_gcm(), None, None, None) != 1:
            raise RuntimeError("EVP_EncryptInit_ex failed")
        if _libcrypto.EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, len(iv), None) != 1:
            raise RuntimeError("Setting IV length failed")
        if _libcrypto.EVP_EncryptInit_ex(ctx, None, None, key, iv) != 1:
            raise RuntimeError("EVP_EncryptInit_ex key/iv failed")

        out_buf = ctypes.create_string_buffer(len(plaintext) + 16)
        out_len = ctypes.c_int(0)
        if _libcrypto.EVP_EncryptUpdate(ctx, out_buf, ctypes.byref(out_len), plaintext, len(plaintext)) != 1:
            raise RuntimeError("EVP_EncryptUpdate failed")
        c_len = out_len.value

        final_len = ctypes.c_int(0)
        dest_ptr = ctypes.cast(ctypes.addressof(out_buf) + c_len, ctypes.c_char_p)
        if _libcrypto.EVP_EncryptFinal_ex(ctx, dest_ptr, ctypes.byref(final_len)) != 1:
            raise RuntimeError("EVP_EncryptFinal_ex failed")
        c_len += final_len.value

        tag = ctypes.create_string_buffer(16)
        if _libcrypto.EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, 16, tag) != 1:
            raise RuntimeError("Retrieving AES-GCM tag failed")

        return out_buf.raw[:c_len], tag.raw[:16]
    finally:
        _libcrypto.EVP_CIPHER_CTX_free(ctx)


def _aes_gcm_decrypt(key: bytes, iv: bytes, ciphertext: bytes, tag: bytes) -> bytes:
    """Decrypts ciphertext with AES-256-GCM. Verifies 16-byte authentication tag."""
    if not _libcrypto:
        raise RuntimeError("OpenSSL libcrypto is required for AES-256-GCM decryption")

    _libcrypto.EVP_CIPHER_CTX_new.restype = ctypes.c_void_p
    _libcrypto.EVP_CIPHER_CTX_free.argtypes = [ctypes.c_void_p]
    _libcrypto.EVP_aes_256_gcm.restype = ctypes.c_void_p

    _libcrypto.EVP_DecryptInit_ex.argtypes = [
        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p
    ]
    _libcrypto.EVP_DecryptInit_ex.restype = ctypes.c_int
    _libcrypto.EVP_DecryptUpdate.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int), ctypes.c_char_p, ctypes.c_int
    ]
    _libcrypto.EVP_DecryptUpdate.restype = ctypes.c_int
    _libcrypto.EVP_DecryptFinal_ex.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_int)
    ]
    _libcrypto.EVP_DecryptFinal_ex.restype = ctypes.c_int
    _libcrypto.EVP_CIPHER_CTX_ctrl.argtypes = [
        ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_void_p
    ]
    _libcrypto.EVP_CIPHER_CTX_ctrl.restype = ctypes.c_int

    EVP_CTRL_GCM_SET_IVLEN = 0x9
    EVP_CTRL_GCM_SET_TAG = 0x11

    ctx = _libcrypto.EVP_CIPHER_CTX_new()
    if not ctx:
        raise RuntimeError("Failed to create EVP_CIPHER_CTX")
    try:
        if _libcrypto.EVP_DecryptInit_ex(ctx, _libcrypto.EVP_aes_256_gcm(), None, None, None) != 1:
            raise RuntimeError("EVP_DecryptInit_ex failed")
        if _libcrypto.EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, len(iv), None) != 1:
            raise RuntimeError("Setting IV length failed")
        if _libcrypto.EVP_DecryptInit_ex(ctx, None, None, key, iv) != 1:
            raise RuntimeError("EVP_DecryptInit_ex key/iv failed")

        out_buf = ctypes.create_string_buffer(len(ciphertext) + 16)
        out_len = ctypes.c_int(0)
        if _libcrypto.EVP_DecryptUpdate(ctx, out_buf, ctypes.byref(out_len), ciphertext, len(ciphertext)) != 1:
            raise RuntimeError("EVP_DecryptUpdate failed")
        p_len = out_len.value

        # Set expected authentication tag
        tag_buf = ctypes.create_string_buffer(tag, 16)
        if _libcrypto.EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, 16, tag_buf) != 1:
            raise RuntimeError("Setting AES-GCM auth tag failed")

        final_len = ctypes.c_int(0)
        dest_ptr = ctypes.cast(ctypes.addressof(out_buf) + p_len, ctypes.c_char_p)
        res = _libcrypto.EVP_DecryptFinal_ex(ctx, dest_ptr, ctypes.byref(final_len))
        if res <= 0:
            raise ValueError("AES-GCM authentication failed: message corrupted or incorrect key")
        p_len += final_len.value

        return out_buf.raw[:p_len]
    finally:
        _libcrypto.EVP_CIPHER_CTX_free(ctx)


def encrypt_payload(shared_secret: bytes, plaintext_str: str) -> str:
    """Encrypts plaintext string with AES-256-GCM using derived shared key.
    Envelope format: Base64(IV [12B] || Ciphertext || Tag [16B]).
    """
    key = derive_aes_key(shared_secret)
    iv = os.urandom(12)
    pt_bytes = plaintext_str.encode('utf-8')
    ciphertext, tag = _aes_gcm_encrypt(key, iv, pt_bytes)
    envelope = iv + ciphertext + tag
    return base64.b64encode(envelope).decode('ascii')


def decrypt_payload(shared_secret: bytes, envelope_b64: str) -> str:
    """Decrypts Base64 envelope (IV [12B] || Ciphertext || Tag [16B]) to plaintext string."""
    key = derive_aes_key(shared_secret)
    raw = base64.b64decode(envelope_b64.strip())
    if len(raw) < 28:  # 12 bytes IV + 16 bytes Tag
        raise ValueError(f"Corrupted E2EE envelope: length {len(raw)} bytes is too short")
    iv = raw[:12]
    tag = raw[-16:]
    ciphertext = raw[12:-16]
    plaintext_bytes = _aes_gcm_decrypt(key, iv, ciphertext, tag)
    return plaintext_bytes.decode('utf-8')


# ---------------------------------------------------------------------------
# 4. Working Directory Private Key Storage (.secret files, mode 0600/0400)
# ---------------------------------------------------------------------------

def sanitize_key_name(val: str) -> str:
    """Sanitizes username/server name for safe filesystem storage."""
    s = re.sub(r'[^a-zA-Z0-9_\-\.]', '_', str(val or '').strip().lower())
    return s.strip('._') or 'default'


def get_secret_filename(username: str, server_name: str) -> str:
    """Returns filename for client key in working directory: .<username>_<server>.secret"""
    u = sanitize_key_name(username)
    s = sanitize_key_name(server_name)
    return f".{u}_{s}.secret"


def save_secret_key(filepath: str, private_key_bytes: bytes) -> None:
    """Writes private key bytes (32 bytes) with restricted 0600 POSIX permissions."""
    if len(private_key_bytes) != 32:
        raise ValueError("Private key must be exactly 32 bytes")
    # Write to file
    with open(filepath, 'wb') as f:
        f.write(private_key_bytes)
    # Enforce restricted permissions (read/write by owner only)
    try:
        os.chmod(filepath, 0o600)
    except Exception:
        pass


def load_secret_key(filepath: str) -> bytes | None:
    """Loads a 32-byte private key from disk if it exists."""
    if not os.path.exists(filepath):
        return None
    try:
        with open(filepath, 'rb') as f:
            data = f.read()
        if len(data) == 32:
            return data
    except Exception:
        pass
    return None


def load_or_create_client_key(username: str, server_name: str, directory: str = ".") -> tuple[bytes, str]:
    """Loads or generates client private/public keypair in the working directory.
    Returns (private_key_bytes, public_key_b64).
    """
    fn = get_secret_filename(username, server_name)
    path = os.path.join(directory, fn)
    key = load_secret_key(path)
    if key:
        return key, get_public_key_b64(key)
    priv, pub = generate_keypair()
    save_secret_key(path, priv)
    return priv, pub



