#!/usr/bin/env python3
"""Tests for the launcher handler in tools/smoke.py, with adb replaced: python3 tools/smoke_launcher_test.py"""
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import smoke  # noqa: E402

APP = smoke.PACKAGE
LAUNCHER = 'com.google.android.tvlauncher'


def window(package, *buttons):
    nodes = ''.join(f'<node package="{package}" text="{text}" bounds="[{10 * i},0][{10 * i + 8},8]"/>'
                    for i, text in enumerate(buttons))
    return ET.fromstring(f'<hierarchy><node package="{package}" text="" bounds="[0,0][100,100]">'
                         f'{nodes}</node></hierarchy>')


class Device:
    """A scripted emulator: each read returns the next value, and the last one repeats."""

    def __init__(self, dumps, focus, activity=('a1',), process=('101',)):
        self.dumps, self.focus = list(dumps), list(focus)
        self.activity, self.process = list(activity), list(process)
        self.taps, self.launches = [], 0

    @staticmethod
    def next(values):
        return values.pop(0) if len(values) > 1 else values[0]

    def adb(self, *args):
        if args[:3] == ('shell', 'input', 'tap'):
            self.taps.append((int(args[3]), int(args[4])))
        return b''

    def launch(self):
        self.launches += 1

    def patches(self):
        return [mock.patch.object(smoke, 'dump', lambda: self.next(self.dumps)),
                mock.patch.object(smoke, 'focused_package', lambda: self.next(self.focus)),
                mock.patch.object(smoke, 'app_activity', lambda: self.next(self.activity)),
                mock.patch.object(smoke, 'app_process', lambda: self.next(self.process)),
                mock.patch.object(smoke, 'adb', self.adb),
                mock.patch.object(smoke, 'launch', self.launch),
                mock.patch.object(smoke.time, 'sleep', lambda seconds: None)]


class LauncherHandlerTest(unittest.TestCase):
    def run_screen(self, device):
        patches = device.patches()
        for patch in patches:
            patch.start()
        self.addCleanup(lambda: [patch.stop() for patch in patches])
        with mock.patch('builtins.print'):
            return smoke.screen()

    def test_app_in_front_is_read_as_is(self):
        device = Device([window(APP, 'Continue')], [APP])
        self.assertEqual(smoke.window_package(self.run_screen(device)), APP)
        self.assertEqual(device.taps, [])

    def test_promotion_is_dismissed_and_the_same_app_returns(self):
        promotion = window(LAUNCHER, 'Buy and rent movies on your TV', 'Go to Shop', 'Dismiss')
        device = Device([promotion, promotion, window(APP)], [LAUNCHER] * 8 + [APP])
        self.assertEqual(smoke.window_package(self.run_screen(device)), APP)
        self.assertEqual(device.taps, [(24, 4)])  # Dismiss, never Go to Shop

    def test_launcher_home_without_a_button_brings_the_app_forward(self):
        home = window(LAUNCHER, 'Home', 'Live', 'Shop')
        device = Device([home, home, window(APP)], [LAUNCHER] * 9 + [APP])
        self.run_screen(device)
        self.assertEqual(device.taps, [])
        self.assertEqual(device.launches, 1)

    def test_app_that_came_back_during_the_wait_is_not_tapped(self):
        # Round 9: the fresh dump is the app's own screen, which has a Continue button.
        device = Device([window(LAUNCHER, 'Continue'), window(APP, 'Continue')],
                        [LAUNCHER] * 6 + [APP])
        self.run_screen(device)
        self.assertEqual(device.taps, [])

    def test_focus_back_on_the_app_before_identity_is_not_tapped(self):
        # The fresh window is still the launcher's, but the focus has gone back to the app. Read
        # after that, a launcher focus must not lead to a tap: this attempt has already ended.
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        device = Device([promotion, promotion, window(APP)], [LAUNCHER] * 6 + [APP, LAUNCHER, APP])
        self.run_screen(device)
        self.assertEqual((device.taps, device.launches), ([], 0))

    def test_focus_back_on_the_app_before_the_tap_is_not_tapped(self):
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        device = Device([promotion, promotion, window(APP)], [LAUNCHER] * 7 + [APP])
        self.run_screen(device)
        self.assertEqual((device.taps, device.launches), ([], 0))

    def test_focus_back_before_the_tap_still_checks_identity(self):
        # Round 11: the app that came back must be the same screen and process.
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        for changed in ({'activity': ['a1', 'a2']}, {'process': ['101', '202']}):
            with self.subTest(**{key: '->'.join(value) for key, value in changed.items()}):
                device = Device([promotion, promotion, window(APP)], [LAUNCHER] * 7 + [APP], **changed)
                with self.assertRaisesRegex(AssertionError, 'recreated'):
                    self.run_screen(device)
                self.assertEqual(device.taps, [])

    def test_attempts_that_find_the_app_back_still_count(self):
        # A window and a focus that keep disagreeing end after three attempts, with no tap.
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        device = Device([promotion], ([LAUNCHER] * 6 + [APP]) * 3)
        with self.assertRaisesRegex(AssertionError, 'three recovery attempts'):
            self.run_screen(device)
        self.assertEqual((device.taps, device.launches), ([], 0))

    def test_a_gone_process_fails(self):
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        device = Device([promotion], [LAUNCHER], process=[''])
        with self.assertRaisesRegex(AssertionError, 'stopped'):
            self.run_screen(device)

    def test_a_new_screen_fails(self):
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        device = Device([promotion], [LAUNCHER] * 9 + [APP], activity=['a1', 'a2'])
        with self.assertRaisesRegex(AssertionError, 'recreated'):
            self.run_screen(device)

    def test_an_app_that_does_not_return_fails(self):
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        device = Device([promotion], [LAUNCHER])
        with self.assertRaisesRegex(AssertionError, 'did not return'):
            self.run_screen(device)

    def test_a_failed_diagnostic_does_not_stop_recovery(self):
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        failures = [smoke.subprocess.TimeoutExpired('adb', 30), smoke.subprocess.CalledProcessError(1, 'adb')]
        for failure in failures:
            with self.subTest(type(failure).__name__):
                device = Device([promotion, promotion, window(APP)], [LAUNCHER] * 8 + [APP])
                tap = device.adb

                def adb(*args, failure=failure, tap=tap):
                    if args[:1] == ('shell',) and 'mCurrentFocus' in args[-1]:
                        raise failure
                    return tap(*args)
                device.adb = adb
                self.run_screen(device)
                self.assertEqual(len(device.taps), 1)

    def test_a_new_process_fails(self):
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        device = Device([promotion], [LAUNCHER] * 8 + [APP], process=['101', '202'])
        with self.assertRaisesRegex(AssertionError, 'recreated'):
            self.run_screen(device)

    def test_a_gone_screen_fails(self):
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        device = Device([promotion], [LAUNCHER], activity=[''])
        with self.assertRaisesRegex(AssertionError, 'stopped'):
            self.run_screen(device)

    def test_a_launcher_that_keeps_coming_back_fails_after_three_recoveries(self):
        promotion = window(LAUNCHER, 'Free live TV channels', 'Continue')
        focus = ([LAUNCHER] * 9 + [APP]) * 3 + [LAUNCHER]
        device = Device([promotion], focus)
        with self.assertRaisesRegex(AssertionError, 'three recovery attempts'):
            self.run_screen(device)
        self.assertEqual(len(device.taps), 3)


if __name__ == '__main__':
    unittest.main()
