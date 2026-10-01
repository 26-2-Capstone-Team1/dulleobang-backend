"""SQL/transactions in a disposable in-memory database."""

import sqlite3


def main():
    connection = sqlite3.connect(":memory:")
    try:
        connection.execute(
            "CREATE TABLE practice_listings (id TEXT PRIMARY KEY, owner_id TEXT NOT NULL, "
            "monthly_rent INTEGER NOT NULL CHECK(monthly_rent > 0))"
        )
        with connection:
            connection.execute(
                "INSERT INTO practice_listings VALUES (?, ?, ?)", ("L1", "U1", 650_000)
            )
        rows = connection.execute(
            "SELECT id, monthly_rent FROM practice_listings WHERE owner_id = ?", ("U1",)
        ).fetchall()
        assert rows == [("L1", 650_000)]
        print("조회:", rows)
        try:
            with connection:
                connection.execute(
                    "UPDATE practice_listings SET monthly_rent = ? WHERE id = ?",
                    (700_000, "L1"),
                )
                raise ValueError("관련 처리 실패를 가정합니다.")
        except ValueError:
            pass
        after_rollback = connection.execute(
            "SELECT monthly_rent FROM practice_listings WHERE id = ?", ("L1",)
        ).fetchone()[0]
        assert after_rollback == 650_000
        print("실패 후 rollback:", after_rollback)
        with connection:
            connection.execute(
                "UPDATE practice_listings SET monthly_rent = ? WHERE id = ?",
                (700_000, "L1"),
            )
        assert (
            connection.execute("SELECT monthly_rent FROM practice_listings").fetchone()[
                0
            ]
            == 700_000
        )
        print("정상 수정 commit: 700000")
        with connection:
            connection.execute("DELETE FROM practice_listings WHERE id = ?", ("L1",))
        assert (
            connection.execute("SELECT COUNT(*) FROM practice_listings").fetchone()[0]
            == 0
        )
        print("삭제 및 SQL 실습: 통과")
    finally:
        connection.close()


if __name__ == "__main__":
    main()
