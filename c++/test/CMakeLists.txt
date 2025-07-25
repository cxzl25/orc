# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set (CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${CXX17_FLAGS} ${WARN_FLAGS}")

add_library (orc-test-include INTERFACE)
target_include_directories (orc-test-include INTERFACE
  ${PROJECT_BINARY_DIR}/c++/include
  ${PROJECT_BINARY_DIR}/c++/src
  ${PROJECT_SOURCE_DIR}/c++/src
)

if(BUILD_ENABLE_AVX512)
  set(SIMD_TEST_SRCS TestRleVectorDecoder.cc)
endif(BUILD_ENABLE_AVX512)

add_executable (orc-test
  MemoryInputStream.cc
  MemoryOutputStream.cc
  MockStripeStreams.cc
  TestAttributes.cc
  TestBlockBuffer.cc
  TestBufferedOutputStream.cc
  TestBloomFilter.cc
  TestByteRle.cc
  TestByteRLEEncoder.cc
  TestColumnPrinter.cc
  TestColumnReader.cc
  TestColumnStatistics.cc
  TestCompression.cc
  TestConvertColumnReader.cc
  TestDecompression.cc
  TestDecimal.cc
  TestDictionaryEncoding.cc
  TestDriver.cc
  TestInt128.cc
  TestMurmur3.cc
  TestPredicateLeaf.cc
  TestPredicatePushdown.cc
  TestReader.cc
  TestRleDecoder.cc
  TestRleEncoder.cc
  TestRLEV2Util.cc
  TestSargsApplier.cc
  TestStatistics.cc
  TestSearchArgument.cc
  TestSchemaEvolution.cc
  TestStripeIndexStatistics.cc
  TestTimestampStatistics.cc
  TestTimezone.cc
  TestType.cc
  TestUtil.cc
  TestWriter.cc
  TestCache.cc
  ${SIMD_TEST_SRCS}
)

target_link_libraries (orc-test
  orc
  orc::lz4
  orc::protobuf
  orc::snappy
  orc::zlib
  orc::gtest
  orc::gmock
  orc-test-include
)

add_executable (create-test-files
  CreateTestFiles.cc
)

target_link_libraries (create-test-files
  orc
  orc::protobuf
  orc-test-include
)

if (TEST_VALGRIND_MEMCHECK)
  add_test (orc-test
          valgrind --tool=memcheck --leak-check=full --error-exitcode=1 ./orc-test)
else ()
  add_test (NAME orc-test COMMAND orc-test)
endif ()

set_property(TEST orc-test
    PROPERTY
      ENVIRONMENT "ORC_EXAMPLE_DIR=${EXAMPLE_DIRECTORY}"
    )

if (WIN32)
  set_property(
    TEST orc-test
    APPEND PROPERTY
      ENVIRONMENT "TZDIR=${TZDATA_DIR}"
    )
endif ()
