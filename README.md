<!-- AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) -->

# Uber Similarity Search Index

Uber Similarity Search Index, or USSI, is a platform-agnostic,
in-memory Java library for nearest neighbor search. It is designed to be
embedded inside search systems such as OpenSearch data nodes, while keeping the
indexing logic independent from any one serving platform.

The index supports mutable ingestion, k-nearest-neighbor search,
minimum-similarity search, and metadata filtering over records represented as
`TermsAndValues`.

## Status

This module is an early open-source candidate and currently implements the core
memory-only index structure:

- Mutable `generic` and `sparse` caches for inserts, updates, deletes, and
  search.
- Delete-only `generic`, `dense`, `inverted`, `signature`, and hybrid `sparse`
  indexes built from graduated cache contents.
- L2, signed Jaccard, and signed weighted-Jaccard (Ruzicka) comparators with
  configurable normalization into a `[0.0, 1.0]` similarity score.
- Exact sparse candidate generation through inverted term lists with length,
  position, and unordered-prefix filtering.
- Approximate sparse candidate generation using MinHash for Jaccard and I2CWS,
  ICWS, PCWS, or SCWS for Ruzicka.
- A hybrid sparse index that sends rows with at most 270 terms to the exact
  inverted index and longer rows to the signature index.
- Metadata filtering with in-filtering, pre-filtering, post-filtering, and
  automatic strategy selection.
- Bounded top-k accumulation with `BoundedSizeMaxHeap`, so each searchable
  structure and the final merge keep only the configured result limit.
- Optional OpenBLAS acceleration for dense matrix-vector dot products, with a
  Java fallback when the native scorer is unavailable.

Dense and sparse numeric records use the same public `TermsAndValues` API and
the same configured comparator. Sequence records can be represented by the data
model but are not currently searchable because no sequence comparator is
implemented.

The library does not provide persistence, sharding, external document-id
mapping, TTL enforcement, authorization, or platform-specific plugin/adapter layers for serving systems such as OpenSearch, RediSearch, Milvus, etc. Those concerns are expected to be handled by the hosting platform.

## Architecture

The top-level `NearestNeighborSearchIndex` owns one active cache, zero or more
graduating caches that are being converted into indexes, and zero or more
indexes. The `Internal Design` section explains why graduated indexes only need
delete support.

```text
insert/update/delete
        |
        v
 active mutable cache
        |
        | reaches maxCacheSize
        v
 graduating cache --background build--> delete-only index
                                      |
                                      | too many searchable structures
                                      v
                              consolidated index

search query
        |
        v
 active cache + graduating caches + indexes
        |
        v
 bounded heap merge
```

Rows are inserted into the active cache. When the cache reaches `maxCacheSize`,
it is rotated into the graduating-cache list and a fresh cache starts accepting
writes. A background task snapshots the graduating cache and builds an index.
When the total number of searchable structures reaches
`maxNumSearchableStructures`, older indexes are consolidated in the background.

Deletes are applied to the newest structure that contains the row. Updates are
implemented as delete plus insert with the same `rowNum`, preserving the logical
row identity while moving the latest version into the active cache.

The top-level index uses a single read/write lock. Searches run under the read
lock, and mutations run under the write lock. Background builds snapshot data
under the read lock, build outside the lock, and take the write lock only for
the final swap and tombstone replay.

## Data Model

Each inserted row has:

- A `TermsAndValues` record.
- A `Map<String, String>` metadata object used only for filtering.
- An internal signed 64-bit `rowNum` returned by `insert`.

Top-level `rowNum` values start at `0` and increment on each insert. Updates
reuse the existing `rowNum`.

The hosting platform should maintain any mapping between its document IDs and
the returned `rowNum` values.

`TermsAndValues` uses parallel arrays:

```java
new TermsAndValues(String[] terms, float[] values)
```

It can represent several feature shapes:

- Dense vector: empty `terms`, non-empty `values`.
- Sparse weighted feature: non-empty `terms`, non-empty `values` with the same
  length.
- Sequence: non-empty `terms`, empty `values`. This shape is reserved for future
  sequence comparators and is not currently searchable.

The `l2`, `jaccard`, and `ruzicka` comparators work on both dense and sparse
numeric records. Dense values align by array position. Sparse values align by
term, and a term missing from either record has value `0.0`. The `dense` index
is the exception: it is a matrix implementation that requires dense L2 records
with a fixed number of dimensions.

Jaccard compares signed presence: every non-zero magnitude contributes `1.0`,
and opposite signs do not intersect. Ruzicka uses the same signed matching rule
but preserves absolute value magnitudes as weights. L2 uses the original numeric
values.

Public string terms are lowercased and encoded into primitive longs on
ingestion to reduce memory overhead. Sparse records are canonicalized by sorting
the encoded terms, summing values for duplicate terms, and dropping zero sums
unless every summed value is zero. Metadata keys and values used for filtering
are also lowercased.

## Quick Start

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
        .cacheType("generic")
        .indexType("dense")
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

  SearchResults neighbors = index.getNearestNeighbors(10, query, filter);
  long nearestRowNum = neighbors.getRowNum(0);
  float nearestSimilarity = neighbors.getSimilarity(0);

  index.update(
      la,
      new TermsAndValues(new String[0], updatedLaValues),
      Map.of("city", "sf"));
  index.delete(sf);
}
```

`getNearestNeighbors` and `getSimilarRowNums` return `SearchResults`, an ordered
result container with parallel `rowNums` and `similarities` arrays. Results are
ordered by descending similarity, with lower `rowNum` values breaking ties.

## Search API

```java
static NearestNeighborSearchIndex create(NamespaceConfig namespaceConfig)
NamespaceConfig getNamespaceConfig()
long insert(TermsAndValues record, Map<String, String> metadata)
boolean delete(long rowNum)
boolean update(long rowNum, TermsAndValues record, Map<String, String> metadata)
SearchResults getNearestNeighbors(int k, TermsAndValues record, MetaFilter metadataFilter)
SearchResults getSimilarRowNums(float minSimilarity, TermsAndValues record, MetaFilter metadataFilter)
int size()
void close()
```

Search behavior:

- `k` must be greater than `0`.
- `minSimilarity` must be in `[0.0, 1.0]`.
- Returned rows are capped by `maxNumSimilarities`.
- Similarities are normalized comparator outputs.
- Top-k tie-breaking prefers higher similarity, then lower `rowNum`.
- Empty metadata filters match all non-deleted rows.

## Configuration

`NamespaceConfig` is immutable and should be built with
`NamespaceConfig.builder()`.

| Field | Description |
| --- | --- |
| `minTermsAndValuesLength` | Declared minimum record length. Must be non-negative. |
| `maxTermsAndValuesLength` | Declared maximum record length. Must be at least `minTermsAndValuesLength`. |
| `maxCacheSize` | Number of active-cache rows that triggers cache graduation to an index. Must be positive. |
| `cacheType` | Supported values: `generic`, `sparse`. |
| `cacheParams` | Cache-specific options, including sparse-term popularity filtering. |
| `indexType` | Supported values: `generic`, `dense`, `inverted`, `signature`, `sparse`. |
| `indexParams` | Index-specific options such as metadata filtering strategy. |
| `comparatorType` | Supported values: `l2`, `jaccard`, `ruzicka`. |
| `comparatorParams` | Comparator-specific options, including signature generation. |
| `comparatorNormalizerType` | Supported values: `identity`, `lp`, `reciprocal`. |
| `comparatorNormalizerParams` | Currently unused; reserved for future normalizer-specific options. |
| `maxNumSearchableStructures` | Maximum number of active, graduating, and indexed structures before consolidation. Must be greater than `2`. |
| `maxNumSimilarities` | Maximum result count kept by structure-level search and final merge. Must be positive. |

The current implementation validates these length bounds structurally but does
not enforce them against each inserted record.

Supported index combinations:

| `indexType` | Record shape | Comparator | Candidate generation |
| --- | --- | --- | --- |
| `generic` | Dense or sparse numeric | `l2`, `jaccard`, `ruzicka` | Exact sequential scan. |
| `dense` | Fixed-dimension dense | `l2` | Exact matrix scan with Java or OpenBLAS dot products. |
| `inverted` | Sparse numeric | `l2`, `jaccard`, `ruzicka` | Exact inverted term lists. |
| `signature` | Sparse numeric | `jaccard` or `ruzicka` with a signature generator | Approximate signature inverted lists; retained candidates are scored with the original comparator. |
| `sparse` | Sparse numeric | `jaccard` or `ruzicka` with a signature generator | Hybrid exact/signature routing at 270 terms. |

The `generic` cache is a sequential scan and works with dense or sparse numeric
records. The `sparse` cache maintains mutable inverted term lists and is
intended for sparse numeric records. A sparse cache should normally graduate to
an `inverted`, `signature`, or hybrid `sparse` index.

Index parameters:

| Parameter | Values | Default | Description |
| --- | --- | --- | --- |
| `metadata_filtering_strategy` | `auto`, `in_filtering`, `pre_filtering`, `post_filtering` | `auto` | Controls how indexes apply metadata filters. Values use underscores. |
| `max_pre_filtering_rows_ratio` | double in `[0.0, 1.0]` | `0.1` | Maximum matching-row ratio that allows pre-filtering. |
| `max_fraction_ids_per_sparse_key` | double in `(0.0, 1.0]` | `1.0` | For sparse indexes, removes a term from candidate generation and comparison when it occurs in more than this fraction of indexed rows. `1.0` disables this filtering. |

The `generic` cache does not currently accept any `cacheParams`. The `sparse`
cache accepts the following parameters:

| Parameter | Values | Default | Description |
| --- | --- | --- | --- |
| `max_fraction_ids_per_sparse_key` | double in `(0.0, 1.0]` | `1.0` | Filters terms whose one-sided popularity confidence bound exceeds this fraction. `1.0` disables this filtering. |
| `max_fraction_ids_per_sparse_key_confidence` | double in `[0.5, 1.0]` | `0.95` | Confidence used for the sparse-cache popularity bound. `0.5` reduces the check to observed popularity. |
| `full_reevaluation_cache_size_decrease_fraction` | double in `[0.0, 1.0]` | `0.10` | Cache-size decrease from the last exact popularity evaluation that triggers a full reevaluation. `0.0` reevaluates after every deletion; `1.0` waits until the cache is empty. |

The mutable sparse cache updates popularity decisions incrementally. Deletions
recheck terms from the deleted row and the currently filtered set. When the
cache has shrunk by at least the configured fraction from the last exact
evaluation, it reevaluates all terms to account for the smaller denominator.

To apply the same popularity threshold before and after cache graduation, set
`max_fraction_ids_per_sparse_key` to the same value in both `cacheParams` and
`indexParams`. The confidence parameter applies only to the mutable cache.

Comparator parameters:

| Parameter | Comparator | Values | Default |
| --- | --- | --- | --- |
| `signature_generator_type` | `jaccard` | `minhash` | None |
| `signature_generator_type` | `ruzicka` | `i2cws`, `icws`, `pcws`, `scws` | None |

Without `signature_generator_type`, Jaccard and Ruzicka still work in generic,
sparse-cache, and exact inverted paths. The `signature` and hybrid `sparse`
indexes require it. L2 does not support signature generation.

When `metadata_filtering_strategy` is `auto`, `GenericIndex` resolves metadata
filtering to in-filtering. `DenseMatrixIndex` tries pre-filtering when the
metadata filter is selective enough according to `max_pre_filtering_rows_ratio`;
otherwise it falls back to post-filtering. Inverted and signature indexes also
try selective pre-filtering, then fall back to in-filtering.

Normalizer behavior:

- `identity`: the comparator value must already be a similarity in `[0.0, 1.0]`.
- `reciprocal`: converts distance `d` to `1 / (1 + d)`.
- `lp`: converts distance `d` to `1 - d / 2`, intended for bounded Lp-style
  distances.

Jaccard and Ruzicka naturally produce similarities and normally use `identity`.
L2 produces a distance and normally uses `reciprocal`, or `lp` when the input
domain guarantees distances in `[0.0, 2.0]`.

None of the currently supported comparator normalizers accept parameters.
`comparatorNormalizerParams` is reserved for future use.

## Metadata Filtering

Metadata is provided at insertion time as `Map<String, String>`. All non-null
metadata keys and values are indexed for filtering. Null metadata keys and
values are skipped.

Queries use `MetaFilter`, which accepts a `Map<String, List<String>>`.

```java
new MetaFilter(
    Map.of(
        "country", List.of("us"),
        "city", List.of("sf", "la")))
```

Filtering semantics:

- Values within the same metadata key are ORed.
- Different metadata keys are ANDed.
- Keys and values are matched exactly after lowercasing.
- An empty `MetaFilter` matches every non-deleted row.

For example, `country in [us]` AND `city in [sf, la]` matches rows in either SF
or LA where the country is US.

## Internal Design

The following sections describe internal maintenance structures. Embedding
platforms should use the public `NearestNeighborSearchIndex` API rather than
managing these structures directly.

### Handling Inserts, Deletes, and Updates

The top-level index is mutable even though graduated indexes are delete-only.
New rows are always inserted into the active mutable cache. When that cache
reaches `maxCacheSize`, it is snapshotted and built into an index; newer writes
continue in a fresh active cache.

Delete-only indexes are enough for graduated data because they only need to
serve searches over the snapshot they were built from and hide rows that are no
longer current. A delete marks the row as deleted in whichever searchable
structure contains it. An update is handled as a delete of the old row version
followed by inserting the new version into the active cache with the same
logical `rowNum`.

This keeps immutable index implementations simple: they do not need to support
in-place inserts or updates, only search and tombstone-style deletes. Background
consolidation later rebuilds older indexed rows into a newer index and drops
deleted rows from that rebuilt snapshot.

### Searchable Structures

Index implementations live under
`com.uber.ussi.searchablestructure.index`, split into
sub-packages by index type:

- `index.generic`: `GenericIndex`, the generic sequential-scan index.
- `index.dense`: `DenseMatrixIndex` and its matrix-vector dot-product scorers
  (`DenseMatrixDotProductScorers` and the Java and OpenBLAS scorers).
- `index.sparse`: `InvertedIndex`, `SignatureIndex`, and the hybrid
  `SparseIndex`.

The shared `Index` base class, `IndexFactory`, and
`MetadataFilteredSearchExecutor` stay in the `index` package itself. Mutable
`GenericCache` and `SparseCache` implementations live under
`searchablestructure.cache`.

#### Generic Cache

`GenericCache` is mutable and supports insert, update, delete, kNN search, and
minimum-similarity search. It scans all cached rows and applies metadata filters
before scoring rows.

#### Sparse Cache

`SparseCache` is mutable and keeps inverted term lists in insertion order.
It generates deduplicated candidates from query terms using unordered-prefix
filtering, then scores candidates with the configured comparator. A metadata
filter matching at most 1% of the cache uses a direct scan of those matching
rows instead.

Sparse-cache searches only return rows sharing at least one non-filtered term
with the query. High-popularity terms are removed dynamically according to the
configured one-sided confidence bound. The complete stored records and inverted
lists retain those terms, allowing filtering decisions to be reversed as the
cache changes.

#### Generic Index

`GenericIndex` (in `index.generic`) is delete-only and uses sequential scan
search over a snapshot of graduated rows. It supports metadata in-filtering and
can participate in pre-filtering or post-filtering depending on configuration.

#### Dense Matrix Index

`DenseMatrixIndex` (in `index.dense`) is delete-only and stores dense vectors in
a row-major float matrix. It supports only the `l2` comparator. Rows must have
empty terms and the same non-zero dimension.

For unfiltered all-row scoring, it computes matrix-vector dot products and then
derives L2 distance from:

```text
||query - row||^2 = ||query||^2 + ||row||^2 - 2 * dot(query, row)
```

The dense scorer tries to use OpenBLAS on supported Linux and macOS platforms.
If OpenBLAS cannot be loaded, it falls back to the Java scorer.
`NearestNeighborSearchIndex.close()` releases any native dense-matrix memory.

#### Exact Inverted Index

`InvertedIndex` is a delete-only sparse index whose keys are the canonicalized
terms. Inverted lists are sorted by each row's comparator-specific unilateral
value, enabling length filtering. Candidate traversal combines length,
position, and unordered-prefix filtering while tightening the similarity
threshold as the top-k heap fills.

Each row and each query must have non-empty terms and values arrays of equal
length after canonicalization; a query and a row do not need to have the same
number of terms as each other. Search only considers rows sharing at least one
non-filtered term with the query. This is important for sparse L2: two disjoint sparse
vectors can have a non-zero normalized L2 similarity, but `inverted` deliberately
does not return such rows. Use `generic` when exhaustive scoring across disjoint
sparse L2 records is required.

At build time, terms occurring in more than
`floor(numRows * max_fraction_ids_per_sparse_key)` rows are removed from both
candidate generation and comparison. The default fraction of `1.0` disables
this behavior.

#### Signature Index

`SignatureIndex` replaces original terms as inverted-list keys with 270 deterministic,
similarity-preserving signatures per row. Jaccard uses MinHash; Ruzicka uses the
configured CWS variant. Signature collisions generate candidates approximately,
but candidates are scored using canonical terms and values after configured
popularity filtering, not by comparing the signatures themselves.

Signature prefix filtering applies a generator-specific approximation safety
margin: `0.1` for MinHash, I2CWS, ICWS, and SCWS, and `0.15` for PCWS. These
margins broaden candidate generation but do not make the signature index exact.

#### Hybrid Sparse Index

`SparseIndex` combines an `InvertedIndex` and a `SignatureIndex`. During each
index build, rows with at most 270 terms go to the exact inverted child and rows
with more than 270 terms go to the signature child. The configured length range
may be entirely below, entirely above, or span this internal boundary.

Queries search the child matching the query length first. Jaccard's cardinality
bounds can skip the other child when no row on that side can reach the active
similarity threshold. Ruzicka and popularity-filtered searches conservatively
search both children because term count alone cannot prove that one side is
irrelevant. Results from the searched children are merged and limited by
`maxNumSimilarities`.

The hybrid requires a signature-capable Jaccard or Ruzicka comparator.

## Build and Test

```bash
bazel build //:src_main
bazel test //:test_main
```

## Operational Notes

- The index is memory-only. The hosting platform remains the source of truth.
- TTL should be enforced by the hosting platform by calling `delete(rowNum)`
  when a row expires.
- Background cache graduation and index consolidation are internal maintenance
  tasks. Search results include active, graduating, and indexed rows while those
  tasks are in flight.
- Signature indexes use approximate candidate generation. Final scores are
  exact for the candidates that are found, but qualifying rows can be missed.
- High-popularity sparse-term filtering changes both candidate generation and
  comparison by removing the filtered terms from each query and row.


- The implementation favors correctness and simple integration for the current MVP.
  More specialized sparse or approximate indexes can be added behind the same
  cache/index factory interfaces.


## Code of Conduct

This project follows the [Uber Code of Conduct](CODE_OF_CONDUCT.md).

## License

Apache License 2.0 — see [`LICENSE.md`](LICENSE).