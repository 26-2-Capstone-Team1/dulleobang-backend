"""Private listing projections, favorites, reports, verification and participant chat."""

from __future__ import annotations
import copy
import time
from .core import bump, epoch, etag, require, stamp, uid


class ProductFeatures:
    def listing_fields(self, body, previous=None):
        v = copy.deepcopy(previous or {})
        v.update(copy.deepcopy(body))
        v.setdefault("floor", None)
        v.setdefault("rentalOptions", [])
        v["location"]["roadAddress"] = v["location"]["roadAddress"].strip()
        require(v["location"]["roadAddress"], 422, "INVALID_LOCATION")
        price = v["price"]
        require(
            (price["leaseType"] == "MONTHLY" and price["monthlyRent"] > 0)
            or (price["leaseType"] == "JEONSE" and price["monthlyRent"] == 0),
            422,
            "INVALID_PRICE",
        )
        require(v.get("supplyAreaM2", v["exclusiveAreaM2"]) >= v["exclusiveAreaM2"], 422, "INVALID_AREA")
        require(
            v["floor"] is None or "totalFloors" not in v or v["floor"] <= v["totalFloors"],
            422,
            "INVALID_FLOOR",
        )
        return v

    def owner_view(self, user_id, summary=False):
        user = self.s.get("user", user_id)["value"]
        profile = self.s.optional("agentprofile", user_id)
        a = profile.get("_approved") if profile else None
        verified = "LISTING_AGENT" in user["roles"]
        out = dict(
            userId=user_id,
            displayName=a["name"] if verified and a else user["displayName"],
            isVerifiedListingAgent=verified,
        )
        if not summary:
            out.update(
                officeName=a["officeName"] if verified and a else None,
                phone=a["phone"]
                if verified and a
                else user.get("phone")
                if user.get("isPhonePublic", False)
                else None,
            )
        return out

    def listing_view(self, value):
        v = {k: copy.deepcopy(x) for k, x in value.items() if not k.startswith("_")}
        v["owner"] = self.owner_view(v["ownerId"])
        v["isScrapped"] = self.s.optional("scrap", self.user["userId"] + ":" + v["listingId"]) is not None
        if v["ownerId"] != self.user["userId"]:
            v["location"].pop("detailAddress", None)
        return v

    def summary(self, listing):
        v = self.listing_view(listing)
        props = self.contract.spec["components"]["schemas"]["ListingSummary"]["properties"]
        out = {k: v[k] for k in props if k in v}
        # Detailed addresses never belong in list cards or chat snapshots.
        out["location"].pop("detailAddress", None)
        out.update(
            thumbnail=v["photos"][0] if v["photos"] else None,
            hasSpace=v["currentSpaceVersion"] is not None,
            owner=self.owner_view(v["ownerId"], summary=True),
        )
        return out

    def searchListings(self, b):
        bounds = [k in self.q for k in ("minLat", "maxLat", "minLng", "maxLng")]
        require(not any(bounds) or all(bounds), 400, "INCOMPLETE_MAP_BOUNDS")
        require(all(bounds) or self.q.get("limit", 20) <= 50, 400, "INVALID_QUERY_PARAMETER")
        for suffix in ("Deposit", "MonthlyRent", "AreaM2", "Lat", "Lng"):
            lo, hi = self.q.get("min" + suffix), self.q.get("max" + suffix)
            require(lo is None or hi is None or lo <= hi, 422, "INVALID_RANGE")
        require(
            not (self.q.get("leaseType") == "JEONSE" and self.q.get("minMonthlyRent", 0) > 0),
            422,
            "INVALID_PRICE_FILTER",
        )

        def matches(x):
            address = x["location"]["roadAddress"].casefold()
            if not all(token in address for token in self.q.get("q", "").casefold().split()):
                return False
            for key, actual in (
                ("regionCode", x["location"].get("regionCode")),
                ("leaseType", x["price"]["leaseType"]),
                ("hasSpace", x["currentSpaceVersion"] is not None),
            ):
                if key in self.q and self.q[key] != actual:
                    return False
            if "roomType" in self.q and x["roomType"] not in self.q["roomType"]:
                return False
            for suffix, actual in (
                ("Deposit", x["price"]["deposit"]),
                ("MonthlyRent", x["price"]["monthlyRent"]),
                ("AreaM2", x["exclusiveAreaM2"]),
            ):
                if not self.q.get("min" + suffix, -1) <= actual <= self.q.get("max" + suffix, float("inf")):
                    return False
            if all(bounds) and not (
                self.q["minLat"] <= x["location"]["latitude"] <= self.q["maxLat"]
                and self.q["minLng"] <= x["location"]["longitude"] <= self.q["maxLng"]
            ):
                return False
            return set(self.q.get("rentalOptions", [])) <= set(x["rentalOptions"])

        values = [x for x in self.s.all("listing") if x["status"] == "PUBLISHED" and matches(x)]
        keys = dict(
            LATEST=lambda x: (-epoch(x["createdAt"]), x["listingId"]),
            DEPOSIT_ASC=lambda x: (x["price"]["deposit"], x["listingId"]),
            MONTHLY_RENT_ASC=lambda x: (x["price"]["monthlyRent"], x["listingId"]),
            AREA_DESC=lambda x: (-x["exclusiveAreaM2"], x["listingId"]),
        )
        paged = self.page(sorted(values, key=keys[self.q.get("sort", "LATEST")]), "listingId")
        paged["items"] = [self.summary(x) for x in paged["items"]]
        return paged

    def createListing(self, b):
        v = self.listing_fields(b)
        id = uid()
        v.update(
            listingId=id,
            ownerId=self.user["userId"],
            status="DRAFT",
            version=1,
            photos=[],
            currentSpaceVersion=None,
            activeJobId=None,
            createdAt=stamp(),
            updatedAt=stamp(),
        )
        self.s.put("listing", id, v, self.user["userId"])
        self.extra["Location"] = "/api/v1/listings/" + id
        return self.response_etag(self.listing_view(v))

    def getMyListings(self, b):
        values = [
            x
            for x in self.s.all("listing", owner=self.user["userId"])
            if "status" not in self.q or x["status"] == self.q["status"]
        ]
        paged = self.page(sorted(values, key=lambda x: (-epoch(x["createdAt"]), x["listingId"])), "listingId")
        paged["items"] = [self.summary(x) for x in paged["items"]]
        return paged

    def getListing(self, b):
        return self.response_etag(self.listing_view(self.listing(self.p["listingId"])["value"]))

    def updateListing(self, b):
        v = self.listing(self.p["listingId"], own=True)["value"]
        etag(v, self.h.get("if-match"))
        require(v["status"] != "ARCHIVED", 409, "INVALID_STATE")
        v = self.listing_fields({k: val for k, val in b.items() if k != "photoFileIds"}, v)
        if "roomType" in b:
            v.pop("_migrationNeedsReview", None)
        if "photoFileIds" in b:
            require(v["status"] != "PUBLISHED" or b["photoFileIds"], 409, "PUBLICATION_REQUIREMENTS_NOT_MET")
            photos = []
            for id in b["photoFileIds"]:
                f = self.own("file", id)["value"]
                require(
                    f["_purpose"] == "LISTING_PHOTO"
                    and f["_targetId"] == v["listingId"]
                    and f["_verified"]
                    and f.get("_derivative"),
                    422,
                    "INVALID_PHOTO",
                )
                photos.append(self.asset(self.s.get("file", f["_derivative"])["value"]))
            v["photos"] = photos
        self.save("listing", v["listingId"], bump(v))
        return self.response_etag(self.listing_view(v))

    def setPublication(self, b):
        v = self.listing(self.p["listingId"], own=True)["value"]
        etag(v, self.h.get("if-match"))
        new = b["status"]
        require(v["status"] != "ARCHIVED" or new in ("ARCHIVED", "DRAFT"), 409, "INVALID_STATE")
        if new == "PUBLISHED":
            require(not v.get("_migrationNeedsReview"), 409, "ROOM_TYPE_REVIEW_REQUIRED")
            require(v["currentSpaceVersion"] and v["photos"], 409, "PUBLICATION_REQUIREMENTS_NOT_MET")
        if new == "ARCHIVED":
            require(
                not any(
                    j["status"] in ("QUEUED", "RUNNING", "RETRY_WAIT") and j["_listingId"] == v["listingId"]
                    for j in self.s.all("job")
                ),
                409,
                "ACTIVE_JOB_EXISTS",
            )
        if v["status"] != new:
            v["status"] = new
            self.save("listing", v["listingId"], bump(v))
        return self.response_etag(self.listing_view(v))

    def saveScrap(self, b):
        listing = self.listing(self.p["listingId"])["value"]
        require(listing["status"] == "PUBLISHED", 404, "RESOURCE_NOT_FOUND")
        key = self.user["userId"] + ":" + listing["listingId"]
        if not self.s.optional("scrap", key):
            self.s.put(
                "scrap",
                key,
                dict(listingId=listing["listingId"], savedAt=stamp()),
                self.user["userId"],
                listing["listingId"],
            )

    def deleteScrap(self, b):
        self.s.conn.execute(
            "DELETE FROM resources WHERE kind='scrap' AND id=? AND owner=?",
            (self.user["userId"] + ":" + self.p["listingId"], self.user["userId"]),
        )

    def getScraps(self, b):
        values = []
        for scrap in sorted(
            self.s.all("scrap", owner=self.user["userId"]),
            key=lambda x: (-epoch(x["savedAt"]), x["listingId"]),
        ):
            v = self.s.optional("listing", scrap["listingId"])
            if v and v["status"] == "PUBLISHED":
                values.append(v)
        paged = self.page(values, "listingId")
        paged["items"] = [self.summary(x) for x in paged["items"]]
        return paged

    def createListingReport(self, b):
        listing = self.listing(self.p["listingId"])
        require(listing["owner"] != self.user["userId"], 403, "SELF_REPORT_FORBIDDEN")
        old = next(
            (
                r
                for r in self.s.all("report", owner=self.user["userId"], parent=self.p["listingId"])
                if time.time() - epoch(r["createdAt"]) < 86400
            ),
            None,
        )
        if old:
            self.status_override = 200
            return dict(reportId=old["reportId"])
        v = dict(
            b,
            reportId=uid(),
            listingId=self.p["listingId"],
            reporterId=self.user["userId"],
            status="OPEN",
            resolution=None,
            createdAt=stamp(),
            reviewedAt=None,
        )
        self.s.put("report", v["reportId"], v, self.user["userId"], self.p["listingId"])
        return dict(reportId=v["reportId"])

    def getAgentProfile(self, b):
        v = self.s.optional("agentprofile", self.user["userId"])
        fields = self.contract.spec["components"]["schemas"]["ListingAgentVerification"]["properties"]
        if not v:
            return {k: "NONE" if k == "status" else None for k in fields}
        return {k: v.get(k) for k in fields}

    def applyAgentVerification(self, b):
        v = self.s.optional("agentprofile", self.user["userId"]) or dict(version=0, _approved=None)
        require(v.get("status") != "PENDING", 409, "VERIFICATION_PENDING")
        f = self.own("file", b["documentFileId"])["value"]
        require(
            f["_purpose"] == "LISTING_AGENT_DOCUMENT"
            and f["_targetType"] == "USER"
            and f["_targetId"] == self.user["userId"]
            and f["_verified"],
            422,
            "INVALID_EVIDENCE",
        )
        v.update(b)
        v.update(
            userId=self.user["userId"],
            applicationId=uid(),
            status="PENDING",
            requestedAt=stamp(),
            reviewedAt=None,
            rejectReason=None,
        )
        bump(v)
        self.s.put(
            "agentapplication",
            v["applicationId"],
            {k: x for k, x in v.items() if not k.startswith("_")},
            self.user["userId"],
        )
        f["_applicationId"] = v["applicationId"]
        self.save("file", f["fileId"], f)
        self.s.put("agentprofile", self.user["userId"], v, self.user["userId"])
        self.extra["Location"] = "/api/v1/users/me/listing-agent-verification"
        return self.getAgentProfile(None)

    def chat_owned(self, id):
        v = self.s.get("chat", id)["value"]
        require(self.user["userId"] in (v["inquirerId"], v["ownerId"]), 404, "RESOURCE_NOT_FOUND")
        return v

    def message_view(self, v):
        return {k: v[k] for k in ("messageId", "chatId", "senderId", "text", "sentAt")}

    def chat_view(self, v, summary=False):
        listing = self.s.optional("listing", v["listingId"])
        snapshot = copy.deepcopy(v["_listingSnapshot"])
        if listing and listing["status"] == "PUBLISHED":
            snapshot = self.summary(listing)
        else:
            snapshot["isScrapped"] = (
                self.s.optional("scrap", self.user["userId"] + ":" + v["listingId"]) is not None
            )
            snapshot.update(
                thumbnail=None, hasSpace=False, status=listing["status"] if listing else "ARCHIVED"
            )
        counterpart_id = v["ownerId"] if self.user["userId"] == v["inquirerId"] else v["inquirerId"]
        counterpart = self.owner_view(counterpart_id)
        counterpart.pop("phone")
        mark = v.get("_read", {}).get(self.user["userId"], 0)
        messages = self.s.all("message", parent=v["chatId"])
        last = max(messages, key=lambda m: m["_sequence"], default=None)
        out = dict(
            chatId=v["chatId"],
            listing=snapshot,
            counterpart=counterpart,
            lastMessage=last["text"] if last else None,
            lastMessageAt=last["sentAt"] if last else None,
            unreadCount=sum(m["senderId"] != self.user["userId"] and m["_sequence"] > mark for m in messages),
        )
        if not summary:
            out.update(
                inquirerId=v["inquirerId"],
                ownerId=v["ownerId"],
                createdAt=v["createdAt"],
                readOnly=not (
                    listing and listing["status"] == "PUBLISHED" and listing["ownerId"] == v["ownerId"]
                ),
            )
        return out

    def createChat(self, b):
        listing = self.listing(b["listingId"])["value"]
        require(listing["ownerId"] != self.user["userId"], 409, "SELF_CHAT_FORBIDDEN")
        require(listing["status"] == "PUBLISHED", 404, "RESOURCE_NOT_FOUND")
        values = [
            v for v in self.s.all("chat", parent=b["listingId"]) if v["inquirerId"] == self.user["userId"]
        ]
        if values:
            v = values[0]
            self.status_override = 200
        else:
            v = dict(
                chatId=uid(),
                listingId=b["listingId"],
                inquirerId=self.user["userId"],
                ownerId=listing["ownerId"],
                createdAt=stamp(),
                updatedAt=stamp(),
                _listingSnapshot=self.summary(listing),
                _read={},
            )
            self.s.put("chat", v["chatId"], v, self.user["userId"], b["listingId"])
        self.extra["Location"] = "/api/v1/chats/" + v["chatId"]
        return self.chat_view(v)

    def getChats(self, b):
        values = [v for v in self.s.all("chat") if self.user["userId"] in (v["inquirerId"], v["ownerId"])]
        paged = self.page(sorted(values, key=lambda v: (-epoch(v["updatedAt"]), v["chatId"])), "chatId")
        paged["items"] = [self.chat_view(v, summary=True) for v in paged["items"]]
        return paged

    def getChat(self, b):
        return self.chat_view(self.chat_owned(self.p["chatId"]))

    def getChatMessages(self, b):
        chat = self.chat_owned(self.p["chatId"])
        values = self.s.all("message", parent=chat["chatId"])
        if "before" in self.q:
            m = self.s.get("message", self.q["before"])["value"]
            require(m["chatId"] == chat["chatId"], 404, "RESOURCE_NOT_FOUND")
            values = [v for v in values if v["_sequence"] < m["_sequence"]]
        paged = self.page(sorted(values, key=lambda m: -m["_sequence"]), "messageId")
        paged["items"] = [self.message_view(v) for v in paged["items"]]
        return paged

    def sendChatMessage(self, b):
        chat = self.chat_owned(self.p["chatId"])
        listing = self.s.optional("listing", chat["listingId"])
        require(
            listing and listing["status"] == "PUBLISHED" and listing["ownerId"] == chat["ownerId"],
            409,
            "CHAT_LISTING_UNAVAILABLE",
        )
        require(b["text"].strip(), 422, "EMPTY_MESSAGE")
        values = self.s.all("message", parent=chat["chatId"])
        v = dict(
            b,
            messageId=uid(),
            chatId=chat["chatId"],
            senderId=self.user["userId"],
            _sequence=max((m["_sequence"] for m in values), default=0) + 1,
            sentAt=stamp(),
        )
        self.s.put("message", v["messageId"], v, self.user["userId"], chat["chatId"])
        chat.update(updatedAt=v["sentAt"], _listingSnapshot=self.summary(listing))
        self.save("chat", chat["chatId"], chat)
        return self.message_view(v)

    def readChat(self, b):
        chat = self.chat_owned(self.p["chatId"])
        m = self.s.get("message", b["lastReadMessageId"])["value"]
        require(m["chatId"] == chat["chatId"], 404, "RESOURCE_NOT_FOUND")
        marks = chat.setdefault("_read", {})
        marks[self.user["userId"]] = max(marks.get(self.user["userId"], 0), m["_sequence"])
        self.save("chat", chat["chatId"], chat)
