#!/usr/bin/env python3
"""Tests for the dumpsys parsers the stats and performance probes use: python3 tools/stats_probe_test.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import smoke  # noqa: E402
import stats_probe  # noqa: E402

APP = smoke.PACKAGE
APP_ACTIVITY = f'ActivityRecord{{a1 u0 {APP}/dev.queuepilot.player.MainActivity t12}}'
LAUNCHER = 'ActivityRecord{b2 u0 com.google.android.tvlauncher/.MainActivity t3}'

# One dump per Android release, with the app behind the launcher, or in front.
ANDROID_12 = """ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
Display #0 (activities from top to bottom):
  * Task{c3 #3 type=home}
    * Hist #0: {launcher}
    mResumedActivity: {launcher}
  * Task{d4 #12 type=standard A={app}}
    * Hist #0: {app}
  ResumedActivity: {launcher}
"""
ANDROID_13 = """Display #0 (activities from top to bottom):
  * Task{c3 #3 type=home}
    topResumedActivity={launcher}
    * Hist #0: {launcher}
  * Task{d4 #12 type=standard}
    mLastPausedActivity: {app}
    * Hist #0: {app}
"""
ANDROID_14 = """Display #0 (activities from top to bottom):
  * Task{c3 #3 type=home}
    * Hist #0: {launcher}
  * Task{d4 #12 type=standard}
    * Hist #0: {app}
  Resumed: {launcher}
"""


def dump(template, front):
    launcher, app = (APP_ACTIVITY, LAUNCHER) if front else (LAUNCHER, APP_ACTIVITY)
    return template.replace('{launcher}', launcher).replace('{app}', app)


class ResumedActivities(unittest.TestCase):
    def test_every_release_reads_the_resumed_activity(self):
        for name, template in (('12', ANDROID_12), ('13', ANDROID_13), ('14', ANDROID_14)):
            with self.subTest(android=name):
                behind = smoke.resumed_activities(dump(template, front=False))
                self.assertTrue(behind)
                self.assertFalse(smoke.is_in_front(behind))
                self.assertTrue(smoke.is_in_front(smoke.resumed_activities(dump(template, front=True))))

    def test_a_paused_or_listed_app_is_not_in_front(self):
        lines = smoke.resumed_activities(dump(ANDROID_13, front=False))
        self.assertFalse(any('Hist' in line or 'Paused' in line for line in lines))

    def test_a_lookalike_package_is_not_the_app(self):
        other = dump(ANDROID_14, front=True).replace(APP + '/', APP + '.debug/')
        self.assertFalse(smoke.is_in_front(smoke.resumed_activities(other)))

    def test_no_resumed_line_reads_as_nothing(self):
        self.assertEqual([], smoke.resumed_activities('Display #0\n  * Task{c3}\n'))


SESSIONS = f"""  Sessions Stack - have 3 sessions:
    QueuePilot {APP}/a (userId=0)\r
      ownerPid=1, ownerUid=2, userId=0\r
      package={APP}\r
      state=null\r
    Other com.example.other/y (userId=0)
      package=com.example.other
      state=PlaybackState {{state=3, position=0}}
    QueuePilot {APP}/b (userId=0)
      package={APP}
      state=PlaybackState {{state=PAUSED(2), position=5}}
"""


class SessionStates(unittest.TestCase):
    def test_reads_each_of_the_apps_sessions_in_either_format(self):
        self.assertEqual([None, 2], stats_probe.session_states(SESSIONS))
        self.assertEqual([None, 3], stats_probe.session_states(SESSIONS.replace('PAUSED(2)', '3')))

    def test_another_apps_state_never_counts(self):
        # The app's session without a state, then another app's paused one: still not paused.
        without_the_live_session = SESSIONS[:SESSIONS.index(f'    QueuePilot {APP}/b')]
        self.assertEqual([None], stats_probe.session_states(without_the_live_session))
        self.assertEqual([], stats_probe.session_states(SESSIONS.replace(f'package={APP}', f'package={APP}.debug')))


if __name__ == '__main__':
    unittest.main()
