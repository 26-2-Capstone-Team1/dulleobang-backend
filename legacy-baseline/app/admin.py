"""Trusted local administration, never exposed as public HTTP routes."""

from __future__ import annotations

import copy

from .core import bump, require, stamp, uid


def review_agent(store, application_id, decision, reason=None):
    require(decision in ("approve", "reject"), 422, "INVALID_DECISION")
    require(
        decision != "reject" or (reason and 1 <= len(reason.strip()) <= 2000),
        422,
        "REJECTION_REASON_REQUIRED",
    )
    record = store.get("agentapplication", application_id)
    application = record["value"]
    user_id = record["owner"]
    profile = store.get("agentprofile", user_id)["value"]
    require(
        profile["applicationId"] == application_id and profile["status"] == "PENDING",
        409,
        "APPLICATION_NOT_PENDING",
    )
    user = store.get("user", user_id)["value"]
    if decision == "approve":
        profile["status"] = "APPROVED"
        profile["_approved"] = {k: profile[k] for k in ("name", "officeName", "phone", "licenseNumber")}
        profile["rejectReason"] = None
        if "LISTING_AGENT" not in user["roles"]:
            user["roles"].append("LISTING_AGENT")
            store.put("user", user_id, bump(user), user_id)
    else:
        profile.update(status="REJECTED", rejectReason=reason.strip())
    profile["reviewedAt"] = stamp()
    bump(profile)
    application.update(
        status=profile["status"], rejectReason=profile["rejectReason"], reviewedAt=profile["reviewedAt"]
    )
    store.put("agentapplication", application_id, application, user_id)
    store.put("agentprofile", user_id, profile, user_id)
    audit = dict(
        auditId=uid(),
        action="AGENT_VERIFICATION_REVIEW",
        targetId=application_id,
        decision=decision,
        reason=reason,
        reviewedAt=profile["reviewedAt"],
        actor="LOCAL_ADMIN",
    )
    store.put("audit", audit["auditId"], audit, "LOCAL_ADMIN")
    return {k: copy.deepcopy(v) for k, v in profile.items() if not k.startswith("_")}


def review_report(store, report_id, decision, reason):
    require(
        decision in ("resolve", "dismiss") and reason and 1 <= len(reason.strip()) <= 2000,
        422,
        "INVALID_DECISION",
    )
    record = store.get("report", report_id)
    report = record["value"]
    require(report["status"] == "OPEN", 409, "REPORT_ALREADY_REVIEWED")
    report.update(
        status="RESOLVED" if decision == "resolve" else "DISMISSED",
        resolution=reason.strip(),
        reviewedAt=stamp(),
    )
    store.put("report", report_id, report, record["owner"], record["parent"])
    audit = dict(
        auditId=uid(),
        action="LISTING_REPORT_REVIEW",
        targetId=report_id,
        decision=decision,
        reason=reason,
        reviewedAt=report["reviewedAt"],
        actor="LOCAL_ADMIN",
    )
    store.put("audit", audit["auditId"], audit, "LOCAL_ADMIN")
    return report
