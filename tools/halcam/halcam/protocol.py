"""Shared validation and CLI error semantics, without Android dependencies."""

import json
import re
import uuid

TERMINAL = {"succeeded", "failed", "cancelled", "interrupted"}
STATES = TERMINAL | {"accepted", "preparing", "running", "saving", "cancelling"}
EXIT_CODES = {
    "INVALID_ARGUMENT": 2, "REQUEST_CONFLICT": 2,
    "DEVICE_NOT_FOUND": 3, "MULTIPLE_DEVICES": 3, "DEVICE_UNAUTHORIZED": 3,
    "CONNECTION_LOST": 3, "ADB_NOT_FOUND": 3,
    "CLI_DISABLED": 4, "PERMISSION_REQUIRED": 4, "BUSY": 4,
    "UNSUPPORTED_CAMERA": 4, "UNSUPPORTED_PROFILE": 4, "PREFLIGHT_FAILED": 4,
    "PROTOCOL_MISMATCH": 4, "APP_NOT_INSTALLED": 4, "APP_NOT_FOREGROUND": 4,
    "DEVICE_LOCKED": 4, "UNSUPPORTED_USER": 4,
    "EXECUTION_TIMEOUT": 6, "WAIT_TIMEOUT": 6,
    "ARTIFACT_MISSING": 7, "ARTIFACT_EXPIRED": 7, "CHECKSUM_MISMATCH": 7,
    "DOWNLOAD_FAILED": 7, "REQUEST_NOT_FOUND": 7, "OUTPUT_EXISTS": 7,
    "PROTOCOL_ERROR": 8, "CANCELLED": 130,
}


class CliError(Exception):
    def __init__(self, code, message, *, request_id=None):
        super().__init__(message)
        self.code, self.request_id = code, request_id

    @property
    def exit_code(self):
        return EXIT_CODES.get(self.code, 5)

    def as_dict(self):
        return {"protocol_version": 1, "request_id": self.request_id,
                "completed": False, "error": {"code": self.code, "message": str(self)}}


def identifier(value):
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError()
    except (ValueError, AttributeError, TypeError):
        raise CliError("INVALID_ARGUMENT", "Request ID must be a canonical lowercase UUID") from None
    return value


def package_name(value):
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", value):
        raise CliError("INVALID_ARGUMENT", "Invalid Android package name")
    return value


def decode_response(raw):
    try:
        data = json.loads(raw)
        if not isinstance(data, dict):
            raise ValueError()
    except (ValueError, UnicodeError):
        raise CliError("PROTOCOL_ERROR", "App returned invalid JSON") from None
    if type(data.get("protocol_version")) is not int or data["protocol_version"] != 1:
        raise CliError("PROTOCOL_MISMATCH", "This CLI requires app protocol version 1")
    return data


def raise_app_error(data):
    error = data.get("error")
    if error:
        if not isinstance(error, dict) or not isinstance(error.get("code"), str):
            raise CliError("PROTOCOL_ERROR", "Malformed app error")
        raise CliError(error["code"], error.get("message", error["code"]),
                       request_id=data.get("request_id"))
    return data


def validate_request(data, request_id):
    if data.get("request_id") != request_id or not isinstance(data.get("state"), str) or data["state"] not in STATES:
        raise CliError("PROTOCOL_ERROR", "App response does not match request", request_id=request_id)
    if data.get("completed") is not (data["state"] in TERMINAL):
        raise CliError("PROTOCOL_ERROR", "Invalid completion flag", request_id=request_id)
    if not isinstance(data.get("artifacts"), list):
        raise CliError("PROTOCOL_ERROR", "Missing artifact list", request_id=request_id)
    error = data.get("error")
    if error is not None and (not isinstance(error, dict) or not isinstance(error.get("code"), str)
                              or not isinstance(error.get("message"), str)):
        raise CliError("PROTOCOL_ERROR", "Malformed app error", request_id=request_id)
    if data["state"] in {"failed", "cancelled", "interrupted"} and not error:
        raise CliError("PROTOCOL_ERROR", "Terminal failure is missing an error", request_id=request_id)
    return data
