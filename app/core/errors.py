"""Error types and handlers. Responses never include stack traces, SQL, or driver messages."""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import DBAPIError, IntegrityError, OperationalError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.logging import get_logger, request_id_var

log = get_logger(__name__)


class AppError(Exception):
    status_code = 400
    code = "bad_request"
    message = "The request could not be processed."

    def __init__(
        self,
        message: str | None = None,
        *,
        details: Any = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(message or self.message)
        self.message = message or self.message
        self.details = details
        self.headers = headers


class Unauthorized(AppError):
    status_code = 401
    code = "unauthorized"
    message = "Authentication required."

    def __init__(self, message: str | None = None, **kw: Any) -> None:
        kw.setdefault("headers", {"WWW-Authenticate": "Bearer"})
        super().__init__(message, **kw)


class Forbidden(AppError):
    status_code = 403
    code = "forbidden"
    message = "You do not have permission to perform this action."


class NotFound(AppError):
    status_code = 404
    code = "not_found"
    message = "Resource not found."


class Conflict(AppError):
    status_code = 409
    code = "conflict"
    message = "The request conflicts with the current state of the resource."


class ValidationFailed(AppError):
    status_code = 422
    code = "validation_failed"
    message = "Request validation failed."


class PayloadTooLarge(AppError):
    status_code = 413
    code = "payload_too_large"
    message = "Request body too large."


class RateLimited(AppError):
    status_code = 429
    code = "rate_limited"
    message = "Too many requests."


class ServiceUnavailable(AppError):
    status_code = 503
    code = "service_unavailable"
    message = "A required dependency is temporarily unavailable."


def _body(code: str, message: str, details: Any = None) -> dict[str, Any]:
    err: dict[str, Any] = {"code": code, "message": message, "request_id": request_id_var.get()}
    if details is not None:
        err["details"] = details
    return {"error": err}


def _safe_validation_details(exc: RequestValidationError) -> list[dict[str, Any]]:
    # Strip "input" (may echo secrets such as passwords) and "ctx" (may hold exception objects).
    return [{"loc": list(e.get("loc", ())), "msg": e.get("msg"), "type": e.get("type")} for e in exc.errors()]


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def _app_error(_: Request, exc: AppError) -> JSONResponse:
        return JSONResponse(
            _body(exc.code, exc.message, exc.details), status_code=exc.status_code, headers=exc.headers
        )

    @app.exception_handler(RequestValidationError)
    async def _validation(_: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            _body("validation_failed", "Request validation failed.", _safe_validation_details(exc)),
            status_code=422,
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        msg = exc.detail if isinstance(exc.detail, str) and exc.status_code < 500 else "Request failed."
        return JSONResponse(_body(f"http_{exc.status_code}", msg), status_code=exc.status_code)

    @app.exception_handler(IntegrityError)
    async def _integrity(_: Request, exc: IntegrityError) -> JSONResponse:
        # SQLSTATE only; never the constraint text, which can leak schema and values.
        log.info("db.integrity_error", sqlstate=getattr(exc.orig, "sqlstate", None))
        return JSONResponse(
            _body("conflict", "The request violates a uniqueness or reference constraint."), status_code=409
        )

    @app.exception_handler(OperationalError)
    async def _operational(_: Request, exc: OperationalError) -> JSONResponse:
        log.warning("db.operational_error", sqlstate=getattr(exc.orig, "sqlstate", None))
        return JSONResponse(
            _body("service_unavailable", "Database temporarily unavailable."), status_code=503
        )

    @app.exception_handler(DBAPIError)
    async def _dbapi(_: Request, exc: DBAPIError) -> JSONResponse:
        sqlstate = getattr(exc.orig, "sqlstate", None)
        if sqlstate == "57014":  # query_canceled (statement_timeout)
            return JSONResponse(_body("timeout", "The query took too long."), status_code=504)
        if sqlstate == "42501":  # insufficient_privilege, e.g. RLS WITH CHECK violation
            log.warning("db.privilege_violation", sqlstate=sqlstate)
            return JSONResponse(_body("forbidden", Forbidden.message), status_code=403)
        log.error("db.error", sqlstate=sqlstate, error_type=type(exc.orig).__name__)
        return JSONResponse(_body("internal_error", "Internal server error."), status_code=500)

    @app.exception_handler(Exception)
    async def _unhandled(_: Request, exc: Exception) -> JSONResponse:
        log.error("unhandled_exception", error_type=type(exc).__name__, exc_info=exc)
        return JSONResponse(_body("internal_error", "Internal server error."), status_code=500)
