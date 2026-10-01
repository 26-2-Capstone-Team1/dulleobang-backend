"""Python introduction. Does not write files or contact a server."""


def annual_rent(monthly: int) -> int:
    if monthly <= 0:
        raise ValueError("월세는 양수여야 합니다.")
    return monthly * 12


def disclosed_phone(user: dict) -> str | None:
    if user.get("isPhonePublic") is True:
        return user.get("phone")
    return None


def main():
    listing = {"roomType": "ONE_ROOM", "monthlyRent": 650_000, "floor": None}
    print("방 종류:", listing["roomType"])
    print("연간 월세:", annual_rent(listing["monthlyRent"]))
    assert annual_rent(650_000) == 7_800_000
    user = {"phone": "010-1234-5678", "isPhonePublic": False}
    assert disclosed_phone(user) is None
    user["isPhonePublic"] = True
    assert disclosed_phone(user) == "010-1234-5678"
    print("공개 동의 전/후 검증: 통과")
    try:
        annual_rent(0)
    except ValueError as error:
        print("잘못된 입력 처리:", error)
    else:
        raise RuntimeError("0원 월세를 거절하지 않았습니다.")
    print("Python 기초 실습: 통과")


if __name__ == "__main__":
    main()
