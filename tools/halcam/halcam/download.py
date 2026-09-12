"""Verified, resumable-by-file artifact collection."""

import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
from .protocol import CliError, identifier


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def collect(adb, response, output, timeout=60):
    request_id = identifier(response["request_id"])
    directory = Path(output).expanduser().resolve() / request_id
    if directory.is_symlink():
        raise CliError("OUTPUT_EXISTS", "Request output directory is a symlink", request_id=request_id)
    directory.mkdir(parents=True, exist_ok=True)
    files, names = [], set()
    for artifact in response["artifacts"]:
        if not isinstance(artifact, dict):
            raise CliError("PROTOCOL_ERROR", "Invalid artifact metadata", request_id=request_id)
        name, aid = artifact.get("name"), artifact.get("artifact_id")
        size, checksum = artifact.get("size_bytes"), artifact.get("sha256")
        if (not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_-]+\.(?:jpg|json|zip)", name)
                or name.split(".")[0].upper() in {"CON", "PRN", "AUX", "NUL", *[f"COM{i}" for i in range(1, 10)], *[f"LPT{i}" for i in range(1, 10)]}
                or name.lower() in names or not isinstance(aid, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", aid)
                or not isinstance(size, int) or isinstance(size, bool) or not 0 < size <= 128 * 1024 * 1024
                or not isinstance(checksum, str) or not re.fullmatch(r"[0-9a-f]{64}", checksum)):
            raise CliError("PROTOCOL_ERROR", "Invalid artifact metadata", request_id=request_id)
        names.add(name.lower())
        target = directory / name
        if target.is_symlink():
            raise CliError("OUTPUT_EXISTS", f"Refusing symlink: {target}", request_id=request_id)
        if target.exists():
            if target.is_file() and target.stat().st_size == size and digest(target) == checksum:
                files.append(str(target))
                continue
            raise CliError("OUTPUT_EXISTS", f"Existing file differs: {target}", request_id=request_id)
        fd, temporary = tempfile.mkstemp(prefix=name + ".", suffix=".part", dir=directory)
        part = Path(temporary)
        try:
            with os.fdopen(fd, "wb") as stream:
                process = subprocess.Popen(adb.argv("exec-out", "content", "read", "--uri",
                    f"{adb.authority}/v1/requests/{request_id}/artifacts/{aid}"),
                    stdout=stream, stderr=subprocess.PIPE,
                    creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                try:
                    _, error = process.communicate(timeout=timeout)
                except (subprocess.TimeoutExpired, KeyboardInterrupt):
                    process.kill()
                    process.communicate()
                    raise
            if process.returncode or error:
                raise CliError("DOWNLOAD_FAILED", error.decode("utf-8", "replace") or "ADB transfer failed", request_id=request_id)
            if part.stat().st_size != size or digest(part) != checksum:
                raise CliError("CHECKSUM_MISMATCH", f"Incomplete or corrupt artifact: {name}", request_id=request_id)
            # Same-directory hard link publishes verified bytes atomically, without replacement.
            os.link(part, target)
            files.append(str(target))
        except subprocess.TimeoutExpired:
            raise CliError("DOWNLOAD_FAILED", "Transfer timed out; use fetch to recover", request_id=request_id) from None
        except OSError as error:
            raise CliError("DOWNLOAD_FAILED", str(error), request_id=request_id) from error
        finally:
            part.unlink(missing_ok=True)
    return files
