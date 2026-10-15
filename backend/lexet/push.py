"""Push "tickles" through Firebase Cloud Messaging (HTTP v1).

Privacy design: the server never knows who the users are or what they filter
on. It sends one tiny data message to a topic all installs subscribe to,
carrying only the latest change version. Each phone then syncs the delta and
decides locally — against the user's filters — whether to show a notification.

`collapse_key` means a phone that was offline receives only the latest tickle,
not a backlog. High priority is used only when a newly *verified* Ethiopian
development (or a verified status change) is in the delta, so phones in Doze
are woken only for things that can produce a notification.
"""
from __future__ import annotations

import logging

import httpx

log = logging.getLogger(__name__)

FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"


class Pusher:
    def __init__(self, settings):
        self.settings = settings
        self._credentials = None

    @property
    def enabled(self) -> bool:
        return bool(self.settings.fcm_project_id and self.settings.fcm_credentials_file)

    def _token(self) -> str:
        from google.oauth2 import service_account  # optional dependency: pip install lexet-backend[push]
        import google.auth.transport.requests
        if self._credentials is None:
            self._credentials = service_account.Credentials.from_service_account_file(
                self.settings.fcm_credentials_file, scopes=[FCM_SCOPE])
        if not self._credentials.valid:
            self._credentials.refresh(google.auth.transport.requests.Request())
        return self._credentials.token

    def build_message(self, version: int, high_priority: bool, verified_count: int) -> dict:
        return {
            "message": {
                "topic": self.settings.fcm_topic,
                "data": {"kind": "sync", "v": str(version), "n": str(verified_count)},
                "android": {
                    "priority": "high" if high_priority else "normal",
                    "collapse_key": "sync",
                    "ttl": "86400s",
                },
            }
        }

    def send_tickle(self, version: int, high_priority: bool, verified_count: int = 0) -> bool:
        if not self.enabled:
            log.info("push disabled; would send tickle v=%s high=%s", version, high_priority)
            return False
        url = f"https://fcm.googleapis.com/v1/projects/{self.settings.fcm_project_id}/messages:send"
        try:
            r = httpx.post(url, json=self.build_message(version, high_priority, verified_count),
                           headers={"Authorization": f"Bearer {self._token()}"}, timeout=15)
            if r.status_code >= 300:
                log.warning("FCM error %s: %s", r.status_code, r.text[:300])
                return False
            return True
        except Exception as e:  # noqa: BLE001 — push is best effort; the app also polls
            log.warning("FCM send failed: %s", e)
            return False
