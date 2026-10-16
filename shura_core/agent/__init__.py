"""The site-to-extension loop, and what it has learned so far.

``converter`` turns an accepted site into a Gradle module and ``builder`` turns
that into an APK, but neither had a caller, and nothing connected a built APK to
the crawl pipeline that publishes extensions. This package is that connection:
:class:`~shura_core.agent.loop.AgentLoop` runs a site through the existing gates,
records what happened, and reuses the next attempt.

It does not replace the gates. It feeds them, and it decides only whether a site
is worth another look - the crawl pipeline still decides whether anything ships.
"""

from .diagnose import classify, classify_build, gate_for_cause
from .knowledge import Attempt, Cause, Knowledge
from .loop import AgentLoop, SiteResult

__all__ = ["AgentLoop", "SiteResult", "Attempt", "Cause", "Knowledge",
           "classify", "classify_build", "gate_for_cause"]