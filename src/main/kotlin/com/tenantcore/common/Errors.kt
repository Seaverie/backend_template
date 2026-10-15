package com.tenantcore.common

import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** Every error response has the shape {"error": {"code", "message", "request_id", "details"}}. */
open class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
    val details: Any? = null,
) : RuntimeException(message)

class NotFound(what: String = "Resource") : ApiException(HttpStatus.NOT_FOUND, "not_found", "$what not found.")
class Unauthorized(message: String = "Authentication required.") : ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", message)
class Forbidden(message: String = "You do not have permission to do this.") : ApiException(HttpStatus.FORBIDDEN, "forbidden", message)
class Conflict(message: String) : ApiException(HttpStatus.CONFLICT, "conflict", message)
class Invalid(message: String, details: Any? = null) : ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", message, details)
class RateLimited(val retryAfterSeconds: Long) :
    ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests. Try again in $retryAfterSeconds seconds.")

data class FieldError(val loc: List<String>, val msg: String)

object ErrorBody {
    fun of(code: String, message: String, details: Any? = null): Map<String, Any?> =
        mapOf("error" to mapOf("code" to code, "message" to message, "request_id" to MDC.get("request_id"), "details" to details))

    /** For filters that run before Spring MVC (authentication, rate limiting). */
    fun write(response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.contentType = "application/json"
        val rid = MDC.get("request_id")?.let { "\"$it\"" } ?: "null"
        response.writer.write(
            """{"error":{"code":"$code","message":${com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(message)},"request_id":$rid,"details":null}}""",
        )
    }
}

@RestControllerAdvice
class ErrorHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun respond(status: HttpStatus, code: String, message: String, details: Any? = null) =
        ResponseEntity.status(status).body(ErrorBody.of(code, message, details))

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException): ResponseEntity<Map<String, Any?>> {
        val builder = ResponseEntity.status(e.status)
        if (e is RateLimited) builder.header("Retry-After", e.retryAfterSeconds.toString())
        return builder.body(ErrorBody.of(e.code, e.message, e.details))
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(e: MethodArgumentNotValidException) =
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "The request is invalid.",
            e.bindingResult.fieldErrors.map { FieldError(listOf("body", toSnake(it.field)), it.defaultMessage ?: "invalid") })

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun invalidParams(e: HandlerMethodValidationException) =
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "The request is invalid.",
            e.parameterValidationResults.flatMap { r -> r.resolvableErrors.map { FieldError(listOf("query", r.methodParameter.parameterName ?: "param"), it.defaultMessage ?: "invalid") } })

    /** Query and path parameters validated on @Validated controllers. */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException::class)
    fun invalidQuery(e: jakarta.validation.ConstraintViolationException) =
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "The request is invalid.",
            e.constraintViolations.map { v -> FieldError(listOf("query", toSnake(v.propertyPath.lastOrNull()?.name ?: "param")), v.message) })

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException): ResponseEntity<Map<String, Any?>> {
        val cause = e.cause
        val details = when (cause) {
            is UnrecognizedPropertyException -> listOf(FieldError(listOf("body", cause.propertyName), "unknown field"))
            is InvalidFormatException -> listOf(FieldError(listOf("body") + cause.path.mapNotNull { it.fieldName }, "invalid value"))
            is MismatchedInputException -> listOf(FieldError(listOf("body") + cause.path.mapNotNull { it.fieldName }, "missing or wrong type"))
            else -> null
        }
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "The request body is not valid JSON for this endpoint.", details)
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException::class)
    fun tooBig(e: Exception) = respond(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "The upload is larger than the server accepts.")

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException::class,
        org.springframework.web.multipart.MultipartException::class, org.springframework.web.HttpMediaTypeNotSupportedException::class)
    fun badMultipart(e: Exception) = respond(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
        "Send the file as multipart/form-data in a part named \"file\".")

    @ExceptionHandler(MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun badParam(e: Exception) = respond(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", e.message ?: "Invalid parameter.")

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(e: DataIntegrityViolationException): ResponseEntity<Map<String, Any?>> {
        val msg = e.mostSpecificCause.message ?: ""
        return if (msg.contains("duplicate key")) respond(HttpStatus.CONFLICT, "conflict", "A record with these values already exists.")
        else respond(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", "The data violates a database rule.")
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun denied(e: AccessDeniedException) = respond(HttpStatus.FORBIDDEN, "forbidden", "You do not have permission to do this.")

    @ExceptionHandler(NoResourceFoundException::class)
    fun noRoute(e: NoResourceFoundException) = respond(HttpStatus.NOT_FOUND, "not_found", "Not found.")

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) = respond(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "Method not allowed.")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<Map<String, Any?>> {
        log.error("unhandled error", e)
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Something went wrong. Quote the request id when reporting it.")
    }

    private fun toSnake(s: String) = s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
}
