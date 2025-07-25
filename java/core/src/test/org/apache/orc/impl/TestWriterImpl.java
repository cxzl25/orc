/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.orc.impl;


import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.LongColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;
import org.apache.orc.OrcConf;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.TypeDescription;
import org.apache.orc.Writer;
import org.apache.orc.*;
import org.apache.orc.geospatial.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class TestWriterImpl implements TestConf {

  Path workDir = new Path(System.getProperty("test.tmp.dir"));
  FileSystem fs;
  Path testFilePath;
  TypeDescription schema;

  @BeforeEach
  public void openFileSystem() throws Exception {
    fs = FileSystem.getLocal(conf);
    testFilePath = new Path(workDir, "testWriterImpl.orc");
    fs.create(testFilePath, true);
    schema = TypeDescription.fromString("struct<x:int,y:int>");
  }

  @AfterEach
  public void deleteTestFile() throws Exception {
    fs.delete(testFilePath, false);
  }

  @Test
  public void testDefaultOverwriteFlagForWriter() throws Exception {
    assertThrows(IOException.class, () -> {
      // default value of the overwrite flag is false, so this should fail
      Writer w = OrcFile.createWriter(testFilePath, OrcFile.writerOptions(conf).setSchema(schema));
      w.close();
    });
  }

  @Test
  public void testOverriddenOverwriteFlagForWriter() throws Exception {
    // overriding the flag should result in a successful write (no exception)
    conf.set(OrcConf.OVERWRITE_OUTPUT_FILE.getAttribute(), "true");
    Writer w = OrcFile.createWriter(testFilePath, OrcFile.writerOptions(conf).setSchema(schema));
    w.close();

    // We should have no stripes available
    assertEquals(0, w.getStripes().size());
  }

  @Test
  public void testNoBFIfNoIndex() throws Exception {
    // overriding the flag should result in a successful write (no exception)
    conf.set(OrcConf.OVERWRITE_OUTPUT_FILE.getAttribute(), "true");
    // Enable bloomfilter, but disable index
    conf.set(OrcConf.ROW_INDEX_STRIDE.getAttribute(), "0");
    conf.set(OrcConf.BLOOM_FILTER_COLUMNS.getAttribute(), "*");
    Writer w = OrcFile.createWriter(testFilePath, OrcFile.writerOptions(conf).setSchema(schema));
    w.close();
  }

  @Test
  public void testNoIndexIfEnableIndexIsFalse() throws Exception {
    conf.set(OrcConf.OVERWRITE_OUTPUT_FILE.getAttribute(), "true");
    conf.set(OrcConf.ROW_INDEX_STRIDE.getAttribute(), "0");
    conf.setBoolean(OrcConf.ENABLE_INDEXES.getAttribute(), false);
    VectorizedRowBatch b = schema.createRowBatch();
    LongColumnVector f1 = (LongColumnVector) b.cols[0];
    LongColumnVector f2 = (LongColumnVector) b.cols[1];
    Writer w = OrcFile.createWriter(testFilePath, OrcFile.writerOptions(conf).setSchema(schema));
    long rowCount = 1000;
    for (int i = 0; i < rowCount; i++) {
      f1.vector[b.size] = 1 ;
      f2.vector[b.size] = 2 ;
      b.size += 1;
      if (b.size == 10) {
        w.addRowBatch(b);
        b.reset();
      }
    }
    w.close();

    for (StripeInformation information: w.getStripes()) {
      assertEquals(0, information.getIndexLength());
    }
  }

  @Test
  public void testEnableDisableIndex() {
    conf.set(OrcConf.ROW_INDEX_STRIDE.getAttribute(), "10000");
    OrcFile.WriterOptions writerOptions = OrcFile.writerOptions(conf);
    writerOptions.buildIndex(false);
    assertEquals(writerOptions.getRowIndexStride(), 0);

    conf.set(OrcConf.ENABLE_INDEXES.getAttribute(), "true");
    OrcFile.WriterOptions writerOptions2 = OrcFile.writerOptions(conf);
    writerOptions2.rowIndexStride(0);
    assertFalse(writerOptions2.isBuildIndex());
  }

  @Test
  public void testStripes() throws Exception {
    conf.set(OrcConf.OVERWRITE_OUTPUT_FILE.getAttribute(), "true");
    VectorizedRowBatch b = schema.createRowBatch();
    LongColumnVector f1 = (LongColumnVector) b.cols[0];
    LongColumnVector f2 = (LongColumnVector) b.cols[1];
    Writer w = OrcFile.createWriter(testFilePath, OrcFile.writerOptions(conf).setSchema(schema));
    long value = 0;
    long rowCount = 1024;
    while (value < rowCount) {
      f1.vector[b.size] = Long.MIN_VALUE + value;
      f2.vector[b.size] = Long.MAX_VALUE - value;
      value += 1;
      b.size += 1;
      if (b.size == b.getMaxSize()) {
        w.addRowBatch(b);
        b.reset();
      }
    }
    assertEquals(0, w.getStripes().size());
    w.close();
    assertEquals(1, w.getStripes().size());
    assertEquals(rowCount, w.getNumberOfRows());
    Reader r = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf));
    assertEquals(r.getStripes(), w.getStripes());
  }

  @Test
  public void testStripeRowCountLimit() throws Exception {
    conf.set(OrcConf.OVERWRITE_OUTPUT_FILE.getAttribute(), "true");
    conf.set(OrcConf.STRIPE_ROW_COUNT.getAttribute(),"100");
    VectorizedRowBatch b = schema.createRowBatch();
    LongColumnVector f1 = (LongColumnVector) b.cols[0];
    LongColumnVector f2 = (LongColumnVector) b.cols[1];
    Writer w = OrcFile.createWriter(testFilePath, OrcFile.writerOptions(conf).setSchema(schema));
    long rowCount = 1000;
    for (int i = 0; i < rowCount; i++) {
      f1.vector[b.size] = Long.MIN_VALUE ;
      f2.vector[b.size] = Long.MAX_VALUE ;
      b.size += 1;
      if (b.size == 10) {
        w.addRowBatch(b);
        b.reset();
      }
    }
    w.close();
    assertEquals(10, w.getStripes().size());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testGeospatialColumnStatistics(boolean useFilter) throws IOException, ParseException {
    conf.set(OrcConf.OVERWRITE_OUTPUT_FILE.getAttribute(), "true");
    // Use the Geometry type
    schema = TypeDescription.createGeometry();
    Writer writer = OrcFile.createWriter(testFilePath, OrcFile.writerOptions(conf).setSchema(schema));
    VectorizedRowBatch batch = schema.createRowBatch();
    BytesColumnVector geomColumn = (BytesColumnVector) batch.cols[0];

    WKTReader wktReader = new WKTReader();
    WKBWriter wkbWriter = new WKBWriter();
    byte[] point1 = wkbWriter.write(wktReader.read("POINT (1 2)"));
    byte[] point2 = wkbWriter.write(wktReader.read("POINT (3 4)"));
    byte[] point3 = wkbWriter.write(wktReader.read("POINT (5 6)"));
    byte[] point4 = wkbWriter.write(wktReader.read("POINT (7 8)"));

    geomColumn.setVal(0, point1);
    geomColumn.setVal(1, point2);
    geomColumn.setVal(2, point3);
    geomColumn.setVal(3, point4);

    if (useFilter) {
      int[] selected = {2};
      batch.setFilterContext(true, selected, selected.length);
    } else {
      batch.size = 4;
    }
    writer.addRowBatch(batch);
    writer.close();

    Reader reader = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf));
    ColumnStatistics[] statistics = reader.getStatistics();
    GeospatialColumnStatistics geometryStatistics = (GeospatialColumnStatistics) statistics[0];
    BoundingBox bbox = geometryStatistics.getBoundingBox();
    if (useFilter) {
      assertEquals(5.0, bbox.getXMin());
      assertEquals(5.0, bbox.getXMax());
      assertEquals(6.0, bbox.getYMin());
      assertEquals(6.0, bbox.getYMax());
    } else {
      assertEquals(1.0, bbox.getXMin());
      assertEquals(7.0, bbox.getXMax());
      assertEquals(2.0, bbox.getYMin());
      assertEquals(8.0, bbox.getYMax());
    }
    assertEquals(Double.NaN, bbox.getZMin());
    assertEquals(Double.NaN, bbox.getZMax());
    assertEquals(Double.NaN, bbox.getMMin());
    assertEquals(Double.NaN, bbox.getMMax());
    reader.close();
  }

  @Test
  public void testCloseIsIdempotent() throws IOException {
    conf.set(OrcConf.OVERWRITE_OUTPUT_FILE.getAttribute(), "true");
    Writer w = OrcFile.createWriter(testFilePath, OrcFile.writerOptions(conf).setSchema(schema));
    w.close();
    w.close();
  }
}
