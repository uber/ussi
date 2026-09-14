<!-- AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) -->

# USSI Design

This document describes how USSI works inside. It is for people changing the
library. If you are embedding it, [README.md](README.md) is what you want, and
the public `NearestNeighborSearchIndex` API is what you should use rather than
managing any of the structures below directly.

## Structure Lifecycle

`NearestNeighborSearchIndex` owns one active cache, zero or more graduating
caches being converted into indexes, and zero or more indexes.

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

Rows are inserted into the active cache. When it reaches `maxCacheSize` it
rotates into the graduating-cache list and a fresh cache starts accepting
writes. A background task snapshots the graduating cache and builds an index.
When the total number of searchable structures reaches
`maxNumSearchableStructures`, older indexes are consolidated in the background.

Each searchable structure and the final merge keep only `maxNumSimilarities`
results, accumulated in a `BoundedSizeMaxHeap`.

The top-level index uses a single read/write lock. Searches run under the read
lock and mutations under the write lock. Background builds snapshot under the
read lock, build outside the lock, and take the write lock only for the final
swap and tombstone replay.

## Inserts, Deletes, and Updates

The top-level index is mutable even though graduated indexes are delete-only.
Delete-only is enough for graduated data because such an index only has to
serve searches over the snapshot it was built from and hide rows that are no
longer current.

A delete removes the row from the active cache if it is there. Otherwise the
structures are scanned newest to oldest and the record is tombstoned in the
first structure containing it. Only the first match needs a tombstone, because
each `rowNum` lives in exactly one structure at a time. Candidate scoring skips
tombstoned records at query time rather than waiting for physical removal from
the inverted lists.

An update is a delete of the old version followed by an insert of the new one
under the same `rowNum`, so the active cache always holds the latest version.

A delete arriving while a graduation or consolidation is building is recorded
in a per-build tombstone set and replayed onto the new index when the build
completes, so rows deleted during a build do not reappear after the swap.

An index implementation therefore supports search and tombstone-style deletes,
and never in-place inserts or updates. Consolidation later rebuilds older
indexed rows into a newer index and drops deleted rows from that rebuilt
snapshot.

## Configuration Validation

A namespace validates its whole configuration before building any layer, so
every violation is reported in one list. Five families of rule are
config-answerable and therefore checked here: a value naming no structure,
comparator, or normalizer; a structure and a comparator with no record type in
common; a structure paired with a comparator other than the one whose
arithmetic it implements; a structure whose keys the comparator cannot
generate; and a candidate generator the comparator does not support.

Most of these ask the comparator what it supports, so they stay silent when its
params leave it unbuildable and let `ComparatorConfigValidator` report that
instead. The structure's required comparator is the exception: it is answerable
from the configured names alone, so it is reported either way.

Each layer contributes a `NamespaceConfigValidator` for the rules only that
layer knows, and also declares the parameter keys its map is read by, so a key
no layer reads is reported rather than silently ignored. Keys are matched the
way `NamespaceConfigParams` matches them, ignoring case and padding, so
validation accepts exactly the keys a read resolves.

Enums a config names implement `ConfigVocabulary`, which holds the one
case-insensitive lookup and the one unsupported-value message. A config never
names a record type, so `RecordType` is not among them.

## Package Layout

### Searchable Structures

Index implementations live under `com.uber.ussi.searchablestructure.index`,
split into sub-packages by the technology each one indexes with. Record type
names no package, because a structure and the record type it stores vary
independently: `IndexType` pairs each structure with the record types it can
store, and an index holds the one record type its comparator also reads.

- `index.scan`: `ScanIndex`, which reads no record type of its own and so
  accepts every one of them.
- `index.matrix`: `MatrixIndex` and its matrix-vector dot-product scorers
  (`MatrixDotProductScorers` and the Java and OpenBLAS scorers). The technology
  presumes dense vectors.
- `index.inverted`: the inverted-list family, which presumes a sparse key
  alphabet, since its pruning is only worth its bookkeeping when a key selects
  few rows. `BaseInvertedIndex` owns the uni-sorted inverted lists and drives
  the candidate generators; `TermIndex` keys its lists by the terms of the
  record being indexed, `SignatureIndex` by signatures derived from it, and
  `HybridIndex` routes by row length between the two. `RecordIndexingStrategy`
  carries the record type: one implementation per record type says how a record
  of that type is validated and what indexed form its lists are keyed by, so a
  record type the family gains is one new strategy.
- `index.inverted.generator`: the two generators every inverted index draws its
  candidates from, `FilteredSearch` (key-major) and `MergeSearch` (row-major),
  along with the inverted list they walk and the search context, row filter,
  and results heap they walk it with. A generator only ever reads keys and uni
  values, so sequences reuse both unchanged: a sequence is indexed by the
  multiset of its elements, and only the comparator that scores a candidate
  cares about their order. Every type here is public only to be reachable from
  the indexes in the parent packages.

The shared `Index` base class, `IndexFactory`, and
`MetadataFilteredSearchExecutor` stay in the `index` package itself.
`searchablestructure.inverted` holds `KeyAndPrefixFilteringData`, the one type
the inverted indexes and the inverted cache both order their query keys with,
so it sits beside both. Mutable `ScanCache` and `InvertedTermCache` live under
`searchablestructure.cache`.

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

## Caches

### Scan Cache

`ScanCache` is mutable and supports insert, update, delete, kNN search, and
minimum-similarity search. It scans all cached rows and applies metadata
filters before scoring.

### Inverted Term Cache

`InvertedTermCache` is mutable and keeps inverted term lists in insertion
order. It generates deduplicated candidates from query terms using prefix
filtering, then scores them with the configured comparator. A metadata filter
matching at most 1% of the cache uses a direct scan of those matching rows
instead.

Its searches only return rows sharing at least one non-discarded term with the
query. High-popularity terms are discarded dynamically according to the
configured one-sided confidence bound; see [Discarding Popular
Terms](#discarding-popular-terms). The stored records and inverted lists retain
those terms, so the decisions can be reversed as the cache changes.

Popularity decisions are updated incrementally. Deletions recheck terms from
the deleted row and the currently filtered set. Once the cache has shrunk by
`full_reevaluation_cache_size_decrease_fraction` from the last exact
evaluation, every term is reevaluated against the smaller denominator.

## Indexes

### Scan Index

`ScanIndex` is delete-only and searches by sequential scan over a snapshot of
graduated rows. It supports metadata in-filtering and can participate in
pre-filtering or post-filtering depending on configuration.

### Matrix Index

`MatrixIndex` is delete-only and stores dense vectors in a row-major float
matrix. It derives L2 from dot products rather than scoring through the
comparator, so `IndexType.getRequiredComparatorType` names `l2` and the
validator rejects any other pairing. Rows must have empty terms and the same
non-zero dimension.

For unfiltered all-row scoring it computes matrix-vector dot products and
derives L2 distance from:

```text
||query - row||^2 = ||query||^2 + ||row||^2 - 2 * dot(query, row)
```

The dense scorer tries OpenBLAS on supported Linux and macOS platforms and
falls back to the Java scorer when OpenBLAS cannot be loaded.
`NearestNeighborSearchIndex.close()` releases any native dense-matrix memory.

### Term Index

`TermIndex` is delete-only and keys its inverted lists by canonicalized terms,
a row's own terms with nothing derived from them, so the terms a query and a
candidate share determine their similarity exactly rather than bounding it.

Inverted lists are sorted by each row's comparator-specific unilateral value,
which is what enables length filtering. Candidate traversal combines length,
position, and prefix filtering while tightening the similarity threshold as the
top-k heap fills. The prefix is chosen per query, cheapest inverted list first,
and is bounded by the uni mass the visited keys accumulate. Either candidate
generator can traverse these lists.

Each row and each query must have non-empty terms and values arrays of equal
length after canonicalization; a query and a row need not have the same number
of terms as each other. Search only considers rows sharing at least one
non-discarded term with the query. This matters for sparse L2: two disjoint
sparse vectors can have a non-zero normalized L2 similarity, and
`inverted_term` does not return such rows.

At build time, terms occurring in more than
`floor(numRows * max_fraction_ids_per_key)` rows are discarded.

### Sequences On The Term Index

Paired with a sequence comparator, that same `TermIndex` stores ordered
sequences, keyed by the elements a sequence carries rather than by the terms of
a sparse record.

An edit distance depends on the order the elements appear in, so it cannot be
read off the elements a query and a row share. What those shared elements give
is a bound: two sequences within edit distance `d` have element multisets
within L1 distance `l1BoundFactor * d` of each other, where the factor is `2.0`
for `levenshtein` and `damerau_levenshtein` and `1.0` for `lcs`. A substitution
takes one element out of a multiset and puts another in, moving two, while an
insertion or a deletion moves one, which is why forbidding substitution halves
the factor and makes `lcs` the more selective choice for candidate generation.
A row sharing too few elements with the query, disregarding order, therefore
cannot be close enough in order either.

Each row travels through a search in two forms. The inverted lists are keyed by
the distinct elements of the row's multiset and carry how many times each
occurs, which is what length and prefix filtering prune on. The comparator then
verifies each surviving candidate against the ordered sequences, running the
banded dynamic program under the budget the active similarity threshold allows.

Each row and each query must have non-empty terms and an empty values array. A
query and a row need not be the same length as each other. Search only
considers rows sharing at least one non-discarded element with the query.

### Signature Index

`SignatureIndex` replaces original terms as inverted-list keys with 270
deterministic, similarity-preserving signatures per row. Jaccard uses MinHash;
Ruzicka uses the configured CWS variant. Signature collisions generate
candidates approximately, but candidates are scored using canonical terms and
values, in whichever form the configured discard scope leaves them, rather than
by comparing signatures.

Signature prefix filtering applies a generator-specific approximation safety
margin: `0.1` for MinHash, I2CWS, ICWS, and SCWS, and `0.15` for PCWS. These
margins broaden candidate generation but do not make the signature index exact.

### Hybrid Index

`HybridIndex` combines a `TermIndex` and a `SignatureIndex`. During each build,
rows with at most 270 terms go to the term child and longer rows to the
signature child. The configured length range may fall entirely below, entirely
above, or across this internal boundary.

Queries search the child matching the query length first. Jaccard's cardinality
bounds can skip the other child when no row on that side can reach the active
similarity threshold. Ruzicka and popularity-filtered searches conservatively
search both children, because term count alone cannot prove one side
irrelevant. Results from the searched children are merged and limited by
`maxNumSimilarities`.

The hybrid requires a signature-capable Jaccard or Ruzicka comparator.

## Discarding Popular Terms

A term occurring in most rows generates most of the index as candidates without
narrowing anything down, so both the inverted cache and the inverted indexes
can discard terms above a configured popularity. What a discard means is
`popular_term_discard_scope`, and the two settings differ in which half of the
answer stays exact rather than in how aggressive they are.

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

## Candidate Generation

The three inverted index types build the same uni-sorted inverted lists but can
traverse them with either of two candidate generators, selected per namespace
with the `candidate_generator` index parameter. Both return identical results
and honor every metadata filtering strategy; they differ only in how much work
they do to get there.

`spars` is the default and is key-major. It visits the query's keys cheapest
first, narrows each key's inverted list to the rows that length filtering
admits, and scores every surviving candidate with the comparator. Because it
always scores through the comparator, it supports every inverted index type and
every supported comparator.

`spars_merge` is row-major. One frontier spans all of the query's keys and
advances them in step, so every inverted-list entry belonging to a candidate
row arrives together. That lets the generator accumulate the row's conjunction,
which is the part of the similarity the query and the row derive from the keys
they share, as it goes, and abandon the row as soon as no completion of it can
reach the active similarity threshold. It trades a priority queue over the
query's keys for the ability to prune a row mid-scan, which pays off when a
query has many keys and the threshold rejects most rows early.

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

## Metadata Filtering Strategies

When `metadata_filtering_strategy` is `auto`, `ScanIndex` resolves to
in-filtering. `MatrixIndex` tries pre-filtering when the metadata filter is
selective enough according to `max_pre_filtering_rows_ratio`, and otherwise
falls back to post-filtering. Term and signature indexes also try selective
pre-filtering, then fall back to in-filtering.
