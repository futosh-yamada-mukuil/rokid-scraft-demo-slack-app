import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("create_rokid_avd", Path(__file__).resolve().parents[1] / "create-rokid-avd.py")
avd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(avd)


class CreateAvdTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sdk = self.root / "sdk"
        self.avd_home = self.root / "avd"

    def image(self, api, arch):
        directory = self.sdk / "system-images" / f"android-{api}" / "google_apis" / arch
        directory.mkdir(parents=True)
        (directory / "system.img").touch()
        (directory / "package.xml").touch()

    @patch.object(avd, "host_abi", return_value="arm64-v8a")
    def test_prefers_device_api_and_preserves_existing_data(self, _abi):
        self.image("32", "arm64-v8a")
        self.image("36", "arm64-v8a")
        directory = avd.create_avd(self.sdk, self.avd_home, "Rokid_Test")
        config = (directory / "config.ini").read_text()
        self.assertIn("hw.lcd.width=480\n", config)
        self.assertIn("hw.lcd.height=640\n", config)
        self.assertIn("hw.lcd.density=240\n", config)
        self.assertIn("system-images/android-32/google_apis/arm64-v8a/", config)
        self.assertIn("abi.type=arm64-v8a\n", config)
        self.assertIn("hw.cpu.arch=arm64\n", config)
        (directory / "config.ini").write_text(config.replace("=", " = "))
        (directory / "userdata-qemu.img").write_bytes(b"keep")
        self.assertEqual(directory, avd.create_avd(self.sdk, self.avd_home, "Rokid_Test"))
        self.assertEqual(b"keep", (directory / "userdata-qemu.img").read_bytes())

    @patch.object(avd, "host_abi", return_value="x86_64")
    def test_missing_host_image_does_not_create_partial_avd(self, _abi):
        self.image("32", "arm64-v8a")
        with self.assertRaises(ValueError):
            avd.create_avd(self.sdk, self.avd_home, "Rokid_Test")
        self.assertFalse(self.avd_home.exists())

    def test_apple_silicon_is_detected_under_an_intel_python(self):
        # pyenv等のIntel版Python（Rosetta上）では platform.machine() が x86_64 を返す。
        answer = type("Probe", (), {"stdout": "1\n"})()
        with patch.object(avd.platform, "machine", return_value="x86_64"), \
                patch.object(avd.sys, "platform", "darwin"), \
                patch.object(avd.subprocess, "run", return_value=answer):
            self.assertEqual("arm64-v8a", avd.host_abi())
        intel = type("Probe", (), {"stdout": ""})()
        with patch.object(avd.platform, "machine", return_value="x86_64"), \
                patch.object(avd.sys, "platform", "darwin"), \
                patch.object(avd.subprocess, "run", return_value=intel):
            self.assertEqual("x86_64", avd.host_abi())
        with patch.object(avd.platform, "machine", return_value="x86_64"), \
                patch.object(avd.sys, "platform", "linux"):
            self.assertEqual("x86_64", avd.host_abi())
        with patch.object(avd.platform, "machine", return_value="arm64"):
            self.assertEqual("arm64-v8a", avd.host_abi())

    @patch.object(avd, "host_abi", return_value="arm64-v8a")
    def test_existing_avd_for_another_cpu_is_reported_instead_of_failing_at_launch(self, _abi):
        self.image("32", "x86_64")
        with patch.object(avd, "host_abi", return_value="x86_64"):
            directory = avd.create_avd(self.sdk, self.avd_home, "Rokid_Test")
        original = (directory / "config.ini").read_text()
        with self.assertRaisesRegex(ValueError, "x86_64用"):
            avd.create_avd(self.sdk, self.avd_home, "Rokid_Test")
        self.assertEqual(original, (directory / "config.ini").read_text())

    def test_rejects_conflicting_existing_avd_without_overwriting_it(self):
        directory = self.avd_home / "Rokid_Test.avd"
        directory.mkdir(parents=True)
        original = "hw.lcd.width=1080\nhw.lcd.height=2400\nhw.lcd.density=420\n"
        (directory / "config.ini").write_text(original)
        (self.avd_home / "Rokid_Test.ini").touch()
        with self.assertRaises(ValueError):
            avd.create_avd(self.sdk, self.avd_home, "Rokid_Test")
        self.assertEqual(original, (directory / "config.ini").read_text())


if __name__ == "__main__":
    unittest.main()
