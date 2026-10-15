import os
import json
import subprocess
import logging
from typing import Dict, Any, List
from app.config import settings

logger = logging.getLogger(__name__)

class CppEngineClient:
    def __init__(self, executable_path: str = None):
        self.executable_path = executable_path or settings.cpp_engine_path
        if not os.path.isabs(self.executable_path):
            # Resolve relative to project root
            base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
            self.executable_path = os.path.join(base_dir, self.executable_path)

    def _ensure_executable(self):
        if not os.path.exists(self.executable_path):
            raise FileNotFoundError(
                f"C++ Ranking Engine executable not found at '{self.executable_path}'. "
                "Please run `cmake --build ranking_engine/build` first."
            )

    def get_version(self) -> str:
        self._ensure_executable()
        res = subprocess.run(
            [self.executable_path, "--version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=5
        )
        if res.returncode == 0:
            data = json.loads(res.stdout)
            return data.get("algorithm_version", "unknown")
        return "1.0.0-phase1"

    def execute_ranking(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self._ensure_executable()
        input_json = json.dumps(payload)
        res = subprocess.run(
            [self.executable_path, "--stdin-json"],
            input=input_json,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=10
        )
        if res.returncode != 0:
            logger.error("C++ Ranking Engine error: %s", res.stderr)
            raise RuntimeError(f"C++ engine failed with exit code {res.returncode}: {res.stderr}")
        return json.loads(res.stdout)

    def execute_experiments(self) -> Dict[str, Any]:
        self._ensure_executable()
        res = subprocess.run(
            [self.executable_path, "--test-experiments"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=5
        )
        if res.returncode != 0:
            raise RuntimeError(f"C++ engine experiments failed: {res.stderr}")
        return json.loads(res.stdout)

    def execute_eval(self) -> Dict[str, Any]:
        self._ensure_executable()
        res = subprocess.run(
            [self.executable_path, "--eval"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=5
        )
        if res.returncode != 0:
            raise RuntimeError(f"C++ engine evaluation failed: {res.stderr}")
        return json.loads(res.stdout)

cpp_client = CppEngineClient()
