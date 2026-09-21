<!-- AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com),
Ahmed Metwally (ametwally@uber.com) -->

# Uber Similarity Search Index

Uber Similarity Search Index, or USSI, is a platform-agnostic, in-memory Java
library for nearest neighbor search. You embed it in a host process, such as an
OpenSearch data node, and it holds your records in memory and answers similarity
queries over them.

It supports mutable ingestion, k-nearest-neighbor search, minimum-similarity
search, and metadata filtering, over dense vectors, sparse weighted features,
and ordered sequences.

USSI keeps nothing on disk and leaves several concerns to its host; see [What
USSI Does Not Do](#what-ussi-does-not-do). For how it works inside, see
[DESIGN.md](DESIGN.md). For embedding it in a host process, see
[INTEGRATION.md](INTEGRATION.md).

## Quick Start

Build the library and run its tests:

```bash
bazel build //:src_main
bazel test //:test_main
```

The same tests run on Linux and macOS for every pull request.

Create a namespace, insert rows, and search:

```java
import com.uber.ussi.NearestNeighborSearchIndex;
import com.uber.ussi.SearchResults;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import java.util.List;
import java.util.Map;

NamespaceConfig config =
    NamespaceConfig.builder()
        .minTermsAndValuesLength(128)
        .maxTermsAndValuesLength(128)
        .maxCacheSize(10000)
        .cacheType("scan")
        .indexType("matrix")
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(4)
        .maxNumSimilarities(1000)
        .build();

try (NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config)) {
  float[] sfValues = new float[128];
  sfValues[0] = 1.0f;
  float[] laValues = new float[128];
  laValues[1] = 1.0f;
  float[] updatedLaValues = new float[128];
  updatedLaValues[0] = 0.8f;
  updatedLaValues[1] = 0.2f;

  long sf =
      index.insert(
          new TermsAndValues(new String[0], sfValues),
          Map.of("city", "sf", "country", "us"));
  long la =
      index.insert(
          new TermsAndValues(new String[0], laValues),
          Map.of("city", "la", "country", "us"));

  TermsAndValues query = new TermsAndValues(new String[0], sfValues);
  MetaFilter filter = new MetaFilter(Map.of("country", List.of("us")));

  SearchResults neighbors = index.getNearestNeighborRowNums(10, query, filter);
  long nearestRowNum = neighbors.getRowNum(0);
  float nearestSimilarity = neighbors.getSimilarity(0);

  index.update(
      la,
      new TermsAndValues(new String[0], updatedLaValues),
      Map.of("city", "sf"));
  index.delete(sf);
}
```

`NearestNeighborSearchIndex.create` validates the whole configuration before
building anything, and reports every problem it finds in one message. A
misspelled value, a parameter key nothing reads, and a structure and comparator
that cannot work together all fail at creation.

## Records

A record reaches USSI as a `TermsAndValues`, built from parallel arrays:

```java
new TermsAndValues(String[] terms, float[] values)
```

A record's type is how it is addressed, and it decides which comparators can
read it:

| Record type | `terms` | `values` | Comparators |
| --- | --- | --- | --- |
| Sparse, addressed by its own terms | non-empty | one per term | `l2`, `jaccard`, `ruzicka` |
| Dense, addressed by position | empty | a vector of a fixed dimension | `l2`, `jaccard`, `ruzicka` |
| Sequence, ordered terms | the terms in arrival order, repeats included | empty | `gld`, `ngld` |

Sparse means addressed by terms, not that most coordinates are zero. A sparse
record with every coordinate populated is fine.

A configuration names an index type and a comparator, not a record type. If the
two share no record type, you find out when you create the namespace.

One record type carries several kinds of data, and the comparator decides which
kind you get. Pick the record type from how your data is addressed, and the
comparator from what you want measured:

| What you have | Values to use | Comparator |
| --- | --- | --- |
| Set | `1.0` for every term | `jaccard` |
| Multiset or bag | a count per term, or the term repeated | `ruzicka` |
| Weighted set | a weight per term | `ruzicka` |
| Vector, sparse or dense | the coordinates | `l2` |
| Sequence or string | none | `gld`, `ngld` |

`jaccard` counts any non-zero value as present and ignores magnitudes, so a
multiset given to it behaves as a set. `ruzicka` keeps magnitudes, giving
weighted Jaccard over weights and multiset Jaccard over counts. Both treat
opposite signs as not intersecting, and `ruzicka` weighs by absolute value. `l2`
uses the values as given.

A few things happen to your input on the way in, which matter when you compare
what you put in against what comes back:

- Terms are lowercased and encoded into primitive longs.
- Sparse records are canonicalized: terms sorted, values summed across duplicate
  terms, and zero sums dropped unless every sum is zero. Summing is what lets a
  multiset arrive as repeated terms or as counts.
- Sequence terms stay in arrival order.
- Metadata keys and values are lowercased.

## API

```java
static NearestNeighborSearchIndex create(NamespaceConfig namespaceConfig)
NamespaceConfig getNamespaceConfig()
long insert(TermsAndValues record, Map<String, String> metadata)
boolean delete(long rowNum)
boolean update(long rowNum, TermsAndValues record, Map<String, String> metadata)
SearchResults getNearestNeighborRowNums(int k, TermsAndValues record, MetaFilter metadataFilter)
SearchResults getSimilarRowNums(float minSimilarity, TermsAndValues record, MetaFilter metadataFilter)
int size()
void close()
```

`insert` returns a signed 64-bit `rowNum`, starting at `0` and incrementing.
`update` reuses the existing `rowNum`, so a row keeps its identity across
versions. Keep your own mapping from your document IDs to these values.

`getNearestNeighborRowNums` is a kNN query and `getSimilarRowNums` a capped
range query, returning the best `maxNumSimilarities` rows meeting the minimum
similarity rather than every row that meets it.

Both searches return `SearchResults`, an ordered container with parallel
`rowNums` and `similarities` arrays, ordered by descending similarity with the
lower `rowNum` breaking ties.

- `k` must be greater than `0`, and `minSimilarity` must be in `[0.0, 1.0]`.
- Results are capped by `maxNumSimilarities`.
- Similarities are comparator outputs put through the configured normalizer, so
  they are always in `[0.0, 1.0]`.
- An empty metadata filter matches every non-deleted row.

Call `close()` when you are done with a namespace. It releases any native memory
held by the dense matrix index.

## Configuration

`NamespaceConfig` is immutable and built with `NamespaceConfig.builder()`.

| Field | Description |
| --- | --- |
| `minTermsAndValuesLength` | Declared minimum record length. Must be non-negative. |
| `maxTermsAndValuesLength` | Declared maximum record length. Must be at least `minTermsAndValuesLength`. |
| `maxCacheSize` | Number of rows in the active cache that triggers graduation to an index. Must be positive. |
| `cacheType` | `scan` or `inverted_term`. |
| `cacheParams` | Cache options; see [Parameters](#parameters). |
| `indexType` | `scan`, `matrix`, `inverted_term`, `inverted_signature`, or `inverted_hybrid`. |
| `indexParams` | Index options; see [Parameters](#parameters). |
| `comparatorType` | `l2`, `jaccard`, `ruzicka`, `gld`, or `ngld`. |
| `comparatorNormalizerType` | `identity`, `lp`, `reciprocal`, or `complement`. |
| `comparatorParams` | Comparator options; see [Parameters](#parameters). |
| `comparatorNormalizerParams` | Accepts no keys. |
| `maxNumSearchableStructures` | Maximum number of active, graduating, and indexed structures before consolidation. Must be greater than `2`. |
| `maxNumSimilarities` | Maximum result count kept per structure and in the final merge. Must be positive. |

The length bounds are validated against each other but not enforced against each
inserted record.

### Choosing An Index Type

An `indexType` names how the index is keyed and how it finds candidates. Pick
the row that matches your records and the guarantee you need:

| `indexType` | Records | Comparator | What you get |
| --- | --- | --- | --- |
| `scan` | any | `l2`, `jaccard`, `ruzicka`, `gld`, `ngld` | Exact. Scores every row. Start here when in doubt. |
| `matrix` | dense | `l2` only | Exact. Dot products on a GPU where the CUDA bindings are present, otherwise OpenBLAS, otherwise Java. |
| `inverted_term` | sparse | `l2`, `jaccard`, `ruzicka` | Exact, and much faster than `scan` when a term selects few rows. |
| `inverted_term` | sequence | `gld`, `ngld` | Exact. Generates candidates from term multisets, then verifies with the edit distance. |
| `inverted_signature` | sparse | `jaccard` or `ruzicka`, with `signature_generator` | Approximate. Qualifying rows can be missed; the scores that come back are exact. |
| `inverted_signature` | sequence | `gld`, `ngld`, with `signature_generator` | Approximate. Draws signatures from the term multiset, then verifies with the edit distance. |
| `inverted_hybrid` | sparse | `jaccard` or `ruzicka`, with `signature_generator` | Exact for rows with at most 270 terms, approximate above that. |
| `inverted_hybrid` | sequence | `gld`, `ngld`, with `signature_generator` | Exact for sequences of at most 270 terms, approximate above that. |

Any pairing not listed is reported when you create the namespace. Note that
`matrix` takes `l2` and nothing else, even though it stores records `jaccard`
and `ruzicka` can also read.

`matrix` gathers the searches running at one moment into a single multiply. A
caller sending one query per request therefore gets the throughput of a batch
without batching anything itself.

For the cache, `scan` works with dense or sparse records and is the right choice
for sequences. `inverted_term` maintains mutable term lists for sparse records
and normally graduates into one of the inverted index types. A sequence
namespace must cache through `scan`.

### Choosing A Normalizer

A comparator produces either a similarity or a distance; the normalizer turns it
into a similarity in `[0.0, 1.0]`.

| Normalizer | Converts | Use with |
| --- | --- | --- |
| `identity` | passes through, requires `[0.0, 1.0]` already | `jaccard`, `ruzicka` |
| `reciprocal` | `d` to `1 / (1 + d)` | `l2`, `gld` |
| `lp` | `d` to `1 - d / 2` | `l2`, when distances are known to be in `[0.0, 2.0]` |
| `complement` | `d` to `1 - d`, requires `d` in `[0.0, 1.0]` | `ngld` |

### Parameters

Every parameter map is read by key, ignoring case and surrounding space. A key
nothing reads is a violation, so a typo fails loudly instead of leaving the
default in place. Keys are recognized per map, so a parameter only one structure
reads stays valid beside a structure that ignores it.

Index parameters:

| Parameter | Values | Description |
| --- | --- | --- |
| `metadata_filtering_strategy` | `auto` (default), `in_filtering`, `pre_filtering`, `post_filtering` | How the index applies metadata filters. Leave at `auto` unless you are tuning. |
| `max_pre_filtering_rows_ratio` | double in `[0.0, 1.0]`, default `0.1` | How selective a metadata filter has to be before pre-filtering is used. |
| `max_fraction_ids_per_term` | double in `(0.0, 1.0]`, default `1.0` | For the inverted index types, discards a term occurring in more than this fraction of rows. `1.0` disables it. See [Discarding Popular Terms](#discarding-popular-terms). |
| `popular_term_discard_scope` | `candidates_and_verification` (default), `candidates_only` | What discarding a term means. See [Discarding Popular Terms](#discarding-popular-terms). |
| `candidate_generator` | `spars` (default), `spars_merge` | Which candidate generator the inverted index types use. Both return identical results. See [Candidate Generation](#candidate-generation). |

Cache parameters, read by the `inverted_term` cache:

| Parameter | Values | Description |
| --- | --- | --- |
| `max_fraction_ids_per_term` | double in `(0.0, 1.0]`, default `1.0` | Discards a term whose one-sided popularity bound exceeds this fraction. `1.0` disables it. |
| `popular_term_discard_scope` | `candidates_and_verification` (default), `candidates_only` | What discarding a term means. See [Discarding Popular Terms](#discarding-popular-terms). |
| `max_fraction_ids_per_term_confidence` | double in `[0.5, 1.0]`, default `0.95` | Confidence used for the popularity bound. `0.5` reduces it to observed popularity. |
| `full_reevaluation_cache_size_decrease_fraction` | double in `[0.0, 1.0]`, default `0.10` | How far the cache must shrink before popularity is reevaluated for every term. `0.0` reevaluates after every deletion. |

Set `max_fraction_ids_per_term` and `popular_term_discard_scope` to the same
values in both `cacheParams` and `indexParams`. Each structure reads its own, so
setting them on only one leaves a namespace behaving one way before graduation
and another way after.

Comparator parameters:

| Parameter | Comparator | Values | Default |
| --- | --- | --- | --- |
| `signature_generator` | `jaccard` | `minhash` | none |
| `signature_generator` | `ruzicka`, `gld`, `ngld` | `i2cws`, `icws`, `pcws`, `scws` | none |
| `sequence_distance_type` | `gld`, `ngld` | `levenshtein`, `damerau_levenshtein`, `lcs` | `levenshtein` |

`signature_generator` is required by `inverted_signature` and `inverted_hybrid`
and optional everywhere else. `l2` does not support signature generation.

`jaccard` reads a record's distinct terms, so `minhash` serves it. The other
comparators read counts, whether a sparse record's values or how often a
sequence repeats a term, so they take a weighted sampler instead.

`gld` reports the number of single-term edits that turn one sequence into the
other. `ngld` divides that count by the two lengths as `2 * d / (length1 +
length2 + d)`, which makes scores comparable across sequences of different
lengths. `sequence_distance_type` chooses which edits count:

- `levenshtein`: insertion, deletion, and substitution.
- `damerau_levenshtein`: the above plus transposing two adjacent terms, so a
  swapped pair costs one edit rather than two.
- `lcs`: insertion and deletion only. Rewriting a term costs both, so an `lcs`
  distance is never below the `levenshtein` distance for the same pair.

## Metadata Filtering

Pass metadata at insertion time as a `Map<String, String>`. Every non-null key
and value is indexed for filtering; null keys and values are skipped.

Query with a `MetaFilter`:

```java
new MetaFilter(
    Map.of(
        "country", List.of("us"),
        "city", List.of("sf", "la")))
```

Values under one key are ORed, and different keys are ANDed, so this matches
rows in either SF or LA where the country is US. Keys and values match exactly
after lowercasing, and an empty `MetaFilter` matches every non-deleted row.

### Strategies An Index Type Supports

Every index type accepts every value of `metadata_filtering_strategy`. The
configuration is rejected only when the value names no strategy at all, so
`in_filtering` on a matrix index builds and runs. What differs between index
types is what `auto` chooses and what an explicitly named strategy costs.

| strategy | scan index | inverted indexes | matrix index |
|---|---|---|---|
| `auto` (default) | in-filtering | pre-filtering when the filter is selective enough, otherwise in-filtering | pre-filtering when the filter is selective enough, otherwise post-filtering |
| `in_filtering` | in-filtering | in-filtering | in-filtering, without the bulk multiply |
| `pre_filtering` | pre-filtering, otherwise in-filtering | pre-filtering, otherwise in-filtering | pre-filtering, otherwise post-filtering |
| `post_filtering` | post-filtering | post-filtering | post-filtering |

A search carrying no `MetaFilter` skips all of this and scores every row. For
the matrix index that is one bulk multiply, and for the others it is their
ordinary traversal.

What each strategy does, what it costs, and where it does not run as named:

- **`in_filtering`** applies the filter to each row as it is scored, and returns
  every row the filter accepts. On the matrix index it costs the bulk multiply:
  that index scores an unfiltered search with one multiply over every row, and a
  filter cannot be pushed into that multiply. In-filtering scores row by row
  instead. It pays there only when the filter rejects enough rows to outweigh
  the multiply, which is why `auto` never chooses it for that index type.
- **`pre_filtering`** asks the metadata index which rows match and scores only
  those, and returns every row the filter accepts. It runs only while the filter
  matches at most `max_pre_filtering_rows_ratio` of the rows. Past that it falls
  back, to in-filtering on the scan and inverted indexes and to post-filtering
  on the matrix index, and the fallback is not reported.
- **`post_filtering`** scores rows without the filter and applies the filter to
  what it kept, so it keeps the matrix index's bulk multiply. It is the one
  strategy that can return fewer rows than the filter accepts. It asks for more
  rows than the caller wanted, expanded by how selective the filter is and
  capped by `maxNumSimilarities`. Rows the filter accepts can still fall outside
  that expanded set.
- **`auto`** chooses per index type as the table shows. It never chooses
  in-filtering on the matrix index, where that would cost the multiply, which is
  why it is the one default that can post-filter.

In-filtering and pre-filtering return the same rows and differ only in the work
done to reach them. Post-filtering is the one strategy that changes which rows
come back. A matrix index under `auto` reaches it whenever a filter is not
selective enough to pre-filter.

## What To Expect From Results

Most of the time results are exact, meaning every row at or above the minimum
similarity is found and the scores are the comparator's. The cases below are
where that changes, and each is something you opt into.

**Signature indexes are approximate.** `inverted_signature`, and
`inverted_hybrid` above 270 terms, find candidates by signature collision. The
scores returned are exact, but qualifying rows can be missed. For sequences the
signatures come from the term multiset. The collision rate then estimates a
bound on the edit distance rather than the distance itself, so recall is looser
than it is for a comparator the signatures estimate directly.

**`inverted_term` only returns rows sharing a term with the query.** This
matters for sparse `l2`, where two records with no terms in common can still
have a non-zero similarity. Those rows are not returned. Use `scan` if you need
them.

**Post-filtering can return fewer rows than exist.** A search that resolves to
post-filtering scores rows first and applies the metadata filter to what it
kept. Rows the filter accepts can therefore fall outside what it kept. The
matrix index post-filters under `auto` whenever a filter is not selective enough
to pre-filter. [Strategies An Index Type
Supports](#strategies-an-index-type-supports) says when each strategy runs.

**Background maintenance is invisible to results.** Rows move from the active
cache into indexes, and older indexes consolidate, without affecting what a
search returns. Deletes that land during a build are replayed onto the new
structure, so a deleted row never comes back.

### Discarding Popular Terms

A term occurring in most rows produces most of the index as candidates without
narrowing anything down. Setting `max_fraction_ids_per_term` below `1.0` lets
the inverted cache and indexes discard such terms. `popular_term_discard_scope`
decides what a discard means, and the two settings differ in which half of the
answer stays exact:

`candidates_and_verification`, the default, removes a discarded term from the
lists and from the records being scored. Similarities are then reported between
the records with the popular terms removed, and every row at or above the
minimum similarity by that measure is found. Choose this when the popular terms
carry no signal worth reporting.

`candidates_only` removes a discarded term from the lists only. Similarities are
reported over your records as supplied, including the popular terms, but recall
is no longer exact: candidates are pruned using the similarity measured without
those terms, so a qualifying row can be dropped before it is ever scored. Choose
this when you must report exact similarities on the original records. How much
recall costs depends on how much similarity the discarded terms carried.

### Candidate Generation

The inverted index types can find candidates two ways, chosen with the
`candidate_generator` index parameter. Both return identical results and honor
every metadata filtering strategy; they differ only in how much work they do.

`spars`, the default, visits the query's keys cheapest first and scores every
surviving candidate. It works with every inverted index type and every
comparator.

`spars_merge` advances all of the query's keys together, letting it abandon a
row as soon as no completion of it can reach the current minimum similarity. It
pays off when queries have many keys and the minimum similarity rejects most
rows early. It is available for `l2`, `jaccard`, and `ruzicka`, and not for the
sequence comparators.

## What USSI Does Not Do

- No persistence. The index is memory-only and your platform remains the source
  of truth.
- No sharding, and no mapping from your document IDs to `rowNum` values.
- No TTL enforcement. Call `delete(rowNum)` when a row expires.
- No authorization.
- No plugin or adapter layers for serving systems such as OpenSearch,
  RediSearch, or Milvus.

More specialized sparse and approximate index types can be added behind the same
USSI cache and index factory interfaces.

## Code of Conduct

This project follows the [Uber Code of Conduct](CODE_OF_CONDUCT.md).

## License

Apache License 2.0. See [`LICENSE.md`](LICENSE).
