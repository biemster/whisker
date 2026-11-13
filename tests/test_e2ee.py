#!/usr/bin/env python3
"""
Whisker End-to-End Encryption (E2EE) Integration & Cryptographic Test Suite.
Tests:
- X25519 Keypair Generation & Base64 Key Encoding
- POSIX 0600 Working Directory Secret Key Storage
- Mutual ECDH Key Agreement & HKDF-SHA256 Derivation
- AES-256-GCM Envelope Encryption, Decryption & Tamper Detection
- SQLite Database is_encrypted Schema Persistence
- End-to-End Integration across Home Server, CLI, and MCP
"""

import os
import stat
import sys
import unittest
import tempfile
import sqlite3

# Ensure workspace root is in python path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from whisker.crypto import (
    generate_keypair,
    get_public_key_b64,
    derive_shared_secret,
    derive_aes_key,
    encrypt_payload,
    decrypt_payload,
    save_secret_key,
    load_secret_key,
    load_or_create_client_key,
    get_secret_filename,
)
from whisker.db import DatabaseManager


class TestWhiskerCryptoUnit(unittest.TestCase):
    def test_keypair_generation(self):
        priv_b64, pub_b64 = generate_keypair()
        self.assertIsNotNone(priv_b64)
        self.assertIsNotNone(pub_b64)
        self.assertEqual(len(pub_b64), 44, "X25519 Base64 public key should be 44 characters")
        derived_pub = get_public_key_b64(priv_b64)
        self.assertEqual(pub_b64, derived_pub)

    def test_ecdh_mutual_agreement(self):
        alice_priv, alice_pub = generate_keypair()
        bob_priv, bob_pub = generate_keypair()

        # Alice computes secret with Bob's public key
        secret_a = derive_shared_secret(alice_priv, bob_pub)
        # Bob computes secret with Alice's public key
        secret_b = derive_shared_secret(bob_priv, alice_pub)

        self.assertEqual(secret_a, secret_b, "Mutual ECDH shared secrets must match exactly")
        self.assertEqual(len(secret_a), 32)

    def test_hkdf_aes_encryption_decryption(self):
        alice_priv, alice_pub = generate_keypair()
        bob_priv, bob_pub = generate_keypair()

        msg = "Secret Whisker E2EE Message payload 🔒 123"
        shared_a = derive_shared_secret(alice_priv, bob_pub)
        shared_b = derive_shared_secret(bob_priv, alice_pub)

        envelope = encrypt_payload(shared_a, msg)
        self.assertIsNotNone(envelope)

        # Bob decrypts message from Alice
        decrypted = decrypt_payload(shared_b, envelope)
        self.assertEqual(decrypted, msg)

    def test_tamper_detection(self):
        alice_priv, alice_pub = generate_keypair()
        bob_priv, bob_pub = generate_keypair()

        msg = "Authentic payload"
        shared_a = derive_shared_secret(alice_priv, bob_pub)
        shared_b = derive_shared_secret(bob_priv, alice_pub)

        envelope = encrypt_payload(shared_a, msg)

        # Corrupt envelope
        raw = list(envelope)
        raw[10] = 'A' if raw[10] != 'A' else 'B'
        tampered_env = "".join(raw)

        with self.assertRaises(Exception):
            decrypt_payload(shared_b, tampered_env)

    def test_working_directory_key_file_permissions(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            orig_cwd = os.getcwd()
            try:
                os.chdir(tmpdir)
                priv, pub = load_or_create_client_key("testuser", "testserver")
                fname = get_secret_filename("testuser", "testserver")
                self.assertTrue(os.path.exists(fname))

                # Check POSIX 0600 file mode
                st = os.stat(fname)
                mode = stat.S_IMODE(st.st_mode)
                self.assertEqual(mode & 0o077, 0, "Secret key file must not be accessible to group or others")

                # Verify reload
                priv_reload, pub_reload = load_or_create_client_key("testuser", "testserver")
                self.assertEqual(priv, priv_reload)
                self.assertEqual(pub, pub_reload)
            finally:
                os.chdir(orig_cwd)


class TestWhiskerDatabaseE2EE(unittest.TestCase):
    def test_db_is_encrypted_flag(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            db_path = os.path.join(tmpdir, "test_e2ee.db")
            db = DatabaseManager(db_path)

            # Insert plaintext and encrypted messages
            msg1 = db.add_message("client_1", "host_1", "guest", "Plaintext message", is_encrypted=0)
            msg2 = db.add_message("client_1", "host_1", "guest", "EncryptedEnvelopeB64==", is_encrypted=1)

            messages = db.get_messages("client_1")
            self.assertEqual(len(messages), 2)
            self.assertEqual(messages[0]["is_encrypted"], 0)
            self.assertEqual(messages[1]["is_encrypted"], 1)


class TestWhiskerInitSecretNaming(unittest.TestCase):
    def test_cli_init_does_not_create_cli_server_secret(self):
        import io
        import json
        from unittest.mock import patch
        from whisker import cli

        class FakeResp:
            def __init__(self, payload):
                self._data = json.dumps(payload).encode("utf-8")
            def read(self):
                return self._data
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass

        def fake_urlopen(req, *args, **kwargs):
            body = json.loads(req.data.decode("utf-8"))
            pub = body["public_key"]
            return FakeResp({
                "uuid": pub,
                "token": "tok_test",
                "name": "backup",
                "server_name": "probook1",
                "host_uuid": "host_pub_key",
                "host_name": "Pixel9"
            })

        with tempfile.TemporaryDirectory() as tmpdir:
            orig_cwd = os.getcwd()
            try:
                os.chdir(tmpdir)
                with patch("urllib.request.urlopen", side_effect=fake_urlopen), \
                     patch("sys.stdout", io.StringIO()):
                    cli.do_init("http://probook1:3001/invite/abcde")

                files = os.listdir(tmpdir)
                self.assertIn(".backup_probook1.secret", files)
                self.assertNotIn(".cli_server.secret", files)
            finally:
                os.chdir(orig_cwd)

    def test_mcp_init_does_not_create_temp_server_secret(self):
        import json
        from unittest.mock import patch
        from whisker import mcp

        class FakeResp:
            def __init__(self, payload):
                self._data = json.dumps(payload).encode("utf-8")
            def read(self):
                return self._data
            def __enter__(self):
                return self
            def __exit__(self, *args):
                pass

        def fake_urlopen(req, *args, **kwargs):
            body = json.loads(req.data.decode("utf-8"))
            pub = body["public_key"]
            return FakeResp({
                "uuid": pub,
                "token": "tok_mcp",
                "name": "backup",
                "server_name": "probook1",
                "host_uuid": "host_pub_key",
                "host_name": "Pixel9"
            })

        with tempfile.TemporaryDirectory() as tmpdir:
            orig_cwd = os.getcwd()
            try:
                os.chdir(tmpdir)
                with patch("urllib.request.urlopen", side_effect=fake_urlopen):
                    mcp.handle_init({"invite_url": "http://probook1:3001/invite/abcde"})

                files = os.listdir(tmpdir)
                self.assertIn(".backup_probook1.secret", files)
                self.assertNotIn(".mcp_agent_server.secret", files)
            finally:
                os.chdir(orig_cwd)


if __name__ == "__main__":
    unittest.main()
