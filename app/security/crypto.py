"""Envelope encryption for secrets stored in the database.

Each secret gets a fresh 256-bit data key (DEK). The secret is sealed with AES-256-GCM under the DEK,
and the DEK is wrapped by a key-encryption key that never leaves the key provider:

* ``vault`` -- HashiCorp Vault Transit (``/transit/datakey`` and ``/transit/decrypt``)
* ``aws``   -- AWS KMS ``GenerateDataKey`` / ``Decrypt`` with an EncryptionContext
* ``local`` -- an AES-256-GCM KEK from configuration. For development and tests only; refused in
  production unless explicitly allowed.

Every encryption is bound to a *context*, e.g. ``{"tenant": ..., "purpose": "payment_gateway", "id": ...}``.
The context is the GCM associated data, and for AWS also the KMS EncryptionContext. A ciphertext copied
to another row, tenant, or purpose therefore fails to decrypt.

Stored format (JSON): ``{"v":1,"p":provider,"k":key_id,"w":wrapped_dek,"n":nonce,"c":ciphertext}``,
with binary fields base64-encoded.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import os
import time
from collections import OrderedDict
from typing import Any, Protocol

import httpx
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.core.config import Settings

FORMAT_VERSION = 1


class CryptoError(Exception):
    """Decryption or key-provider failure. Messages never include key material."""


def _b64e(b: bytes) -> str:
    return base64.b64encode(b).decode()


def _b64d(s: str) -> bytes:
    return base64.b64decode(s.encode(), validate=True)


def canonical_context(context: dict[str, str]) -> bytes:
    if not context or any(not isinstance(v, str) for v in context.values()):
        raise CryptoError("encryption context must be a non-empty dict of strings")
    return json.dumps(context, sort_keys=True, separators=(",", ":")).encode()


class KeyProvider(Protocol):
    name: str

    async def generate_data_key(self, context: dict[str, str]) -> tuple[bytes, bytes, str]:
        """Return (plaintext_dek, wrapped_dek, key_id)."""
        ...

    async def decrypt_data_key(self, wrapped: bytes, key_id: str, context: dict[str, str]) -> bytes: ...


class LocalKeyProvider:
    name = "local"

    def __init__(self, kek: bytes) -> None:
        if len(kek) != 32:
            raise CryptoError("local KEK must be 32 bytes")
        self._aead = AESGCM(kek)
        self.key_id = "local:" + hashlib.sha256(kek).hexdigest()[:16]

    async def generate_data_key(self, context: dict[str, str]) -> tuple[bytes, bytes, str]:
        dek = AESGCM.generate_key(bit_length=256)
        nonce = os.urandom(12)
        return dek, nonce + self._aead.encrypt(nonce, dek, canonical_context(context)), self.key_id

    async def decrypt_data_key(self, wrapped: bytes, key_id: str, context: dict[str, str]) -> bytes:
        if key_id != self.key_id:
            raise CryptoError("data key was wrapped by a different KEK")
        try:
            return self._aead.decrypt(wrapped[:12], wrapped[12:], canonical_context(context))
        except Exception as e:
            raise CryptoError("unable to unwrap data key") from e


class VaultTransitProvider:
    """HashiCorp Vault Transit. Vault is operator-configured infrastructure, not tenant input."""

    name = "vault"

    def __init__(
        self,
        addr: str,
        token: str,
        key: str,
        namespace: str | None = None,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base = addr.rstrip("/")
        self._key = key
        headers = {"X-Vault-Token": token}
        if namespace:
            headers["X-Vault-Namespace"] = namespace
        self._client = client or httpx.AsyncClient(timeout=5.0)
        self._headers = headers

    async def _post(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        try:
            r = await self._client.post(
                f"{self._base}/v1/transit/{path}/{self._key}", json=body, headers=self._headers
            )
        except httpx.HTTPError as e:
            raise CryptoError("vault unreachable") from e
        if r.status_code != 200:
            raise CryptoError(f"vault returned {r.status_code}")
        data = r.json().get("data")
        if not isinstance(data, dict):
            raise CryptoError("unexpected vault response")
        return data

    async def generate_data_key(self, context: dict[str, str]) -> tuple[bytes, bytes, str]:
        data = await self._post("datakey/plaintext", {"bits": 256})
        return _b64d(data["plaintext"]), data["ciphertext"].encode(), f"vault:{self._key}"

    async def decrypt_data_key(self, wrapped: bytes, key_id: str, context: dict[str, str]) -> bytes:
        if key_id != f"vault:{self._key}":
            raise CryptoError("data key was wrapped by a different transit key")
        data = await self._post("decrypt", {"ciphertext": wrapped.decode()})
        return _b64d(data["plaintext"])


class AwsKmsProvider:
    name = "aws"

    def __init__(self, key_id: str, region: str | None, client: Any = None) -> None:
        if client is None:
            import boto3  # optional dependency, imported only when configured

            client = boto3.client("kms", region_name=region)
        self._kms = client
        self._key_id = key_id

    async def generate_data_key(self, context: dict[str, str]) -> tuple[bytes, bytes, str]:
        try:
            r = await asyncio.to_thread(
                self._kms.generate_data_key, KeyId=self._key_id, KeySpec="AES_256", EncryptionContext=context
            )
        except Exception as e:
            raise CryptoError("KMS GenerateDataKey failed") from e
        return bytes(r["Plaintext"]), bytes(r["CiphertextBlob"]), str(r["KeyId"])

    async def decrypt_data_key(self, wrapped: bytes, key_id: str, context: dict[str, str]) -> bytes:
        try:
            r = await asyncio.to_thread(
                self._kms.decrypt, CiphertextBlob=wrapped, EncryptionContext=context, KeyId=key_id
            )
        except Exception as e:
            raise CryptoError("KMS Decrypt failed") from e
        return bytes(r["Plaintext"])


class Envelope:
    def __init__(self, provider: KeyProvider, *, dek_cache_ttl_s: int = 300, max_cached: int = 1024) -> None:
        self.provider = provider
        self._ttl = dek_cache_ttl_s
        self._max = max_cached
        self._cache: OrderedDict[str, tuple[float, bytes]] = OrderedDict()

    async def encrypt(self, plaintext: bytes, context: dict[str, str]) -> str:
        aad = canonical_context(context)
        dek, wrapped, key_id = await self.provider.generate_data_key(context)
        nonce = os.urandom(12)
        ct = AESGCM(dek).encrypt(nonce, plaintext, aad)
        return json.dumps(
            {
                "v": FORMAT_VERSION,
                "p": self.provider.name,
                "k": key_id,
                "w": _b64e(wrapped),
                "n": _b64e(nonce),
                "c": _b64e(ct),
            },
            separators=(",", ":"),
        )

    async def decrypt(self, blob: str, context: dict[str, str]) -> bytes:
        aad = canonical_context(context)
        try:
            env = json.loads(blob)
            if env.get("v") != FORMAT_VERSION or env.get("p") != self.provider.name:
                raise CryptoError("ciphertext was produced by a different key provider; re-wrap required")
            wrapped, nonce, ct = _b64d(env["w"]), _b64d(env["n"]), _b64d(env["c"])
            key_id = str(env["k"])
        except (ValueError, KeyError, TypeError) as e:
            raise CryptoError("malformed ciphertext") from e
        dek = await self._dek(wrapped, key_id, context, aad)
        try:
            return AESGCM(dek).decrypt(nonce, ct, aad)
        except Exception as e:
            raise CryptoError("decryption failed (wrong context or tampered ciphertext)") from e

    async def encrypt_str(self, value: str, context: dict[str, str]) -> str:
        return await self.encrypt(value.encode(), context)

    async def decrypt_str(self, blob: str, context: dict[str, str]) -> str:
        return (await self.decrypt(blob, context)).decode()

    async def rewrap(self, blob: str, context: dict[str, str]) -> str:
        """Re-encrypt under the provider's current key (KEK rotation)."""
        return await self.encrypt(await self.decrypt(blob, context), context)

    async def _dek(self, wrapped: bytes, key_id: str, context: dict[str, str], aad: bytes) -> bytes:
        ck = hashlib.sha256(wrapped + b"\0" + aad).hexdigest()
        hit = self._cache.get(ck)
        now = time.monotonic()
        if hit and hit[0] > now:
            self._cache.move_to_end(ck)
            return hit[1]
        dek = await self.provider.decrypt_data_key(wrapped, key_id, context)
        if self._ttl:
            self._cache[ck] = (now + self._ttl, dek)
            while len(self._cache) > self._max:
                self._cache.popitem(last=False)
        return dek


def build_envelope(settings: Settings) -> Envelope:
    provider: KeyProvider
    if settings.kms_provider == "vault":
        if not (settings.vault_addr and settings.vault_token):
            raise CryptoError("vault KMS is not configured")
        provider = VaultTransitProvider(
            settings.vault_addr,
            settings.vault_token.get_secret_value(),
            settings.vault_transit_key,
            settings.vault_namespace,
        )
    elif settings.kms_provider == "aws":
        if not settings.aws_kms_key_id:
            raise CryptoError("aws KMS is not configured")
        provider = AwsKmsProvider(settings.aws_kms_key_id, settings.aws_region)
    else:
        if settings.kms_local_kek is None:
            if settings.environment in ("production", "staging"):
                raise CryptoError("TC_KMS_LOCAL_KEK is required for the local provider")
            # Fixed, publicly known development key: offers no secrecy, so it is refused outside dev/test.
            kek = hashlib.sha256(b"tenantcore-dev-only-kek").digest()
        else:
            kek = base64.b64decode(settings.kms_local_kek.get_secret_value())
        provider = LocalKeyProvider(kek)
    return Envelope(provider, dek_cache_ttl_s=settings.dek_cache_ttl_s)
