"""Locust load profile for TenantCore.

This file defines *how* to load-test; it carries no results. Measure on hardware that resembles
production (PgBouncer, real Redis, separate load generator) and record your own numbers.

Setup (one tenant with a dynamic table that has a ``title`` string field):

    export TC_LOAD_TENANT=acme TC_LOAD_EMAIL=owner@acme.example.com TC_LOAD_PASSWORD=...
    export TC_LOAD_TABLE=products
    locust -f loadtest/locustfile.py --host http://127.0.0.1:8000

Each simulated user logs in once and then mixes reads, searches, writes and QR generation. The
tenant's rate limits apply: raise them for the test tenant or expect 429s (counted as successes
here, since rejecting excess load is correct behaviour; watch the 429 rate separately).
"""

from __future__ import annotations

import os
import random
import uuid

from locust import HttpUser, between, events, task

P = "/api/v1"
TENANT = os.environ.get("TC_LOAD_TENANT", "")
EMAIL = os.environ.get("TC_LOAD_EMAIL", "")
PASSWORD = os.environ.get("TC_LOAD_PASSWORD", "")
TABLE = os.environ.get("TC_LOAD_TABLE", "")


@events.test_start.add_listener
def _check(environment, **_):  # type: ignore[no-untyped-def]
    missing = [
        n
        for n, v in {
            "TC_LOAD_TENANT": TENANT,
            "TC_LOAD_EMAIL": EMAIL,
            "TC_LOAD_PASSWORD": PASSWORD,
            "TC_LOAD_TABLE": TABLE,
        }.items()
        if not v
    ]
    if missing:
        raise SystemExit(f"set {', '.join(missing)}")


class TenantUser(HttpUser):
    wait_time = between(0.1, 0.5)

    def on_start(self) -> None:
        r = self.client.post(
            f"{P}/auth/login",
            json={"tenant": TENANT, "email": EMAIL, "password": PASSWORD},
            name="auth/login",
        )
        r.raise_for_status()
        self.client.headers["Authorization"] = f"Bearer {r.json()['access_token']}"
        self.ids: list[str] = []

    def _ok(self, r) -> None:  # type: ignore[no-untyped-def]
        if r.status_code == 429:
            r.success()

    @task(10)
    def list_rows(self) -> None:
        with self.client.get(
            f"{P}/data/{TABLE}", params={"limit": 20}, name="data:list", catch_response=True
        ) as r:
            self._ok(r)

    @task(4)
    def search(self) -> None:
        with self.client.get(
            f"{P}/data/{TABLE}",
            params={"q": random.choice("abcdef")},
            name="data:search",
            catch_response=True,
        ) as r:
            self._ok(r)

    @task(3)
    def create(self) -> None:
        with self.client.post(
            f"{P}/data/{TABLE}",
            json={"title": f"load {uuid.uuid4().hex[:8]}"},
            name="data:create",
            catch_response=True,
        ) as r:
            self._ok(r)
            if r.status_code == 201:
                self.ids.append(r.json()["id"])

    @task(3)
    def read_one(self) -> None:
        if self.ids:
            with self.client.get(
                f"{P}/data/{TABLE}/{random.choice(self.ids)}", name="data:get", catch_response=True
            ) as r:
                self._ok(r)

    @task(1)
    def qrcode(self) -> None:
        with self.client.get(
            f"{P}/qrcode/generate",
            params={"data": f"order:{uuid.uuid4()}"},
            name="qrcode",
            catch_response=True,
        ) as r:
            self._ok(r)
