import importlib.util
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("emulator_media", Path(__file__).resolve().parents[1] / "emulator-media.py")
media = importlib.util.module_from_spec(spec)
spec.loader.exec_module(media)


class EmulatorMediaTest(unittest.TestCase):
    def test_real_camera_selection_never_silently_uses_dummy_camera(self):
        listing = "Camera 'webcam0' connected to internal\nCamera 'webcam1' connected to USB\n"
        self.assertEqual("webcam0", media.select_camera("auto", listing))
        self.assertEqual("webcam1", media.select_camera("webcam1", listing))
        with self.assertRaises(ValueError):
            media.select_camera("auto", "")
        with self.assertRaises(ValueError):
            media.select_camera("webcam2", listing)
        self.assertEqual("emulated", media.select_camera("emulated", ""))

    def test_configuration_preserves_other_settings_and_user_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = root / "config.ini"
            config.write_text("hw.lcd.width = 480\nhw.camera.back = emulated\nhw.camera.back=none\nhw.audioInput = no\ncustom.setting = keep\n")
            userdata = root / "userdata-qemu.img"
            userdata.write_bytes(b"device-pairing-and-settings")
            media.configure_media(config, "webcam1", True)
            updated = config.read_text()
            self.assertIn("hw.lcd.width = 480\n", updated)
            self.assertIn("custom.setting = keep\n", updated)
            self.assertEqual(1, updated.count("hw.camera.back="))
            self.assertEqual("webcam1", media.read_config(config)["hw.camera.back"])
            self.assertEqual("yes", media.read_config(config)["hw.audioInput"])
            self.assertEqual("yes", media.read_config(config)["hw.audioOutput"])
            self.assertEqual("none", media.read_config(config)["hw.camera.front"])
            media.configure_media(config, "webcam1", True)
            self.assertEqual(updated, config.read_text())
            self.assertEqual(b"device-pairing-and-settings", userdata.read_bytes())

    def test_command_overrides_require_restart_even_after_config_change(self):
        config = {"hw.camera.back": "webcam0", "hw.camera.front": "none", "hw.audioInput": "yes"}
        arguments = ["qemu-system-x86_64", "-avd", "Rokid_Test"]
        self.assertTrue(media.media_matches(config, arguments, "webcam0", True))
        self.assertFalse(media.media_matches(config, arguments + ["-no-audio"], "webcam0", True))
        self.assertFalse(media.media_matches(config, arguments + ["-camera-back", "emulated"], "webcam0", True))
        self.assertFalse(media.media_matches(config, arguments + ["-camera-front", "webcam0"], "webcam0", True))
        self.assertFalse(media.media_matches(config, arguments, "webcam1", True))

    def test_real_and_preview_launches_set_the_intended_media(self):
        real = media.launch_arguments("emulator", "Rokid_Test", "software", "webcam0", True)
        self.assertIn("-allow-host-audio", real)
        self.assertNotIn("-no-audio", real)
        self.assertEqual("webcam0", media.option(real, "-camera-back"))
        preview = media.launch_arguments("emulator", "Rokid_Test", "software", "emulated", False)
        self.assertIn("-no-audio", preview)
        self.assertNotIn("-allow-host-audio", preview)
        self.assertEqual("emulated", media.option(preview, "-camera-back"))
        self.assertNotIn("-wipe-data", real + preview)

    @patch.object(media.subprocess, "run")
    def test_process_detection_targets_only_the_named_emulator(self, run):
        run.return_value = SimpleNamespace(stdout="/bin/sh -c 'echo qemu-system -avd Rokid_Test'\n/sdk/qemu-system-x86_64 -avd Another_Device\n/sdk/qemu-system-x86_64 -avd Rokid_Test -no-audio\n")
        self.assertIn("-no-audio", media.running_arguments("Rokid_Test"))
        self.assertIsNone(media.running_arguments("Missing_Device"))


if __name__ == "__main__":
    unittest.main()
