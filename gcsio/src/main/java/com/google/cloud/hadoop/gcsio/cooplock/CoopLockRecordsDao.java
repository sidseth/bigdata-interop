/*
 * Copyright 2019 Google LLC. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.hadoop.gcsio.cooplock;

import static com.google.cloud.hadoop.gcsio.GoogleCloudStorage.PATH_DELIMITER;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.flogger.LazyArgs.lazy;
import static com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.api.client.util.ExponentialBackOff;
import com.google.cloud.hadoop.gcsio.CreateObjectOptions;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageImpl;
import com.google.cloud.hadoop.gcsio.GoogleCloudStorageItemInfo;
import com.google.cloud.hadoop.gcsio.StorageResourceId;
import com.google.cloud.hadoop.util.ApiErrorExtractor;
import com.google.common.flogger.GoogleLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DAO class for {@link CoopLockRecords} class (persisted in {@code gs://<BUCKET>/_lock/all.lock}
 * file).
 *
 * <p>Main function of this class is to perform atomic acuisiton and release of a lock for resources
 * specified in {@link CoopLockRecord} class.
 */
public class CoopLockRecordsDao {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  public static final String LOCK_DIRECTORY = "_lock/";

  private static final String LOCK_FILE = "all.lock";
  public static final String LOCK_PATH = LOCK_DIRECTORY + LOCK_FILE;

  private static final String LOCK_METADATA_KEY = "lock";

  private static final int MIN_BACK_OFF_INTERVAL_MILLIS = 500;
  private static final int MAX_BACK_OFF_INTERVAL_MILLIS = 2_000;
  private static final int RETRY_LOCK_INTERVAL_MILLIS = 2_000;

  private static final Gson GSON = createGson();

  private static final CreateObjectOptions CREATE_NEW_OBJECT_OPTIONS =
      new CreateObjectOptions(/* overwriteExisting= */ false);

  private final GoogleCloudStorageImpl gcs;
  private final CooperativeLockingOptions options;

  public CoopLockRecordsDao(GoogleCloudStorageImpl gcs) {
    this.gcs = gcs;
    this.options = gcs.getOptions().getCooperativeLockingOptions();
  }

  public Set<CoopLockRecord> getLockedOperations(String bucketName) throws IOException {
    long startMs = System.currentTimeMillis();
    StorageResourceId lockId = getLockId(bucketName);
    GoogleCloudStorageItemInfo lockInfo = gcs.getItemInfo(lockId);
    Set<CoopLockRecord> operations =
        !lockInfo.exists()
                || lockInfo.getMetaGeneration() == 0
                || lockInfo.getMetadata().get(LOCK_METADATA_KEY) == null
            ? new HashSet<>()
            : getLockRecords(lockInfo).getLocks();
    logger.atFine().log(
        "[%dms] getLockedOperations(%s): %s",
        System.currentTimeMillis() - startMs, bucketName, operations);
    return operations;
  }

  public void relockOperation(String bucketName, CoopLockRecord operationRecord)
      throws IOException {
    long startMs = System.currentTimeMillis();
    String operationId = operationRecord.getOperationId();
    String clientId = operationRecord.getClientId();
    modifyLock(
        records -> reacquireOperationLock(records, operationRecord), bucketName, operationId);
    logger.atFine().log(
        "[%dms] lockOperation(%s, %s)",
        System.currentTimeMillis() - startMs, operationId, clientId);
  }

  public void lockPaths(
      String operationId,
      Instant operationInstant,
      CoopLockOperationType operationType,
      StorageResourceId... resources)
      throws IOException {
    long startMs = System.currentTimeMillis();
    Set<String> objects = validateResources(resources);
    String bucketName = resources[0].getBucketName();
    modifyLock(
        records -> addLockRecords(records, operationId, operationInstant, operationType, objects),
        bucketName,
        operationId);
    logger.atFine().log(
        "[%dms] lockPaths(%s, %s)",
        System.currentTimeMillis() - startMs, operationId, lazy(() -> Arrays.toString(resources)));
  }

  public void unlockPaths(String operationId, StorageResourceId... resources) throws IOException {
    long startMs = System.currentTimeMillis();
    Set<String> objects = validateResources(resources);
    String bucketName = resources[0].getBucketName();
    modifyLock(
        records -> removeLockRecords(records, operationId, objects), bucketName, operationId);
    logger.atFine().log(
        "[%dms] unlockPaths(%s, %s)",
        System.currentTimeMillis() - startMs, operationId, lazy(() -> Arrays.toString(resources)));
  }

  private static Set<String> validateResources(StorageResourceId[] resources) {
    checkNotNull(resources, "resources should not be null");
    checkArgument(resources.length > 0, "resources should not be empty");
    String bucketName = resources[0].getBucketName();
    checkState(
        Arrays.stream(resources).allMatch(r -> r.getBucketName().equals(bucketName)),
        "All resources should be in the same bucket");

    return Arrays.stream(resources).map(StorageResourceId::getObjectName).collect(toImmutableSet());
  }

  private void modifyLock(
      Function<CoopLockRecords, Boolean> modificationFn, String bucketName, String operationId)
      throws IOException {
    long startMs = System.currentTimeMillis();
    StorageResourceId lockId = getLockId(bucketName);

    ExponentialBackOff backOff =
        new ExponentialBackOff.Builder()
            .setInitialIntervalMillis(MIN_BACK_OFF_INTERVAL_MILLIS)
            .setMultiplier(1.2)
            .setMaxIntervalMillis(MAX_BACK_OFF_INTERVAL_MILLIS)
            .setMaxElapsedTimeMillis(Integer.MAX_VALUE)
            .build();

    do {
      try {
        GoogleCloudStorageItemInfo lockInfo = gcs.getItemInfo(lockId);
        if (!lockInfo.exists()) {
          gcs.createEmptyObject(lockId, CREATE_NEW_OBJECT_OPTIONS);
          lockInfo = gcs.getItemInfo(lockId);
        }
        CoopLockRecords lockRecords =
            lockInfo.getMetaGeneration() == 0
                    || lockInfo.getMetadata().get(LOCK_METADATA_KEY) == null
                ? new CoopLockRecords().setFormatVersion(CoopLockRecords.FORMAT_VERSION)
                : getLockRecords(lockInfo);

        if (!modificationFn.apply(lockRecords)) {
          logger.atInfo().atMostEvery(5, SECONDS).log(
              "Failed to update %s entries in %s file: resources could be locked, retrying.",
              lockRecords.getLocks().size(), lockId);
          sleepUninterruptibly(Duration.ofMillis(RETRY_LOCK_INTERVAL_MILLIS));
          continue;
        }

        // If unlocked all objects - delete lock object
        if (lockRecords.getLocks().isEmpty()) {
          gcs.deleteObject(lockInfo.getResourceId(), lockInfo.getMetaGeneration());
          break;
        }

        if (lockRecords.getLocks().size() > options.getMaxConcurrentOperations()) {
          logger.atInfo().atMostEvery(5, SECONDS).log(
              "Skipping lock entries update in %s file: too many (%d) locked resources, retrying.",
              lockId, lockRecords.getLocks().size());
          sleepUninterruptibly(Duration.ofMillis(RETRY_LOCK_INTERVAL_MILLIS));
          continue;
        }

        String lockContent = GSON.toJson(lockRecords, CoopLockRecords.class);
        Map<String, byte[]> metadata = new HashMap<>(lockInfo.getMetadata());
        metadata.put(LOCK_METADATA_KEY, lockContent.getBytes(UTF_8));

        gcs.updateMetadata(lockInfo, metadata);

        logger.atFine().log(
            "Updated lock file in %dms for %s operation",
            System.currentTimeMillis() - startMs, operationId);
        break;
      } catch (IOException e) {
        // continue after sleep if update failed due to file generation mismatch or other
        // IOException
        if (ApiErrorExtractor.INSTANCE.preconditionNotMet(e)) {
          logger.atInfo().atMostEvery(5, SECONDS).log(
              "Failed to update entries (condition not met) in %s file for operation %s, retrying.",
              lockId, operationId);
        } else if (ApiErrorExtractor.INSTANCE.itemNotFound(e)) {
          logger.atInfo().atMostEvery(5, SECONDS).log(
              "Failed to update entries (file not found) in %s file for operation %s, retrying.",
              lockId, operationId);
        } else {
          logger.atWarning().withCause(e).log(
              "Failed to modify lock for %s operation, retrying.", operationId);
        }
        sleepUninterruptibly(Duration.ofMillis(backOff.nextBackOffMillis()));
      }
    } while (true);
  }

  private static StorageResourceId getLockId(String bucketName) {
    return new StorageResourceId(bucketName, LOCK_PATH);
  }

  private static CoopLockRecords getLockRecords(GoogleCloudStorageItemInfo lockInfo) {
    String lockContent = new String(lockInfo.getMetadata().get(LOCK_METADATA_KEY), UTF_8);
    CoopLockRecords lockRecords = GSON.fromJson(lockContent, CoopLockRecords.class);
    checkState(
        lockRecords.getFormatVersion() == CoopLockRecords.FORMAT_VERSION,
        "Unsupported metadata format: expected %s, but was %s",
        lockRecords.getFormatVersion(),
        CoopLockRecords.FORMAT_VERSION);
    return lockRecords;
  }

  private boolean reacquireOperationLock(
      CoopLockRecords lockRecords, CoopLockRecord operationRecord) {
    Optional<CoopLockRecord> operationOptional =
        lockRecords.getLocks().stream().filter(o -> o.equals(operationRecord)).findAny();
    checkState(
        operationOptional.isPresent(), "operation %s not found", operationRecord.getOperationId());
    operationOptional
        .get()
        .setClientId(newClientId(operationRecord.getOperationId()))
        .setLockExpiration(Instant.now().plusMillis(options.getLockExpirationTimeoutMilli()));
    return true;
  }

  private boolean addLockRecords(
      CoopLockRecords lockRecords,
      String operationId,
      Instant operationInstant,
      CoopLockOperationType operationType,
      Set<String> resourcesToAdd) {
    // TODO: optimize to match more efficiently
    boolean atLestOneResourceAlreadyLocked =
        lockRecords.getLocks().stream()
            .flatMap(operation -> operation.getResources().stream())
            .anyMatch(
                lockedResource -> {
                  for (String resourceToAdd : resourcesToAdd) {
                    if (resourceToAdd.equals(lockedResource)
                        || isChildObject(lockedResource, resourceToAdd)
                        || isChildObject(resourceToAdd, lockedResource)) {
                      return true;
                    }
                  }
                  return false;
                });
    if (atLestOneResourceAlreadyLocked) {
      return false;
    }

    CoopLockRecord record =
        new CoopLockRecord()
            .setClientId(newClientId(operationId))
            .setOperationId(operationId)
            .setOperationTime(operationInstant)
            .setLockExpiration(Instant.now().plusMillis(options.getLockExpirationTimeoutMilli()))
            .setOperationType(operationType)
            .setResources(resourcesToAdd);
    lockRecords.getLocks().add(record);

    return true;
  }

  private static boolean isChildObject(String parent, String child) {
    return parent.startsWith(child.endsWith(PATH_DELIMITER) ? child : child + PATH_DELIMITER);
  }

  private static boolean removeLockRecords(
      CoopLockRecords lockRecords, String operationId, Set<String> resourcesToRemove) {
    List<CoopLockRecord> recordsToRemove =
        lockRecords.getLocks().stream()
            .filter(o -> o.getResources().stream().anyMatch(resourcesToRemove::contains))
            .collect(Collectors.toList());
    checkState(
        recordsToRemove.size() == 1,
        "Only %s operation with %s resources should be unlocked, but found %s operations:\n%s",
        operationId,
        resourcesToRemove,
        recordsToRemove.size(),
        recordsToRemove);
    CoopLockRecord operationToRemove = recordsToRemove.get(0);
    checkState(
        operationToRemove.getOperationId().equals(operationId),
        "All resources should be locked by %s operation, but they are locked by %s operation",
        operationId,
        operationToRemove.getOperationId());
    checkState(
        operationToRemove.getResources().equals(resourcesToRemove),
        "All of %s resources should be locked by operation, but was locked only %s resources",
        resourcesToRemove,
        operationToRemove.getResources());
    checkState(
        lockRecords.getLocks().remove(operationToRemove),
        "operation %s was not removed",
        operationToRemove);
    return true;
  }

  private static String newClientId(String operationId) {
    InetAddress localHost;
    try {
      localHost = InetAddress.getLocalHost();
    } catch (UnknownHostException e) {
      throw new RuntimeException(
          String.format("Failed to get clientId for %s operation", operationId), e);
    }
    String epochMillis = String.valueOf(Instant.now().toEpochMilli());
    return localHost.getCanonicalHostName() + "-" + epochMillis.substring(epochMillis.length() - 6);
  }

  public static Gson createGson() {
    return new GsonBuilder()
        .registerTypeAdapter(
            Instant.class,
            (JsonSerializer<Instant>)
                (instant, type, context) -> new JsonPrimitive(instant.toString()))
        .registerTypeAdapter(
            Instant.class,
            (JsonDeserializer<Instant>)
                (json, type, context) -> Instant.parse(json.getAsJsonPrimitive().getAsString()))
        .create();
  }
}
