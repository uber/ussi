<!-- AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com),
Ahmed Metwally (ametwally@uber.com) -->

# Integrating USSI

This document describes the seam between USSI and a host that embeds it. A host
owns its own storage lifecycle and embeds USSI as an in-memory search index beside
it. USSI owns its own cache, graduation, and consolidation lifecycle. Those are
two independent lifecycles over the same documents.

The supported integration surface is `NearestNeighborSearchIndex` and the
types its methods expose: `NamespaceConfig`, `TermsAndValues`, `MetaFilter`,
`SearchResults`, `MemoryFootprint`, and `ProcessorAllowance`. Other public
classes are implementation details and may change.

## Terms

The vocabulary the rest of this document uses. [README.md](README.md) covers
records, comparators, and index types.

| Term | Meaning |
| --- | --- |
| Namespace | One `NearestNeighborSearchIndex`, holding one configuration and the rows inserted into it. |
| Row | One record USSI holds, addressed by a `rowNum`, which is a signed 64-bit handle USSI allocates. |
| Active cache | The mutable structure every insert lands in, until it reaches `maxCacheSize` rows. |
| Graduation | Building an immutable index from a full active cache, in the background. |
| Consolidation | Merging several indexes into one, in the background, when their count reaches `maxNumSearchableStructures`. |
| Unilateral value | A quantity derived from one record alone, which a comparator combines with a dot product to yield a similarity. Replacing it with a value that is not a number is how a delete is recorded. |
| Admission | The process-wide semaphore a search acquires a permit from before it runs, which bounds how many searches run at once. |
| Work unit | One piece of a search that divides itself, run on the search pool. |
| Shard | One part of an inverted index, searched as its own work unit. |
| Batch | The queries one dense matrix multiply carries. Its width is the most queries that multiply may carry at once. |
| Parallelism budget | What derives, from the processor allowance and the observed concurrency, the threads one search may use and the threads a native library holds. |

## What USSI owns

USSI owns the in-memory structures a namespace is built from:

- An active mutable cache that holds inserts and updates until it graduates.
- Background graduation of a full cache into an immutable index.
- Consolidation of indexes when their count exceeds the configured maximum.
- Deletes recorded as a unilateral value that is not a number, so a deleted row
  is excluded by the arithmetic a search performs.
- Allocation of `rowNum` values, starting at zero and incrementing on each insert.
- Concurrency inside the library: admission, parallelism budget, search threads,
  and the batch latch.

A search sees the union of the active cache, the graduating caches, and the
indexes. Background maintenance is invisible to results. Deletes that land during
a build are replayed onto the new structure, so a deleted row never returns.

## What the host owns

The host owns everything USSI does not:

- Persistence. USSI keeps nothing on disk, so the host remains the source of
  truth.
- The mapping from the host's document identifiers to `rowNum`. USSI allocates
  `rowNum` values but assigns no meaning to them. Keep your own mapping.
- Rebuild on restart or relocation. USSI holds no state across process
  boundaries, so every restart re-inserts every live document.
- Authorization. USSI enforces none.
- TTL enforcement. Call `delete(rowNum)` when a row expires.
- Sharding across nodes. USSI holds one namespace per process and does not
  divide it.

## Rebuild cost

There is no persistence and no incremental restore. On restart the host must
recreate the `NamespaceConfig`, call `NearestNeighborSearchIndex.create`, and
re-insert every live document. The cost increases monotonically with the number
of rows and with the dimension, and `maxCacheSize` decides how often a
graduation runs while it proceeds. A dense namespace of two million rows of 512
dimensions occupies roughly 4.1 GB on the Java heap and 4.1 GB in native buffers
once the matrix index is built, so a restart re-pays that allocation as well.

## Blocking model

USSI blocks the caller's thread. A host scheduling USSI on a bounded pool must
expect its threads to park inside USSI. There are three blocking sites:

1. Admission. Before the read lock, a fair semaphore bounds concurrent
   searches to the processor allowance. A queued search parks here.
2. The batch latch. A dense search enqueues its query and contends for a lock.
   The winner runs one multiply for every query waiting. Losers park on a
   per-query latch with a 50 microsecond bounded wait and re-contend.
3. Work-unit futures. A search that divides its work submits the pieces to the
   search pool and waits for every one to finish.

Do not schedule USSI on a bounded pool unless that pool can block. A pool that
rejects a thread that parks turns back-pressure into rejections.

## Processor allowance

`ProcessorAllowance.shared()` lets a host lower the processors USSI may use at
runtime, through a supplier rather than a constant. A static number cannot
track a bursty host.

Propagation is deferred rather than immediate. The parallelism budget re-reads
the supplier once per averaging window rather than once per sample, and only
while at least one namespace is open. A host that needs the new value to take
effect at once builds the namespace after setting it, since a namespace reads
the allowance as it is built.

A change is applied only after every running search has finished. The budget
drains the admission semaphore first, which suspends every search in the
process, and admission is fair, so no arriving search overtakes the drain. A
search holds its permit across its whole traversal, including the dense
multiply, so the moment the change is applied has no multiply dispatching work.
The thread count a native BLAS library holds is process-global, and setting it
rebuilds that library's thread pool, which is unsafe during a call.

Two quantities are fixed when an index is built and do not follow the allowance
afterwards:

- The batch width a dense scorer allocates its buffers for. Raising the
  allowance admits more concurrent searches than one multiply carries, and the
  surplus waits for the next multiply rather than overflowing a buffer. Rebuild
  the namespace to widen it.
- The number of shards an inverted index divides into.

The allowance is clamped by the processors the process may run on, which a
processor set or a bandwidth quota may already bound, and by the per-thread
buffer table a loaded BLAS binary retains. Setting the allowance at or above the
host's own pool size makes USSI's admission semaphore stop binding, so only one
gate governs concurrency. USSI blocks the caller's thread, and two nested
admission limits turn back-pressure into rejections in a host with a bounded
pool.

A processor count is not NUMA support. Memory locality needs thread and
allocation affinity, which the JVM cannot provide without native help, so it
has to come from the launcher.

## Search threads

The search pool is sized from the processor allowance and may be resized while
no search is running. A host unloading USSI, or a test, must call
`SearchThreads.shutdown()` to release its daemon threads. USSI does not call
it from `NearestNeighborSearchIndex.close()`, because the pool is process-wide
and serves every namespace.

## Cancellation

A host cancels a search by interrupting the thread it runs on. USSI treats an
interrupt as a cancellation rather than a failure, restores the interrupt flag
before throwing, and throws `SearchCancelledException`.

- A search cancelled while queued for admission took no permit and ran nothing.
- A search cancelled while waiting on a batch another thread was running leaves
  the batch to finish for the other queries in it. The cancelled caller does
  not add its rows.
- A search cancelled while its work units were outstanding waits for them to
  finish before throwing, since returning earlier would leave a work unit reading
  a structure that the read lock its caller held is no longer protecting.

The exclusive admission path stays uninterruptible. It applies the BLAS thread
count, and a timeout must not tear that down.

## Exception contract

USSI throws typed exceptions at the public API boundary, so a host mapping
failures to status codes does not match on message text.

- `InvalidQueryException` covers a malformed query: a `k` that is not
  positive, a `minSimilarity` outside `[0.0, 1.0]`, a record whose shape does
  not match the namespace, or a configuration that fails validation at
  `create`. Treat this as a client error.
- `SearchCancelledException` covers a search cancelled by interrupt. Treat
  this as a cancellation.
- `InternalIndexException` covers a fault inside USSI that is not the caller's
  doing, such as a missing generation for an index or a scorer used after it was
  closed. Treat this as an internal error.

## Memory footprint

`NearestNeighborSearchIndex.getMemoryFootprint()` returns a `MemoryFootprint`
that separates on-heap from native or device bytes.

The on-heap estimate counts the rows, the structure answering metadata filters,
and the auxiliary structures an index type builds beside them: the dense matrix
where it is retained, and the inverted lists, the indexed and verification row
maps, the unilateral values, and the discarded terms of an inverted index. The
native estimate counts the buffers a scorer allocated outside the heap.

Both are estimates. The on-heap figure omits the per-object overhead the JVM
carries and approximates the encoded metadata per distinct value. The native
figure omits the per-thread buffers a loaded BLAS library retains for the whole
process, since those are not owned by any one index.

The estimate is derived rather than memoized, so it traverses every row and every
inverted list and runs in time linear in what the namespace holds. Sample it
periodically rather than reading it per request.

Deletes do not shrink the estimate. A deleted row keeps its place in the matrix
until the index is rebuilt, so the estimate reflects allocated rows rather than
live ones.

## Native libraries

A dense `matrix` index may extract and load a shared library through JavaCPP on
first use. JavaCPP's `Loader.load` extracts the native binary to a cache
directory and `dlopen`s it. The properties that control this are:

- `org.bytedeco.javacpp.cachedir` sets the extraction directory, defaulting to
  `~/.javacpp/cache/`.
- `org.bytedeco.javacpp.cacheLibraries` enables or disables extraction,
  defaulting to `true`.
- `org.bytedeco.javacpp.platform` overrides the detected platform string.
- `java.library.path` is searched for an already-extracted library before
  extraction.

A restrictive runtime must grant extraction and native loading. The policy
file itself is the host's artifact and does not belong in USSI. A platform
without a matching binary falls back to the pure Java scorer, which carries no
native code.

## Maven consumption

USSI is published as `com.uber.ussi:ussi:0.1.0`. Consumers build with Gradle
or Maven and declare the coordinate as a dependency.

The generated POM lists the runtime dependencies, including the OpenBLAS and
JavaCPP platform jars that dense search needs, one per platform. The CUDA
bindings and Lombok are compile-time only and are absent from it, so a consumer
that wants the device scorer declares the CUDA platform jar itself.

Publishing takes the repository to publish to, which may be any Maven
repository. A `file://` URL writes to a directory, which is what verifying the
artifact locally uses:

```text
bazel run --define maven_repo=file:///absolute/path/to/repository //:src_main.publish
```

A remote repository takes credentials the same way, through `--define
maven_user` and `--define maven_password`. No Maven Central or GitHub Packages
publication is configured in this work.
