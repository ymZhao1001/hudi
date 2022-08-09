/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileStatus
import org.apache.hudi.client.utils.SparkInternalSchemaConverter
import org.apache.hudi.common.util.StringUtils.isNullOrEmpty
import org.apache.hudi.internal.schema.InternalSchema
import org.apache.hudi.internal.schema.utils.SerDeHelper
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.PredicateHelper
import org.apache.spark.sql.execution.datasources.PartitionedFile
import org.apache.spark.sql.execution.datasources.orc.OrcFileFormat
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.vectorized.ColumnarBatch

import scala.collection.JavaConverters._

object HoodieDataSourceHelper extends PredicateHelper with SparkAdapterSupport {


  /**
   * Wrapper for `buildReaderWithPartitionValues` of [[ParquetFileFormat]] handling [[ColumnarBatch]],
   * when Parquet's Vectorized Reader is used
   */
  def buildHoodieParquetReader(sparkSession: SparkSession,
                               dataSchema: StructType,
                               partitionSchema: StructType,
                               requiredSchema: StructType,
                               filters: Seq[Filter],
                               options: Map[String, String],
                               hadoopConf: Configuration,
                               appendPartitionValues: Boolean = false): PartitionedFile => Iterator[InternalRow] = {
    val parquetFileFormat: ParquetFileFormat = sparkAdapter.createHoodieParquetFileFormat(appendPartitionValues).get
    val readParquetFile: PartitionedFile => Iterator[Any] = parquetFileFormat.buildReaderWithPartitionValues(
      sparkSession = sparkSession,
      dataSchema = dataSchema,
      partitionSchema = partitionSchema,
      requiredSchema = requiredSchema,
      filters = filters,
      options = options,
      hadoopConf = hadoopConf
    )

    file: PartitionedFile => {
      val iter = readParquetFile(file)
      iter.flatMap {
        case r: InternalRow => Seq(r)
        case b: ColumnarBatch => b.rowIterator().asScala
      }
    }
  }

  /**
   * Wrapper `buildReaderWithPartitionValues` of [[OrcFileFormat]]
   * to deal with [[ColumnarBatch]] when enable orc vectorized reader if necessary.
   */
  def buildHoodieOrcReader(sparkSession: SparkSession,
                           dataSchema: StructType,
                           partitionSchema: StructType,
                           requiredSchema: StructType,
                           filters: Seq[Filter],
                           options: Map[String, String],
                           hadoopConf: Configuration): PartitionedFile => Iterator[InternalRow] = {

    val readOrcFile: PartitionedFile => Iterator[Any] = new OrcFileFormat().buildReaderWithPartitionValues(
      sparkSession = sparkSession,
      dataSchema = dataSchema,
      partitionSchema = partitionSchema,
      requiredSchema = requiredSchema,
      filters = filters,
      options = options,
      hadoopConf = hadoopConf
    )

    file: PartitionedFile => {
      val iter = readOrcFile(file)
      iter.flatMap {
        case r: InternalRow => Seq(r)
        case b: ColumnarBatch => b.rowIterator().asScala
      }
    }
  }

  def splitFiles(
                  sparkSession: SparkSession,
                  file: FileStatus,
                  partitionValues: InternalRow): Seq[PartitionedFile] = {
    val filePath = file.getPath
    val maxSplitBytes = sparkSession.sessionState.conf.filesMaxPartitionBytes
    (0L until file.getLen by maxSplitBytes).map { offset =>
      val remaining = file.getLen - offset
      val size = if (remaining > maxSplitBytes) maxSplitBytes else remaining
      PartitionedFile(partitionValues, filePath.toUri.toString, offset, size)
    }
  }

  /**
   * Set internalSchema evolution parameters to configuration.
   * spark will broadcast them to each executor, we use those parameters to do schema evolution.
   *
   * @param conf           hadoop conf.
   * @param internalSchema internalschema for query.
   * @param tablePath      hoodie table base path.
   * @param validCommits   valid commits, using give validCommits to validate all legal histroy Schema files, and return the latest one.
   */
  def getConfigurationWithInternalSchema(conf: Configuration, internalSchema: InternalSchema, tablePath: String, validCommits: String): Configuration = {
    val querySchemaString = SerDeHelper.toJson(internalSchema)
    if (!isNullOrEmpty(querySchemaString)) {
      conf.set(SparkInternalSchemaConverter.HOODIE_QUERY_SCHEMA, SerDeHelper.toJson(internalSchema))
      conf.set(SparkInternalSchemaConverter.HOODIE_TABLE_PATH, tablePath)
      conf.set(SparkInternalSchemaConverter.HOODIE_VALID_COMMITS_LIST, validCommits)
    }
    conf
  }
}
