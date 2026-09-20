/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import static org.bytedeco.cuda.global.cublas.CUBLAS_OP_N;
import static org.bytedeco.cuda.global.cublas.CUBLAS_OP_T;
import static org.bytedeco.cuda.global.cublas.cublasCreate_v2;
import static org.bytedeco.cuda.global.cublas.cublasDestroy_v2;
import static org.bytedeco.cuda.global.cublas.cublasSgemm_v2;
import static org.bytedeco.cuda.global.cudart.cuInit;
import static org.bytedeco.cuda.global.cudart.cuLaunchKernel;
import static org.bytedeco.cuda.global.cudart.cuModuleGetFunction;
import static org.bytedeco.cuda.global.cudart.cuModuleLoadData;
import static org.bytedeco.cuda.global.cudart.cuModuleUnload;
import static org.bytedeco.cuda.global.cudart.cudaDeviceSynchronize;
import static org.bytedeco.cuda.global.cudart.cudaFree;
import static org.bytedeco.cuda.global.cudart.cudaMalloc;
import static org.bytedeco.cuda.global.cudart.cudaMemcpy;
import static org.bytedeco.cuda.global.cudart.cudaMemcpyDeviceToHost;
import static org.bytedeco.cuda.global.cudart.cudaMemcpyHostToDevice;
import static org.bytedeco.cuda.global.nvrtc.nvrtcCompileProgram;
import static org.bytedeco.cuda.global.nvrtc.nvrtcCreateProgram;
import static org.bytedeco.cuda.global.nvrtc.nvrtcDestroyProgram;
import static org.bytedeco.cuda.global.nvrtc.nvrtcGetPTX;
import static org.bytedeco.cuda.global.nvrtc.nvrtcGetPTXSize;
import static org.bytedeco.cuda.global.nvrtc.nvrtcGetProgramLog;
import static org.bytedeco.cuda.global.nvrtc.nvrtcGetProgramLogSize;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import javax.annotation.Nullable;
import org.bytedeco.cuda.cublas.cublasContext;
import org.bytedeco.cuda.cudart.CUfunc_st;
import org.bytedeco.cuda.cudart.CUmod_st;
import org.bytedeco.cuda.nvrtc._nvrtcProgram;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.SizeTPointer;

/**
 * A dense matrix-vector dot-product scorer that holds the matrix in a GPU's memory.
 *
 * <p><b>Never run on a GPU.</b> It compiles, and no result it produces has been verified
 * against another scorer, because no machine available to this project has one. It is therefore
 * not reached by {@link MatrixDotProductScorers}, which lists it after a scorer that is always
 * available, and promoting it means moving that entry ahead of the Java scorer.
 *
 * <p>The matrix is copied to the GPU once and stays for the life of the scorer, so the GPU's
 * memory bounds the rows an index may hold. A batch's queries are copied in, multiplied
 * against the whole matrix at once, and reduced where they were computed, so what returns is the
 * rows a query keeps rather than a value for every row.
 *
 * <p>The multiply's products are read and never written, so a batch's products are what the
 * multiply produced and nothing else.
 *
 * <p>Not tuned, in ways worth naming. A batch multiplies against the whole matrix at once and
 * selects from the whole result, where an implementation meant for use would tile the multiply
 * over blocks of rows and select within each tile, which bounds the memory the products need
 * and keeps a tile in cache while it is selected from. Host memory is pageable rather than
 * pinned and every call runs on the default stream, so a copy never overlaps a multiply. The
 * matrix is held in single precision, where half precision would halve both what the multiply
 * reads and how large a matrix fits.
 *
 * <p>The rows a query may keep are fixed when the scorer is built, since the select on the GPU
 * keeps that many, so a query asking for more is refused rather than answered short.
 *
 * <p>CUDA calls a GPU's memory device memory, which the fields holding it are named for.
 */
final class CudaMatrixDotProductScorer
    extends BatchedMatrixDotProductScorer<CudaMatrixDotProductScorer.KeptRows> {

  /** Enough threads to cover the histogram the select counts into, and within a block. */
  private static final int NUM_THREADS_PER_BLOCK = 256;

  /** What CUDA reports when the GPU has no room left, which is not a failure to ask properly. */
  private static final int CUDA_ERROR_MEMORY_ALLOCATION = 2;

  /**
   * One block a query, selecting that query's best rows in a fixed number of passes rather
   * than one pass for each row kept.
   *
   * <p>The passes are a radix select. Reading a similarity's bits as an unsigned number
   * preserves its order, so four passes over the rows, each counting one byte of that number
   * into a histogram, narrow the rows to the key of the last row to keep. A fifth pass writes
   * out every row above that key, and enough of those equal to it to make the count up.
   *
   * <p>What it writes is the rows to keep in no particular order, which is all the caller
   * needs, and fewer than asked for when the matrix holds fewer.
   *
   * <p>A deleted row carries a unilateral value that is not a number, so the similarity
   * derived from it is not a number either, and the passes drop it.
   */
  private static final String REDUCTION_SOURCE =
      "__device__ unsigned int orderedKey(float similarity) {\n"
          + "  // Flipping the sign bit of a positive number and every bit of a negative\n"
          + "  // one makes the unsigned order of the bits the order of the numbers.\n"
          + "  unsigned int bits = __float_as_uint(similarity);\n"
          + "  return (bits & 0x80000000u) ? ~bits : (bits | 0x80000000u);\n"
          + "}\n"
          + "\n"
          + "extern \"C\" __global__ void keepBestRows(\n"
          + "    const float* products, const float* rowUniValues, const float* queryUniValues,\n"
          + "    int numRows, int numKept, long long* keptRowNums, float* keptSimilarities) {\n"
          + "  __shared__ int histogram[256];\n"
          + "  __shared__ unsigned int thresholdKey;\n"
          + "  __shared__ int numToKeep;\n"
          + "  __shared__ int numStillNeeded;\n"
          + "  __shared__ int numAboveWritten;\n"
          + "  __shared__ int numAtWritten;\n"
          + "  int query = blockIdx.x;\n"
          + "  const float* queryProducts = products + (long long) query * numRows;\n"
          + "  float queryUniValue = queryUniValues[query];\n"
          + "  long long base = (long long) query * numKept;\n"
          + "  for (int slot = threadIdx.x; slot < numKept; slot += blockDim.x) {\n"
          + "    keptRowNums[base + slot] = -1;\n"
          + "    keptSimilarities[base + slot] = 0.0f;\n"
          + "  }\n"
          + "  if (threadIdx.x == 0) {\n"
          + "    thresholdKey = 0u;\n"
          + "    numToKeep = numKept;\n"
          + "    numStillNeeded = numKept;\n"
          + "  }\n"
          + "  __syncthreads();\n"
          + "  for (int digit = 0; digit < 4; ++digit) {\n"
          + "    int shift = 24 - 8 * digit;\n"
          + "    // Which bits the threshold has fixed, and none on the first digit, where\n"
          + "    // shifting by the width of the type would not be defined.\n"
          + "    unsigned int fixedBits = (digit == 0) ? 0u : (0xffffffffu << (shift + 8));\n"
          + "    for (int bin = threadIdx.x; bin < 256; bin += blockDim.x) {\n"
          + "      histogram[bin] = 0;\n"
          + "    }\n"
          + "    __syncthreads();\n"
          + "    for (int row = threadIdx.x; row < numRows; row += blockDim.x) {\n"
          + "      float similarity =\n"
          + "          -(queryUniValue + rowUniValues[row] - 2.0f * queryProducts[row]);\n"
          + "      // A deleted row's unilateral value is not a number, so neither is its\n"
          + "      // similarity, and such a value is the only one unequal to itself.\n"
          + "      if (similarity != similarity) {\n"
          + "        continue;\n"
          + "      }\n"
          + "      unsigned int key = orderedKey(similarity);\n"
          + "      if ((key & fixedBits) == (thresholdKey & fixedBits)) {\n"
          + "        atomicAdd(&histogram[(key >> shift) & 0xffu], 1);\n"
          + "      }\n"
          + "    }\n"
          + "    __syncthreads();\n"
          + "    if (threadIdx.x == 0) {\n"
          + "      if (digit == 0) {\n"
          + "        int numAlive = 0;\n"
          + "        for (int bin = 0; bin < 256; ++bin) {\n"
          + "          numAlive += histogram[bin];\n"
          + "        }\n"
          + "        // Fewer rows than asked for leaves the remaining slots as they were.\n"
          + "        if (numAlive < numToKeep) {\n"
          + "          numToKeep = numAlive;\n"
          + "          numStillNeeded = numAlive;\n"
          + "        }\n"
          + "      }\n"
          + "      int numAbove = 0;\n"
          + "      for (int bin = 255; bin >= 0; --bin) {\n"
          + "        if (numAbove + histogram[bin] >= numStillNeeded) {\n"
          + "          thresholdKey |= ((unsigned int) bin) << shift;\n"
          + "          numStillNeeded -= numAbove;\n"
          + "          break;\n"
          + "        }\n"
          + "        numAbove += histogram[bin];\n"
          + "      }\n"
          + "    }\n"
          + "    __syncthreads();\n"
          + "  }\n"
          + "  // The threshold is the key of the last row to keep. Every row above it is\n"
          + "  // kept, and as many of those equal to it as the count is short by.\n"
          + "  int numAboveThreshold = numToKeep - numStillNeeded;\n"
          + "  int numAtThreshold = numStillNeeded;\n"
          + "  if (threadIdx.x == 0) {\n"
          + "    numAboveWritten = 0;\n"
          + "    numAtWritten = 0;\n"
          + "  }\n"
          + "  __syncthreads();\n"
          + "  for (int row = threadIdx.x; row < numRows; row += blockDim.x) {\n"
          + "    float similarity =\n"
          + "        -(queryUniValue + rowUniValues[row] - 2.0f * queryProducts[row]);\n"
          + "    if (similarity != similarity) {\n"
          + "      continue;\n"
          + "    }\n"
          + "    unsigned int key = orderedKey(similarity);\n"
          + "    if (key > thresholdKey) {\n"
          + "      int slot = atomicAdd(&numAboveWritten, 1);\n"
          + "      // Defensive check: the histograms counted how many are above it.\n"
          + "      if (slot < numAboveThreshold) {\n"
          + "        keptRowNums[base + slot] = row;\n"
          + "        keptSimilarities[base + slot] = similarity;\n"
          + "      }\n"
          + "    } else if (key == thresholdKey) {\n"
          + "      int slot = atomicAdd(&numAtWritten, 1);\n"
          + "      if (slot < numAtThreshold) {\n"
          + "        keptRowNums[base + numAboveThreshold + slot] = row;\n"
          + "        keptSimilarities[base + numAboveThreshold + slot] = similarity;\n"
          + "      }\n"
          + "    }\n"
          + "  }\n"
          + "}\n";

  /** The rows a query kept, which is all that crosses back from the GPU. */
  static final class KeptRows {
    private final long[] rowNums;
    private final float[] similarities;

    KeptRows(int numKept) {
      this.rowNums = new long[numKept];
      this.similarities = new float[numKept];
    }
  }

  @Nullable private static volatile Boolean isAvailable;

  private final int numRows;
  private final int dimension;
  private final int numKeptPerQuery;
  private boolean isReductionLoaded;
  private final cublasContext handle = new cublasContext();
  private final CUmod_st module = new CUmod_st();
  private final CUfunc_st reduction = new CUfunc_st();
  private final FloatPointer deviceMatrix = new FloatPointer();
  private final FloatPointer deviceRowUniValues = new FloatPointer();
  private final FloatPointer deviceQueryUniValues = new FloatPointer();
  private final FloatPointer deviceQueries = new FloatPointer();
  private final FloatPointer deviceProducts = new FloatPointer();
  private final FloatPointer deviceKeptSimilarities = new FloatPointer();
  private final LongPointer deviceKeptRowNums = new LongPointer();
  private final FloatPointer one = new FloatPointer(1).put(1.0f);
  private final FloatPointer zero = new FloatPointer(1).put(0.0f);

  /**
   * Whether a GPU and the libraries reaching it are present, which asking the driver settles.
   * Memoized, since the answer cannot change within a process, and false when the bindings are
   * absent altogether, which is how a deployment that did not ask for them behaves.
   */
  static boolean isAvailable() {
    Boolean memoized = isAvailable;
    if (memoized == null) {
      synchronized (CudaMatrixDotProductScorer.class) {
        memoized = isAvailable;
        if (memoized == null) {
          try {
            memoized = cuInit(0) == 0;
          } catch (LinkageError | RuntimeException e) {
            memoized = false;
          }
          isAvailable = memoized;
        }
      }
    }
    return memoized;
  }

  CudaMatrixDotProductScorer(
      DenseMatrix matrix, MatrixRows rows, int maxNumQueriesInABatch, int maxResults) {
    super(matrix, rows, maxNumQueriesInABatch);
    this.numRows = matrix.numRows();
    this.dimension = matrix.dimension();
    if (maxResults < 1) {
      throw new IllegalArgumentException("maxResults must be >= 1.");
    }
    this.numKeptPerQuery = maxResults;
    try {
      check(cuInit(0), "start");
      check(cublasCreate_v2(handle), "create a handle");
      allocate(deviceMatrix, (long) numRows * dimension * Float.BYTES);
      allocate(deviceRowUniValues, (long) numRows * Float.BYTES);
      allocate(deviceQueryUniValues, (long) maxNumQueriesInABatch * Float.BYTES);
      allocate(deviceQueries, (long) maxNumQueriesInABatch * dimension * Float.BYTES);
      allocate(deviceProducts, (long) maxNumQueriesInABatch * numRows * Float.BYTES);
      allocate(
          deviceKeptSimilarities, (long) maxNumQueriesInABatch * numKeptPerQuery * Float.BYTES);
      allocate(deviceKeptRowNums, (long) maxNumQueriesInABatch * numKeptPerQuery * Long.BYTES);
      copyMatrixToDevice(matrix);
      copyToDevice(deviceRowUniValues, rows.getRowUniValues());
      compileReduction();
    } catch (Error | RuntimeException e) {
      // The memory the GPU holds is reached only through this scorer, and a constructor that
      // throws leaves nothing that would release it, so what was taken is released here. A
      // matrix too large for the GPU makes this the expected path rather than a rare one.
      releaseResources();
      throw e;
    }
  }

  @Override
  protected KeptRows newMultiplyResult() {
    return new KeptRows(numKeptPerQuery);
  }

  @Override
  protected KeptRows[] newMultiplyResults(int numResults) {
    return new KeptRows[numResults];
  }

  @Override
  protected void multiplyOneQuery(float[] queryValues, RowSelection selection, KeptRows into) {
    multiplyQueries(
        new float[][] {queryValues}, new RowSelection[] {selection}, new KeptRows[] {into}, 1);
  }

  @Override
  protected void multiplyQueries(
      float[][] queryValues, RowSelection[] selections, KeptRows[] into, int numQueries) {
    for (int query = 0; query < numQueries; ++query) {
      if (selections[query].getMaxResults() > numKeptPerQuery) {
        throw new IllegalArgumentException(
            String.format(
                "This scorer keeps %s rows a query and was asked for %s.",
                numKeptPerQuery, selections[query].getMaxResults()));
      }
    }
    copyQueriesToDevice(queryValues, selections, numQueries);
    // The library is column-major and the matrix is row-major, so the rows read as their own
    // transpose and the products come back with each query's contiguous.
    check(
        cublasSgemm_v2(
            handle, CUBLAS_OP_T, CUBLAS_OP_N, numRows, numQueries, dimension, one, deviceMatrix,
            dimension, deviceQueries, dimension, zero, deviceProducts, numRows),
        "multiply");
    launchReduction(numQueries);
    check(cudaDeviceSynchronize(), "finish");
    copyKeptRowsToHost(into, numQueries);
  }

  @Override
  protected void addRows(
      KeptRows result, RowSelection selection, BoundedSizeMaxHeap<RowNumAndSimilarity> rows) {
    for (int kept = 0; kept < numKeptPerQuery; ++kept) {
      long matrixRowIndex = result.rowNums[kept];
      if (matrixRowIndex >= 0 && result.similarities[kept] >= selection.getMinSimilarity()) {
        rows.add(
            new RowNumAndSimilarity(
                getRows().getRowNum((int) matrixRowIndex), result.similarities[kept]));
      }
    }
  }

  /**
   * Releases what the scorer took, and is called by the constructor as well when construction
   * fails partway, so it runs against whatever was taken by then. Freeing memory that was
   * never allocated is defined and does nothing, where unloading a module that was never
   * loaded is not, which is what the module is tracked for.
   */
  @Override
  protected void releaseResources() {
    if (isReductionLoaded) {
      cuModuleUnload(module);
    }
    module.deallocate();
    reduction.deallocate();
    cudaFree(deviceKeptRowNums);
    cudaFree(deviceKeptSimilarities);
    cudaFree(deviceProducts);
    cudaFree(deviceQueries);
    cudaFree(deviceQueryUniValues);
    cudaFree(deviceRowUniValues);
    cudaFree(deviceMatrix);
    cublasDestroy_v2(handle);
    handle.deallocate();
    one.deallocate();
    zero.deallocate();
  }

  private void copyQueriesToDevice(
      float[][] queryValues, RowSelection[] selections, int numQueries) {
    FloatPointer queries = new FloatPointer((long) numQueries * dimension);
    FloatPointer queryUniValues = new FloatPointer(numQueries);
    for (int query = 0; query < numQueries; ++query) {
      queries.position((long) query * dimension).put(queryValues[query], 0, dimension);
      queryUniValues.put(query, (float) selections[query].getQueryUniValue());
    }
    queries.position(0);
    check(
        cudaMemcpy(
            deviceQueries, queries, (long) numQueries * dimension * Float.BYTES,
            cudaMemcpyHostToDevice),
        "copy the queries");
    check(
        cudaMemcpy(
            deviceQueryUniValues, queryUniValues, (long) numQueries * Float.BYTES,
            cudaMemcpyHostToDevice),
        "copy the query unilateral values");
    queries.deallocate();
    queryUniValues.deallocate();
  }

  private void launchReduction(int numQueries) {
    IntPointer numRowsArgument = new IntPointer(1).put(numRows);
    IntPointer numKeptArgument = new IntPointer(1).put(numKeptPerQuery);
    // Every argument is read from the address given for it, so an argument that is itself an
    // address is handed over as a buffer holding it rather than as itself. Passing a device
    // address directly has it read as though it were a host one.
    PointerPointer<Pointer> productsArgument = new PointerPointer<>(1).put(deviceProducts);
    PointerPointer<Pointer> rowUniValuesArgument =
        new PointerPointer<>(1).put(deviceRowUniValues);
    PointerPointer<Pointer> queryUniValuesArgument =
        new PointerPointer<>(1).put(deviceQueryUniValues);
    PointerPointer<Pointer> keptRowNumsArgument =
        new PointerPointer<>(1).put(deviceKeptRowNums);
    PointerPointer<Pointer> keptSimilaritiesArgument =
        new PointerPointer<>(1).put(deviceKeptSimilarities);
    PointerPointer<Pointer> arguments =
        new PointerPointer<>(
            productsArgument,
            rowUniValuesArgument,
            queryUniValuesArgument,
            numRowsArgument,
            numKeptArgument,
            keptRowNumsArgument,
            keptSimilaritiesArgument);
    check(
        cuLaunchKernel(
            reduction, numQueries, 1, 1, NUM_THREADS_PER_BLOCK, 1, 1, 0, null, arguments, null),
        "reduce");
    arguments.deallocate();
    productsArgument.deallocate();
    rowUniValuesArgument.deallocate();
    queryUniValuesArgument.deallocate();
    keptRowNumsArgument.deallocate();
    keptSimilaritiesArgument.deallocate();
    numRowsArgument.deallocate();
    numKeptArgument.deallocate();
  }

  private void copyKeptRowsToHost(KeptRows[] into, int numQueries) {
    long numKept = (long) numQueries * numKeptPerQuery;
    FloatPointer similarities = new FloatPointer(numKept);
    LongPointer rowNums = new LongPointer(numKept);
    check(
        cudaMemcpy(
            similarities, deviceKeptSimilarities, numKept * Float.BYTES, cudaMemcpyDeviceToHost),
        "copy the kept similarities");
    check(
        cudaMemcpy(rowNums, deviceKeptRowNums, numKept * Long.BYTES, cudaMemcpyDeviceToHost),
        "copy the kept rows");
    for (int query = 0; query < numQueries; ++query) {
      similarities.position((long) query * numKeptPerQuery).get(into[query].similarities);
      rowNums.position((long) query * numKeptPerQuery).get(into[query].rowNums);
    }
    similarities.position(0).deallocate();
    rowNums.position(0).deallocate();
  }

  private void copyMatrixToDevice(DenseMatrix matrix) {
    // A view of the device's memory, which holds none of its own and so is never released.
    FloatPointer atChunk = new FloatPointer(deviceMatrix);
    long offset = 0;
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      float[] values = matrix.chunk(chunk);
      FloatPointer host = new FloatPointer(values);
      check(
          cudaMemcpy(
              atChunk.position(offset),
              host,
              (long) values.length * Float.BYTES,
              cudaMemcpyHostToDevice),
          "copy a chunk");
      host.deallocate();
      offset += values.length;
    }
  }

  private void copyToDevice(FloatPointer device, double[] values) {
    // Narrowing leaves a value that is not a number as one, so a deleted row stays deleted.
    float[] narrowed = new float[values.length];
    for (int index = 0; index < values.length; ++index) {
      narrowed[index] = (float) values[index];
    }
    FloatPointer host = new FloatPointer(narrowed);
    check(
        cudaMemcpy(device, host, (long) narrowed.length * Float.BYTES, cudaMemcpyHostToDevice),
        "copy the unilateral values");
    host.deallocate();
  }

  private void compileReduction() {
    _nvrtcProgram program = new _nvrtcProgram();
    BytePointer source = new BytePointer(REDUCTION_SOURCE);
    BytePointer name = new BytePointer("keepBestRows.cu");
    SizeTPointer size = new SizeTPointer(1);
    BytePointer ptx = null;
    BytePointer functionName = null;
    try {
      check(nvrtcCreateProgram(program, source, name, 0, (PointerPointer<Pointer>) null, null),
          "create the reduction");
      int compiled = nvrtcCompileProgram(program, 0, (PointerPointer<Pointer>) null);
      if (compiled != 0) {
        // The kernel is compiled where it runs, so what the compiler objected to is the only
        // account of why, and a status on its own would not identify the line.
        throw new IllegalStateException(
            "CUDA failed to compile the reduction: " + readCompilerLog(program));
      }
      check(nvrtcGetPTXSize(program, size), "size the reduction");
      ptx = new BytePointer(size.get());
      check(nvrtcGetPTX(program, ptx), "read the reduction");
      check(cuModuleLoadData(module, ptx), "load the reduction");
      isReductionLoaded = true;
      functionName = new BytePointer("keepBestRows");
      check(cuModuleGetFunction(reduction, module, functionName), "find it");
    } finally {
      // The compiler holds the program until told otherwise, which a failure part way through
      // would otherwise leave it holding for the life of the process.
      nvrtcDestroyProgram(program);
      program.deallocate();
      if (functionName != null) {
        functionName.deallocate();
      }
      if (ptx != null) {
        ptx.deallocate();
      }
      source.deallocate();
      name.deallocate();
      size.deallocate();
    }
  }

  private static String readCompilerLog(_nvrtcProgram program) {
    SizeTPointer size = new SizeTPointer(1);
    BytePointer log = null;
    try {
      if (nvrtcGetProgramLogSize(program, size) != 0) {
        return "the compiler gave no account of what it objected to.";
      }
      log = new BytePointer(size.get());
      if (nvrtcGetProgramLog(program, log) != 0) {
        return "the compiler gave no account of what it objected to.";
      }
      return log.getString();
    } finally {
      if (log != null) {
        log.deallocate();
      }
      size.deallocate();
    }
  }

  private static void allocate(Pointer pointer, long numBytes) {
    int status = cudaMalloc(pointer, numBytes);
    if (status == CUDA_ERROR_MEMORY_ALLOCATION) {
      throw new OutOfMemoryError(
          "The GPU has no room for " + numBytes + " bytes, which bounds the rows an index may "
              + "hold.");
    }
    check(status, "allocate " + numBytes + " bytes");
  }

  private static void check(int status, String what) {
    if (status != 0) {
      throw new IllegalStateException("CUDA failed to " + what + ", status " + status + ".");
    }
  }
}
