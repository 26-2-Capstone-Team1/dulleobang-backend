"""Atomic migration of legacy JSON, including deletion tombstones. Artifact bytes stay unchanged."""

import json
from decimal import Decimal
from .core import dumps, stamp


def migrate(store):
    if store.conn.execute("SELECT 1 FROM schema_migrations WHERE version=3").fetchone():
        return
    for row in store.conn.execute(
        "SELECT kind,id,data FROM resources WHERE kind IN ('user','listing')"
    ).fetchall():
        v = json.loads(row["data"])
        if row["kind"] == "user":
            v["roles"] = ["LISTING_AGENT" if r == "BROKER" else r for r in v["roles"]]
            v.setdefault("authProviders", ["EMAIL"])
            v.setdefault("phone", None)
            v.setdefault("isPhonePublic", False)
        else:
            changed = False
            if "brokerId" in v:
                v["ownerId"] = v.pop("brokerId")
                changed = True
            if "listingAgentId" in v:
                v["ownerId"] = v.pop("listingAgentId")
                changed = True
            if "propertyType" in v:
                typ = v.pop("propertyType")
                v["roomType"] = "OFFICETEL" if typ == "OFFICETEL" else "ONE_ROOM"
                v["_migrationNeedsReview"] = typ in ("VILLA", "APARTMENT")
                changed = True
                if v["_migrationNeedsReview"] and v["status"] == "PUBLISHED":
                    v["status"] = "DRAFT"
            # Recover unpublished intermediate flat-address draft data, if present.
            if isinstance(v.get("location"), str):
                v["location"] = dict(
                    roadAddress=v["location"], latitude=v.pop("latitude"), longitude=v.pop("longitude")
                )
                code = v.pop("regionCode", None)
                if code:
                    v["location"]["regionCode"] = code
                p = v["price"]
                v["price"] = dict(
                    leaseType=v.pop("dealType"),
                    deposit=int(Decimal(str(p["deposit"])) * 10000),
                    monthlyRent=int(Decimal(str(p["monthly"])) * 10000),
                    maintenanceFee=None
                    if v["maintenanceFee"] is None
                    else int(Decimal(str(v["maintenanceFee"])) * 10000),
                    currency="KRW",
                )
                v.pop("maintenanceFee", None)
                v["exclusiveAreaM2"] = v.pop("area")
                for old, new in [
                    ("supplyArea", "supplyAreaM2"),
                    ("rooms", "roomCount"),
                    ("bathrooms", "bathroomCount"),
                ]:
                    value = v.pop(old, None)
                    if value is not None:
                        v[new] = value
                options = dict(
                    aircon="AIR_CONDITIONER",
                    fridge="REFRIGERATOR",
                    washer="WASHING_MACHINE",
                    induction="INDUCTION",
                    gas_stove="GAS_STOVE",
                    bed="BED",
                    desk="DESK",
                    closet="WARDROBE",
                    microwave="MICROWAVE",
                    tv="TV",
                    parking="PARKING",
                    pet="PET_ALLOWED",
                    elevator="ELEVATOR",
                )
                v["rentalOptions"] = [options.get(x, x) for x in v["rentalOptions"]]
                for k in ("totalFloors", "availableFrom"):
                    if v.get(k) is None:
                        v.pop(k, None)
                if isinstance(v.get("livingEnvironment"), list):
                    v["livingEnvironment"] = " · ".join(v["livingEnvironment"])[:500]
                changed = True
            v.setdefault("floor", None)
            if changed:
                v["version"] += 1
                v["updatedAt"] = stamp()
        store.conn.execute(
            "UPDATE resources SET data=? WHERE kind=? AND id=?", (dumps(v), row["kind"], row["id"])
        )
    store.conn.execute("INSERT INTO schema_migrations VALUES(3,?)", (stamp(),))
