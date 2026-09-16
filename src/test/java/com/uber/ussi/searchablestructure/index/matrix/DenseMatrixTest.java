package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DenseMatrixTest {
  private static final float DELTA = 1e-6f;

  @Test
  void holdsASmallMatrixInOneChunk() {
    DenseMatrix matrix = DenseMatrix.allocate(3, 2, /* maxChunkValues */ 1024);

    assertEquals(1, matrix.numChunks());
    assertEquals(3, matrix.numRows());
    assertEquals(2, matrix.dimension());
    assertEquals(3, matrix.numRowsInChunk(0));
    assertEquals(0, matrix.firstRowInChunk(0));
  }

  /** A chunk holds a whole number of rows, so a row is never split across two of them. */
  @Test
  void splitsOnRowBoundaries() {
    // Room for two rows per chunk, with a fifth row left over.
    DenseMatrix matrix = DenseMatrix.allocate(5, 3, /* maxChunkValues */ 7);

    assertEquals(3, matrix.numChunks());
    assertEquals(2, matrix.numRowsInChunk(0));
    assertEquals(2, matrix.numRowsInChunk(1));
    assertEquals(1, matrix.numRowsInChunk(2), "the last chunk holds the remainder");
    assertEquals(0, matrix.firstRowInChunk(0));
    assertEquals(2, matrix.firstRowInChunk(1));
    assertEquals(4, matrix.firstRowInChunk(2));
    for (int chunk = 0; chunk < matrix.numChunks(); chunk++) {
      assertEquals(
          matrix.numRowsInChunk(chunk) * 3,
          matrix.chunk(chunk).length,
          "chunk " + chunk + " holds exactly its rows");
    }
  }

  @Test
  void readsBackEveryValueAcrossChunks() {
    DenseMatrix matrix = DenseMatrix.allocate(5, 3, /* maxChunkValues */ 7);
    for (int row = 0; row < 5; row++) {
      matrix.setRow(row, new float[] {row * 10f, row * 10f + 1f, row * 10f + 2f});
    }

    for (int row = 0; row < 5; row++) {
      for (int column = 0; column < 3; column++) {
        assertEquals(row * 10f + column, matrix.valueAt(row, column), DELTA);
      }
    }
  }

  @Test
  void chunkContentsAreRowMajorSoABulkMultiplyCanUseThemDirectly() {
    DenseMatrix matrix = DenseMatrix.allocate(4, 2, /* maxChunkValues */ 4);
    for (int row = 0; row < 4; row++) {
      matrix.setRow(row, new float[] {row, -row});
    }

    // Second chunk holds rows 2 and 3, laid out end to end.
    assertEquals(2, matrix.numChunks());
    float[] second = matrix.chunk(1);
    assertEquals(2f, second[0], DELTA);
    assertEquals(-2f, second[1], DELTA);
    assertEquals(3f, second[2], DELTA);
    assertEquals(-3f, second[3], DELTA);
  }

  @Test
  void anEmptyMatrixHasNoChunks() {
    assertEquals(0, DenseMatrix.allocate(0, 0, 1024).numChunks());
    assertEquals(0, DenseMatrix.allocate(0, 4, 1024).numChunks());
    assertEquals(0, DenseMatrix.allocate(4, 0, 1024).numChunks(), "no values to hold");
  }

  @Test
  void rejectsARowTooWideForAChunk() {
    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> DenseMatrix.allocate(1, 9, 8));

    assertTrue(thrown.getMessage().contains("does not fit"), thrown.getMessage());
  }

  @Test
  void rejectsNonsenseGeometry() {
    assertThrows(IllegalArgumentException.class, () -> DenseMatrix.allocate(-1, 2, 1024));
    assertThrows(IllegalArgumentException.class, () -> DenseMatrix.allocate(2, -1, 1024));
    assertThrows(IllegalArgumentException.class, () -> DenseMatrix.allocate(2, 2, 0));
  }

  @Test
  void rejectsARowOfTheWrongWidth() {
    DenseMatrix matrix = DenseMatrix.allocate(2, 3, 1024);

    assertThrows(IllegalArgumentException.class, () -> matrix.setRow(0, new float[] {1f, 2f}));
  }

  /** The default is sized so that the largest chunk is still an allocatable array. */
  @Test
  void theDefaultChunkSizeIsBelowTheArrayLimit() {
    assertTrue(DenseMatrix.DEFAULT_MAX_CHUNK_VALUES < Integer.MAX_VALUE);
    assertTrue(DenseMatrix.DEFAULT_MAX_CHUNK_VALUES > Integer.MAX_VALUE - 4096);
  }

  /**
   * The geometry that used to be refused: more values than one array can hold. Only the arithmetic
   * is checked, since allocating it would need tens of gigabytes.
   */
  @Test
  void spansAMatrixLargerThanOneArrayCanHold() {
    int dimension = 4096;
    int numRows = 1_000_000;
    long values = (long) numRows * dimension;
    assertTrue(values > Integer.MAX_VALUE, "the point of the test is a matrix past the limit");

    int rowsPerChunk = DenseMatrix.DEFAULT_MAX_CHUNK_VALUES / dimension;
    int numChunks = (numRows + rowsPerChunk - 1) / rowsPerChunk;

    assertTrue(numChunks > 1, "such a matrix needs more than one chunk");
    assertTrue(
        (long) rowsPerChunk * dimension <= DenseMatrix.DEFAULT_MAX_CHUNK_VALUES,
        "each chunk stays allocatable");
  }
}
