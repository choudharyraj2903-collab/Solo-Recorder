#!/usr/bin/env python3
"""Offline deterministic checks for sensitive Android project configuration."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main"
manifest = ET.parse(SRC / "AndroidManifest.xml").getroot()
ns = "{http://schemas.android.com/apk/res/android}"
permissions = {e.get(ns + "name") for e in manifest.findall("uses-permission")}
required = {
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    "android.permission.RECORD_AUDIO",
    "android.permission.POST_NOTIFICATIONS",
}
assert required <= permissions, f"Missing permissions: {required - permissions}"
assert "android.permission.INTERNET" not in permissions, "No Internet access allowed"
assert "android.permission.MANAGE_EXTERNAL_STORAGE" not in permissions
app = manifest.find("application")
assert app is not None
assert app.get(ns + "allowBackup") == "false"
assert app.get(ns + "usesCleartextTraffic") == "false"
services = app.findall("service")
assert len(services) == 1
service = services[0]
assert service.get(ns + "exported") == "false"
assert service.get(ns + "foregroundServiceType") == "mediaProjection|microphone"
xmls = list((SRC / "res").rglob("*.xml"))
for path in xmls:
    ET.parse(path)
assert len(xmls) >= 6
main = (SRC / "java/com/solorecord/app/MainActivity.kt").read_text()
engine = (SRC / "java/com/solorecord/app/RecorderService.kt").read_text()
policy = (SRC / "java/com/solorecord/app/RecordingPolicy.kt").read_text()
for essential in ["createScreenCaptureIntent", "startForegroundService", "RECORD_AUDIO"]:
    assert essential in main, essential
for essential in ["registerCallback", "onStop", "setOutputFile", "MediaRecorder", "IS_PENDING", "createVirtualDisplay", "stopForeground", "FLAG_IMMUTABLE"]:
    assert essential in engine, essential
assert "send(CaptureEvent.STOP)" in engine
assert "recorder?.pause()" in engine and "recorder?.resume()" in engine
assert "safePrefix" in policy
assert re.search(r"minSdk\s*=\s*29\b", (ROOT / "app/build.gradle.kts").read_text())
assert (ROOT / ".github/workflows/android.yml").is_file()
print(f"PASS: manifest privacy, 5 permissions, non-exported service, capture lifecycle, XML ({len(xmls)}), build workflow")
