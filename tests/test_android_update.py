"""Public safe-update entry + controlled external commands; never contacts devices."""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class SafeUpdateTests(unittest.TestCase):
    def run_case(self, scenario="normal", **options):
        with tempfile.TemporaryDirectory(prefix="safe update ") as directory:
            result = subprocess.run(
                ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                 str(ROOT / "tests" / "android_update_harness.ps1"),
                 "-Scenario", scenario, "-Workspace", directory,
                 "-OptionsJson", json.dumps(options)],
                capture_output=True, encoding="utf-8", errors="replace", timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            return json.loads(result.stdout.strip().splitlines()[-1])

    def test_unique_installed_app_updates_and_launches_once(self):
        result = self.run_case()
        self.assertTrue(result["ok"], result)
        self.assertEqual(result["installs"], 1)
        self.assertEqual(result["launches"], 1)
        self.assertIn("-PbluetoothTestApp=false", result["build"])
        self.assertEqual(result["install"][:6], ["-s", "fake-serial", "install", "--user", "10", "-r"])
        self.assertEqual(len(result['install']), 7)
        self.assertEqual(pathlib.Path(result['install'][6]).name, '更新 APK.apk')

    def test_real_native_process_receives_separate_install_argv_with_unicode_spaces(self):
        with tempfile.TemporaryDirectory(prefix='更新 APK 空白 ') as directory:
            workspace = pathlib.Path(directory).resolve() / '隔離 輸出'
            config = pathlib.Path(directory) / 'config.json'
            config.write_text(json.dumps(dict(Python=sys.executable,
                Probe=str(ROOT / 'tests/android_update_native_probe.py'),
                Root=str(ROOT), Workspace=str(workspace)), ensure_ascii=False), encoding='utf-8')
            process = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                '-File', str(ROOT / 'tests/android_update_native.ps1'), '-Config', str(config)],
                capture_output=True, encoding='utf-8', errors='replace', timeout=60)
            self.assertEqual(process.returncode, 0, process.stdout + process.stderr)
            report = json.loads(process.stdout.strip().splitlines()[-1])
            self.assertTrue(report['Launched'])
            commands = [json.loads(line) for line in (workspace / 'native-argv.jsonl').read_text(encoding='utf-8').splitlines()]
            installs = [args for tool, args in commands if tool == 'adb' and args[2:3] == ['install']]
            self.assertEqual(installs, [['-s', 'fake-serial', 'install', '--user', '10', '-r', str(workspace / '更新 APK.apk')]])

    def test_native_nonzero_exit_is_not_hidden_by_success_message(self):
        with tempfile.TemporaryDirectory() as directory:
            workspace = pathlib.Path(directory).resolve() / 'output'
            config = pathlib.Path(directory) / 'config.json'
            config.write_text(json.dumps(dict(Python=sys.executable,
                Probe=str(ROOT / 'tests/android_update_native_probe.py'),
                Root=str(ROOT), Workspace=str(workspace))), encoding='utf-8')
            environment = dict(os.environ, SAFE_UPDATE_TEST_FAIL_TOOL='gradle')
            process = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                '-File', str(ROOT / 'tests/android_update_native.ps1'), '-Config', str(config)],
                capture_output=True, encoding='utf-8', errors='replace', timeout=60, env=environment)
            self.assertEqual(process.returncode, 1, process.stdout + process.stderr)
            self.assertIn('simulated native failure', process.stderr)
            commands = [json.loads(line) for line in (workspace / 'native-argv.jsonl').read_text(encoding='utf-8').splitlines()]
            self.assertFalse(any(tool == 'adb' and args[2:3] == ['install'] for tool, args in commands))

    def test_cli_rejects_missing_toolchain_without_contacting_any_device(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = pathlib.Path(directory) / 'SDK'
            sdk.mkdir()
            commands = [
                ['-SdkRoot', str(sdk / 'missing')],
                ['-SdkRoot', str(sdk), '-BuildToolsVersion', '36.0.0'],
                ['-SdkRoot', str(sdk), '-BuildToolsVersion', '../escape'],
                ['-SdkRoot', str(sdk), '-SkipSignature'],
                ['-SdkRoot', str(sdk), '-ForceInstall'],
            ]
            for arguments in commands:
                process = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                    '-File', str(ROOT / 'tools/android/update.ps1'), *arguments],
                    capture_output=True, encoding='utf-8', errors='replace', timeout=30)
                self.assertNotEqual(process.returncode, 0, process.stdout + process.stderr)

    def test_cli_validates_java_before_running_any_placeholder_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = pathlib.Path(directory) / 'SDK'
            for relative in ['platform-tools/adb.exe', 'build-tools/36.0.0/aapt2.exe', 'build-tools/36.0.0/apksigner.bat']:
                file = sdk / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.touch()  # Not executable: the CLI must stop before invoking it.
            environment = dict(os.environ, JAVA_HOME=str(pathlib.Path(directory) / 'missing-jdk'))
            process = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                '-File', str(ROOT / 'tools/android/update.ps1'), '-SdkRoot', str(sdk)],
                capture_output=True, encoding='utf-8', errors='replace', timeout=30, env=environment)
            self.assertEqual(process.returncode, 1)
            self.assertIn('JAVA_HOME', process.stderr)

    def test_powershell_syntax(self):
        paths = [ROOT / 'tools/android/update.ps1', ROOT / 'tools/android/SafeUpdate.psm1',
                 ROOT / 'tests/android_update_harness.ps1', ROOT / 'tests/android_update_native.ps1']
        code = ';'.join(
            "$t=$null;$e=$null;[System.Management.Automation.Language.Parser]::ParseFile('"
            + str(path).replace("'", "''") + "',[ref]$t,[ref]$e)|Out-Null;if($e.Count){throw ($e|Out-String)}"
            for path in paths)
        process = subprocess.run(['powershell', '-NoProfile', '-Command', code], capture_output=True, timeout=30)
        self.assertEqual(process.returncode, 0, process.stdout + process.stderr)

    def test_bluetooth_selects_explicit_build_setting(self):
        result = self.run_case('bluetooth')
        self.assertTrue(result['ok'], result)
        self.assertIn('-PbluetoothTestApp=true', result['build'])
        self.assertEqual(result['report']['Target']['Package'], 'com.example.talktoagent.bluetoothdebug')

    def test_check_only_has_no_device_writes(self):
        result = self.run_case(CheckOnly=True)
        self.assertTrue(result['ok'], result)
        self.assertEqual((result['installs'], result['launches']), (0, 0))
        self.assertFalse(result['report']['Installed'])

    def test_explicit_user_is_not_replaced_by_current_user(self):
        result = self.run_case(UserId=0)
        self.assertTrue(result['ok'], result)
        self.assertEqual(result['install'][4], '0')
        result = self.run_case('current-switch', UserId=10)
        self.assertTrue(result['ok'], result)

    def test_explicit_serial_resolves_other_device_ambiguity(self):
        result = self.run_case('two-devices', Serial='fake-serial')
        self.assertTrue(result['ok'], result)

    def test_explicit_package_resolves_package_ambiguity(self):
        result = self.run_case('two-packages', Package='com.example.talktoagent')
        self.assertTrue(result['ok'], result)

    def test_explicit_missing_serial_never_falls_back(self):
        result = self.run_case(Serial='missing')
        self.assertFalse(result['ok'], result)
        self.assertEqual(result['installs'], 0)

    def test_explicit_missing_package_never_falls_back(self):
        result = self.run_case(Package='com.example.talktoagent.bluetoothdebug')
        self.assertFalse(result['ok'], result)
        self.assertEqual(result['installs'], 0)

    def test_nonproject_package_and_negative_user_rejected(self):
        for options in [dict(Package='com.example.talktoagent.test'), dict(UserId=-1), dict(Package=''), dict(Serial=''), dict(UserId=None)]:
            result = self.run_case(**options)
            self.assertFalse(result['ok'], result)
            self.assertEqual(result['installs'], 0)


PREINSTALL_REJECTIONS = (
    'offline unauthorized device-query-fail no-devices two-devices current-unknown '
    'user-denied user-query-fail user-absent absent only-diagnosis substring two-packages '
    'package-query-fail package-denied state-error sdk-low sdk-unknown path-error split '
    'pull-error build-error missing-artifact multiple-artifacts wrong-package old-wrong-package '
    'downgrade bad-metadata metadata-error apk-split signer-mismatch signer-error old-signer-error '
    'signer-unverified signer-missing source-stamp multi-partial multi-extra '
    'target-disappears current-switch state-drift artifact-drift path-drift old-drift '
    'sdk-drift old-metadata-drift max-sdk-low sdk-preview apk-read-drift duplicate-signer'
).split()


def rejection_test(scenario):
    def test(self):
        result = self.run_case(scenario)
        self.assertFalse(result['ok'], result)
        self.assertEqual((result['installs'], result['launches']), (0, 0), result)
        self.assertNotIn('Unexpected', result['error'], result)
    return test


for scenario in PREINSTALL_REJECTIONS:
    setattr(SafeUpdateTests, 'test_reject_' + scenario.replace('-', '_'), rejection_test(scenario))


def success_test(scenario):
    def test(self):
        result = self.run_case(scenario)
        self.assertTrue(result['ok'], result)
        self.assertEqual((result['installs'], result['launches']), (1, 1), result)
    return test


for scenario in ['upgrade', 'multi-match', 'multi-order', 'real-min-sdk', 'valid-source-stamp']:
    setattr(SafeUpdateTests, 'test_accept_' + scenario.replace('-', '_'), success_test(scenario))


def postinstall_test(scenario):
    def test(self):
        result = self.run_case(scenario)
        self.assertFalse(result['ok'], result)
        self.assertEqual(result['installs'], 1, result)
        self.assertEqual(result['launches'], int(scenario.startswith('launch-')), result)
        self.assertNotIn('Unexpected', result['error'], result)
        if scenario.startswith('install-'):
            self.assertIn('可能已', result['error'])
        else:
            self.assertIn('已', result['error'])
    return test


for scenario in ['install-error', 'install-unknown', 'after-hash', 'after-package', 'after-query-fail', 'launch-error', 'launch-unknown']:
    setattr(SafeUpdateTests, 'test_stop_without_retry_' + scenario.replace('-', '_'), postinstall_test(scenario))


if __name__ == "__main__":
    unittest.main()
