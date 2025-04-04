/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.format.cow.vector;

import org.apache.flink.table.data.MapData;
import org.apache.flink.table.data.columnar.vector.MapColumnVector;
import org.apache.flink.table.data.columnar.vector.heap.AbstractHeapVector;
import org.apache.flink.table.data.columnar.vector.writable.WritableColumnVector;

/**
 * This class represents a nullable heap map column vector.
 */
public class HeapMapColumnVector extends AbstractHeapVector
    implements WritableColumnVector, MapColumnVector {

  private final WritableColumnVector keys;
  private final WritableColumnVector values;

  public HeapMapColumnVector(int len, WritableColumnVector keys, WritableColumnVector values) {
    super(len);
    this.keys = keys;
    this.values = values;
  }

  public WritableColumnVector getKeys() {
    return keys;
  }

  public WritableColumnVector getValues() {
    return values;
  }

  @Override
  public MapData getMap(int rowId) {
    return new ColumnarGroupMapData(keys, values, rowId);
  }
}
