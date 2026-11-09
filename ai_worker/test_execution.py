"""Lease loss and timeout must reap the GPU process before an attempt exits."""

from pathlib import Path
import signal
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
from .execution import run_engine


class ExecutionTests(unittest.TestCase):
    def execute(self, task, lost):
        with tempfile.TemporaryDirectory() as folder:
            run_engine({}, Path(folder) / "engine.log", lost)

    def test_lease_loss_stops_and_reaps_engine(self):
        task = Mock(pid=1234, returncode=None)
        task.poll.return_value = None
        lost = threading.Event()
        lost.set()
        with (
            patch("ai_worker.execution.subprocess.Popen", return_value=task),
            patch("ai_worker.execution.os.killpg", create=True) as kill,
        ):
            with self.assertRaisesRegex(RuntimeError, "lease"):
                self.execute(task, lost)
        kill.assert_called_once_with(task.pid, signal.SIGTERM)
        task.wait.assert_called_once_with(timeout=15)

    def test_timeout_escalates_and_reaps_unresponsive_engine(self):
        task = Mock(pid=1234, returncode=None)
        task.poll.return_value = None
        task.wait.side_effect = [subprocess.TimeoutExpired("engine", 15), 0]
        with (
            patch("ai_worker.execution.subprocess.Popen", return_value=task),
            patch("ai_worker.execution.time.monotonic", side_effect=[0, 1701]),
            patch("ai_worker.execution.signal.SIGKILL", 9, create=True),
            patch("ai_worker.execution.os.killpg", create=True) as kill,
        ):
            with self.assertRaisesRegex(RuntimeError, "time limit"):
                self.execute(task, threading.Event())
        self.assertEqual([call.args[1] for call in kill.call_args_list], [signal.SIGTERM, 9])
        self.assertEqual(task.wait.call_count, 2)

    def test_success_does_not_signal_completed_engine(self):
        task = Mock(pid=1234, returncode=0)
        task.poll.return_value = 0
        with (
            patch("ai_worker.execution.subprocess.Popen", return_value=task),
            patch("ai_worker.execution.os.killpg", create=True) as kill,
        ):
            self.execute(task, threading.Event())
        kill.assert_not_called()

    def test_engine_failure_is_reported(self):
        task = Mock(pid=1234, returncode=1)
        task.poll.return_value = 1
        with patch("ai_worker.execution.subprocess.Popen", return_value=task):
            with self.assertRaisesRegex(RuntimeError, "engine failed"):
                self.execute(task, threading.Event())
