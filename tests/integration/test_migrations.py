"""Migration checks. The full downgrade -> upgrade round trip runs as a separate CI step because it
destroys data; here we verify the migrated schema matches the models exactly."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_models_match_migrations() -> None:
    env = {**os.environ}
    proc = subprocess.run(
        [sys.executable, "-m", "alembic", "check"],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
        timeout=120,
        check=False,
    )
    assert proc.returncode == 0, proc.stdout + proc.stderr
