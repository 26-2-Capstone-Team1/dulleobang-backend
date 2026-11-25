"""Run and reap the GPU engine process group within the worker lease deadline."""

import os
import signal
import subprocess
import sys
import time

from .failures import (
    ENGINE_DEPENDENCY_EXIT,
    INVALID_ENGINE_DATA_EXIT,
    EngineDependencyError,
    InvalidEngineData,
)

# Leave time to publish artifacts before the backend's two-hour attempt deadline.
ENGINE_TIMEOUT_SECONDS = 2 * 60 * 60 - 100


def stop_process(task):
    if task.poll() is not None:
        return
    try:
        os.killpg(task.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        task.wait(timeout=15)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(task.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        task.wait()


def wait_for_engine(task, lost, timeout=ENGINE_TIMEOUT_SECONDS, tick=None):
    deadline = time.monotonic() + timeout
    while task.poll() is None:
        if lost.is_set():
            raise RuntimeError("Worker lease was lost")
        if time.monotonic() > deadline:
            raise TimeoutError("Worker attempt time limit expired")
        if tick is not None:
            tick()
        time.sleep(1)
    if task.returncode == INVALID_ENGINE_DATA_EXIT:
        raise InvalidEngineData("Invalid engine input or export; inspect engine.log")
    if task.returncode == ENGINE_DEPENDENCY_EXIT:
        raise EngineDependencyError("Engine input service unavailable; inspect engine.log")
    if task.returncode:
        raise RuntimeError("AI engine failed; inspect engine.log")


def run_engine(environment, log_path, lost, timeout=ENGINE_TIMEOUT_SECONDS, tick=None):
    if lost.is_set():
        raise RuntimeError("Worker lease was lost")
    if timeout <= 0:
        raise TimeoutError("Worker attempt time limit expired")
    with log_path.open("wb") as log:
        task = subprocess.Popen(
            [sys.executable, "-m", "ai_worker.engine"],
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            wait_for_engine(task, lost, timeout, tick)
        finally:
            stop_process(task)
