<!-- AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) -->

# USSI Design

This document describes how USSI works inside. It is for people changing the
library. If you are embedding it, [README.md](README.md) is what you want, and
the public `NearestNeighborSearchIndex` API is what you should use rather than
managing any of the structures below directly.

## Rows and Records

Four names sit close enough together to look like duplicates. They are four
levels of one thing.

A **row** is the unit a searchable structure stores: a record together with
its metadata, the extra data a filter matches on. A row is identified by a
`rowNum`, which is the handle the internals pass around in place of the row
itself.

A **record** is the feature data of a row. It is a concept, not a type, and
it can take the shape of a vector, a time series, a histogram, a sparse
(multi-)set, or a sequence of terms. `RecordType` names which shape a given
record is in; a comparator declares the shapes it reads and a structure
declares the one it stores, so the two pair up by agreeing on one.

`TermsAndValues` is the public data structure a record arrives in: two
parallel arrays, `String[] terms` and `float[] values`, which between them
express every shape above depending on how they are populated. Terms alone
is a sequence, values alone is dense, both together is sparse.

`LongTermsAndValues` is the internal form of the same pair, with terms
hashed to `long` and the comparator-derived `uniValue` cached alongside them.

So a record is the idea and a `TermsAndValues` is the array pair implementing
it, which is why the enum is `RecordType` and not `TermsAndValuesType`.

Below these sit two more words. Every record holds **terms**, whatever its
shape: a sequence's ordered items are terms in the `terms` array just as a
sparse record's are. A **key** is the collective name for whatever an inverted
list can be keyed by, which is a term or a signature, so a key is not always
something the record itself holds.

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
results, accumulated in a `BoundedSizeMaxHeap`. A threshold search is
therefore a **capped range query**, returning the best `maxNumSimilarities`
rows meeting the threshold rather than every row that meets it.

### Searching Several Structures

A query visits the active cache, the graduating caches, and the indexes, and
merges what they return. Each is told the weakest score the answer already
holds enough of, and may decline to score any row beneath it: once the merge
holds its full complement of results, a weaker row cannot reach the answer, so
scoring it is wasted work. That floor starts at whatever the caller asked for,
rises as structures return results, and never falls, which is what lets a
structure treat it as a bound rather than a hint. It sits one step below the
weakest result held, so a row scoring exactly as well still qualifies. Passing
it costs nothing to add, because every structure already prunes on a floor to
answer a threshold search; a nearest-neighbour search used to pass zero.

Order therefore matters, and the structures are visited oldest first. Caches
graduate at one size and older indexes are consolidated into larger ones, so the
oldest structure holds the most rows and is the likeliest to hold the answer's
best; raising the floor there is what every structure after it spends. The active
cache holds the newest rows, which have no reason to be the best matches, so it
is searched last. Ordering this way is free rather than a trade: an update
deletes a row before re-inserting it, so one structure holds any given row and no
order can change the version a search finds. Deletes still scan newest first,
where the first structure holding a row must be the current one.

The saving depends on the number of structures, because a floor must have
somewhere to be spent. An inverted index holds, for each key, a list of the rows
carrying that key; these are its **inverted lists**, and a row drawn from them to
be scored is a **candidate**. Indexes and Candidate Generation describe both.
Measured across several structures, the floor cuts candidates by about half, and
the inverted lists walked by more. With one active cache and one index it saves
nothing.

The hybrid index already searched its term index and signature index in this
order. It now also accepts a floor from its caller, in addition to raising one
between the two.

The structures are visited one after another rather than at the same time.
Their sizes differ by orders of magnitude, since an active cache is bounded by
`maxCacheSize` while a consolidated index holds everything that has graduated,
so the largest search decides the latency whether or not the others run beside
it, and threads spent on the small ones would buy nearly nothing. Visiting them
in turn buys something the other arrangement cannot: searches running at the
same time cannot narrow one another, because neither has results yet. Splitting
a single structure's own search is a separate question, answered in [Threads
Within One Search](#threads-within-one-search).

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

## Concurrent Searches

`QueryAdmission` bounds the searches in flight, across every index and cache in
the process, to the number of cores, and admits waiting searches in the order
they arrived. Past the core count searches contend for the same cores without
any of them finishing sooner, so the bound gives up no throughput that was
otherwise reachable, and arrival order keeps a search from losing its turn to one
that arrived later. The bound is process-wide because the cores it rations are
not divided between indexes.

Keeping within the cores is sound practice on its own, and for some index
implementations it is more than that. A native scorer may hold a per-thread
resource whose supply its library fixes when the binary is built and does not
check before using: OpenBLAS keeps one memory buffer per thread inside the
library, and past that count its allocator faults rather than failing, so enough
concurrent dense searches abort the process rather than merely slowing it down.
The supply the shipped binaries are built with is above the core count on the
machines tested, so a bound of the cores keeps them inside it.

### Threads Within One Search

`ParallelismBudget` divides the cores among the searches in flight, so a search
splitting its own work stays within what the machine has left once the other
searches are counted. The budget is the whole machine while one search runs
alone and falls to a single thread once the searches in flight already fill the
cores, which is what turns splitting off under load rather than letting
concurrent searches multiply their own fan-out against each other.

A scan is the search that splits most readily, since rows score independently
and only the best few survive: `ScanSplit` gives each part of the row space its
own heap and merges the heaps, a merge whose size is the part count rather than
the row count. The hash table holding the rows has no index, so a part is a
range of its slots, with the empty ones skipped.

A scan of the candidate rows a metadata filter produced splits the same way,
which covers pre-filtering in the scan index and the direct scan the inverted
term cache falls back to. Candidates arrive as a set rather than a map, and a
set cannot be divided into slot ranges: it holds no values to mark an empty slot
with, and it keeps the zero key outside its slots without exposing whether that
key is present, so a slot range cannot tell row zero from an empty slot. The
candidates are copied out instead, which gives parts something to index and
costs one pass. A candidate scan not worth splitting is scanned where it lies,
so it pays for no copy.

Only a scan whose rows all score against the same threshold is split. A scan
that raises its threshold as its heap fills prunes using what it has already
scored, and parts each raising a threshold from their own heap would prune less
than the whole scan does, so such a scan keeps its single heap and its pruning.
An inverted index scores its candidates against a rising threshold, so no phase
of a single inverted search is multi-threaded. The pruning that would forfeit
depends on the data rather than the machine, so no measurement settles it.

An inverted index multi-threads by sharding instead. It searches several shards
at once, and each shard search is complete in itself, with its own heap and its
own threshold. This forfeits the same pruning, and here the loss is accepted: a
sharded search does more total work and receives concurrency in return. See
Sharded Inverted Index.

Whether to split at all is worth deciding, because a scan can be short enough
that handing its parts out costs more than the scan. How finely to split is not,
because that cost does not grow with the number of parts enough to matter: a scan
barely past the minimum still gains from as many parts as there are cores, so
holding parts back to keep each one large loses more than it protects. A scan
below a minimum amount of work therefore stays on the calling thread, and one
above it uses every thread it may. The minimum is expressed in work rather than in
rows so that it holds for records an order of magnitude apart in length, since a
scan of few long records costs as much as one of many short ones.

The minimum earns its place at high query rates rather than on an idle machine.
The budget is derived from the searches observed in flight, and searches short
enough to leave the cores idle between them read as lower concurrency than they
impose, so the budget can sit above one thread while a small cache serves hundreds
of thousands of searches a second. Handing out parts for each of those costs far
more than splitting saves.

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

## Two Senses Of "Index"

The word covers two levels, and both are correct in their place.

The library is an index: `NearestNeighborSearchIndex` is the whole searchable
thing, which is the sense the project's own name carries in Uber Similarity
Search Index. It stores rows, answers queries, and is the only type an
embedder needs.

An `Index` is one searchable structure inside it, a sibling of a `Cache` and
a `SearchableStructure` like it. The facade owns a list of these and a list
of caches, and merges their results.

So the facade holding indexes rather than being one is not a contradiction.
A reader who expects `NearestNeighborSearchIndex` to extend `Index` has the
narrow sense in mind; nothing does, and nothing should.

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
  multiset of its terms, and only the comparator that scores a candidate
  cares about their order. Every type here is public only to be reachable from
  the indexes in the parent packages.

The shared `Index` base class, `IndexFactory`, and
`MetadataFilteredSearchExecutor` stay in the `index` package itself.
`searchablestructure.inverted` holds `KeyAndPrefixFilteringData`, the one type
the inverted indexes and the inverted cache both order their query keys with,
so it sits beside both. Mutable `ScanCache` and `InvertedTermCache` live under
`searchablestructure.cache`.

### Comparators

Comparator implementations live under `com.uber.ussi.comparator`, with two
pieces in sub-packages of their own:

- `comparator.sequencedistance`: the edit distances the `gld` and `ngld`
  comparators measure with, sharing the banded dynamic program in
  `SequenceDistance`.
- `comparator.signaturegenerator`: the MinHash and consistent weighted sampling
  generators, which a namespace names through a comparator param because which
  of them says anything depends on the measure, but which no comparator holds.
  Drawing signatures belongs to the structure keyed by them, so a comparator
  contributes only `KeyShareBounded`: the share of a record's keys a qualifying
  candidate has to share with it. That is a property of the measure and holds
  whether or not any structure is keyed by signatures, which is why the same
  comparator serves a scan unchanged, and it is one fraction across both key
  spaces, signatures colliding at the rate a record's own terms are shared at.

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

Scoring several waiting queries in one matrix-matrix multiply was benchmarked on
x86-64 and ARM64 and not adopted. The appeal is that a batch reads the matrix
once for all of its queries rather than once for each, but the library first
copies the matrix into packed buffers, a cost set by the size of the matrix
rather than the size of the batch, so a small batch pays it for almost no reuse.
Batches large enough to amortize that copy are bound by arithmetic rather than by
memory, so the remaining gain is throughput taken out of tail latency, and it
only appears at loads well past the core count.

### Term Index

`TermIndex` is delete-only and keys its inverted lists by canonicalized terms,
a row's own terms with nothing derived from them, so the terms a query and a
candidate share determine their similarity exactly rather than bounding it.

Inverted lists are sorted by each row's comparator-specific unilateral value,
its `uniValue`, which is what enables length filtering. They are one of two
components: the **forward index** holds the other side, mapping each `rowNum`
to its record and its precomputed `uniValue`, so candidate generation reads
the lists and verification reads the forward index.

Candidate traversal runs on two axes. A **vertical scan** visits the query's
keys, and a **horizontal scan** walks the inverted list of each key it visits.
Traversal combines length, position, and prefix filtering while tightening the
similarity threshold as the top-k heap fills. The latter two both prune on a
partial unilateral value: the portion of a `uniValue` consumed so far, leaving
the rest to bound what the unconsumed part can still contribute.

Position filtering prunes during a comparison, on the partial unilateral values
of the two records being compared, which the comparators carry as
`partialUniValue1` and `partialUniValue2`. Once the most the unscanned terms
could still add leaves the pair short of the threshold, the comparison stops.

Prefix filtering prunes before any comparison, on the same quantity taken over
the query's keys. The prefix is chosen per query, cheapest inverted list
first, and the vertical scan halts once the partial unilateral value of the
visited keys exceeds the query's `maxPrefixSum`, the most a qualifying
candidate may leave unmatched. `maxPrefixSum` takes one of two shapes, and
which one a measure takes is what decides how it is derived. A threshold that
is already a share of the keys gives the share of their unilateral value a
candidate at exactly the threshold can afford to miss: a similarity for
Jaccard and Ruzicka, a normalized distance for NGLD, and any signature-keyed
structure, a signature standing for one draw. Keys are shared in proportion to
the multiset similarity of the records they were drawn from whether they are
terms or signatures, so that share is one fraction serving both key spaces. A
threshold that counts keys instead states `maxPrefixSum` directly and no share
comes into it, GLD's edits counting a sequence's terms and L2's squared
distance being in the units of the squared values its unilateral value sums.
The comparator supplies `maxPrefixSum` for term keys and the signature keying
strategy for signature keys. A row absent from every list visited so far has
missed all of them, so once that accumulation passes `maxPrefixSum`, no row
still unseen can qualify and the rest of the query's keys go unvisited. Either
candidate generator can traverse these lists.

Each row and each query must have non-empty terms and values arrays of equal
length after canonicalization; a query and a row need not have the same number
of terms as each other. Search only considers rows sharing at least one
non-discarded term with the query. This matters for sparse L2: two disjoint
sparse vectors can have a non-zero normalized L2 similarity, and
`inverted_term` does not return such rows.

At build time, terms occurring in more than
`floor(numRows * max_fraction_ids_per_term)` rows are discarded.

### Sequences On The Term Index

Paired with a sequence comparator, that same `TermIndex` stores ordered
sequences, whose terms arrive in order and with repeats rather than once each
alongside a value.

An edit distance depends on the order the terms appear in, so it cannot be
read off the terms a query and a row share. What those shared terms give
is a bound: two sequences within edit distance `d` have term multisets
within L1 distance `l1BoundFactor * d` of each other, where the factor is `2.0`
for `levenshtein` and `damerau_levenshtein` and `1.0` for `lcs`. A substitution
takes one term out of a multiset and puts another in, moving two, while an
insertion or a deletion moves one, which is why forbidding substitution halves
the factor and makes `lcs` the more selective choice for candidate generation.
A row sharing too few terms with the query, disregarding order, therefore
cannot be close enough in order either.

Each row travels through a search in two forms. The inverted lists are keyed by
the distinct terms of the row's multiset and carry how many times each
occurs, which is what length and prefix filtering prune on. The comparator then
verifies each surviving candidate against the ordered sequences, running the
banded dynamic program under the budget the current `minSimilarity` allows.

Each row and each query must have non-empty terms and an empty values array. A
query and a row need not be the same length as each other. Search only
considers rows sharing at least one non-discarded term with the query.

### Signature Index

`SignatureIndex` replaces original terms as inverted-list keys with 270
deterministic, similarity-preserving signatures per row. Jaccard uses MinHash;
Ruzicka and the two sequence comparators use the configured CWS variant.
Signature collisions generate candidates approximately, but candidates are
scored using canonical terms and values, in whichever form the configured
discard scope leaves them, rather than by comparing signatures.

A sequence's signatures are drawn from its term multiset, the same form
`TermIndex` keys it by, so its counts are what the weighted samplers read and
MinHash is not among the generators it accepts.

Prefix filtering over signature keys needs the smallest share of the query's
signatures that a qualifying candidate can collide on. Signatures collide at a
rate tracking the multiset similarity of the records behind them, so for
Jaccard and Ruzicka that share is the threshold itself. An edit distance
measures something else, and the share follows from the same L1 bound the
term-keyed lists use: multisets within L1 distance `u` of their combined length
share at least `(1 - u) / (1 + u)` of it, and the lengths cancel, so one share
covers every candidate the threshold admits. A normalized distance is already a
share of the combined length; a raw edit count becomes one against the shortest
candidate length filtering admits.

Signature prefix filtering applies a generator-specific approximation safety
margin: `0.1` for MinHash, I2CWS, ICWS, and SCWS, and `0.15` for PCWS. The
margin relaxes that share rather than the comparator's own threshold, because a
generator's concentration bound is stated on the similarity it estimates. These
margins broaden candidate generation but do not make the signature index
exact.

### Hybrid Index

`HybridIndex` combines a `TermIndex` and a `SignatureIndex`. During each build,
rows with at most 270 terms go to its term index and longer rows to its
signature index. The configured length range may
fall entirely below, entirely above, or across this internal boundary.

Queries search the index matching the query length first. Jaccard's cardinality
bounds can skip the other when no row on that side can reach the search's current
`minSimilarity`. Ruzicka and popularity-filtered searches conservatively search
both, because term count alone cannot prove one side irrelevant. Results from the
indexes searched are merged and limited by `maxNumSimilarities`.

The hybrid requires a comparator with a configured signature generator, which
rules out `l2` and any other comparator left without one.

### Sharded Inverted Index

An inverted index **shards** its inverted lists, building one set of lists per
shard, each holding the lists of a disjoint share of its rows. A search searches
every shard and keeps the nearest rows across all of them.

Only the lists are sharded. The forward index, each row's uni value, the
metadata and the tombstones are keyed by row number, belong to the index, and are
read by every shard's search. An index of one shard is the general case with one
shard rather than a separate form, so no index is converted between sharded and
unsharded.

A row's lists belong to the shard given by its row number modulo the shard count.
Row numbers are issued in sequence, so the shards receive equal shares and cost
the same to search.

A search submits all of its shards together. The pool serves shards in submission
order, so a search submitting some shards and returning for the rest would let a
later search overtake it. Submitting together also delegates the thread count to
the pool, which is sized to the cores and so imposes the bound
`ParallelismBudget` would impose.

Sharding multi-threads a search entire, which the alternatives do not.
Multi-threading verification reaches only the phase that scores candidates.
Multi-threading the query's keys visits a row once per thread holding one of its
keys, where one thread walking every key visits it once.

The cost is weaker pruning. Inverted lists are sorted by uni value, and both
length filtering and the rising `minSimilarity` of a filling heap prune against
that order, so a shard prunes against a weaker threshold over a narrower range
than the whole index does. Each shard also repeats the walk of the query's keys
and the seek into each list. Sharding therefore raises the total work of a search
and returns concurrency for it; without a spare thread it is a loss.

That cost bounds the shard count. An index takes one shard per core only once
every shard would hold a minimum number of rows, and fewer shards until then. The
minimum is 50,000 rows, chosen conservatively rather than measured: sharding was
measured to pay at about 60,000 rows per shard and to lose at about 15,000.

Only inverted indexes are sharded. A scan index already multi-threads one search
across its rows, and a matrix index scored by OpenBLAS multi-threads inside that
scorer, both from the same budget. A matrix index scored by the pure-Java scorer
draws nothing from the budget, so sharding it remains unexplored.

The inverted term cache is not sharded. It is bounded by `max_cache_size` and so
holds fewer rows than one shard requires, and it is the one inverted structure
that changes: it revises its popular-term decisions as rows are inserted, deleted
and updated, and every shard would need those revisions as they occurred. It
multi-threads through `ScanSplit` instead.

Sharding is invisible to callers, which address rows only by the row numbers they
inserted them under.

## Discarding Popular Terms

A term occurring in most rows generates most of the index as candidates without
narrowing anything down, so both the inverted cache and the inverted indexes
can discard terms above a configured popularity. Popularity is measured over the
whole structure, never over part of one. A sharded index finds its popular terms
once over all its rows and gives the same terms to every shard. A shard measuring
for itself would count a term's share of its own rows rather than of the index's,
and would discard terms the index keeps.

In a hybrid index, only the term index discards, and its popular terms are
counted over the rows that term index holds. Discarding shortens inverted lists,
which only a term index gains: a signature list holds one entry per row whatever
that row's terms are, so discarding leaves a signature index's lists the same
length and merely moves the signatures its rows are keyed by.

`popular_term_discard_scope` decides what a discard means. The two settings
differ in which side of the answer stays exact, not in how aggressive they are.

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

Discarding removes terms, and removes them while they are still terms.
Popularity is counted over a record's terms, and the popular ones are removed
before the structure derives the keys its lists are under. A signature-keyed
structure that discards therefore generates signatures from an already stripped
record, so a discard changes which signatures a row has rather than removing
signatures it had.

No structure discards a key for being frequent, which for signatures is
deliberate. A term in most rows carries no signal, whereas signatures collide at
a rate tracking the multiset similarity of the records behind them, so a
signature in most rows reports a similarity worth keeping. Such a signature
arises from a term dominating the multisets, and the term-level discard removes
that term.

## Candidate Generation

The three inverted index types build the same uni-sorted inverted lists but can
traverse them with either of two candidate generators, selected per namespace
with the `candidate_generator` index parameter. Both return identical results
and honor every metadata filtering strategy; they differ only in how much work
they do to get there.

`spars` is the default and is key-major. Its vertical scan visits the query's
keys cheapest first, each horizontal scan narrows that key's inverted list to
the rows length filtering admits, and every surviving candidate is scored with
the comparator. Because it always scores through the comparator, it supports
every inverted index type and every supported comparator.

`spars_merge` is row-major. One frontier spans all of the query's keys and
advances them in step, so every inverted-list entry belonging to a candidate
row arrives together. That lets the generator accumulate the row's conjunction,
which is the part of the similarity the query and the row derive from the keys
they share, as it goes. What it holds mid-row is a partial conjunction, and
`maxSimilarityFromPartialConjunction` bounds the best any completion of it
could reach, using the unscanned keys' unilateral value to bound what the keys
still to arrive can add. The row is abandoned as soon as that bound falls below
the threshold the search currently holds. It trades a priority queue over the
query's keys for the ability to prune a row mid-scan, which pays off when a
query has many keys and the threshold rejects most rows early.

When the keys are the terms of a sparse record, the inverted lists also carry
the row's value at that key, so the accumulated conjunction is the row's exact
similarity and no further comparison is needed. Signature keys carry no usable
value, and a sequence's terms bound its similarity without determining it,
so in both cases the merge generator scores each retained candidate with the
comparator, exactly as the filtered scan does. `inverted_hybrid` applies the
generator independently to each of the two, so its term index scores from the
conjunction while its signature index verifies.

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
