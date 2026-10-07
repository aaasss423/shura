# Telegram

Telegram is optional through `SHURA_TELEGRAM_TOKEN` and `SHURA_TELEGRAM_CHAT_ID`. Best-effort sends are wired to crawl success/failure, discovery proposals, candidate-ready review, quarantine, source stop, and successful publication. Send exceptions return false and do not fail crawl/release. There is no durable outbox or send retry; not every state event currently sends a message.
