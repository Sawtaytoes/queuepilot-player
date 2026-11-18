#!/usr/bin/env python3
"""A release build installs over a debug install and keeps its sign-in and settings; emulator only.

    python3 tools/upgrade_smoke.py <debug APK the TVs have now> <release APK>

Run it through `queuepilot-test run`, which holds the shared emulator's lock and restores the
person's session afterward. The TVs had debug builds through 0.5.9. The release build is
signed with the same key, so `adb install -r` replaces the app in place and Android keeps its
data: the encrypted Plex tokens, the chosen profile, and the settings. This check signs in
with the debug build, changes a setting, installs the release build over it, and confirms the
app is still signed in, can still decrypt its tokens to load a library, and keeps the setting.
It leaves the release build installed.
"""
import re
import subprocess
import sys
import time

import smoke as s

SIZE = 70  # a Display size that is not the default


def install(apk):
    """adb install -r: replace the app in place. A large APK can take longer than adb()'s 30 s."""
    out = subprocess.run(['adb', '-s', s.SERIAL, 'install', '-r', apk], capture_output=True,
                         text=True, timeout=600)
    assert out.returncode == 0 and 'Success' in out.stdout, out.stdout + out.stderr


def package():
    """The app's install facts: whether it is debuggable, its version, and when it was installed."""
    out = s.adb('shell', 'dumpsys', 'package', s.PACKAGE).decode()
    flags = re.search(r'pkgFlags=\[([^\]]*)\]', out)
    return {
        'is_debuggable': bool(flags) and 'DEBUGGABLE' in flags.group(1).split(),
        'version': re.search(r'versionName=(\S+)', out).group(1),
        'first_install': re.search(r'firstInstallTime=(.+)', out).group(1).strip(),
        'last_update': re.search(r'lastUpdateTime=(.+)', out).group(1).strip(),
    }


def wait_for(text, seconds=30):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if text in s.text():
            return
        time.sleep(.5)
    raise AssertionError('Missing text: ' + text + '\n' + s.text())


def display_size():
    """The Display size row's label in Settings, such as 'Display size · 70%'."""
    s.click('Settings')
    for _ in range(30):
        found = [n.get('text') for n in s.screen().iter('node')
                 if n.get('text', '').startswith('Display size · ')]
        if found:
            return found[0]
        time.sleep(.5)
    raise AssertionError('No Display size row\n' + s.text())


def main():
    old_apk, new_apk = sys.argv[1:3]
    hardware = s.adb('shell', 'getprop', 'ro.hardware').decode().strip()
    assert hardware in ('ranchu', 'goldfish'), 'This test must never target a physical TV'
    s.adb('shell', 'wm', 'size', 'reset')
    s.adb('shell', 'wm', 'density', 'reset')

    # The TV as it is today: a debug build, signed in, with a setting changed.
    # Uninstall first, so the old build installs even when the new one has a higher version.
    s.post('_online')
    subprocess.run(['adb', '-s', s.SERIAL, 'uninstall', s.PACKAGE], capture_output=True, timeout=120)
    install(old_apk)
    before = package()
    assert before['is_debuggable'], f'{old_apk} is not a debug build: {before}'
    s.launch()
    wait_for('Your people. Your queues.')
    s.click('Explore the demo')
    wait_for('What are we watching?')
    s.click('Settings')
    s.click('Connect Plex')
    try:
        wait_for('ABCD')
    except AssertionError:
        # The old build asks the fixture for a code once; on a busy lab that request can fail.
        # One new code, as a person would ask for, is enough; a second failure fails the check.
        assert "Plex couldn't be reached" in s.text(), s.text()
        print('Retry: the debug build could not reach the fixture; asking for a new code', flush=True)
        s.click('New code')
        wait_for('ABCD')
    s.post('_claim')
    wait_for('Demo Cinema')
    current = display_size()
    s.click(current)
    wait_for('Make everything smaller')
    s.click(f'{SIZE}%')
    wait_for(f'Display size · {SIZE}%')
    state = s.app_file('shared_prefs/state.xml')
    secrets = s.app_file('shared_prefs/secrets.xml')
    assert f'name="display-size" value="{SIZE}"' in state, state
    assert 'ciphertext' in secrets
    s.adb('shell', 'am', 'force-stop', s.PACKAGE)

    # The upgrade.
    install(new_apk)
    after = package()
    print(f'Upgrade: {before["version"]} debug -> {after["version"]} '
          f'{"debug" if after["is_debuggable"] else "release"}', flush=True)
    assert not after['is_debuggable'], f'{new_apk} is debuggable: {after}'
    assert after['first_install'] == before['first_install'], (before, after)
    # The release build's files cannot be read (smoke.app_file), so the screens below show that
    # the saved state and tokens survived: still signed in, a library loads, and the size stays.

    # The release build opens signed in, decrypts its tokens to load a library, and keeps
    # the setting.
    s.adb('logcat', '-b', 'crash', '-c')
    s.launch()
    wait_for('What are we watching?')
    wait_for('Demo Cinema')
    # Home's own line, "Your people. Your queues. Your library.", contains the welcome screen's
    # tagline, so the signs of a signed-out app are the welcome's demo button and the demo Home's
    # "Sign in with Plex" card. "Demo Cinema" and "Harbor Lights" exist only on the fixture
    # server, not in the app's demo, so they show a signed-in account and a decrypted token.
    home = s.text()
    assert 'Explore the demo' not in home and 'Sign in with Plex' not in home, \
        'The upgrade signed the app out\n' + home
    s.click('Series')
    s.open_library('Series')
    wait_for('Harbor Lights')
    s.click('Home')
    assert display_size() == f'Display size · {SIZE}%', 'The upgrade lost the Display size'
    crashes = s.adb('logcat', '-d', '-b', 'crash').decode()
    assert 'FATAL EXCEPTION' not in crashes, crashes
    print('PASS: the release build installs over the debug build in place; it stays signed in, '
          'decrypts its tokens to load a library, and keeps the Display size')


if __name__ == '__main__':
    main()
