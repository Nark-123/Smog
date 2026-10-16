#!/usr/bin/env python3
"""Compatibility entry point for the bounded performance regression suite."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).with_name('run_performance_tests.py')),run_name='__main__')
