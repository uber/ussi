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

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import javax.annotation.Nullable;
import org.bytedeco.cuda.cublas.cublasContext;
import org.bytedeco.cuda.cudart.CUfunc_st;
import org.bytedeco.cuda.cudart.CUmod_st;
import org.bytedeco.cuda.nvrtc._nvrtcProgram;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.LongPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.SizeTPointer;

/**
 * A dense matrix-vector dot-product scorer that holds the matrix in a device's memory.
 *
 * <p><b>Never run on a device.</b> It compiles, and its results have never been checked against
 * anything, because no machine available to this project has the hardware. It is therefore not
 * reached by {@link MatrixDotProductScorers}, which lists it below a scorer that is always
 * available, and promoting it means moving that entry above the Java scorer.
 *
 * <p>The matrix is copied to the device once and stays for the life of the scorer, so the
 * device's memory bounds the rows an index may hold. A batch's queries are copied in, multiplied
 * against the whole matrix at once, and reduced where they were computed, so what returns is the
 * rows a query keeps rather than a value for every row.
 *
 * <p>The multiply's products are read and never written, so a batch's products are what the
 * multiply produced and nothing else.
 *
 * <p>Not tuned, in ways worth naming. The reduction takes the largest remaining similarity once
 * per row it keeps, so it reads the products once for each of them, where an implementation
 * meant for use would select them all in one pass. Host memory is pageable rather than pinned
 * and every call runs on the default stream, so a copy never overlaps a multiply. The matrix is
 * held in single precision, where half precision would halve what the multiply reads.
 *
 * <p>The rows a query may keep are fixed when the scorer is built, since the device reduces to
 * that many, so a query asking for more is refused rather than answered short.
 */
final class CudaMatrixDotProductScorer
    extends BatchedMatrixDotProductScorer<CudaMatrixDotProductScorer.KeptRows> {

  /** A whole number of warps, which the reduction requires, and within one block's limit. */
  private static final int NUM_THREADS_PER_BLOCK = 256;

  /** What the device reports when it has no room left, which is not a failure to ask properly. */
  private static final int CUDA_ERROR_MEMORY_ALLOCATION = 2;

  /**
   * One block a query. Each pass over the products finds the largest similarity the block has
   * not taken yet. Each warp reduces its own lanes through register shuffles, every warp's
   * leader records what it found in shared memory, and the first warp reduces those to the
   * block's best row.
   *
   * <p>A deleted row carries a unilateral value that is not a number, so the similarity derived
   * from it is not a number either, and a comparison against a value that is not a number is
   * false, so the reduction never prefers it.
   *
   * <p>The shuffles take every lane of a warp, and the leaders occupy one shared slot each, so
   * the block must be a whole number of warps and no more than the warps those slots hold.
   */
  private static final String REDUCTION_SOURCE =
      "extern \"C\" __global__ void keepBestRows(\n"
          + "    const float* products, const double* rowUniValues,\n"
          + "    const double* queryUniValues, unsigned char* taken, int numRows, int numKept,\n"
          + "    long long* keptRowNums, float* keptSimilarities) {\n"
          + "  const float infinity = __int_as_float(0x7f800000);\n"
          + "  __shared__ float warpBestSimilarity[32];\n"
          + "  __shared__ int warpBestRow[32];\n"
          + "  int query = blockIdx.x;\n"
          + "  const float* queryProducts = products + (long long) query * numRows;\n"
          + "  unsigned char* queryTaken = taken + (long long) query * numRows;\n"
          + "  double queryUniValue = queryUniValues[query];\n"
          + "  int laneId = threadIdx.x % 32;\n"
          + "  int warpId = threadIdx.x / 32;\n"
          + "  int numWarps = blockDim.x / 32;\n"
          + "  for (int row = threadIdx.x; row < numRows; row += blockDim.x) {\n"
          + "    queryTaken[row] = 0;\n"
          + "  }\n"
          + "  __syncthreads();\n"
          + "  for (int kept = 0; kept < numKept; ++kept) {\n"
          + "    float bestSimilarity = -infinity;\n"
          + "    int bestRow = -1;\n"
          + "    for (int row = threadIdx.x; row < numRows; row += blockDim.x) {\n"
          + "      if (queryTaken[row]) {\n"
          + "        continue;\n"
          + "      }\n"
          + "      float similarity = (float) -(queryUniValue + rowUniValues[row]\n"
          + "          - 2.0 * (double) queryProducts[row]);\n"
          + "      if (similarity > bestSimilarity) {\n"
          + "        bestSimilarity = similarity;\n"
          + "        bestRow = row;\n"
          + "      }\n"
          + "    }\n"
          + "    // 1. Intra-warp reduction using register shuffles\n"
          + "    for (int offset = 16; offset > 0; offset /= 2) {\n"
          + "      float otherSimilarity = __shfl_down_sync(0xffffffff, bestSimilarity, offset);\n"
          + "      int otherRow = __shfl_down_sync(0xffffffff, bestRow, offset);\n"
          + "      if (otherSimilarity > bestSimilarity) {\n"
          + "        bestSimilarity = otherSimilarity;\n"
          + "        bestRow = otherRow;\n"
          + "      }\n"
          + "    }\n"
          + "    // 2. Warp leaders record their best result to shared memory\n"
          + "    if (laneId == 0) {\n"
          + "      warpBestSimilarity[warpId] = bestSimilarity;\n"
          + "      warpBestRow[warpId] = bestRow;\n"
          + "    }\n"
          + "    __syncthreads();\n"
          + "    // 3. Inter-warp reduction handled purely by the first warp\n"
          + "    if (warpId == 0) {\n"
          + "      bestSimilarity = (laneId < numWarps) ? warpBestSimilarity[laneId] : -infinity;\n"
          + "      bestRow = (laneId < numWarps) ? warpBestRow[laneId] : -1;\n"
          + "      for (int offset = 16; offset > 0; offset /= 2) {\n"
          + "        float otherSimilarity =\n"
          + "            __shfl_down_sync(0xffffffff, bestSimilarity, offset);\n"
          + "        int otherRow = __shfl_down_sync(0xffffffff, bestRow, offset);\n"
          + "        if (otherSimilarity > bestSimilarity) {\n"
          + "          bestSimilarity = otherSimilarity;\n"
          + "          bestRow = otherRow;\n"
          + "        }\n"
          + "      }\n"
          + "      // 4. Thread 0 records the global winner and masks it for the next pass\n"
          + "      if (laneId == 0) {\n"
          + "        long long slot = (long long) query * numKept + kept;\n"
          + "        keptRowNums[slot] = bestRow;\n"
          + "        keptSimilarities[slot] = bestSimilarity;\n"
          + "        if (bestRow >= 0) {\n"
          + "          queryTaken[bestRow] = 1;\n"
          + "        }\n"
          + "      }\n"
          + "    }\n"
          + "    __syncthreads();\n"
          + "  }\n"
          + "}\n";

  /** The rows a query kept, which is all that crosses back from the device. */
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
  private final cublasContext handle = new cublasContext();
  private final CUmod_st module = new CUmod_st();
  private final CUfunc_st reduction = new CUfunc_st();
  private final FloatPointer deviceMatrix = new FloatPointer();
  private final DoublePointer deviceRowUniValues = new DoublePointer();
  private final DoublePointer deviceQueryUniValues = new DoublePointer();
  private final FloatPointer deviceQueries = new FloatPointer();
  private final FloatPointer deviceProducts = new FloatPointer();
  private final BytePointer deviceTaken = new BytePointer();
  private final FloatPointer deviceKeptSimilarities = new FloatPointer();
  private final LongPointer deviceKeptRowNums = new LongPointer();
  private final FloatPointer one = new FloatPointer(1).put(1.0f);
  private final FloatPointer zero = new FloatPointer(1).put(0.0f);

  /**
   * Whether a device and its libraries are present, which asking the driver settles. Memoized,
   * since the answer cannot change within a process, and false when the bindings are absent
   * altogether, which is how a deployment that did not ask for them behaves.
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
    check(cuInit(0), "start");
    check(cublasCreate_v2(handle), "create a handle");
    allocate(deviceMatrix, (long) numRows * dimension * Float.BYTES);
    allocate(deviceRowUniValues, (long) numRows * Double.BYTES);
    allocate(deviceQueryUniValues, (long) maxNumQueriesInABatch * Double.BYTES);
    allocate(deviceQueries, (long) maxNumQueriesInABatch * dimension * Float.BYTES);
    allocate(deviceProducts, (long) maxNumQueriesInABatch * numRows * Float.BYTES);
    allocate(deviceTaken, (long) maxNumQueriesInABatch * numRows);
    allocate(deviceKeptSimilarities, (long) maxNumQueriesInABatch * numKeptPerQuery * Float.BYTES);
    allocate(deviceKeptRowNums, (long) maxNumQueriesInABatch * numKeptPerQuery * Long.BYTES);
    copyMatrixToDevice(matrix);
    copyToDevice(deviceRowUniValues, rows.getRowUniValues());
    compileReduction();
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

  @Override
  protected void releaseResources() {
    cuModuleUnload(module);
    module.deallocate();
    reduction.deallocate();
    cudaFree(deviceKeptRowNums);
    cudaFree(deviceKeptSimilarities);
    cudaFree(deviceTaken);
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
    DoublePointer queryUniValues = new DoublePointer(numQueries);
    for (int query = 0; query < numQueries; ++query) {
      queries.position((long) query * dimension).put(queryValues[query], 0, dimension);
      queryUniValues.put(query, selections[query].getQueryUniValue());
    }
    queries.position(0);
    check(
        cudaMemcpy(
            deviceQueries, queries, (long) numQueries * dimension * Float.BYTES,
            cudaMemcpyHostToDevice),
        "copy the queries");
    check(
        cudaMemcpy(
            deviceQueryUniValues, queryUniValues, (long) numQueries * Double.BYTES,
            cudaMemcpyHostToDevice),
        "copy the query unilateral values");
    queries.deallocate();
    queryUniValues.deallocate();
  }

  private void launchReduction(int numQueries) {
    IntPointer numRowsArgument = new IntPointer(1).put(numRows);
    IntPointer numKeptArgument = new IntPointer(1).put(numKeptPerQuery);
    PointerPointer<Pointer> arguments =
        new PointerPointer<>(
            deviceProducts,
            deviceRowUniValues,
            deviceQueryUniValues,
            deviceTaken,
            numRowsArgument,
            numKeptArgument,
            deviceKeptRowNums,
            deviceKeptSimilarities);
    check(
        cuLaunchKernel(
            reduction, numQueries, 1, 1, NUM_THREADS_PER_BLOCK, 1, 1, 0, null, arguments, null),
        "reduce");
    arguments.deallocate();
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

  private void copyToDevice(DoublePointer device, double[] values) {
    DoublePointer host = new DoublePointer(values);
    check(
        cudaMemcpy(device, host, (long) values.length * Double.BYTES, cudaMemcpyHostToDevice),
        "copy the unilateral values");
    host.deallocate();
  }

  private void compileReduction() {
    _nvrtcProgram program = new _nvrtcProgram();
    BytePointer source = new BytePointer(REDUCTION_SOURCE);
    BytePointer name = new BytePointer("keepBestRows.cu");
    check(nvrtcCreateProgram(program, source, name, 0, (PointerPointer<Pointer>) null, null),
        "create the reduction");
    check(nvrtcCompileProgram(program, 0, (PointerPointer<Pointer>) null), "compile the reduction");
    SizeTPointer size = new SizeTPointer(1);
    check(nvrtcGetPTXSize(program, size), "size the reduction");
    BytePointer ptx = new BytePointer(size.get());
    check(nvrtcGetPTX(program, ptx), "read the reduction");
    check(cuModuleLoadData(module, ptx), "load the reduction");
    BytePointer functionName = new BytePointer("keepBestRows");
    check(cuModuleGetFunction(reduction, module, functionName), "find it");
    nvrtcDestroyProgram(program);
    program.deallocate();
    functionName.deallocate();
    ptx.deallocate();
    source.deallocate();
    name.deallocate();
    size.deallocate();
  }

  private static void allocate(Pointer pointer, long numBytes) {
    int status = cudaMalloc(pointer, numBytes);
    if (status == CUDA_ERROR_MEMORY_ALLOCATION) {
      throw new OutOfMemoryError(
          "The device has no room for " + numBytes + " bytes, which bounds the rows an index "
              + "may hold.");
    }
    check(status, "allocate " + numBytes + " bytes");
  }

  private static void check(int status, String what) {
    if (status != 0) {
      throw new IllegalStateException("The device failed to " + what + ", status " + status + ".");
    }
  }
}
