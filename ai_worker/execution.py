"""Run and reap the GPU engine process group within the worker lease deadline."""

import os
import signal
import subprocess
import sys
import time


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


def wait_for_engine(task, lost):
    deadline = time.monotonic() + 1700
    while task.poll() is None:
        if lost.is_set() or time.monotonic() > deadline:
            raise RuntimeError("Worker lease or attempt time limit expired")
        time.sleep(1)
    if task.returncode:
        raise RuntimeError("PR #2 engine failed; inspect engine.log")


def run_engine(environment, log_path, lost):
    with log_path.open("wb") as log:
        task = subprocess.Popen(
            [sys.executable, "-m", "ai_worker.engine"],
            env=environment,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            wait_for_engine(task, lost)
        finally:
            stop_process(task)
