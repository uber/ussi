# A dense scorer that computes elsewhere

This directory holds an illustration, not a build target. Nothing here is compiled, run or
tested, and it has not been tuned.

## What a scorer of this kind implements

`BatchedMatrixDotProductScorer<S>` supplies the batching: a caller enqueues its query and then
contends to perform the multiply, whichever caller wins takes everything enqueued at that
instant, and the rest wait only for the multiply already running. An implementation supplies
seven methods and nothing else:

| Method | What it does |
| --- | --- |
| `newMultiplyResult` | Somewhere for one query's multiply to leave what it produced |
| `newMultiplyResults` | An array of those, which only the implementation can create |
| `recycleMultiplyResult` | Optional, to reuse an allocation rather than make another |
| `multiplyOneQuery` | Scores one query against the whole matrix |
| `multiplyQueries` | Scores a batch in one multiply |
| `addRows` | Adds what the query keeps to the bounded heap it is handed |
| `releaseResources` | Frees whatever was allocated, once |

`S` is the implementation's own type. A scorer computing where this process cannot read returns
the rows it kept rather than a value per row, so `addRows` copies back only those. That is the
point of the shape: a million-row matrix would otherwise return four megabytes for every query,
and a batch multiplies that by its size.

## Two things to settle before writing one

**The extension point is package-private.** Every type involved — the base class, the scorer
interface, `MatrixRows`, `RowSelection` — is visible only inside
`com.uber.ussi.searchablestructure.index.matrix`. A scorer must therefore be declared in that
package. Publishing the seam, so that a scorer may live in another jar, is a deliberate change to
what the library exports and has not been made.

**The device memory bounds the index.** The matrix is resident for the life of the scorer, so a
card of sixteen gigabytes holds a matrix of four million rows of a thousand dimensions and no
more. That is a limit on what the feature can serve, not on how fast it serves it, and it is
worth settling before any of the rest.

## What the similarity costs

`MatrixRows.getSimilarity()` runs the configured comparator, in Java. A scorer computing
elsewhere cannot call it, so it reimplements the arithmetic for the comparators it supports and
rejects a namespace configured with any other. The unilateral value of every row is available
through `MatrixRows.getRowUniValues()` to be copied wherever the arithmetic runs, and it carries
the deletions too: a deleted row's value is not a number, so a similarity derived from it is not
a number either and no minimum admits it.

## Selecting where the rows were scored

Only the rows a query keeps should come back. A reduction that compares rows pairwise, on
whether each is deleted and then on similarity, halves the survivors each pass until the count
reaches the power of two above the number of results asked for, and only those are returned for
`addRows` to offer to the heap. Reducing on the host instead would return a value per row and
give up what the shape was for.

## Where it would sit

`MatrixDotProductScorers.PREFERENCE_ORDER` is tried in order and the first available scorer is
built, so a scorer of this kind is an entry ahead of OpenBLAS whose availability check asks
whether the hardware and its libraries are present. Nothing else in the library changes.
