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

- Mutable `scan` and `inverted_term` caches for inserts, updates, deletes, and
  search.
- Delete-only `scan`, `matrix`, `inverted_term`, `inverted_signature`, and
  `inverted_hybrid` indexes built from graduated cache contents.
- L2, signed Jaccard, and signed weighted-Jaccard (Ruzicka) comparators with
  configurable normalization into a `[0.0, 1.0]` similarity score.
- Generalized Levenshtein distance (`gld`) and its normalized form (`ngld`)
  over ordered sequences, each over a configurable Levenshtein,
  Damerau-Levenshtein, or longest common subsequence distance.
- Exact candidate generation through inverted term lists with length, position,
  and prefix filtering.
- Approximate candidate generation using MinHash for Jaccard and I2CWS, ICWS,
  PCWS, or SCWS for Ruzicka.
- A hybrid index that sends rows with at most 270 terms to the exact term lists
  and longer rows to the signature lists.
- Sequence support over the same term-keyed structure, which generates
  candidates from element multisets and has the configured edit distance verify
  each candidate against the ordered sequences.
- Metadata filtering with in-filtering, pre-filtering, post-filtering, and
  automatic strategy selection.
- Bounded top-k accumulation with `BoundedSizeMaxHeap`, so each searchable
  structure and the final merge keep only the configured result limit.
- Optional OpenBLAS acceleration for dense matrix-vector dot products, with a
  Java fallback when the native scorer is unavailable.

Dense, sparse numeric, and sequence records all use the same public
`TermsAndValues` API. Which of the three a comparator can read is what decides
the searchable structures it can be paired with. Three rules are checked up
front, before a namespace holds any rows: a structure and a comparator with no
record layout in common, a structure whose keys the comparator cannot generate,
and a candidate generator the comparator cannot support are each reported as a
config violation rather than surfacing when the first index is built.

The library does not provide persistence, sharding, external document-id
mapping, TTL enforcement, authorization, or platform-specific plugin/adapter
layers for serving systems such as OpenSearch, RediSearch, Milvus, etc.
Those concerns are expected to be handled by the hosting platform.

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

It can represent three record layouts. The first two are order-agnostic: their
similarity does not depend on the order their elements arrived in, which is why
their terms may be kept sorted. The third is ordered, and its order is what the
comparator measures. None of the three is named in a config, because a
comparator publishes the layouts it reads and a record is validated against the
one its index resolved.

- Order-agnostic dense vector: empty `terms`, non-empty `values` of a fixed
  dimension, addressed by position.
- Order-agnostic sparse weighted feature: non-empty `terms`, non-empty `values`
  of the same length, addressed by the record's own terms. Sparse names how a
  record is addressed rather than how many of its coordinates are populated,
  which nothing validates.
- Sequence: non-empty `terms` holding the elements in the order they arrived,
  repeats included, and empty `values`.

The `l2`, `jaccard`, and `ruzicka` comparators work on both order-agnostic
layouts. Dense values align by array position. Sparse values align by term, and
a term missing from either record has value `0.0`. The `matrix` index is the
exception: it stores only dense records, so it can only be paired with `l2`.

The `gld` and `ngld` comparators read sequences only. Sequence terms are left
in the order they arrived rather than canonicalized, since that order is what
an edit distance measures.

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

`getNearestNeighborRowNums` and `getSimilarRowNums` return `SearchResults`, an ordered
result container with parallel `rowNums` and `similarities` arrays. Results are
ordered by descending similarity, with lower `rowNum` values breaking ties.

## Search API

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
| `cacheType` | Supported values: `scan`, `inverted_term`. |
| `cacheParams` | Cache-specific options, including term popularity filtering. |
| `indexType` | Supported values: `scan`, `matrix`, `inverted_term`, `inverted_signature`, `inverted_hybrid`. |
| `indexParams` | Index-specific options such as metadata filtering strategy. |
| `comparatorType` | Supported values: `l2`, `jaccard`, `ruzicka`, `gld`, `ngld`. |
| `comparatorParams` | Comparator-specific options, including signature generation and sequence distance. |
| `comparatorNormalizerType` | Supported values: `identity`, `lp`, `reciprocal`, `complement`. |
| `comparatorNormalizerParams` | Currently unused; reserved for future normalizer-specific options. |
| `maxNumSearchableStructures` | Maximum number of active, graduating, and indexed structures before consolidation. Must be greater than `2`. |
| `maxNumSimilarities` | Maximum result count kept by structure-level search and final merge. Must be positive. |

The current implementation validates these length bounds structurally but does
not enforce them against each inserted record.

An `indexType` names a structure: what the index is keyed by and how it
generates candidates. It does not name a record layout, because a comparator
publishes the layouts it can read and an index stores the one layout it and its
structure have in common. That is why one structure serves two layouts below
and why an unlisted pairing is a config violation rather than a silent choice.

| `indexType` | Record layout stored | Comparator | Candidate generation |
| --- | --- | --- | --- |
| `scan` | Whichever the comparator reads | `l2`, `jaccard`, `ruzicka`, `gld`, `ngld` | Exact sequential scan. |
| `matrix` | Fixed-dimension dense | `l2` | Exact matrix scan with Java or OpenBLAS dot products. |
| `inverted_term` | Sparse numeric | `l2`, `jaccard`, `ruzicka` | Exact inverted term lists. |
| `inverted_term` | Sequence | `gld`, `ngld` | Element-multiset inverted lists; retained candidates are scored against the ordered sequences. |
| `inverted_signature` | Sparse numeric | `jaccard` or `ruzicka` with a signature generator | Approximate signature inverted lists; retained candidates are scored with the original comparator. |
| `inverted_hybrid` | Sparse numeric | `jaccard` or `ruzicka` with a signature generator | Hybrid exact/signature routing at 270 terms. |

The `scan` cache is a sequential scan and works with dense or sparse numeric
records. The `inverted_term` cache maintains mutable inverted term lists and is
intended for sparse numeric records. It should normally graduate to an
`inverted_term`, `inverted_signature`, or `inverted_hybrid` index. A sequence
namespace caches through `scan`, because the `inverted_term` cache reads one
value per distinct term and a sequence supplies neither.

Index parameters:

| Parameter | Values | Default | Description |
| --- | --- | --- | --- |
| `metadata_filtering_strategy` | `auto`, `in_filtering`, `pre_filtering`, `post_filtering` | `auto` | Controls how indexes apply metadata filters. Values use underscores. |
| `max_pre_filtering_rows_ratio` | double in `[0.0, 1.0]` | `0.1` | Maximum matching-row ratio that allows pre-filtering. |
| `max_fraction_ids_per_key` | double in `(0.0, 1.0]` | `1.0` | For the inverted index types, discards a term when it occurs in more than this fraction of indexed rows. `1.0` disables this filtering. |
| `popular_term_discard_scope` | `candidates_and_verification`, `candidates_only` | `candidates_and_verification` | Which phases of a search a discarded term is absent from. See [Discarding Popular Terms](#discarding-popular-terms). |
| `candidate_generator` | `spars`, `spars_merge` | `spars` | For the inverted index types, selects the candidate generation algorithm. See [Candidate Generation](#candidate-generation). |

The `scan` cache does not currently accept any `cacheParams`. The
`inverted_term` cache accepts the following parameters:

| Parameter | Values | Default | Description |
| --- | --- | --- | --- |
| `max_fraction_ids_per_key` | double in `(0.0, 1.0]` | `1.0` | Filters terms whose one-sided popularity confidence bound exceeds this fraction. `1.0` disables this filtering. |
| `popular_term_discard_scope` | `candidates_and_verification`, `candidates_only` | `candidates_and_verification` | Which phases of a search a discarded term is absent from. See [Discarding Popular Terms](#discarding-popular-terms). |
| `max_fraction_ids_per_key_confidence` | double in `[0.5, 1.0]` | `0.95` | Confidence used for the cache popularity bound. `0.5` reduces the check to observed popularity. |
| `full_reevaluation_cache_size_decrease_fraction` | double in `[0.0, 1.0]` | `0.10` | Cache-size decrease from the last exact popularity evaluation that triggers a full reevaluation. `0.0` reevaluates after every deletion; `1.0` waits until the cache is empty. |

The mutable `inverted_term` cache updates popularity decisions incrementally. Deletions
recheck terms from the deleted row and the currently filtered set. When the
cache has shrunk by at least the configured fraction from the last exact
evaluation, it reevaluates all terms to account for the smaller denominator.

To apply the same popularity threshold before and after cache graduation, set
`max_fraction_ids_per_key` to the same value in both `cacheParams` and
`indexParams`. The same goes for `popular_term_discard_scope`: it is read from
each structure's own params, so setting it on only one of the two leaves a
namespace reporting one kind of similarity before graduation and the other kind
after. The confidence parameter applies only to the mutable cache.

Comparator parameters:

| Parameter | Comparator | Values | Default |
| --- | --- | --- | --- |
| `signature_generator_type` | `jaccard` | `minhash` | None |
| `signature_generator_type` | `ruzicka` | `i2cws`, `icws`, `pcws`, `scws` | None |
| `sequence_distance_type` | `gld`, `ngld` | `levenshtein`, `damerau_levenshtein`, `lcs` | `levenshtein` |

Without `signature_generator_type`, Jaccard and Ruzicka still work on the
`scan` and `inverted_term` structures. The `inverted_signature` and
`inverted_hybrid` indexes require it. L2 does not support signature generation.

Both sequence comparators are named for the distance they report. `gld` is the
generalized Levenshtein distance, the number of single-element edits that turn
one sequence into the other. It is generalized in that which edits count is
itself configurable, through `sequence_distance_type`. `ngld` is the normalized
generalized Levenshtein distance, that same edit count divided by the two
sequences' lengths as `2 * d / (length1 + length2 + d)`, which is what makes it
comparable across sequences of different lengths.

The distances differ only in the edits they permit:

- `levenshtein`: insertion, deletion, and substitution of one element.
- `damerau_levenshtein`: the Levenshtein edits plus transposition of two
  adjacent elements, so a pair of elements in the wrong order costs one edit
  rather than two.
- `lcs`: insertion and deletion only, the distance complementing the longest
  common subsequence. Every element outside that subsequence has to be deleted
  from one sequence or inserted into the other, so the distance is
  `length1 + length2 - 2 * lcsLength`. Rewriting an element costs a deletion and
  an insertion, so an `lcs` distance is never below the `levenshtein` distance
  over the same pair.

When `metadata_filtering_strategy` is `auto`, `ScanIndex` resolves metadata
filtering to in-filtering. `MatrixIndex` tries pre-filtering when the
metadata filter is selective enough according to `max_pre_filtering_rows_ratio`;
otherwise it falls back to post-filtering. Term and signature indexes also
try selective pre-filtering, then fall back to in-filtering.

Normalizer behavior:

- `identity`: the comparator value must already be a similarity in `[0.0, 1.0]`.
- `reciprocal`: converts distance `d` to `1 / (1 + d)`.
- `lp`: converts distance `d` to `1 - d / 2`, intended for bounded Lp-style
  distances.
- `complement`: converts distance `d` to `1 - d`, and requires `d` to already
  be in `[0.0, 1.0]`.

Jaccard and Ruzicka naturally produce similarities and normally use `identity`.
L2 produces a distance and normally uses `reciprocal`, or `lp` when the input
domain guarantees distances in `[0.0, 2.0]`. `gld` produces an unbounded edit
count and normally uses `reciprocal`; `ngld` produces one already normalized
into `[0.0, 1.0]` and normally uses `complement`.

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
longer current. A delete removes the row from the active cache if present.
Otherwise, the system scans searchable structures from newest to oldest and
tombstones the record in the first structure that contains it. Only the first
match needs a tombstone because each `rowNum` lives in exactly one structure at
a time. Candidate scoring skips tombstoned records at query time without
waiting for physical removal from inverted lists. An update is handled as a
delete of the old row version followed by inserting the new version into the
active cache with the same logical `rowNum`, so the active cache always holds
the latest version.

If a record is deleted while a cache graduation or index consolidation is
building in the background, that delete is recorded in a per-build tombstone
set and replayed onto the newly built index when the build completes. This
ensures that rows deleted during the build do not reappear in search results
after the swap.

This keeps immutable index implementations simple: they do not need to support
in-place inserts or updates, only search and tombstone-style deletes. Background
consolidation later rebuilds older indexed rows into a newer index and drops
deleted rows from that rebuilt snapshot.

### Searchable Structures

Index implementations live under
`com.uber.ussi.searchablestructure.index`, split into sub-packages by the
technology each one indexes with. Record layout names no package, because a
structure and the layout it stores vary independently: `IndexType` pairs each
structure with the layouts it can store, and an index holds the one layout its
comparator also reads.

- `index.scan`: `ScanIndex`, the sequential-scan index, which reads no record
  layout of its own and so accepts every one of them.
- `index.matrix`: `MatrixIndex` and its matrix-vector dot-product scorers
  (`MatrixDotProductScorers` and the Java and OpenBLAS scorers). The technology
  presumes dense vectors.
- `index.inverted`: the inverted-list family, which presumes a sparse key
  alphabet, since its pruning is only worth its bookkeeping when a key selects
  few rows. `BaseInvertedIndex` owns the uni-sorted inverted lists and drives
  the candidate generators; `TermIndex` keys its lists by the terms of the
  record being indexed, `SignatureIndex` by signatures derived from it, and
  `HybridIndex` routes by row length between the two. The layout a record has
  is composed in rather than subclassed for, through `RecordIndexingStrategy`:
  one implementation per layout says how a record of that layout is validated
  and what indexed form its lists are keyed by, so a layout the family gains is
  one new strategy rather than one new index class per structure.
- `index.inverted.generator`: the two generators every inverted index draws its
  candidates from, `FilteredSearch` (key-major) and `MergeSearch` (row-major),
  along with the inverted list they walk and the search context, row filter,
  and results heap they walk it with. A generator only ever reads keys and uni
  values, so sequences reuse both unchanged: a sequence is indexed by the
  multiset of its elements, and only the comparator that scores a candidate
  cares about their order. Every type here is public purely to be reachable
  from the indexes in the parent packages, and says so in its javadoc.

The shared `Index` base class, `IndexFactory`, and
`MetadataFilteredSearchExecutor` stay in the `index` package itself.
`searchablestructure.inverted` holds `KeyAndPrefixFilteringData`, the one type
the inverted indexes and the inverted cache both order their query keys with,
which is why it sits beside both rather than inside either. Mutable `ScanCache`
and `InvertedTermCache` implementations live under `searchablestructure.cache`.

### Comparators

Comparator implementations live under `com.uber.ussi.comparator`, with the two
pieces that only a comparator composes in sub-packages of their own:

- `comparator.sequencedistance`: the edit distances the `gld` and `ngld`
  comparators measure with, sharing the banded dynamic program in
  `SequenceDistance`.
- `comparator.signaturegenerator`: the MinHash and consistent weighted sampling
  generators the `jaccard` and `ruzicka` comparators draw signatures from.

Normalizers are separate, under `com.uber.ussi.comparatornormalizer`, because a
namespace configures one independently of its comparator.

#### Scan Cache

`ScanCache` is mutable and supports insert, update, delete, kNN search, and
minimum-similarity search. It scans all cached rows and applies metadata filters
before scoring rows.

#### Inverted Term Cache

`InvertedTermCache` is mutable and keeps inverted term lists in insertion order.
It generates deduplicated candidates from query terms using prefix filtering,
then scores candidates with the configured comparator. A metadata
filter matching at most 1% of the cache uses a direct scan of those matching
rows instead.

Its searches only return rows sharing at least one non-discarded term
with the query. High-popularity terms are discarded dynamically according to the
configured one-sided confidence bound, and `popular_term_discard_scope` decides
what that discard means; see
[Discarding Popular Terms](#discarding-popular-terms). The complete stored
records and inverted lists retain those terms, allowing the decisions to be
reversed as the cache changes.

#### Scan Index

`ScanIndex` (in `index.scan`) is delete-only and uses sequential scan
search over a snapshot of graduated rows. It supports metadata in-filtering and
can participate in pre-filtering or post-filtering depending on configuration.

#### Matrix Index

`MatrixIndex` (in `index.matrix`) is delete-only and stores dense vectors in
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

#### Term Index

`TermIndex` is a delete-only index whose keys are the terms themselves,
canonicalized. Every inverted index keeps
inverted lists, so what sets this one apart is the source of its keys: a row's
own terms, with nothing derived from them, which is why the terms a query and a
candidate share determine their similarity exactly rather than bounding it.

Inverted lists are sorted by each row's comparator-specific unilateral value,
enabling length filtering. Candidate traversal combines length, position, and
prefix filtering while tightening the similarity threshold as the top-k heap
fills. The prefix is chosen per query, cheapest inverted list first, and is
bounded by the uni mass the visited keys accumulate, rather than being a prefix
under an order fixed over the whole term universe. Either candidate generator
can traverse these lists; see [Candidate Generation](#candidate-generation).

Each row and each query must have non-empty terms and values arrays of equal
length after canonicalization; a query and a row do not need to have the same
number of terms as each other. Search only considers rows sharing at least one
non-discarded term with the query. This is important for sparse L2: two
disjoint sparse vectors can have a non-zero normalized L2 similarity, but
`inverted_term` deliberately does not return such rows. Use `scan` when
exhaustive scoring across disjoint sparse L2 records is required.

At build time, terms occurring in more than
`floor(numRows * max_fraction_ids_per_key)` rows are discarded. The
default fraction of `1.0` disables this behavior. See
[Discarding Popular Terms](#discarding-popular-terms) for what a discard means.

#### Sequences On The Term Index

Paired with a sequence comparator, that same `TermIndex` stores ordered
sequences, keyed by the elements a sequence carries rather than by the terms of
a sparse record. An edit distance
depends on the order the elements appear in, so it cannot be read off the
elements a query and a row share. What those shared elements do give is a
bound: two sequences within edit distance `d` have element multisets within L1
distance `l1BoundFactor * d` of each other, where the factor is `2.0` for
`levenshtein` and `damerau_levenshtein` and `1.0` for `lcs`. A substitution
takes one element out of a multiset and puts another in, moving two, while an
insertion or a deletion moves one, which is why forbidding substitution halves
the factor and makes `lcs` the more selective choice for candidate generation. A
row sharing too few elements with the query, disregarding their order, therefore
cannot be close enough in order either.

Each row travels through a search in two forms. The inverted lists are keyed by
the distinct elements of the row's multiset and carry how many times each
occurs, which is what length and prefix filtering prune on. The comparator then
verifies each surviving candidate against the ordered sequences, running the
banded dynamic program under the budget the active similarity threshold allows.

Each row and each query must have non-empty terms and an empty values array. A
query and a row do not need to be the same length as each other. Search only
considers rows sharing at least one non-discarded element with the query.

#### Signature Index

`SignatureIndex` replaces original terms as inverted-list keys with 270 deterministic,
similarity-preserving signatures per row. Jaccard uses MinHash; Ruzicka uses the
configured CWS variant. Signature collisions generate candidates approximately,
but candidates are scored using canonical terms and values, in whichever form
the configured discard scope leaves them, not by comparing the signatures
themselves.

Signature prefix filtering applies a generator-specific approximation safety
margin: `0.1` for MinHash, I2CWS, ICWS, and SCWS, and `0.15` for PCWS. These
margins broaden candidate generation but do not make the signature index exact.

#### Hybrid Index

`HybridIndex` combines a `TermIndex` and a `SignatureIndex`. During each
index build, rows with at most 270 terms go to the term child and rows
with more than 270 terms go to the signature child. The configured length range
may be entirely below, entirely above, or span this internal boundary.

Queries search the child matching the query length first. Jaccard's cardinality
bounds can skip the other child when no row on that side can reach the active
similarity threshold. Ruzicka and popularity-filtered searches conservatively
search both children because term count alone cannot prove that one side is
irrelevant. Results from the searched children are merged and limited by
`maxNumSimilarities`.

The hybrid requires a signature-capable Jaccard or Ruzicka comparator.

#### Discarding Popular Terms

A term that occurs in most rows generates most of the index as candidates
without narrowing anything down, so both the inverted cache and the inverted
indexes can discard the terms above a configured popularity. What a discard
means is `popular_term_discard_scope`, and the two settings differ in which half
of the answer stays exact rather than in how aggressive they are.

Under the default `candidates_and_verification`, a discarded term is absent
from the inverted lists and from the records the comparator scores. A search
reports the similarity between the records that remain once the popular terms
are removed from both, and every row within the threshold of the query,
measured that same way, is found. This redefines what the reported similarities
mean, which is the right trade when the popular terms carry no signal worth
reporting.

Under `candidates_only`, a discarded term is absent from the inverted lists
only. A search reports the similarity between the records as supplied,
including their discarded terms, which is the right trade when an application
has to report an exact similarity on the original records but cannot pay to
generate candidates from their popular terms. The cost is recall: candidate
generation still prunes on the similarity measured without the discarded terms,
and removing a shared term can only lower that measure, so a row within the
threshold of the query can be pruned before verification ever scores it. How
much is lost depends on how much of the similarity the discarded terms carried.
This also rules out scoring a row from the conjunction accumulated over the
inverted lists, since that conjunction can only report the similarity that
excludes the discarded terms, so `spars_merge` verifies each candidate through
the comparator under this scope.

#### Candidate Generation

The three inverted index types (`inverted_term`, `inverted_signature`, and
`inverted_hybrid`) build the same uni-sorted inverted lists but can traverse them with either of
two candidate generators, selected per namespace with the
`candidate_generator` index parameter. Both return identical results and
honor every metadata filtering strategy; they differ only in how much work they
do to get there.

`spars` is the default and is key-major. It visits the query's keys cheapest
first, narrows each key's inverted list to the rows that length
filtering admits, and scores every surviving candidate with the comparator.
Because it always scores through the comparator, it supports every inverted
index type and every supported comparator.

`spars_merge` is row-major. One frontier spans all of the query's keys and
advances them in step, so every inverted-list entry belonging to a candidate
row arrives together. That lets the generator accumulate the row's conjunction,
which is the part of the similarity that the query and the row derive from the
keys they share, as it goes, and abandon the row as soon as no completion
of it can reach the active similarity threshold. It trades a priority queue
over the query's keys for the ability to prune a row mid-scan, which pays off
when a query has many keys and the threshold rejects most rows early.

When the keys are the terms of a sparse record, the inverted lists also carry
the row's value at that key, so the accumulated conjunction is the row's exact
similarity and no further comparison is needed. Signature keys carry no usable
value, and a sequence's elements bound its similarity without determining it,
so in both cases the merge generator scores each retained candidate with the
comparator, exactly as the filtered scan does. `inverted_hybrid` applies the
generator independently to each child, so its term child scores from the
conjunction while its signature child verifies.

A comparator opts into the merge generator by implementing its conjunction
hooks. Jaccard, Ruzicka, and L2 all do, so `spars_merge` is available for every
order-agnostic comparator; the inverted-list values it needs are only
materialized where they are read. The sequence comparators do not, because a
dynamic program over ordered sequences cannot be accumulated from shared keys.

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
  tasks are in flight. Deletes that occur during a background build are recorded
  and replayed onto the new index at swap time, so deleted rows never reappear.
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

Apache License 2.0. See [`LICENSE.md`](LICENSE).