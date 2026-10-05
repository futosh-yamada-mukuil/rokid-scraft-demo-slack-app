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

    @patch.object(avd.platform, "machine", return_value="arm64")
    def test_prefers_device_api_and_preserves_existing_data(self, _machine):
        self.image("32", "arm64-v8a")
        self.image("36", "arm64-v8a")
        directory = avd.create_avd(self.sdk, self.avd_home, "Rokid_Test")
        config = (directory / "config.ini").read_text()
        self.assertIn("hw.lcd.width=480\n", config)
        self.assertIn("hw.lcd.height=640\n", config)
        self.assertIn("hw.lcd.density=240\n", config)
        self.assertIn("system-images/android-32/google_apis/arm64-v8a/", config)
        (directory / "config.ini").write_text(config.replace("=", " = "))
        (directory / "userdata-qemu.img").write_bytes(b"keep")
        self.assertEqual(directory, avd.create_avd(self.sdk, self.avd_home, "Rokid_Test"))
        self.assertEqual(b"keep", (directory / "userdata-qemu.img").read_bytes())

    @patch.object(avd.platform, "machine", return_value="x86_64")
    def test_missing_host_image_does_not_create_partial_avd(self, _machine):
        self.image("32", "arm64-v8a")
        with self.assertRaises(ValueError):
            avd.create_avd(self.sdk, self.avd_home, "Rokid_Test")
        self.assertFalse(self.avd_home.exists())

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
