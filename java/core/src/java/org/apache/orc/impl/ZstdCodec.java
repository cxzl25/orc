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

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import org.apache.orc.CompressionCodec;
import org.apache.orc.CompressionKind;

import java.io.IOException;
import java.nio.ByteBuffer;

public class ZstdCodec implements CompressionCodec {
  private ZstdOptions zstdOptions = null;
  private ZstdCompressCtx zstdCompressCtx = null;

  public ZstdCodec(int level, int windowLog, boolean fixed) {
    this.zstdOptions = new ZstdOptions(level, windowLog, fixed);
  }

  public ZstdCodec(int level, int windowLog) {
    this(level, windowLog, false);
  }

  public ZstdCodec() {
    this(3, 0);
  }

  public ZstdOptions getZstdOptions() {
    return zstdOptions;
  }

  // Thread local buffer
  private static final ThreadLocal<byte[]> threadBuffer =
          ThreadLocal.withInitial(() -> null);

  protected static byte[] getBuffer(int size) {
    byte[] result = threadBuffer.get();
    if (result == null || result.length < size || result.length > size * 2) {
      result = new byte[size];
      threadBuffer.set(result);
    }
    return result;
  }

  static class ZstdOptions implements Options {
    private int level;
    private int windowLog;
    private final boolean FIXED;

    ZstdOptions(int level, int windowLog, boolean FIXED) {
      this.level = level;
      this.windowLog = windowLog;
      this.FIXED = FIXED;
    }

    @Override
    public ZstdOptions copy() {
      return new ZstdOptions(level, windowLog, FIXED);
    }

    /**
     * Sets the Zstandard long mode maximum back-reference distance, expressed
     * as a power of 2.
     * <p>
     * The value must be between ZSTD_WINDOWLOG_MIN (10) and ZSTD_WINDOWLOG_MAX
     * (30 and 31 on 32/64-bit architectures, respectively).
     * <p>
     * A value of 0 is a special value indicating to use the default
     * ZSTD_WINDOWLOG_LIMIT_DEFAULT of 27, which corresponds to back-reference
     * window size of 128MiB.
     *
     * @param newValue The desired power-of-2 value back-reference distance.
     * @return ZstdOptions
     */
    public ZstdOptions setWindowLog(int newValue) {
      if ((newValue < Zstd.windowLogMin() || newValue > Zstd.windowLogMax()) && newValue != 0) {
        throw new IllegalArgumentException(
            String.format(
                "Zstd compression window size should be in the range %d to %d,"
                    + " or set to the default value of 0.",
                Zstd.windowLogMin(),
                Zstd.windowLogMax()));
      }
      windowLog = newValue;
      return this;
    }

    /**
     * Sets the Zstandard compression codec compression level directly using
     * the integer setting. This value is typically between 0 and 22, with
     * larger numbers indicating more aggressive compression and lower speed.
     * <p>
     * This method provides additional granularity beyond the setSpeed method
     * so that users can select a specific level.
     *
     * @param newValue The level value of compression to set.
     * @return ZstdOptions
     */
    public ZstdOptions setLevel(int newValue) {
      if (newValue < Zstd.minCompressionLevel() || newValue > Zstd.maxCompressionLevel()) {
        throw new IllegalArgumentException(
            String.format(
                "Zstd compression level should be in the range %d to %d",
                Zstd.minCompressionLevel(),
                Zstd.maxCompressionLevel()));
      }
      level = newValue;
      return this;
    }

    /**
     * Sets the Zstandard compression codec compression level via the Enum
     * (FASTEST, FAST, DEFAULT). The default value of 3 is the
     * ZSTD_CLEVEL_DEFAULT level.
     * <p>
     * Alternatively, the compression level can be set directly with setLevel.
     *
     * @param newValue An Enum specifying how aggressively to compress.
     * @return ZstdOptions
     */
    @Override
    public ZstdOptions setSpeed(SpeedModifier newValue) {
      if (FIXED) {
        throw new IllegalStateException(
            "Attempt to modify the default options");
      }
      switch (newValue) {
        case FAST:
          setLevel(2);
          break;
        case DEFAULT:
          // zstd level 3 achieves good ratio/speed tradeoffs, and is the
          // ZSTD_CLEVEL_DEFAULT level.
          setLevel(3);
          break;
        case FASTEST:
          // zstd level 1 is the fastest level.
          setLevel(1);
          break;
        default:
          break;
      }
      return this;
    }

    @Override
    public ZstdOptions setData(DataKind newValue) {
      return this; // We don't support setting DataKind in ZstdCodec.
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ZstdOptions that = (ZstdOptions) o;

      if (level != that.level) return false;
      if (windowLog != that.windowLog) return false;
      return FIXED == that.FIXED;
    }

    @Override
    public int hashCode() {
      int result = level;
      result = 31 * result + windowLog;
      result = 31 * result + (FIXED ? 1 : 0);
      return result;
    }
  }

  private static final ZstdOptions DEFAULT_OPTIONS =
      new ZstdOptions(3, 0, false);

  @Override
  public Options getDefaultOptions() {
    return DEFAULT_OPTIONS;
  }

  /**
   * Compresses an input ByteBuffer into an output ByteBuffer using Zstandard
   * compression. If the maximum bound of the number of output bytes exceeds
   * the output ByteBuffer size, the remaining bytes are written to the overflow
   * ByteBuffer.
   *
   * @param in       the bytes to compress
   * @param out      the compressed bytes
   * @param overflow put any additional bytes here
   * @param options  the options to control compression
   * @return ZstdOptions
   */
  @Override
  public boolean compress(ByteBuffer in, ByteBuffer out,
      ByteBuffer overflow,
      Options options) throws IOException {
    ZstdOptions zso = (ZstdOptions) options;

    zstdCompressCtx = new ZstdCompressCtx();
    zstdCompressCtx.setLevel(zso.level);
    zstdCompressCtx.setLong(zso.windowLog);
    zstdCompressCtx.setChecksum(false);

    try {
      int inBytes = in.remaining();
      byte[] compressed = getBuffer((int) Zstd.compressBound(inBytes));

      int outBytes = zstdCompressCtx.compressByteArray(compressed, 0, compressed.length,
              in.array(), in.arrayOffset() + in.position(), inBytes);
      if (Zstd.isError(outBytes)) {
        throw new IOException(String.format("Error code %s!", outBytes));
      }
      if (outBytes < inBytes) {
        int remaining = out.remaining();
        if (remaining >= outBytes) {
          System.arraycopy(compressed, 0, out.array(), out.arrayOffset() +
                  out.position(), outBytes);
          out.position(out.position() + outBytes);
        } else {
          System.arraycopy(compressed, 0, out.array(), out.arrayOffset() +
                  out.position(), remaining);
          out.position(out.limit());
          System.arraycopy(compressed, remaining, overflow.array(),
                  overflow.arrayOffset(), outBytes - remaining);
          overflow.position(outBytes - remaining);
        }
        return true;
      } else {
        return false;
      }
    } finally {
      zstdCompressCtx.close();
    }
  }

  @Override
  public void decompress(ByteBuffer in, ByteBuffer out) throws IOException {
    int srcOffset = in.arrayOffset() + in.position();
    int srcSize = in.remaining();
    int dstOffset = out.arrayOffset() + out.position();
    int dstSize = out.remaining() - dstOffset;

    long decompressOut =
        Zstd.decompressByteArray(out.array(), dstOffset, dstSize, in.array(),
            srcOffset, srcSize);
    if (Zstd.isError(decompressOut)) {
      throw new IOException(String.format("Error code %s!", decompressOut));
    }
    in.position(in.limit());
    out.position(dstOffset + (int) decompressOut);
    out.flip();
  }

  @Override
  public void reset() {

  }

  @Override
  public void destroy() {
    if (zstdCompressCtx != null) {
      zstdCompressCtx.close();
    }
  }

  @Override
  public CompressionKind getKind() {
    return CompressionKind.ZSTD;
  }

  @Override
  public void close() {
    OrcCodecPool.returnCodec(CompressionKind.ZSTD, this);
  }
}