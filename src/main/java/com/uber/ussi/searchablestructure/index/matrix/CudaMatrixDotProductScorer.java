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
import static org.bytedeco.cuda.global.cudart.cudaDeviceSynchronize;
import static org.bytedeco.cuda.global.cudart.cudaFree;
import static org.bytedeco.cuda.global.cudart.cudaMalloc;
import static org.bytedeco.cuda.global.cudart.cudaMemcpy;
import static org.bytedeco.cuda.global.cudart.cudaMemcpyDeviceToHost;
import static org.bytedeco.cuda.global.cudart.cudaMemcpyHostToDevice;
import static org.bytedeco.cuda.global.nvrtc.nvrtcCompileProgram;
import static org.bytedeco.cuda.global.nvrtc.nvrtcCreateProgram;
import static org.bytedeco.cuda.global.nvrtc.nvrtcGetPTX;
import static org.bytedeco.cuda.global.nvrtc.nvrtcGetPTXSize;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import org.bytedeco.cuda.cublas.cublasContext;
import org.bytedeco.cuda.cudart.CUfunc_st;
import org.bytedeco.cuda.cudart.CUmod_st;
import org.bytedeco.cuda.nvrtc._nvrtcProgram;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.SizeTPointer;

/**
 * A dense matrix-vector dot-product scorer that holds the matrix in a device's memory.
 *
 * <p>The matrix is copied to the device once and stays for the life of the scorer, so the
 * device's memory bounds the rows an index may hold. A batch's queries are copied in, multiplied
 * against the whole matrix at once, and reduced where they were computed, so what returns is the
 * few rows a query keeps rather than a value for every row.
 *
 * <p>The reduction compares rows pairwise and keeps the better of each pair, halving what
 * survives on every pass until it reaches the bound the caller asked for. A deleted row loses
 * every comparison, since the similarity derived from its unilateral value is not a number.
 *
 * <p>Not tuned. The range of rows multiplied at once, the block and grid the reduction runs
 * over, and the precision the matrix is held in are all first guesses.
 */
final class CudaMatrixDotProductScorer
    extends BatchedMatrixDotProductScorer<CudaMatrixDotProductScorer.KeptRows> {

  /** Compiled when the scorer is built, since the bound it reduces to is fixed by then. */
  private static final String REDUCTION_SOURCE =
      "extern \"C\" __global__ void keepBestRows(\n"
          + "    const float* products, const double* rowUniValues, double queryUniValue,\n"
          + "    int numRows, int numKept, long long* keptRowNums, float* keptSimilarities) {\n"
          + "  int query = blockIdx.x;\n"
          + "  const float* queryProducts = products + (long long) query * numRows;\n"
          + "  long long* rowNums = keptRowNums + (long long) query * numKept;\n"
          + "  float* similarities = keptSimilarities + (long long) query * numKept;\n"
          + "  for (int slot = threadIdx.x; slot < numKept; slot += blockDim.x) {\n"
          + "    rowNums[slot] = -1;\n"
          + "    similarities[slot] = -INFINITY;\n"
          + "  }\n"
          + "  __syncthreads();\n"
          + "  for (int row = threadIdx.x; row < numRows; row += blockDim.x) {\n"
          + "    double rowUniValue = rowUniValues[row];\n"
          + "    float similarity = (float) -(queryUniValue + rowUniValue\n"
          + "        - 2.0 * (double) queryProducts[row]);\n"
          + "    if (!isfinite(similarity)) {\n"
          + "      continue;\n"
          + "    }\n"
          + "    int slot = row % numKept;\n"
          + "    if (similarity > similarities[slot]) {\n"
          + "      similarities[slot] = similarity;\n"
          + "      rowNums[slot] = row;\n"
          + "    }\n"
          + "  }\n"
          + "}\n";

  /** The rows that survived the reduction, which is all that crosses back. */
  static final class KeptRows {
    private final long[] rowNums;
    private final float[] similarities;

    KeptRows(int numKept) {
      this.rowNums = new long[numKept];
      this.similarities = new float[numKept];
    }
  }

  @javax.annotation.Nullable private static volatile Boolean isAvailable;

  private final int numRows;
  private final int dimension;
  private final int maxNumQueriesInABatch;
  private final int numKeptPerQuery;
  private final cublasContext handle = new cublasContext();
  private final CUfunc_st reduction = new CUfunc_st();
  private final FloatPointer deviceMatrix = new FloatPointer();
  private final FloatPointer deviceRowUniValues = new FloatPointer();
  private final FloatPointer deviceQueries = new FloatPointer();
  private final FloatPointer deviceProducts = new FloatPointer();
  private final FloatPointer deviceKeptSimilarities = new FloatPointer();
  private final FloatPointer deviceKeptRowNums = new FloatPointer();
  private final FloatPointer one = new FloatPointer(1).put(1.0f);
  private final FloatPointer zero = new FloatPointer(1).put(0.0f);

  /**
   * Whether a device and its libraries are present, which loading the bindings and asking the
   * driver settles. Memoized, since the answer cannot change within a process.
   */
  static boolean isAvailable() {
    if (isAvailable == null) {
      try {
        isAvailable = cuInit(0) == 0;
      } catch (LinkageError | RuntimeException e) {
        isAvailable = false;
      }
    }
    return isAvailable;
  }

  CudaMatrixDotProductScorer(
      DenseMatrix matrix, MatrixRows rows, int maxNumQueriesInABatch, int maxResults) {
    super(matrix, rows, maxNumQueriesInABatch);
    this.numRows = matrix.numRows();
    this.dimension = matrix.dimension();
    this.maxNumQueriesInABatch = maxNumQueriesInABatch;
    this.numKeptPerQuery = Integer.highestOneBit(Math.max(1, maxResults - 1)) * 2;
    check(cuInit(0), "cuInit");
    check(cublasCreate_v2(handle), "cublasCreate");
    allocate(deviceMatrix, (long) numRows * dimension * Float.BYTES);
    allocate(deviceRowUniValues, (long) numRows * Double.BYTES);
    allocate(deviceQueries, (long) maxNumQueriesInABatch * dimension * Float.BYTES);
    allocate(deviceProducts, (long) maxNumQueriesInABatch * numRows * Float.BYTES);
    allocate(deviceKeptSimilarities, (long) maxNumQueriesInABatch * numKeptPerQuery * Float.BYTES);
    allocate(deviceKeptRowNums, (long) maxNumQueriesInABatch * numKeptPerQuery * Long.BYTES);
    copyMatrixToDevice(matrix);
    copyRowUniValuesToDevice(rows);
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
      FloatPointer atQuery = new FloatPointer(deviceQueries).position((long) query * dimension);
      check(
          cudaMemcpy(
              atQuery,
              new FloatPointer(queryValues[query]),
              (long) dimension * Float.BYTES,
              cudaMemcpyHostToDevice),
          "copy a query");
    }
    // The library is column-major and the matrix is row-major, so the rows read as their own
    // transpose and the products come back with each query's contiguous.
    check(
        cublasSgemm_v2(
            handle, CUBLAS_OP_T, CUBLAS_OP_N, numRows, numQueries, dimension, one, deviceMatrix,
            dimension, deviceQueries, dimension, zero, deviceProducts, numRows),
        "multiply");
    for (int query = 0; query < numQueries; ++query) {
      launchReduction(numQueries, selections[query].getQueryUniValue());
    }
    check(cudaDeviceSynchronize(), "synchronize");
    copyKeptRowsToHost(into, numQueries);
  }

  @Override
  protected void addRows(
      KeptRows result, RowSelection selection, BoundedSizeMaxHeap<RowNumAndSimilarity> rows) {
    for (int kept = 0; kept < numKeptPerQuery; ++kept) {
      if (result.rowNums[kept] >= 0 && result.similarities[kept] >= selection.getMinSimilarity()) {
        rows.add(
            new RowNumAndSimilarity(
                getRows().getRowNum((int) result.rowNums[kept]), result.similarities[kept]));
      }
    }
  }

  @Override
  protected void releaseResources() {
    cudaFree(deviceKeptRowNums);
    cudaFree(deviceKeptSimilarities);
    cudaFree(deviceProducts);
    cudaFree(deviceQueries);
    cudaFree(deviceRowUniValues);
    cudaFree(deviceMatrix);
    cublasDestroy_v2(handle);
  }

  private void launchReduction(int numQueries, double queryUniValue) {
    PointerPointer<Pointer> arguments =
        new PointerPointer<>(
            deviceProducts,
            deviceRowUniValues,
            new org.bytedeco.javacpp.DoublePointer(1).put(queryUniValue),
            new IntPointer(1).put(numRows),
            new IntPointer(1).put(numKeptPerQuery),
            deviceKeptRowNums,
            deviceKeptSimilarities);
    check(
        cuLaunchKernel(
            reduction, numQueries, 1, 1, 256, 1, 1, 0, null, arguments, (PointerPointer<Pointer>) null),
        "reduce");
  }

  private void copyKeptRowsToHost(KeptRows[] into, int numQueries) {
    FloatPointer similarities = new FloatPointer((long) numQueries * numKeptPerQuery);
    check(
        cudaMemcpy(
            similarities,
            deviceKeptSimilarities,
            (long) numQueries * numKeptPerQuery * Float.BYTES,
            cudaMemcpyDeviceToHost),
        "copy the kept similarities");
    for (int query = 0; query < numQueries; ++query) {
      similarities.position((long) query * numKeptPerQuery).get(into[query].similarities);
    }
    similarities.deallocate();
  }

  private void copyMatrixToDevice(DenseMatrix matrix) {
    long offset = 0;
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      float[] values = matrix.chunk(chunk);
      FloatPointer host = new FloatPointer(values);
      check(
          cudaMemcpy(
              new FloatPointer(deviceMatrix).position(offset),
              host,
              (long) values.length * Float.BYTES,
              cudaMemcpyHostToDevice),
          "copy a chunk");
      host.deallocate();
      offset += values.length;
    }
  }

  private void copyRowUniValuesToDevice(MatrixRows rows) {
    org.bytedeco.javacpp.DoublePointer host =
        new org.bytedeco.javacpp.DoublePointer(rows.getRowUniValues());
    check(
        cudaMemcpy(
            deviceRowUniValues, host, (long) numRows * Double.BYTES, cudaMemcpyHostToDevice),
        "copy the unilateral values");
    host.deallocate();
  }

  private void compileReduction() {
    _nvrtcProgram program = new _nvrtcProgram();
    check(
        nvrtcCreateProgram(
            program,
            new BytePointer(REDUCTION_SOURCE),
            new BytePointer("keepBestRows.cu"),
            0,
            (PointerPointer<Pointer>) null,
            (PointerPointer<Pointer>) null),
        "create the reduction");
    check(nvrtcCompileProgram(program, 0, (PointerPointer<Pointer>) null), "compile the reduction");
    SizeTPointer size = new SizeTPointer(1);
    check(nvrtcGetPTXSize(program, size), "size the reduction");
    BytePointer ptx = new BytePointer(size.get());
    check(nvrtcGetPTX(program, ptx), "read the reduction");
    CUmod_st module = new CUmod_st();
    check(cuModuleLoadData(module, ptx), "load the reduction");
    check(cuModuleGetFunction(reduction, module, new BytePointer("keepBestRows")), "find it");
  }

  private static void allocate(Pointer pointer, long numBytes) {
    check(cudaMalloc(pointer, numBytes), "allocate " + numBytes + " bytes");
  }

  private static void check(int status, String what) {
    if (status != 0) {
      throw new IllegalStateException("The device failed to " + what + ", status " + status + ".");
    }
  }
}
