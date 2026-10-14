"""Explicit response serialisation. Only listed attributes are emitted, so new model columns (or
secret ones such as ``secrets_enc``) never leak into responses by accident."""

from __future__ import annotations

from typing import Any

from app.dynamic.crud import to_json_value


def pick(obj: Any, *fields: str, **renamed: str) -> dict[str, Any]:
    out = {f: to_json_value(_val(getattr(obj, f))) for f in fields}
    out.update({k: to_json_value(_val(getattr(obj, attr))) for k, attr in renamed.items()})
    return out


def _val(v: Any) -> Any:
    return v.value if hasattr(v, "value") and not isinstance(v, str | int | float) else v
