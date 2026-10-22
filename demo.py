#!/usr/bin/env python
"""
Issue Pilot - Final-Year Project Demonstration Runner.
Usage:
    python demo.py
"""

import os
import sys

backend_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "backend"))
if backend_dir not in sys.path:
    sys.path.insert(0, backend_dir)

from app.demo_runner import run_demonstration

if __name__ == "__main__":
    run_demonstration()
