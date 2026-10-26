# Phase 1 official API evidence

Checked against the official Notion documentation on 2026-10-26. This describes
API behavior, not implementation completion.

- [Versioning](https://developers.notion.com/reference/versioning): the current
  request example uses `Notion-Version: 2026-03-11`, already pinned by this app.
  Ignore unknown response fields. Record IDs are stable UUIDs; generated links
  are not stable identity formats. Pagination cursors are opaque and may contain
  session information: return them verbatim, and restart a traversal after a
  process restart rather than treating a saved cursor as a durable identity.
- [Search limitations](https://developers.notion.com/reference/search-optimizations-and-limitations):
  search does not guarantee exhaustive enumeration of accessible workspace
  content. Indexing is delayed and results may change during pagination. Absence
  from search cannot establish deletion or withdrawn access.
- [Search endpoint](https://developers.notion.com/reference/post-search): on the
  pinned version, the object filter accepts `page` or `data_source`, not the
  legacy `database` filter. Retrieve databases through known IDs, child-database
  references and data-source parents. Use the
  [data-source query](https://developers.notion.com/reference/query-a-data-source)
  endpoint for member enumeration, with cursor pagination.
- [Block children](https://developers.notion.com/reference/get-block-children):
  list direct children using cursor pagination; recursively request descendants
  when `has_children` is true. Listing only the root is not complete hierarchy.
- [Block objects](https://developers.notion.com/reference/block): preserve stable
  block identity, parent, type and child status. Synced blocks distinguish their
  source reference from duplicated content; unsupported types remain explicit.
- [Parent objects](https://developers.notion.com/reference/parent-object): page,
  block, database, data-source and workspace parents have distinct semantics.
  Current page membership commonly refers to a data source; a data source is
  parented by its database. Less common agent and externally synced parents must
  remain representable as unknown/limited, not falsely converted to page parents.
- [Page properties](https://developers.notion.com/reference/retrieve-a-page-property):
  the page-object response may truncate reference-bearing values. Retrieve and
  paginate property items for complete relation coverage; a truncated relation
  cannot authorize removal of previously known targets.
- [Data-source retrieval](https://developers.notion.com/reference/retrieve-a-data-source):
  metadata retrieval is distinct from querying its member pages. Permission to a
  page does not imply permission to all related sources or targets.
- [Status codes](https://developers.notion.com/reference/status-codes):
  `object_not_found` can mean either nonexistent content or content not shared
  with the connection. Represent this ambiguity separately from an observed
  `in_trash` flag. Transient network/server failures do not establish either.

Structural edges report only their explicit source semantics. Neither adjacency
nor hyperlinks establish conceptual similarity, topic membership or prerequisites.

The [property-item object](https://developers.notion.com/reference/property-item-object)
uses the same property ID on multiple list items. Item IDs are not unique entity
IDs: preserve the ordered values and validate cursor progress rather than rejecting
repeated property IDs. Property IDs returned URL-encoded must be passed through
without double encoding. A regression test exercises two pages with the same
`title` property ID and distinct ordered values.
