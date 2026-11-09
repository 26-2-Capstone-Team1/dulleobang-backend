package com.dulleobang.scrap.repository;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ScrapRepository {
  private final JdbcTemplate jdbc;

  public ScrapRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void save(UUID actor, UUID listing, Instant now) {
    jdbc.update(
        "INSERT INTO scraps VALUES(?,?,?) ON CONFLICT DO NOTHING",
        actor,
        listing,
        java.sql.Timestamp.from(now));
  }

  public void delete(UUID actor, UUID listing) {
    jdbc.update("DELETE FROM scraps WHERE user_id=? AND listing_id=?", actor, listing);
  }

  public List<UUID> list(UUID actor) {
    return jdbc.query(
        "SELECT s.listing_id FROM scraps s JOIN listings l ON l.listing_id=s.listing_id WHERE"
            + " s.user_id=? AND l.deleted_at IS NULL AND l.status='PUBLISHED' ORDER BY s.saved_at"
            + " DESC,s.listing_id",
        (rs, n) -> rs.getObject(1, UUID.class),
        actor);
  }
}
