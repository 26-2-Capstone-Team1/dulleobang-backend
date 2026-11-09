package com.dulleobang.chat.repository;

import com.dulleobang.chat.domain.*;
import com.dulleobang.common.json.StrictJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
public class ChatRepository {
  private final JdbcTemplate jdbc;

  public ChatRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private final RowMapper<ChatRoom> room =
      (rs, n) ->
          new ChatRoom(
              rs.getObject("chat_id", UUID.class),
              rs.getObject("listing_id", UUID.class),
              rs.getObject("inquirer_id", UUID.class),
              rs.getObject("owner_id", UUID.class),
              (ObjectNode) StrictJson.parse(rs.getString("listing_snapshot")),
              rs.getTimestamp("created_at").toInstant(),
              rs.getTimestamp("updated_at").toInstant());
  private final RowMapper<ChatMessage> message =
      (rs, n) ->
          new ChatMessage(
              rs.getObject("message_id", UUID.class),
              rs.getObject("chat_id", UUID.class),
              rs.getObject("sender_id", UUID.class),
              rs.getString("text"),
              rs.getLong("sequence"),
              rs.getTimestamp("sent_at").toInstant());

  public Optional<ChatRoom> find(UUID id, boolean lock) {
    return jdbc
        .query("SELECT * FROM chats WHERE chat_id=?" + (lock ? " FOR UPDATE" : ""), room, id)
        .stream()
        .findFirst();
  }

  public Optional<ChatRoom> existing(UUID listing, UUID actor) {
    return jdbc
        .query("SELECT * FROM chats WHERE listing_id=? AND inquirer_id=?", room, listing, actor)
        .stream()
        .findFirst();
  }

  public void create(ChatRoom r) {
    jdbc.update(
        "INSERT INTO chats VALUES(?,?,?,?,?::jsonb,?,?)",
        r.id(),
        r.listing(),
        r.inquirer(),
        r.owner(),
        StrictJson.stringify(r.snapshot()),
        java.sql.Timestamp.from(r.createdAt()),
        java.sql.Timestamp.from(r.updatedAt()));
  }

  public List<ChatRoom> rooms(UUID actor) {
    return jdbc.query(
        "SELECT * FROM chats WHERE owner_id=? OR inquirer_id=? ORDER BY updated_at DESC,chat_id",
        room,
        actor,
        actor);
  }

  public List<ChatMessage> messages(UUID chat) {
    return jdbc.query(
        "SELECT * FROM chat_messages WHERE chat_id=? ORDER BY sequence DESC", message, chat);
  }

  public Optional<ChatMessage> message(UUID id) {
    return jdbc.query("SELECT * FROM chat_messages WHERE message_id=?", message, id).stream()
        .findFirst();
  }

  public void add(UUID id, UUID chat, UUID sender, String text, Instant now, ObjectNode snapshot) {
    jdbc.update(
        "INSERT INTO chat_messages(message_id,chat_id,sender_id,text,sent_at) VALUES(?,?,?,?,?)",
        id,
        chat,
        sender,
        text,
        java.sql.Timestamp.from(now));
    jdbc.update(
        "UPDATE chats SET updated_at=?,listing_snapshot=?::jsonb WHERE chat_id=?",
        java.sql.Timestamp.from(now),
        StrictJson.stringify(snapshot),
        chat);
  }

  public long readMark(UUID chat, UUID actor) {
    return jdbc
        .query(
            "SELECT last_sequence FROM chat_reads WHERE chat_id=? AND user_id=?",
            (rs, n) -> rs.getLong(1),
            chat,
            actor)
        .stream()
        .findFirst()
        .orElse(0L);
  }

  public void mark(UUID chat, UUID actor, long sequence) {
    jdbc.update(
        "INSERT INTO chat_reads VALUES(?,?,?) ON CONFLICT(chat_id,user_id) DO UPDATE SET"
            + " last_sequence=GREATEST(chat_reads.last_sequence,EXCLUDED.last_sequence)",
        chat,
        actor,
        sequence);
  }
}
