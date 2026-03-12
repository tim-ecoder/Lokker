E:\KEY2> adb shell pm enable com.lokker.app/.LokkerLauncher

Component {com.lokker.app/com.lokker.app.LokkerLauncher} new state: enabled

Summary of all dialpad codes:

- *#*#565537#*#* — If you forgot lokker hotkey, and lokker is hided, open Lokker (with auth)
- If you forgot password simply clear lokker app data from settings 
- *#*#5655377469#*#* — If you cleared lokker app data, unhide all earlier hidden apps (no auth)

For adb unhide in adb root mode:
$packages = adb shell "pm list packages -u --user 0"
foreach ($line in $packages) {
$pkg = ($line -replace 'package:', '').Trim()
if ($pkg) {
adb shell "pm unhide --user 0 $pkg"
}
}
Write-Host "Done"