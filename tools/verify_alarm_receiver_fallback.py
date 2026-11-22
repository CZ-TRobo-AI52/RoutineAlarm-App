#!/usr/bin/env python3
"""Static contract test for the isolated main-branch AlarmReceiver fallback.

This intentionally does not claim Android runtime/device coverage. It verifies that
the receiver keeps both fail-closed guards in the source:
1. missing exact-alarm access avoids FGS launch;
2. OS FGS-launch rejection reaches the same notification fallback.
"""

from pathlib import Path

source = Path("app/src/main/java/com/cztr/routinealarm/AlarmReceiver.kt").read_text(
    encoding="utf-8"
)

required = {
    "exact-alarm gate": "AlarmScheduler.canScheduleExact(context)",
    "inexact fallback": "postInexactAlarmFallback(context, title)",
    "FGS rejection catch": "exception is ForegroundServiceStartNotAllowedException",
    "broad exception guard": "catch (exception: Exception)",
    "no FGS before gate": "if (\n            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S",
    "alarm notification": "Notification.CATEGORY_ALARM",
    "full-screen alarm": "setFullScreenIntent(fullScreen, true)",
    "notification delivery": "manager.notify(AlarmService.NOTIFICATION_ID, notification)",
}

missing = [name for name, token in required.items() if token not in source]
assert not missing, f"Missing AlarmReceiver fallback contract(s): {missing}"

gate = source.index("!AlarmScheduler.canScheduleExact(context)")
launch = source.index("context.startForegroundService(serviceIntent)")
assert gate < launch, "Exact-alarm gate must precede FGS launch"

catch = source.index("catch (exception: Exception)")
rejection = source.index(
    "exception is ForegroundServiceStartNotAllowedException",
    catch,
)
fallback = source.index("postInexactAlarmFallback(context, title)", rejection)
reraised = source.index("throw exception", rejection)
assert catch < rejection < fallback < reraised, (
    "FGS rejection must route to fallback while unrelated exceptions re-raise"
)

print("AlarmReceiver fallback contract: PASS")
print("Coverage: source structure only; no Android runtime/device assertion.")
