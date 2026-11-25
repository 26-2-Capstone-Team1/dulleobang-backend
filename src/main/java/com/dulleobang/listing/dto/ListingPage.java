package com.dulleobang.listing.dto;

import java.util.List;

public sealed interface ListingPage<T> {
  record Aggregate<T>(List<T> items, String nextCursor) implements ListingPage<T> {}

  record Paginated<T>(List<T> items, PageInfo page) implements ListingPage<T> {}

  record PageInfo(String nextCursor, boolean hasNext) {}
}
