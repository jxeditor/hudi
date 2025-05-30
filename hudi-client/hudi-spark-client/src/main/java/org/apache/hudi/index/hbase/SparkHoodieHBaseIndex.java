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

package org.apache.hudi.index.hbase;

import org.apache.hudi.client.WriteStatus;
import org.apache.hudi.client.common.HoodieSparkEngineContext;
import org.apache.hudi.common.data.HoodieData;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.model.EmptyHoodieRecordPayload;
import org.apache.hudi.common.model.HoodieAvroRecord;
import org.apache.hudi.common.model.HoodieKey;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordDelegate;
import org.apache.hudi.common.model.HoodieRecordLocation;
import org.apache.hudi.common.model.HoodieRecordPayload;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.TimelineUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.RateLimiter;
import org.apache.hudi.common.util.ReflectionUtils;
import org.apache.hudi.config.HoodieHBaseIndexConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.data.HoodieJavaRDD;
import org.apache.hudi.exception.HoodieDependentSystemUnavailableException;
import org.apache.hudi.exception.HoodieIndexException;
import org.apache.hudi.index.HoodieIndex;
import org.apache.hudi.index.HoodieIndexUtils;
import org.apache.hudi.table.HoodieTable;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.spark.Partitioner;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkFiles;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.storage.StorageLevel;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import scala.Tuple2;

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION;
import static org.apache.hadoop.hbase.HConstants.ZOOKEEPER_CLIENT_PORT;
import static org.apache.hadoop.hbase.HConstants.ZOOKEEPER_QUORUM;
import static org.apache.hadoop.hbase.HConstants.ZOOKEEPER_ZNODE_PARENT;
import static org.apache.hadoop.hbase.security.SecurityConstants.MASTER_KRB_PRINCIPAL;
import static org.apache.hadoop.hbase.security.SecurityConstants.REGIONSERVER_KRB_PRINCIPAL;
import static org.apache.hadoop.hbase.security.User.HBASE_SECURITY_AUTHORIZATION_CONF_KEY;
import static org.apache.hadoop.hbase.security.User.HBASE_SECURITY_CONF_KEY;
import static org.apache.hudi.common.util.StringUtils.fromUTF8Bytes;
import static org.apache.hudi.common.util.StringUtils.getUTF8Bytes;
import static org.apache.hudi.config.HoodieWriteConfig.WRITE_STATUS_STORAGE_LEVEL_VALUE;

/**
 * Hoodie Index implementation backed by HBase.
 */
public class SparkHoodieHBaseIndex extends HoodieIndex<Object, Object> {

  public static final String DEFAULT_SPARK_EXECUTOR_INSTANCES_CONFIG_NAME = "spark.executor.instances";
  public static final String DEFAULT_SPARK_DYNAMIC_ALLOCATION_ENABLED_CONFIG_NAME = "spark.dynamicAllocation.enabled";
  public static final String DEFAULT_SPARK_DYNAMIC_ALLOCATION_MAX_EXECUTORS_CONFIG_NAME =
      "spark.dynamicAllocation.maxExecutors";

  private static final byte[] SYSTEM_COLUMN_FAMILY = getUTF8Bytes("_s");
  private static final byte[] COMMIT_TS_COLUMN = getUTF8Bytes("commit_ts");
  private static final byte[] FILE_NAME_COLUMN = getUTF8Bytes("file_name");
  private static final byte[] PARTITION_PATH_COLUMN = getUTF8Bytes("partition_path");

  private static final Logger LOG = LoggerFactory.getLogger(SparkHoodieHBaseIndex.class);
  private static Connection HBASE_CONNECTION = null;
  private static transient Thread SHUTDOWN_THREAD;

  private HBaseIndexQPSResourceAllocator hBaseIndexQPSResourceAllocator = null;
  private int maxQpsPerRegionServer;
  private long totalNumInserts;
  private int numWriteStatusWithInserts;

  /**
   * multiPutBatchSize will be computed and re-set in updateLocation if
   * {@link HoodieHBaseIndexConfig#PUT_BATCH_SIZE_AUTO_COMPUTE} is set to true.
   */
  private Integer multiPutBatchSize;
  private Integer numRegionServersForTable;
  private final String tableName;
  private HBasePutBatchSizeCalculator putBatchSizeCalculator;

  public SparkHoodieHBaseIndex(HoodieWriteConfig config) {
    super(config);
    this.tableName = config.getHbaseTableName();
    addShutDownHook();
    init(config);
  }

  private void init(HoodieWriteConfig config) {
    this.multiPutBatchSize = config.getHbaseIndexPutBatchSize();
    this.maxQpsPerRegionServer = config.getHbaseIndexMaxQPSPerRegionServer();
    this.putBatchSizeCalculator = new HBasePutBatchSizeCalculator();
    this.hBaseIndexQPSResourceAllocator = createQPSResourceAllocator(this.config);
  }

  public HBaseIndexQPSResourceAllocator createQPSResourceAllocator(HoodieWriteConfig config) {
    try {
      LOG.info("createQPSResourceAllocator :" + config.getHBaseQPSResourceAllocatorClass());
      return (HBaseIndexQPSResourceAllocator) ReflectionUtils
              .loadClass(config.getHBaseQPSResourceAllocatorClass(), config);
    } catch (Exception e) {
      LOG.warn("error while instantiating HBaseIndexQPSResourceAllocator", e);
    }
    return new DefaultHBaseQPSResourceAllocator(config);
  }

  private Connection getHBaseConnection() {
    Configuration hbaseConfig = HBaseConfiguration.create();
    String quorum = config.getHbaseZkQuorum();
    hbaseConfig.set(ZOOKEEPER_QUORUM, quorum);
    String zkZnodeParent = config.getHBaseZkZnodeParent();
    if (zkZnodeParent != null) {
      hbaseConfig.set(ZOOKEEPER_ZNODE_PARENT, zkZnodeParent);
    }
    String port = String.valueOf(config.getHbaseZkPort());
    hbaseConfig.set(ZOOKEEPER_CLIENT_PORT, port);

    try {
      String authentication = config.getHBaseIndexSecurityAuthentication();
      if (authentication.equals("kerberos")) {
        hbaseConfig.set(HBASE_SECURITY_CONF_KEY, "kerberos");
        hbaseConfig.set(HADOOP_SECURITY_AUTHENTICATION, "kerberos");
        hbaseConfig.set(HBASE_SECURITY_AUTHORIZATION_CONF_KEY, "true");
        hbaseConfig.set(REGIONSERVER_KRB_PRINCIPAL, config.getHBaseIndexRegionserverPrincipal());
        hbaseConfig.set(MASTER_KRB_PRINCIPAL, config.getHBaseIndexMasterPrincipal());

        String principal = config.getHBaseIndexKerberosUserPrincipal();
        String keytab = SparkFiles.get(config.getHBaseIndexKerberosUserKeytab());

        UserGroupInformation.setConfiguration(hbaseConfig);
        UserGroupInformation ugi = UserGroupInformation.loginUserFromKeytabAndReturnUGI(principal, keytab);
        return ugi.doAs((PrivilegedExceptionAction<Connection>) () ->
          (Connection) ConnectionFactory.createConnection(hbaseConfig)
        );
      } else {
        return ConnectionFactory.createConnection(hbaseConfig);
      }
    } catch (IOException | InterruptedException e) {
      throw new HoodieDependentSystemUnavailableException(HoodieDependentSystemUnavailableException.HBASE,
          quorum + ":" + port, e);
    }
  }

  /**
   * Since we are sharing the HBaseConnection across tasks in a JVM, make sure the HBaseConnection is closed when JVM
   * exits.
   */
  private void addShutDownHook() {
    if (null == SHUTDOWN_THREAD) {
      SHUTDOWN_THREAD = new Thread(() -> {
        try {
          HBASE_CONNECTION.close();
        } catch (Exception e) {
          // fail silently for any sort of exception
        }
      });
      Runtime.getRuntime().addShutdownHook(SHUTDOWN_THREAD);
    }
  }

  /**
   * Ensure that any resources used for indexing are released here.
   */
  @Override
  public void close() {
    LOG.info("No resources to release from Hbase index");
  }

  private Get generateStatement(String key) throws IOException {
    return new Get(getUTF8Bytes(getHBaseKey(key))).readVersions(1).addColumn(SYSTEM_COLUMN_FAMILY, COMMIT_TS_COLUMN)
        .addColumn(SYSTEM_COLUMN_FAMILY, FILE_NAME_COLUMN).addColumn(SYSTEM_COLUMN_FAMILY, PARTITION_PATH_COLUMN);
  }

  private Get generateStatement(String key, long startTime, long endTime) throws IOException {
    return generateStatement(key).setTimeRange(startTime, endTime);
  }

  protected String getHBaseKey(String key) {
    return key;
  }

  /**
   * Function that tags each HoodieRecord with an existing location, if known.
   */
  private <R> Function2<Integer, Iterator<HoodieRecord<R>>, Iterator<HoodieRecord<R>>> locationTagFunction(
      HoodieTableMetaClient metaClient) {

    // `multiGetBatchSize` is intended to be a batch per 100ms. To create a rate limiter that measures
    // operations per second, we need to multiply `multiGetBatchSize` by 10.
    Integer multiGetBatchSize = config.getHbaseIndexGetBatchSize();
    return (partitionNum, hoodieRecordIterator) -> {
      boolean updatePartitionPath = config.getHbaseIndexUpdatePartitionPath();
      RateLimiter limiter = RateLimiter.create(multiGetBatchSize * 10, TimeUnit.SECONDS);
      // Grab the global HBase connection
      synchronized (SparkHoodieHBaseIndex.class) {
        if (HBASE_CONNECTION == null || HBASE_CONNECTION.isClosed()) {
          HBASE_CONNECTION = getHBaseConnection();
        }
      }
      List<HoodieRecord<R>> taggedRecords = new ArrayList<>();
      try (HTable hTable = (HTable) HBASE_CONNECTION.getTable(TableName.valueOf(tableName))) {
        List<Get> statements = new ArrayList<>();
        List<HoodieRecord> currentBatchOfRecords = new LinkedList<>();
        // Do the tagging.
        HoodieTimeline completedCommitsTimeline = metaClient.getCommitsTimeline().filterCompletedInstants();
        while (hoodieRecordIterator.hasNext()) {
          HoodieRecord rec = hoodieRecordIterator.next();
          statements.add(generateStatement(rec.getRecordKey()));
          currentBatchOfRecords.add(rec);
          // iterator till we reach batch size
          if (hoodieRecordIterator.hasNext() && statements.size() < multiGetBatchSize) {
            continue;
          }
          // get results for batch from Hbase
          Result[] results = doGet(hTable, statements, limiter);
          // clear statements to be GC'd
          statements.clear();
          for (Result result : results) {
            // first, attempt to grab location from HBase
            HoodieRecord currentRecord = currentBatchOfRecords.remove(0);
            if (result.getRow() == null) {
              taggedRecords.add(currentRecord);
              continue;
            }
            String keyFromResult = fromUTF8Bytes(result.getRow());
            String commitTs = fromUTF8Bytes(result.getValue(SYSTEM_COLUMN_FAMILY, COMMIT_TS_COLUMN));
            String fileId = fromUTF8Bytes(result.getValue(SYSTEM_COLUMN_FAMILY, FILE_NAME_COLUMN));
            String partitionPath = fromUTF8Bytes(result.getValue(SYSTEM_COLUMN_FAMILY, PARTITION_PATH_COLUMN));
            if (!HoodieIndexUtils.checkIfValidCommit(completedCommitsTimeline, commitTs)) {
              // if commit is invalid, treat this as a new taggedRecord
              taggedRecords.add(currentRecord);
              continue;
            }
            // check whether to do partition change processing
            if (updatePartitionPath && !partitionPath.equals(currentRecord.getPartitionPath())) {
              // delete partition old data record
              HoodieRecord emptyRecord = new HoodieAvroRecord(new HoodieKey(currentRecord.getRecordKey(), partitionPath),
                  new EmptyHoodieRecordPayload());
              emptyRecord.unseal();
              emptyRecord.setCurrentLocation(new HoodieRecordLocation(commitTs, fileId));
              emptyRecord.setIgnoreIndexUpdate(true);
              emptyRecord.seal();
              // insert partition new data record
              currentRecord = new HoodieAvroRecord(new HoodieKey(currentRecord.getRecordKey(), currentRecord.getPartitionPath()),
                  (HoodieRecordPayload) currentRecord.getData());
              taggedRecords.add(emptyRecord);
              taggedRecords.add(currentRecord);
            } else {
              currentRecord = new HoodieAvroRecord(new HoodieKey(currentRecord.getRecordKey(), partitionPath),
                  (HoodieRecordPayload) currentRecord.getData());
              currentRecord.unseal();
              currentRecord.setCurrentLocation(new HoodieRecordLocation(commitTs, fileId));
              currentRecord.seal();
              taggedRecords.add(currentRecord);
              // the key from Result and the key being processed should be same
              assert (currentRecord.getRecordKey().contentEquals(keyFromResult));
            }
          }
        }
      } catch (IOException e) {
        throw new HoodieIndexException("Failed to Tag indexed locations because of exception with HBase Client", e);
      } finally {
        limiter.stop();
      }
      return taggedRecords.iterator();
    };
  }

  private Result[] doGet(HTable hTable, List<Get> keys, RateLimiter limiter) throws IOException {
    if (keys.size() > 0) {
      limiter.tryAcquire(keys.size());
      return hTable.get(keys);
    }
    return new Result[keys.size()];
  }

  @Override
  public <R> HoodieData<HoodieRecord<R>> tagLocation(
      HoodieData<HoodieRecord<R>> records, HoodieEngineContext context,
      HoodieTable hoodieTable) {
    return HoodieJavaRDD.of(HoodieJavaRDD.getJavaRDD(records)
        .mapPartitionsWithIndex(locationTagFunction(hoodieTable.getMetaClient()), true));
  }

  private Function2<Integer, Iterator<WriteStatus>, Iterator<WriteStatus>> updateLocationFunction() {

    return (partition, statusIterator) -> {

      List<WriteStatus> writeStatusList = new ArrayList<>();
      // Grab the global HBase connection
      synchronized (SparkHoodieHBaseIndex.class) {
        if (HBASE_CONNECTION == null || HBASE_CONNECTION.isClosed()) {
          HBASE_CONNECTION = getHBaseConnection();
        }
      }
      final long startTimeForPutsTask = DateTime.now().getMillis();
      LOG.info("startTimeForPutsTask for this task: " + startTimeForPutsTask);

      final RateLimiter limiter = RateLimiter.create(multiPutBatchSize, TimeUnit.SECONDS);
      try (BufferedMutator mutator = HBASE_CONNECTION.getBufferedMutator(TableName.valueOf(tableName))) {
        while (statusIterator.hasNext()) {
          WriteStatus writeStatus = statusIterator.next();
          List<Mutation> mutations = new ArrayList<>();
          try {
            long numOfInserts = writeStatus.getStat().getNumInserts();
            LOG.info("Num of inserts in this WriteStatus: " + numOfInserts);
            LOG.info("Total inserts in this job: " + this.totalNumInserts);
            LOG.info("multiPutBatchSize for this job: " + this.multiPutBatchSize);
            // Create a rate limiter that allows `multiPutBatchSize` operations per second
            // Any calls beyond `multiPutBatchSize` within a second will be rate limited
            for (HoodieRecordDelegate recordDelegate : writeStatus.getWrittenRecordDelegates()) {
              if (!writeStatus.isErrored(recordDelegate.getHoodieKey())) {
                if (recordDelegate.getIgnoreIndexUpdate()) {
                  continue;
                }
                Option<HoodieRecordLocation> loc = recordDelegate.getNewLocation();
                if (loc.isPresent()) {
                  if (recordDelegate.getCurrentLocation().isPresent()) {
                    // This is an update, no need to update index
                    continue;
                  }
                  Put put = new Put(getUTF8Bytes(getHBaseKey(recordDelegate.getRecordKey())));
                  put.addColumn(SYSTEM_COLUMN_FAMILY, COMMIT_TS_COLUMN, getUTF8Bytes(loc.get().getInstantTime()));
                  put.addColumn(SYSTEM_COLUMN_FAMILY, FILE_NAME_COLUMN, getUTF8Bytes(loc.get().getFileId()));
                  put.addColumn(SYSTEM_COLUMN_FAMILY, PARTITION_PATH_COLUMN, getUTF8Bytes(recordDelegate.getPartitionPath()));
                  mutations.add(put);
                } else {
                  // Delete existing index for a deleted record
                  Delete delete = new Delete(getUTF8Bytes(getHBaseKey(recordDelegate.getRecordKey())));
                  mutations.add(delete);
                }
              }
              if (mutations.size() < multiPutBatchSize) {
                continue;
              }
              doMutations(mutator, mutations, limiter);
            }
            // process remaining puts and deletes, if any
            doMutations(mutator, mutations, limiter);
          } catch (Exception e) {
            Exception we = new Exception("Error updating index for " + writeStatus, e);
            LOG.error(we.getMessage(), e);
            writeStatus.setGlobalError(we);
          }
          writeStatusList.add(writeStatus);
        }
        final long endPutsTime = DateTime.now().getMillis();
        LOG.info("hbase puts task time for this task: " + (endPutsTime - startTimeForPutsTask));
      } catch (IOException e) {
        throw new HoodieIndexException("Failed to Update Index locations because of exception with HBase Client", e);
      } finally {
        limiter.stop();
      }
      return writeStatusList.iterator();
    };
  }

  /**
   * Helper method to facilitate performing mutations (including puts and deletes) in Hbase.
   */
  private void doMutations(BufferedMutator mutator, List<Mutation> mutations, RateLimiter limiter) throws IOException {
    if (mutations.isEmpty()) {
      return;
    }
    // report number of operations to account per second with rate limiter.
    // If #limiter.getRate() operations are acquired within 1 second, ratelimiter will limit the rest of calls
    // for within that second
    limiter.tryAcquire(mutations.size());
    mutator.mutate(mutations);
    mutator.flush();
    mutations.clear();
  }

  Map<String, Integer> mapFileWithInsertsToUniquePartition(JavaRDD<WriteStatus> writeStatusRDD) {
    final Map<String, Integer> fileIdPartitionMap = new HashMap<>();
    int partitionIndex = 0;
    // Map each fileId that has inserts to a unique partition Id. This will be used while
    // repartitioning RDD<WriteStatus>
    final List<String> fileIds = writeStatusRDD.filter(w -> w.getStat().getNumInserts() > 0)
                                   .map(WriteStatus::getFileId).collect();
    for (final String fileId : fileIds) {
      fileIdPartitionMap.put(fileId, partitionIndex++);
    }
    return fileIdPartitionMap;
  }

  @Override
  public HoodieData<WriteStatus> updateLocation(
      HoodieData<WriteStatus> writeStatus, HoodieEngineContext context,
      HoodieTable hoodieTable) {
    JavaRDD<WriteStatus> writeStatusRDD = HoodieJavaRDD.getJavaRDD(writeStatus);
    final Option<Float> desiredQPSFraction = calculateQPSFraction(writeStatusRDD);
    final Map<String, Integer> fileIdPartitionMap = mapFileWithInsertsToUniquePartition(writeStatusRDD);
    JavaRDD<WriteStatus> partitionedRDD = this.numWriteStatusWithInserts == 0 ? writeStatusRDD :
        writeStatusRDD.mapToPair(w -> new Tuple2<>(w.getFileId(), w))
            .partitionBy(new WriteStatusPartitioner(fileIdPartitionMap,
                this.numWriteStatusWithInserts))
            .map(Tuple2::_2);
    JavaSparkContext jsc = HoodieSparkEngineContext.getSparkContext(context);
    acquireQPSResourcesAndSetBatchSize(desiredQPSFraction, jsc);
    JavaRDD<WriteStatus> writeStatusJavaRDD = partitionedRDD.mapPartitionsWithIndex(updateLocationFunction(),
        true);
    // caching the index updated status RDD
    writeStatusJavaRDD = writeStatusJavaRDD.persist(
        StorageLevel.fromString(config.getString(WRITE_STATUS_STORAGE_LEVEL_VALUE)));
    // force trigger update location(hbase puts)
    writeStatusJavaRDD.count();
    this.hBaseIndexQPSResourceAllocator.releaseQPSResources();
    return HoodieJavaRDD.of(writeStatusJavaRDD);
  }

  private Option<Float> calculateQPSFraction(JavaRDD<WriteStatus> writeStatusRDD) {
    if (config.getHbaseIndexPutBatchSizeAutoCompute()) {
      /*
        Each writeStatus represents status information from a write done in one of the IOHandles.
        If a writeStatus has any insert, it implies that the corresponding task contacts HBase for
        doing puts, since we only do puts for inserts from HBaseIndex.
       */
      final Tuple2<Long, Integer> numPutsParallelismTuple  = getHBasePutAccessParallelism(writeStatusRDD);
      this.totalNumInserts = numPutsParallelismTuple._1;
      this.numWriteStatusWithInserts = numPutsParallelismTuple._2;
      this.numRegionServersForTable = getNumRegionServersAliveForTable();
      final float desiredQPSFraction = this.hBaseIndexQPSResourceAllocator.calculateQPSFractionForPutsTime(
          this.totalNumInserts, this.numRegionServersForTable);
      LOG.info("Desired QPSFraction :" + desiredQPSFraction);
      LOG.info("Number HBase puts :" + this.totalNumInserts);
      LOG.info("Number of WriteStatus with inserts :" + numWriteStatusWithInserts);
      return Option.of(desiredQPSFraction);
    }
    return Option.empty();
  }

  private void acquireQPSResourcesAndSetBatchSize(final Option<Float> desiredQPSFraction,
                                                  final JavaSparkContext jsc) {
    if (config.getHbaseIndexPutBatchSizeAutoCompute()) {
      SparkConf conf = jsc.getConf();
      int maxExecutors = conf.getInt(DEFAULT_SPARK_EXECUTOR_INSTANCES_CONFIG_NAME, 1);
      if (conf.getBoolean(DEFAULT_SPARK_DYNAMIC_ALLOCATION_ENABLED_CONFIG_NAME, false)) {
        maxExecutors = Math.max(maxExecutors, conf.getInt(
          DEFAULT_SPARK_DYNAMIC_ALLOCATION_MAX_EXECUTORS_CONFIG_NAME, 1));
      }
      final float availableQpsFraction = this.hBaseIndexQPSResourceAllocator
                                           .acquireQPSResources(desiredQPSFraction.get(), this.totalNumInserts);
      LOG.info("Allocated QPS Fraction :" + availableQpsFraction);
      multiPutBatchSize = putBatchSizeCalculator
                            .getBatchSize(
                              numRegionServersForTable,
                              maxQpsPerRegionServer,
                              numWriteStatusWithInserts,
                              maxExecutors,
                              availableQpsFraction);
      LOG.info("multiPutBatchSize :" + multiPutBatchSize);
    }
  }

  Tuple2<Long, Integer> getHBasePutAccessParallelism(final JavaRDD<WriteStatus> writeStatusRDD) {
    final JavaPairRDD<Long, Integer> insertOnlyWriteStatusRDD = writeStatusRDD
        .filter(w -> w.getStat().getNumInserts() > 0).mapToPair(w -> new Tuple2<>(w.getStat().getNumInserts(), 1));
    return insertOnlyWriteStatusRDD.fold(new Tuple2<>(0L, 0), (w, c) -> new Tuple2<>(w._1 + c._1, w._2 + c._2));
  }

  public static class HBasePutBatchSizeCalculator implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(HBasePutBatchSizeCalculator.class);

    /**
     * Calculate putBatch size so that sum of requests across multiple jobs in a second does not exceed
     * maxQpsPerRegionServer for each Region Server. Multiplying qpsFraction to reduce the aggregate load on common RS
     * across topics. Assumption here is that all tables have regions across all RS, which is not necessarily true for
     * smaller tables. So, they end up getting a smaller share of QPS than they deserve, but it might be ok.
     * <p>
     * Example: int putBatchSize = batchSizeCalculator.getBatchSize(10, 16667, 1200, 200, 100, 0.1f)
     * </p>
     * <p>
     * Expected batchSize is 8 because in that case, total request sent to a Region Server in one second is:
     *
     * 8 (batchSize) * 200 (parallelism) * 10 (maxReqsInOneSecond) * 10 (numRegionServers) * 0.1 (qpsFraction)) =>
     * 16000. We assume requests get distributed to Region Servers uniformly, so each RS gets 1600 requests which
     * happens to be 10% of 16667 (maxQPSPerRegionServer), as expected.
     * </p>
     * <p>
     * Assumptions made here
     * <li>In a batch, writes get evenly distributed to each RS for that table. Since we do writes only in the case of
     * inserts and not updates, for this assumption to fail, inserts would have to be skewed towards few RS, likelihood
     * of which is less if Hbase table is pre-split and rowKeys are UUIDs (random strings). If this assumption fails,
     * then it is possible for some RS to receive more than maxQpsPerRegionServer QPS, but for simplicity, we are going
     * ahead with this model, since this is meant to be a lightweight distributed throttling mechanism without
     * maintaining a global context. So if this assumption breaks, we are hoping the HBase Master relocates hot-spot
     * regions to new Region Servers.
     *
     * </li>
     * <li>For Region Server stability, throttling at a second level granularity is fine. Although, within a second, the
     * sum of queries might be within maxQpsPerRegionServer, there could be peaks at some sub second intervals. So, the
     * assumption is that these peaks are tolerated by the Region Server (which at max can be maxQpsPerRegionServer).
     * </li>
     * </p>
     */
    public int getBatchSize(int numRegionServersForTable, int maxQpsPerRegionServer,
                            int numTasksDuringPut, int maxExecutors, float qpsFraction) {
      int numRSAlive = numRegionServersForTable;
      int maxReqPerSec = getMaxReqPerSec(numRSAlive, maxQpsPerRegionServer, qpsFraction);
      int numTasks = numTasksDuringPut;
      int maxParallelPutsTask = Math.max(1, Math.min(numTasks, maxExecutors));
      int multiPutBatchSizePerSecPerTask = Math.max(1, (int) Math.ceil((double) maxReqPerSec / maxParallelPutsTask));
      LOG.info("HbaseIndexThrottling: qpsFraction :" + qpsFraction);
      LOG.info("HbaseIndexThrottling: numRSAlive :" + numRSAlive);
      LOG.info("HbaseIndexThrottling: maxReqPerSec :" + maxReqPerSec);
      LOG.info("HbaseIndexThrottling: numTasks :" + numTasks);
      LOG.info("HbaseIndexThrottling: maxExecutors :" + maxExecutors);
      LOG.info("HbaseIndexThrottling: maxParallelPuts :" + maxParallelPutsTask);
      LOG.info("HbaseIndexThrottling: numRegionServersForTable :" + numRegionServersForTable);
      LOG.info("HbaseIndexThrottling: multiPutBatchSizePerSecPerTask :" + multiPutBatchSizePerSecPerTask);
      return multiPutBatchSizePerSecPerTask;
    }

    public int getMaxReqPerSec(int numRegionServersForTable, int maxQpsPerRegionServer, float qpsFraction) {
      return (int) (qpsFraction * numRegionServersForTable * maxQpsPerRegionServer);
    }
  }

  private Integer getNumRegionServersAliveForTable() {
    // This is being called in the driver, so there is only one connection
    // from the driver, so ok to use a local connection variable.
    if (numRegionServersForTable == null) {
      try (Connection conn = getHBaseConnection()) {
        RegionLocator regionLocator = conn.getRegionLocator(TableName.valueOf(tableName));
        numRegionServersForTable = Math
            .toIntExact(regionLocator.getAllRegionLocations().stream().map(HRegionLocation::getServerName).distinct().count());
        return numRegionServersForTable;
      } catch (IOException e) {
        LOG.error("Get region locator error", e);
        throw new RuntimeException(e);
      }
    }
    return numRegionServersForTable;
  }

  @Override
  public boolean rollbackCommit(String instantTime) {
    int multiGetBatchSize = config.getHbaseIndexGetBatchSize();
    boolean rollbackSync = config.getHBaseIndexRollbackSync();

    if (!config.getHBaseIndexRollbackSync()) {
      // Default Rollback in HbaseIndex is managed via method {@link #checkIfValidCommit()}
      return true;
    }

    synchronized (SparkHoodieHBaseIndex.class) {
      if (HBASE_CONNECTION == null || HBASE_CONNECTION.isClosed()) {
        HBASE_CONNECTION = getHBaseConnection();
      }
    }
    final RateLimiter limiter = RateLimiter.create(multiPutBatchSize, TimeUnit.SECONDS);
    try (HTable hTable = (HTable) HBASE_CONNECTION.getTable(TableName.valueOf(tableName));
         BufferedMutator mutator = HBASE_CONNECTION.getBufferedMutator(TableName.valueOf(tableName))) {
      Long rollbackTime = TimelineUtils.parseDateFromInstantTime(instantTime).getTime();
      Long currentTime = new Date().getTime();
      Scan scan = new Scan();
      scan.addFamily(SYSTEM_COLUMN_FAMILY);
      scan.setTimeRange(rollbackTime, currentTime);
      ResultScanner scanner = hTable.getScanner(scan);
      Iterator<Result> scannerIterator = scanner.iterator();

      List<Get> statements = new ArrayList<>();
      List<Result> currentVersionResults = new ArrayList<Result>();
      List<Mutation> mutations = new ArrayList<>();
      while (scannerIterator.hasNext()) {
        Result result = scannerIterator.next();
        currentVersionResults.add(result);
        statements.add(generateStatement(fromUTF8Bytes(result.getRow()), 0L, rollbackTime - 1));

        if (scannerIterator.hasNext() &&  statements.size() < multiGetBatchSize) {
          continue;
        }
        Result[] lastVersionResults = hTable.get(statements);
        for (int i = 0; i < lastVersionResults.length; i++) {
          Result lastVersionResult = lastVersionResults[i];
          if (null == lastVersionResult.getRow() && rollbackSync) {
            Result currentVersionResult = currentVersionResults.get(i);
            Delete delete = new Delete(currentVersionResult.getRow());
            mutations.add(delete);
          }

          if (null != lastVersionResult.getRow()) {
            String oldPath = new String(lastVersionResult.getValue(SYSTEM_COLUMN_FAMILY, PARTITION_PATH_COLUMN));
            String nowPath = new String(currentVersionResults.get(i).getValue(SYSTEM_COLUMN_FAMILY, PARTITION_PATH_COLUMN));
            if (!oldPath.equals(nowPath) || rollbackSync) {
              Put put = new Put(lastVersionResult.getRow());
              put.addColumn(SYSTEM_COLUMN_FAMILY, COMMIT_TS_COLUMN, lastVersionResult.getValue(SYSTEM_COLUMN_FAMILY, COMMIT_TS_COLUMN));
              put.addColumn(SYSTEM_COLUMN_FAMILY, FILE_NAME_COLUMN, lastVersionResult.getValue(SYSTEM_COLUMN_FAMILY, FILE_NAME_COLUMN));
              put.addColumn(SYSTEM_COLUMN_FAMILY, PARTITION_PATH_COLUMN, lastVersionResult.getValue(SYSTEM_COLUMN_FAMILY, PARTITION_PATH_COLUMN));
              mutations.add(put);
            }
          }
        }
        doMutations(mutator, mutations, limiter);
        currentVersionResults.clear();
        statements.clear();
        mutations.clear();
      }
    } catch (Exception e) {
      LOG.error("hbase index roll back failed", e);
      return false;
    } finally {
      limiter.stop();
    }
    return true;
  }

  /**
   * Only looks up by recordKey.
   */
  @Override
  public boolean isGlobal() {
    return true;
  }

  /**
   * Mapping is available in HBase already.
   */
  @Override
  public boolean canIndexLogFiles() {
    return true;
  }

  /**
   * Index needs to be explicitly updated after storage write.
   */
  @Override
  public boolean isImplicitWithStorage() {
    return false;
  }

  public void setHbaseConnection(Connection hbaseConnection) {
    SparkHoodieHBaseIndex.HBASE_CONNECTION = hbaseConnection;
  }

  /**
   * Partitions each WriteStatus with inserts into a unique single partition. WriteStatus without inserts will be
   * assigned to random partitions. This partitioner will be useful to utilize max parallelism with spark operations
   * that are based on inserts in each WriteStatus.
   */
  public static class WriteStatusPartitioner extends Partitioner {
    private final int totalPartitions;
    final Map<String, Integer> fileIdPartitionMap;

    public WriteStatusPartitioner(final Map<String, Integer> fileIdPartitionMap, final int totalPartitions) {
      this.totalPartitions = totalPartitions;
      this.fileIdPartitionMap = fileIdPartitionMap;
    }

    @Override
    public int numPartitions() {
      return this.totalPartitions;
    }

    @Override
    public int getPartition(Object key) {
      final String fileId = (String) key;
      if (!fileIdPartitionMap.containsKey(fileId)) {
        LOG.info("This writestatus(fileId: " + fileId + ") is not mapped because it doesn't have any inserts. "
                 + "In this case, we can assign a random partition to this WriteStatus.");
        // Assign random spark partition for the `WriteStatus` that has no inserts. For a spark operation that depends
        // on number of inserts, there won't be any performance penalty in packing these WriteStatus'es together.
        return Math.abs(fileId.hashCode()) % totalPartitions;
      }
      return fileIdPartitionMap.get(fileId);
    }
  }
}
