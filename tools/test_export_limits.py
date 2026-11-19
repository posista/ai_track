import unittest
from datetime import datetime, timezone

from export_limits import export_lines


class ExportTests(unittest.TestCase):
    now = datetime(2026, 3, 27, 10, tzinfo=timezone.utc)
    soon = int(now.timestamp()) + 3600

    def test_codex_exports_by_documented_window_length(self):
        data = {"rateLimitsByLimitId": {"codex": {
            "limitId": "codex",
            "primary": {"usedPercent": 100, "windowDurationMins": 300, "resetsAt": self.soon},
            "secondary": {"usedPercent": 35, "windowDurationMins": 10080, "resetsAt": self.soon},
        }}}
        self.assertEqual(export_lines("codex", data, self.now), [
            "Codex | kurzfristig | 2026-03-27T11:00:00Z | erreicht",
            "Codex | wöchentlich | 2026-03-27T11:00:00Z | geplant",
        ])

    def test_codex_ignores_other_limit_namespaces_and_conflicting_windows(self):
        self.assertEqual(export_lines("codex", {"rateLimits": {"limitId": "other"}}, self.now), [])
        data = {"rateLimits": {
            "primary": {"windowDurationMins": 300, "resetsAt": self.soon},
            "secondary": {"windowDurationMins": 600, "resetsAt": self.soon},
        }}
        self.assertEqual(export_lines("codex", data, self.now), [])

    def test_codex_exports_named_weekly_limits_separately(self):
        week = {"windowDurationMins": 10080, "resetsAt": self.soon}
        data = {"rateLimitsByLimitId": {
            "codex": {"limitId": "codex", "primary": week},
            "other-scope": {"limitId": "other-scope", "primary": week},
        }}
        self.assertEqual(export_lines("codex", data, self.now), [
            "Codex | wöchentlich | 2026-03-27T11:00:00Z | geplant",
            "Codex | wöchentlich | 2026-03-27T11:00:00Z | geplant | other-scope",
        ])

    def test_claude_ignores_expired_and_non_subscription_windows(self):
        data = {"rate_limits": {
            "five_hour": {"resets_at": self.soon - 3601},
            "seven_day": {"resets_at": self.soon, "used_percentage": 25},
            "spend_limit": {"resets_at": self.soon},
        }}
        self.assertEqual(export_lines("claude-status", data, self.now),
                         ["Claude Code | wöchentlich | 2026-03-27T11:00:00Z | geplant"])


if __name__ == "__main__":
    unittest.main()
