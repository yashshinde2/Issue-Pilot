#!/usr/bin/env python
"""
Issue Pilot CLI Driver script.
Usage:
    python cli.py demo
    python cli.py benchmark
    python cli.py evaluate
    python cli.py discover
    python cli.py refresh
    python cli.py cleanup
    python cli.py run-all
    python cli.py status
    python cli.py scheduler [--daemon]
    python cli.py jobs
"""
import sys
import os

# Automatically add backend to Python module search path
backend_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "backend"))
if backend_dir not in sys.path:
    sys.path.insert(0, backend_dir)

from app.cli import main

if __name__ == "__main__":
    main()
