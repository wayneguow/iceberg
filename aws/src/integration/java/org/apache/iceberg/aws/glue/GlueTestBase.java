/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.aws.glue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.aws.AwsClientFactories;
import org.apache.iceberg.aws.AwsClientFactory;
import org.apache.iceberg.aws.AwsIntegTestUtil;
import org.apache.iceberg.aws.AwsProperties;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.glue.GlueClient;
import software.amazon.awssdk.services.glue.model.Column;
import software.amazon.awssdk.services.glue.model.GetTableRequest;
import software.amazon.awssdk.services.glue.model.GetTableResponse;
import software.amazon.awssdk.services.glue.model.Table;
import software.amazon.awssdk.services.glue.model.TableInput;
import software.amazon.awssdk.services.glue.model.UpdateTableRequest;
import software.amazon.awssdk.services.s3.S3Client;

@SuppressWarnings({"VisibilityModifier", "HideUtilityClassConstructor"})
public class GlueTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(GlueTestBase.class);

  // the integration test requires the following env variables
  static final String testBucketName = AwsIntegTestUtil.testBucketName();

  static final String catalogName = "glue";
  static final String testPathPrefix = getRandomName();
  static final List<String> namespaces = Lists.newArrayList();

  // aws clients
  static final AwsClientFactory clientFactory = AwsClientFactories.defaultFactory();
  static final GlueClient glue = clientFactory.glue();
  static final S3Client s3 = clientFactory.s3();

  // iceberg
  static GlueCatalog glueCatalog;
  static GlueCatalog glueCatalogWithSkipNameValidation;

  static Schema schema =
      new Schema(Types.NestedField.required(1, "c1", Types.StringType.get(), "c1"));
  static PartitionSpec partitionSpec = PartitionSpec.builderFor(schema).build();
  // table location properties
  static final Map<String, String> tableLocationProperties =
      ImmutableMap.of(
          TableProperties.WRITE_DATA_LOCATION, "s3://" + testBucketName + "/writeDataLoc",
          TableProperties.WRITE_METADATA_LOCATION, "s3://" + testBucketName + "/writeMetaDataLoc",
          TableProperties.WRITE_FOLDER_STORAGE_LOCATION,
              "s3://" + testBucketName + "/writeFolderStorageLoc");

  static final String testBucketPath = "s3://" + testBucketName + "/" + testPathPrefix;

  @BeforeAll
  public static void beforeClass() {
    glueCatalog = new GlueCatalog();
    AwsProperties awsProperties = new AwsProperties();
    S3FileIOProperties s3FileIOProperties = new S3FileIOProperties();
    s3FileIOProperties.setDeleteBatchSize(10);
    glueCatalog.initialize(
        catalogName,
        testBucketPath,
        awsProperties,
        s3FileIOProperties,
        glue,
        null,
        ImmutableMap.of());

    glueCatalogWithSkipNameValidation = new GlueCatalog();
    AwsProperties propertiesSkipNameValidation = new AwsProperties();
    propertiesSkipNameValidation.setGlueCatalogSkipNameValidation(true);
    glueCatalogWithSkipNameValidation.initialize(
        catalogName,
        testBucketPath,
        propertiesSkipNameValidation,
        new S3FileIOProperties(),
        glue,
        null,
        ImmutableMap.of());
  }

  @AfterAll
  public static void afterClass() {
    AwsIntegTestUtil.cleanGlueCatalog(glue, namespaces);
    AwsIntegTestUtil.cleanS3Bucket(s3, testBucketName, testPathPrefix);
  }

  public static String getRandomName() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public static String createNamespace() {
    String namespace = getRandomName();
    namespaces.add(namespace);
    glueCatalog.createNamespace(Namespace.of(namespace));
    return namespace;
  }

  public static String createTable(String namespace) {
    String tableName = getRandomName();
    return createTable(namespace, tableName);
  }

  public static String createTable(String namespace, String tableName) {
    glueCatalog.createTable(TableIdentifier.of(namespace, tableName), schema, partitionSpec);
    return tableName;
  }

  // Directly call Glue API to update table description
  public static void updateTableDescription(
      String namespace, String tableName, String description) {
    GetTableResponse response =
        glue.getTable(GetTableRequest.builder().databaseName(namespace).name(tableName).build());
    Table table = response.table();
    UpdateTableRequest request =
        UpdateTableRequest.builder()
            .catalogId(table.catalogId())
            .databaseName(table.databaseName())
            .tableInput(
                TableInput.builder()
                    .description(description)
                    .name(table.name())
                    .partitionKeys(table.partitionKeys())
                    .tableType(table.tableType())
                    .owner(table.owner())
                    .parameters(table.parameters())
                    .storageDescriptor(table.storageDescriptor())
                    .build())
            .build();
    glue.updateTable(request);
  }

  public static void updateTableColumns(
      String namespace, String tableName, Function<Column, Column> columnUpdater) {
    GetTableResponse response =
        glue.getTable(GetTableRequest.builder().databaseName(namespace).name(tableName).build());
    Table existingTable = response.table();
    List<Column> updatedColumns =
        existingTable.storageDescriptor().columns().stream()
            .map(columnUpdater)
            .collect(Collectors.toList());

    UpdateTableRequest request =
        UpdateTableRequest.builder()
            .catalogId(existingTable.catalogId())
            .databaseName(existingTable.databaseName())
            .tableInput(
                TableInput.builder()
                    .description(existingTable.description())
                    .name(existingTable.name())
                    .partitionKeys(existingTable.partitionKeys())
                    .tableType(existingTable.tableType())
                    .owner(existingTable.owner())
                    .parameters(existingTable.parameters())
                    .storageDescriptor(
                        existingTable
                            .storageDescriptor()
                            .toBuilder()
                            .columns(updatedColumns)
                            .build())
                    .build())
            .build();
    glue.updateTable(request);
  }
}
