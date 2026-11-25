"""Map attempt failures to the backend's retry policy, without exposing exception details."""

import httpx


# The engine wrapper reports invalid data and transient input failures separately.
INVALID_ENGINE_DATA_EXIT = 65
ENGINE_DEPENDENCY_EXIT = 75
LEASE_CONFLICTS = ("JOB_LEASE_LOST", "CALLBACK_CONFLICT")


class InvalidEngineData(ValueError):
    pass


class InvalidArtifact(ValueError):
    pass


class EngineDependencyError(RuntimeError):
    pass


class FurnitureFailure(RuntimeError):
    def __init__(self, code, message, retryable):
        super().__init__(code)
        self.report = (code, message, retryable)


def problem_code(response):
    try:
        body = response.json()
    except ValueError:
        return None
    code = body.get("code") if isinstance(body, dict) else None
    return code if isinstance(code, str) else None


def retryable_status(status):
    return status in (408, 429) or 500 <= status < 600


def failure_for(error, job_type):
    if isinstance(error, FurnitureFailure):
        return error.report
    if isinstance(error, httpx.HTTPStatusError):
        status = error.response.status_code
        code = problem_code(error.response)
        if status == 409 and code in LEASE_CONFLICTS:
            return None  # This attempt no longer owns the output or callback.
        if retryable_status(status) or (
            status == 403 and error.request.method == "PUT" and code == "INVALID_STORAGE_GRANT"
        ):
            return "DEPENDENCY_UNAVAILABLE", "결과를 전송하는 서비스에 일시적인 오류가 발생했습니다.", True
        if 400 <= status < 500:
            return "INVALID_ARTIFACT", f"서버가 작업 결과를 거절했습니다(HTTP {status}).", False
    if isinstance(error, TimeoutError):
        return "WORKER_TIMEOUT", "AI 처리 시간이 초과됐습니다.", True
    if isinstance(error, (httpx.RequestError, EngineDependencyError)):
        return "DEPENDENCY_UNAVAILABLE", "서버와 통신하지 못했습니다.", True
    if isinstance(error, InvalidArtifact):
        return "INVALID_ARTIFACT", "작업 결과 파일이 없거나 형식이 올바르지 않습니다.", False
    if isinstance(error, InvalidEngineData):
        if job_type == "ROOM_OBJECT_REMOVAL":
            return "REMOVAL_FAILED", "가구 지우기에 필요한 방 데이터가 올바르지 않습니다.", False
        return "RECONSTRUCTION_FAILED", "복원에 필요한 입력 또는 결과 데이터가 올바르지 않습니다.", False
    return "INTERNAL_ERROR", "AI 작업을 처리하는 중 오류가 발생했습니다.", True
