/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.spinach

import org.apache.hadoop.fs.Path

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.SimpleCatalogRelation
import org.apache.spark.sql.catalyst.expressions.{Ascending, Attribute, Descending}
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, SubqueryAlias}
import org.apache.spark.sql.execution.command.CreateDataSourceTableUtils.DATASOURCE_PROVIDER
import org.apache.spark.sql.execution.command.{DDLUtils, RunnableCommand}

/**
 * Creates an index for table on indexColumns
 */
case class CreateIndex(
    indexName: String,
    tableName: TableIdentifier,
    indexColumns: Array[IndexColumn],
    allowExists: Boolean) extends RunnableCommand with Logging {
  override def children: Seq[LogicalPlan] = Seq.empty

  override val output: Seq[Attribute] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog
    assert(catalog.tableExists(tableName), s"$tableName not exists")
    catalog.lookupRelation(tableName) match {
      case SubqueryAlias(_, r: SimpleCatalogRelation)
        if DDLUtils.isDatasourceTable(r.metadata) &&
          r.metadata.properties(DATASOURCE_PROVIDER) == SpinachFileFormat.defaultName =>
        logInfo(s"Creating index $indexName")
        val meta = SpinachFileFormat.deserializeDataSourceMeta(
          sparkSession.sparkContext.hadoopConfiguration)
        val path = r.metadata.properties("path")
        meta match {
          case Some(oldMeta) =>
            val existsIndexes = oldMeta.indexMetas
            val exist = existsIndexes.exists(_.name == indexName)
            if (!allowExists) assert(!exist)
            if (exist) {
              log.warn(s"dup index name $indexName")
            }
            val metaBuilder = DataSourceMeta.newBuilder()
            oldMeta.fileMetas.foreach(metaBuilder.addFileMeta)
            existsIndexes.foreach(metaBuilder.addIndexMeta)
            val entries = indexColumns.map(c => {
              val dir = if (c.isAscending) Ascending else Descending
              BTreeIndexEntry(schema.map(_.name).toIndexedSeq.indexOf(c.columnName), dir)
            })
            metaBuilder.addIndexMeta(new IndexMeta(indexName, BTreeIndex(entries)))

            // TODO _metaPaths can be empty while in an empty spinach data source folder
            DataSourceMeta.write(
              new Path(path),
              sparkSession.sparkContext.hadoopConfiguration,
              metaBuilder.withNewSchema(oldMeta.schema).build(),
              deleteIfExits = true)
            SpinachIndexBuild(sparkSession, indexName, indexColumns, schema, Array(path)).execute()
          case None =>
            sys.error("meta cannot be empty during the index building")
        }
      case _ => sys.error("Only support CreateIndex for SpinachRelation")
    }
    Seq.empty
  }
}

/**
 * Drops an index
 */
case class DropIndex(
    indexName: String,
    tableIdentifier: TableIdentifier,
    allowNotExists: Boolean) extends RunnableCommand {

  override def children: Seq[LogicalPlan] = Seq.empty

  override val output: Seq[Attribute] = Seq.empty

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val catalog = sparkSession.sessionState.catalog
    catalog.lookupRelation(tableIdentifier) match {
      case SubqueryAlias(_, r: SimpleCatalogRelation)
        if DDLUtils.isDatasourceTable(r.metadata) &&
          r.metadata.properties(DATASOURCE_PROVIDER) == SpinachFileFormat.defaultName =>
        logInfo(s"Dropping index $indexName")
        val meta = SpinachFileFormat.deserializeDataSourceMeta(
          sparkSession.sparkContext.hadoopConfiguration)
        val p = r.metadata.properties("path")
        assert(meta.nonEmpty)
        val oldMeta = meta.get
        val existsIndexes = oldMeta.indexMetas
        val exist = existsIndexes.exists(_.name == indexName)
        if (!allowNotExists) assert(exist, "IndexMeta not found in SpinachMeta")
        if (!exist) {
          logWarning(s"drop non-exists index $indexName")
        }
        val metaBuilder = DataSourceMeta.newBuilder()
        oldMeta.fileMetas.foreach(metaBuilder.addFileMeta)
        existsIndexes.filter(_.name != indexName).foreach(metaBuilder.addIndexMeta)

        val path = new Path(p)
        val fs = path.getFileSystem(sparkSession.sparkContext.hadoopConfiguration)
        val allFile = fs.listFiles(path, true)
        val filePaths = new Iterator[Path] {
          override def hasNext: Boolean = allFile.hasNext
          override def next(): Path = allFile.next().getPath
        }.toSeq
        filePaths.filter(_.toString.endsWith(
          "." + indexName + SpinachFileFormat.SPINACH_INDEX_EXTENSION)).foreach(fs.delete(_, true))

        DataSourceMeta.write(
          path,
          sparkSession.sparkContext.hadoopConfiguration,
          metaBuilder.withNewSchema(oldMeta.schema).build(),
          deleteIfExits = true)
      case _ => sys.error("Only support DropIndex for SpinachRelation")
    }
    Seq.empty
  }
}
