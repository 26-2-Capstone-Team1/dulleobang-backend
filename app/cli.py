"""Offline administration; listing_agent privileges are never accepted from signup."""

from __future__ import annotations

import argparse
import json
import sqlite3
import time

from .core import Database, Settings, bump, worker_token


def main():
    parser = argparse.ArgumentParser(description="둘러방 백엔드 관리 도구")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("init", help="Initialize persistent storage")
    listing_agent = sub.add_parser(
        "grant-listing-agent", help="Grant verified account listing_agent role (offline only)"
    )
    listing_agent.add_argument("email")
    agent_review = sub.add_parser(
        "review-agent", help="Review a verified identity application (trusted operator only)"
    )
    agent_review.add_argument("application_id")
    agent_review.add_argument("--decision", choices=("approve", "reject"), required=True)
    agent_review.add_argument("--reason")
    report_review = sub.add_parser("review-report", help="Resolve or dismiss a listing report")
    report_review.add_argument("report_id")
    report_review.add_argument("--decision", choices=("resolve", "dismiss"), required=True)
    report_review.add_argument("--reason", required=True)
    sub.add_parser(
        "pending-reviews",
        help="List pending verification applications and open reports (private local output)",
    )
    token = sub.add_parser("worker-token", help="Issue a scoped short-lived AI worker token")
    token.add_argument("worker_id")
    token.add_argument("--seconds", type=int, default=3600)
    queue = sub.add_parser("queue", help="Read due local queue job IDs")
    queue.add_argument("--limit", type=int, default=20)
    sub.add_parser("maintenance", help="Run outbox, timeouts and cleanup once")
    backup = sub.add_parser(
        "backup", help="Online consistent SQLite backup; also back up objects and secrets"
    )
    backup.add_argument("destination")
    args = parser.parse_args()
    settings = Settings()
    settings.prepare()
    database = Database(settings)
    if args.command == "worker-token":
        if not 1 <= args.seconds <= 86400:
            parser.error("seconds must be 1..86400")
        print(worker_token(settings, args.worker_id, args.seconds))
    elif args.command == "grant-listing-agent":
        with database.transaction() as store:
            row = store.conn.execute(
                "SELECT user_id FROM credentials WHERE email=?", (args.email.lower().strip(),)
            ).fetchone()
            if not row:
                parser.error("Account not found. Sign up first.")
            user = store.get("user", row[0])["value"]
            if "LISTING_AGENT" not in user["roles"]:
                user["roles"].append("LISTING_AGENT")
                bump(user)
                store.put("user", row[0], user, row[0])
            print(json.dumps(user, ensure_ascii=False))
    elif args.command in ("review-agent", "review-report", "pending-reviews"):
        from .admin import review_agent, review_report
        from .core import Problem

        try:
            with database.transaction() as store:
                if args.command == "review-agent":
                    result = review_agent(store, args.application_id, args.decision, args.reason)
                elif args.command == "review-report":
                    result = review_report(store, args.report_id, args.decision, args.reason)
                else:
                    result = {
                        "applications": [
                            v for v in store.all("agentapplication") if v["status"] == "PENDING"
                        ],
                        "reports": [v for v in store.all("report") if v["status"] == "OPEN"],
                    }
            print(json.dumps(result, ensure_ascii=False))
        except Problem as exc:
            parser.error(exc.code + ": " + exc.detail)
    elif args.command == "queue":
        if not 1 <= args.limit <= 100:
            parser.error("limit must be 1..100")
        with database.connect() as conn:
            print(
                json.dumps(
                    [
                        r[0]
                        for r in conn.execute(
                            "SELECT job_id FROM queue WHERE available_at<=? ORDER BY available_at,job_id LIMIT ?",
                            (time.time(), args.limit),
                        )
                    ]
                )
            )
    elif args.command == "maintenance":
        from .core import Contract
        from .service import Service

        with database.transaction() as store:
            Service(store, settings, Contract()).maintenance()
        print("Maintenance completed.")
    elif args.command == "backup":
        source, destination = database.connect(), sqlite3.connect(args.destination)
        try:
            source.backup(destination)
        finally:
            source.close()
            destination.close()
        print("Database backed up. Copy objects and secret configuration securely as well.")
    else:
        print("Storage initialized: " + str(database.path))


if __name__ == "__main__":
    main()
