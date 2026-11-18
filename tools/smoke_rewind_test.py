#!/usr/bin/env python3
"""Tests for smoke.is_rewind, the resume-rewind bound native_smoke.py uses: python3 tools/smoke_rewind_test.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import smoke  # noqa: E402


class IsRewind(unittest.TestCase):
    def test_off_allows_the_pause_delay_forward_only(self):
        for step_back in (0, -481, -659, -999, 150):
            self.assertTrue(smoke.is_rewind(step_back, 0), step_back)
        for step_back in (-1000, -1500, 151):
            self.assertFalse(smoke.is_rewind(step_back, 0), step_back)

    def test_off_catches_a_one_second_rewind_unless_the_delay_passes_850_ms(self):
        for delay in (0, 400, 659, 849):
            self.assertFalse(smoke.is_rewind(1000 - delay, 0), delay)

    def test_three_seconds_allows_the_delay_and_rejects_the_neighbors(self):
        for step_back in (3000, 2541, 2341, 2001, 3150):
            self.assertTrue(smoke.is_rewind(step_back, 3), step_back)
        for step_back in (2000, 1000, 3151, 5000, 4100):
            self.assertFalse(smoke.is_rewind(step_back, 3), step_back)


if __name__ == '__main__':
    unittest.main()
