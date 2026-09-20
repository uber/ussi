# Scoring dense rows on a GPU

`CudaMatrixDotProductScorer`, in the matrix package, scores dense rows on a GPU reached through
CUDA. This directory describes what such a scorer implements and what remains to be settled
before one is used. The Java file beside this one is an outline of a scorer for a different
library, and is neither compiled nor run.

## The state of the one in the library

It compiles on Linux and macOS, and no result it produces has been verified against another
scorer, because no machine available to this project has a GPU. It leads
`MatrixDotProductScorers.PREFERENCE_ORDER`, since a GPU outruns a CPU at this, and reaching it
takes the CUDA bindings on the runtime classpath. They are a compile-time dependency of the
library, so a deployment that does not add them never builds it, and a deployment that adds
them is opting into a scorer whose results have not been checked on a GPU.

`CudaMatrixDotProductScorerTest` scores the same rows through it and through the Java scorer and
asserts they keep the same rows. It skips wherever no GPU is present, which is everywhere today.

## What it costs to carry

The CUDA bindings, `org.bytedeco:cuda:12.6-9.5-1.5.11`, are a compile-time dependency of one
megabyte, declared through the `cuda_compile_only` target so that nothing reaches a consumer's
runtime classpath. Running the scorer additionally needs the platform library,
`org.bytedeco:cuda:12.6-9.5-1.5.11:linux-x86_64`, which is under seven megabytes and expects a
CUDA toolkit installed on the machine. The variant bundling the toolkit,
`org.bytedeco:cuda:12.6-9.5-1.5.11:linux-x86_64-redist`, is nearly two gigabytes and is not
needed on a machine that has a GPU.

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

## How this one works

The matrix is copied to the GPU once and stays for the life of the scorer. A batch's queries are
copied in and multiplied against the whole matrix with one `cublasSgemm`. CUDA is column-major
and the matrix is row-major, so the rows read as their own transpose and the multiply is given
`CUBLAS_OP_T, CUBLAS_OP_N` with the row count as the leading dimension, which leaves each
query's products contiguous.

A kernel compiled at construction by NVRTC then reduces each query's products where they are.
One block takes one query, and each pass finds the largest similarity not yet taken: every warp
reduces its own lanes through register shuffles, each warp's leader records what it found in
shared memory, and the first warp reduces those to the block's best row, which it marks taken.
Only the rows kept are copied back. A deleted row carries a unilateral value that is not a
number, so the similarity derived from it is not a number, and a comparison against such a value
is false, which is what keeps it out.

## What it does not do

It is not tuned. The reduction reads the products once for every row it keeps, where an
implementation meant for use would select them all in one pass. Host memory is pageable rather
than pinned and every call runs on the default stream, so a copy never overlaps a multiply. The
matrix is held in single precision, where half precision would halve what the multiply reads.

## Two things to settle before using one

**The extension point is package-private.** Every type involved — the base class, the scorer
interface, `MatrixRows`, `RowSelection` — is visible only inside
`com.uber.ussi.searchablestructure.index.matrix`. A scorer must therefore be declared in that
package. Publishing the seam, so that a scorer may live in another jar, is a deliberate change to
what the library exports and has not been made.

**The GPU's memory bounds the index.** The matrix is resident for the life of the scorer, so a
card of sixteen gigabytes holds a matrix of four million rows of a thousand dimensions and no
more. That is a limit on what the feature can serve, not on how fast it serves it. Asking for
more reports exhaustion rather than a status code.

## What the similarity costs

`MatrixRows.getSimilarity()` runs the configured comparator, in Java. A scorer computing
elsewhere cannot call it, so it reimplements the arithmetic for the comparators it supports and
rejects a namespace configured with any other. The unilateral value of every row is available
through `MatrixRows.getRowUniValues()` to be copied wherever the arithmetic runs, and it carries
the deletions too.

The rows a query may keep are fixed when the scorer is built, since the reduction keeps that
many, so a query asking for more is refused rather than answered short.
