package org.openmetadata.service.migration;

import static org.openmetadata.service.Entity.INGESTION_PIPELINE;
import static org.openmetadata.service.Entity.TEST_CASE;
import static org.openmetadata.service.Entity.TEST_SUITE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.openmetadata.common.utils.CommonUtil;
import org.openmetadata.schema.CreateEntity;
import org.openmetadata.schema.EntityInterface;
import org.openmetadata.schema.analytics.WebAnalyticEvent;
import org.openmetadata.schema.api.tests.CreateTestSuite;
import org.openmetadata.schema.dataInsight.DataInsightChart;
import org.openmetadata.schema.dataInsight.kpi.Kpi;
import org.openmetadata.schema.entity.Bot;
import org.openmetadata.schema.entity.Type;
import org.openmetadata.schema.entity.automations.Workflow;
import org.openmetadata.schema.entity.classification.Classification;
import org.openmetadata.schema.entity.classification.Tag;
import org.openmetadata.schema.entity.data.Chart;
import org.openmetadata.schema.entity.data.Container;
import org.openmetadata.schema.entity.data.Dashboard;
import org.openmetadata.schema.entity.data.DashboardDataModel;
import org.openmetadata.schema.entity.data.Database;
import org.openmetadata.schema.entity.data.DatabaseSchema;
import org.openmetadata.schema.entity.data.Glossary;
import org.openmetadata.schema.entity.data.GlossaryTerm;
import org.openmetadata.schema.entity.data.Metrics;
import org.openmetadata.schema.entity.data.MlModel;
import org.openmetadata.schema.entity.data.Pipeline;
import org.openmetadata.schema.entity.data.Query;
import org.openmetadata.schema.entity.data.Report;
import org.openmetadata.schema.entity.data.Table;
import org.openmetadata.schema.entity.data.Topic;
import org.openmetadata.schema.entity.events.EventSubscription;
import org.openmetadata.schema.entity.policies.Policy;
import org.openmetadata.schema.entity.services.DashboardService;
import org.openmetadata.schema.entity.services.DatabaseService;
import org.openmetadata.schema.entity.services.MessagingService;
import org.openmetadata.schema.entity.services.MetadataService;
import org.openmetadata.schema.entity.services.MlModelService;
import org.openmetadata.schema.entity.services.PipelineService;
import org.openmetadata.schema.entity.services.StorageService;
import org.openmetadata.schema.entity.services.connections.TestConnectionDefinition;
import org.openmetadata.schema.entity.services.ingestionPipelines.IngestionPipeline;
import org.openmetadata.schema.entity.teams.Role;
import org.openmetadata.schema.entity.teams.Team;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.tests.TestDefinition;
import org.openmetadata.schema.tests.TestSuite;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.schema.type.Relationship;
import org.openmetadata.schema.utils.EntityInterfaceUtil;
import org.openmetadata.service.Entity;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.CollectionDAO;
import org.openmetadata.service.jdbi3.EntityDAO;
import org.openmetadata.service.jdbi3.IngestionPipelineRepository;
import org.openmetadata.service.jdbi3.ListFilter;
import org.openmetadata.service.jdbi3.MigrationDAO;
import org.openmetadata.service.jdbi3.TableRepository;
import org.openmetadata.service.jdbi3.TestCaseRepository;
import org.openmetadata.service.jdbi3.TestSuiteRepository;
import org.openmetadata.service.migration.api.MigrationStep;
import org.openmetadata.service.resources.databases.DatasourceConfig;
import org.openmetadata.service.resources.feeds.MessageParser;
import org.openmetadata.service.util.EntityUtil;
import org.openmetadata.service.util.EntityUtil.Fields;
import org.openmetadata.service.util.FullyQualifiedName;
import org.openmetadata.service.util.JsonUtils;

@Slf4j
public class MigrationUtil {
  private MigrationUtil() {
    /* Cannot create object  util class*/
  }

  private static final String MYSQL_ENTITY_UPDATE = "UPDATE %s SET %s = :nameHashColumnValue WHERE id = :id";
  private static final String POSTGRES_ENTITY_UPDATE = "UPDATE %s SET %s = :nameHashColumnValue WHERE id = :id";

  private static final String MYSQL_ENTITY_EXTENSION_TIME_SERIES_UPDATE =
      "UPDATE entity_extension_time_series set entityFQNHash = :entityFQNHash where entityFQN=:entityFQN and extension=:extension and timestamp=:timestamp";
  private static final String POSTGRES_ENTITY_EXTENSION_TIME_SERIES_UPDATE =
      "UPDATE entity_extension_time_series set entityFQNHash = :entityFQNHash  where entityFQN=:entityFQN and extension=:extension and timestamp=:timestamp";

  @SneakyThrows
  public static <T extends EntityInterface> void updateFQNHashForEntity(
      Handle handle, Class<T> clazz, EntityDAO<T> dao) {
    if (Boolean.TRUE.equals(DatasourceConfig.getInstance().isMySQL())) {
      readAndProcessEntity(
          handle, String.format(MYSQL_ENTITY_UPDATE, dao.getTableName(), dao.getNameHashColumn()), clazz, dao, false);
    } else {
      readAndProcessEntity(
          handle,
          String.format(POSTGRES_ENTITY_UPDATE, dao.getTableName(), dao.getNameHashColumn()),
          clazz,
          dao,
          false);
    }
  }

  @SneakyThrows
  public static <T extends EntityInterface> void updateFQNHashForEntityWithName(
      Handle handle, Class<T> clazz, EntityDAO<T> dao) {
    if (Boolean.TRUE.equals(DatasourceConfig.getInstance().isMySQL())) {
      readAndProcessEntity(
          handle, String.format(MYSQL_ENTITY_UPDATE, dao.getTableName(), dao.getNameHashColumn()), clazz, dao, true);
    } else {
      readAndProcessEntity(
          handle, String.format(POSTGRES_ENTITY_UPDATE, dao.getTableName(), dao.getNameHashColumn()), clazz, dao, true);
    }
  }

  public static <T extends EntityInterface> void readAndProcessEntity(
      Handle handle, String updateSql, Class<T> clazz, EntityDAO<T> dao, boolean withName) throws IOException {
    LOG.debug("Starting Migration for table : {}", dao.getTableName());
    int limitParam = 1000;
    int offset = 0;
    int totalCount = dao.listTotalCount();
    while (offset < totalCount) {
      PreparedBatch upsertBatch = handle.prepareBatch(updateSql);
      // Read from Database
      List<String> jsons = dao.listAfterWithOffset(limitParam, offset);
      offset = offset + limitParam;
      // Process Update
      for (String json : jsons) {
        // Update the Statements to Database
        T entity = JsonUtils.readValue(json, clazz);
        String hash;
        try {
          hash =
              withName
                  ? FullyQualifiedName.buildHash(EntityInterfaceUtil.quoteName(entity.getFullyQualifiedName()))
                  : FullyQualifiedName.buildHash(entity.getFullyQualifiedName());
        } catch (Exception ex) {
          LOG.error("Failed in creating FQN Hash for Entity Name : {}", entity.getFullyQualifiedName(), ex);
          // Continue to update further jsons
          continue;
        }
        upsertBatch.bind("nameHashColumnValue", hash).bind("id", entity.getId().toString()).add();
      }
      upsertBatch.execute();
    }
    LOG.debug("End Migration for table : {}", dao.getTableName());
  }

  public static MigrationDAO.ServerMigrationSQLTable buildServerMigrationTable(String version, String statement) {
    MigrationDAO.ServerMigrationSQLTable result = new MigrationDAO.ServerMigrationSQLTable();
    result.setVersion(String.valueOf(version));
    result.setSqlStatement(statement);
    result.setCheckSum(EntityUtil.hash(statement));
    return result;
  }

  public static List<MigrationDAO.ServerMigrationSQLTable> addInListIfToBeExecuted(
      String version, Set<String> lookUp, List<String> queries) {
    List<MigrationDAO.ServerMigrationSQLTable> result = new ArrayList<>();
    for (String query : queries) {
      MigrationDAO.ServerMigrationSQLTable tableContent = buildServerMigrationTable(version, query);
      if (!lookUp.contains(tableContent.getCheckSum())) {
        result.add(tableContent);
      } else {
        LOG.debug("Query will be skipped in Migration Step , as this has already been executed");
      }
    }
    return result;
  }

  public static void dataMigrationFQNHashing(Handle handle, CollectionDAO collectionDAO) {
    // Migration for Entities with Name as their FQN
    // We need to quote the FQN, if these entities have "." in their name we are storing it as it is
    // into the FQN field.
    updateFQNHashForEntityWithName(handle, Bot.class, collectionDAO.botDAO());
    updateFQNHashForEntityWithName(handle, User.class, collectionDAO.userDAO());
    updateFQNHashForEntityWithName(handle, Team.class, collectionDAO.teamDAO());

    // Update all the services
    updateFQNHashForEntityWithName(handle, DatabaseService.class, collectionDAO.dbServiceDAO());
    updateFQNHashForEntityWithName(handle, DashboardService.class, collectionDAO.dashboardServiceDAO());
    updateFQNHashForEntityWithName(handle, MessagingService.class, collectionDAO.messagingServiceDAO());
    updateFQNHashForEntityWithName(handle, MetadataService.class, collectionDAO.metadataServiceDAO());
    updateFQNHashForEntityWithName(handle, MlModelService.class, collectionDAO.mlModelServiceDAO());
    updateFQNHashForEntityWithName(handle, StorageService.class, collectionDAO.storageServiceDAO());
    updateFQNHashForEntityWithName(handle, PipelineService.class, collectionDAO.pipelineServiceDAO());
    updateFQNHashForEntity(handle, IngestionPipeline.class, collectionDAO.ingestionPipelineDAO());

    // Update Entities
    updateFQNHashForEntity(handle, Database.class, collectionDAO.databaseDAO());
    updateFQNHashForEntity(handle, DatabaseSchema.class, collectionDAO.databaseSchemaDAO());
    updateFQNHashForEntity(handle, Table.class, collectionDAO.tableDAO());
    updateFQNHashForEntity(handle, Query.class, collectionDAO.queryDAO());
    updateFQNHashForEntity(handle, Topic.class, collectionDAO.topicDAO());
    updateFQNHashForEntity(handle, Dashboard.class, collectionDAO.dashboardDAO());
    updateFQNHashForEntity(handle, DashboardDataModel.class, collectionDAO.dashboardDataModelDAO());
    updateFQNHashForEntity(handle, Chart.class, collectionDAO.chartDAO());
    updateFQNHashForEntity(handle, Container.class, collectionDAO.containerDAO());
    updateFQNHashForEntity(handle, MlModel.class, collectionDAO.mlModelDAO());
    updateFQNHashForEntity(handle, Pipeline.class, collectionDAO.pipelineDAO());
    updateFQNHashForEntity(handle, Metrics.class, collectionDAO.metricsDAO());
    updateFQNHashForEntity(handle, Report.class, collectionDAO.reportDAO());

    // Update Glossaries & Classifications
    updateFQNHashForEntity(handle, Classification.class, collectionDAO.classificationDAO());
    updateFQNHashForEntity(handle, Glossary.class, collectionDAO.glossaryDAO());
    updateFQNHashForEntity(handle, GlossaryTerm.class, collectionDAO.glossaryTermDAO());
    updateFQNHashForEntity(handle, Tag.class, collectionDAO.tagDAO());

    // Update DataInsights
    updateFQNHashForEntity(handle, DataInsightChart.class, collectionDAO.dataInsightChartDAO());
    updateFQNHashForEntity(handle, Kpi.class, collectionDAO.kpiDAO());

    // Update DQ
    updateFQNHashForEntity(handle, TestCase.class, collectionDAO.testCaseDAO());
    updateFQNHashForEntity(handle, TestConnectionDefinition.class, collectionDAO.testConnectionDefinitionDAO());
    updateFQNHashForEntity(handle, TestDefinition.class, collectionDAO.testDefinitionDAO());
    updateFQNHashForEntity(handle, TestSuite.class, collectionDAO.testSuiteDAO());

    // Update Misc
    updateFQNHashForEntity(handle, Policy.class, collectionDAO.policyDAO());
    updateFQNHashForEntity(handle, EventSubscription.class, collectionDAO.eventSubscriptionDAO());
    updateFQNHashForEntity(handle, Role.class, collectionDAO.roleDAO());
    updateFQNHashForEntity(handle, Type.class, collectionDAO.typeEntityDAO());
    updateFQNHashForEntity(handle, WebAnalyticEvent.class, collectionDAO.webAnalyticEventDAO());
    updateFQNHashForEntity(handle, Workflow.class, collectionDAO.workflowDAO());

    // Field Relationship
    updateFQNHashForFieldRelationship(collectionDAO);

    // TimeSeries
    if (Boolean.TRUE.equals(DatasourceConfig.getInstance().isMySQL())) {
      updateFQNHashEntityExtensionTimeSeries(handle, MYSQL_ENTITY_EXTENSION_TIME_SERIES_UPDATE, collectionDAO);
    } else {
      updateFQNHashEntityExtensionTimeSeries(handle, POSTGRES_ENTITY_EXTENSION_TIME_SERIES_UPDATE, collectionDAO);
    }

    // Tag Usage
    updateFQNHashTagUsage(collectionDAO);
  }

  private static void updateFQNHashForFieldRelationship(CollectionDAO collectionDAO) {
    LOG.debug("Starting Migration for Field Relationship");
    int limitParam = 200;
    int offset = 0;
    int totalCount = collectionDAO.fieldRelationshipDAO().listCount();
    while (offset < totalCount) {
      List<CollectionDAO.FieldRelationshipDAO.FieldRelationship> fieldRelationships =
          collectionDAO.fieldRelationshipDAO().listWithOffset(limitParam, offset);
      for (CollectionDAO.FieldRelationshipDAO.FieldRelationship fieldRelationship : fieldRelationships) {
        if (CommonUtil.nullOrEmpty(fieldRelationship.getFromFQNHash())
            && CommonUtil.nullOrEmpty(fieldRelationship.getToFQNHash())) {
          String fromFQNHash = "";
          String toFQNHash = "";
          try {
            fromFQNHash = FullyQualifiedName.buildHash(fieldRelationship.getFromFQN());
            toFQNHash = FullyQualifiedName.buildHash(fieldRelationship.getToFQN());
          } catch (Exception ex) {
            LOG.error("Failed in creating FromFQNHash : {} , toFQNHash : {}", fromFQNHash, toFQNHash, ex);
            // Update further rows
            continue;
          }
          collectionDAO
              .fieldRelationshipDAO()
              .upsertFQNHash(
                  fromFQNHash,
                  toFQNHash,
                  fieldRelationship.getFromFQN(),
                  fieldRelationship.getToFQN(),
                  fieldRelationship.getFromType(),
                  fieldRelationship.getToType(),
                  fieldRelationship.getRelation(),
                  fieldRelationship.getJsonSchema(),
                  fieldRelationship.getJson());
        }
      }
      offset = offset + limitParam;
    }
    LOG.debug("End Migration for Field Relationship");
  }

  private static void updateFQNHashEntityExtensionTimeSeries(
      Handle handle, String updateSql, CollectionDAO collectionDAO) {
    LOG.debug("Starting Migration for Entity Extension Time Series");
    int limitParam = 1000;
    int offset = 0;
    int totalCount = collectionDAO.entityExtensionTimeSeriesDao().listAllCount();
    PreparedBatch upsertBatch = handle.prepareBatch(updateSql);
    while (offset < totalCount) {
      List<CollectionDAO.EntityExtensionTimeSeriesDAO.EntityExtensionTimeSeriesTable> timeSeriesTables =
          collectionDAO.entityExtensionTimeSeriesDao().listWithOffset(limitParam, offset);

      for (CollectionDAO.EntityExtensionTimeSeriesDAO.EntityExtensionTimeSeriesTable timeSeries : timeSeriesTables) {
        if (CommonUtil.nullOrEmpty(timeSeries.getEntityFQNHash())) {
          String entityFQN = "";
          try {
            entityFQN = FullyQualifiedName.buildHash(timeSeries.getEntityFQN());
          } catch (Exception ex) {
            LOG.error("Failed in creating EntityFQN : {}", entityFQN, ex);
            // Update further rows
            continue;
          }
          upsertBatch
              .bind("entityFQNHash", entityFQN)
              .bind("entityFQN", timeSeries.getEntityFQN())
              .bind("extension", timeSeries.getExtension())
              .bind("timestamp", timeSeries.getTimestamp())
              .add();
        }
      }
      upsertBatch.execute();
      offset = offset + limitParam;
    }
    LOG.debug("Ended Migration for Entity Extension Time Series");
  }

  public static void updateFQNHashTagUsage(CollectionDAO collectionDAO) {
    LOG.debug("Starting Migration for Tag Usage");
    List<CollectionDAO.TagUsageDAO.TagLabelMigration> tagLabelMigrationList = collectionDAO.tagUsageDAO().listAll();
    for (CollectionDAO.TagUsageDAO.TagLabelMigration tagLabel : tagLabelMigrationList) {
      if (CommonUtil.nullOrEmpty(tagLabel.getTagFQNHash()) && CommonUtil.nullOrEmpty(tagLabel.getTargetFQNHash())) {
        String tagFQNHash = "";
        String targetFQNHash = "";
        try {
          tagFQNHash = FullyQualifiedName.buildHash(tagLabel.getTagFQN());
          targetFQNHash = FullyQualifiedName.buildHash(tagLabel.getTargetFQN());
        } catch (Exception ex) {
          LOG.error("Failed in creating tagFQNHash : {}, targetFQNHash: {}", tagFQNHash, targetFQNHash, ex);
          // Update further rows
          continue;
        }
        collectionDAO
            .tagUsageDAO()
            .upsertFQNHash(
                tagLabel.getSource(),
                tagLabel.getTagFQN(),
                tagFQNHash,
                targetFQNHash,
                tagLabel.getLabelType(),
                tagLabel.getState(),
                tagLabel.getTargetFQN());
      }
    }
    LOG.debug("Ended Migration for Tag Usage");
  }

  public static void performSqlExecutionAndUpdation(
      MigrationStep step, MigrationDAO migrationDAO, Handle handle, List<String> queryList) {
    // These are DDL Statements and will cause an Implicit commit even if part of transaction still committed inplace
    Set<String> executedSQLChecksums =
        new HashSet<>(migrationDAO.getServerMigrationSQLWithVersion(String.valueOf(step.getMigrationVersion())));
    // Execute the Statements as batch
    List<MigrationDAO.ServerMigrationSQLTable> toBeExecuted =
        addInListIfToBeExecuted(String.valueOf(step.getMigrationVersion()), executedSQLChecksums, queryList);

    for (MigrationDAO.ServerMigrationSQLTable tableData : toBeExecuted) {
      handle.execute(tableData.getSqlStatement());
      migrationDAO.upsertServerMigrationSQL(
          tableData.getVersion(), tableData.getSqlStatement(), tableData.getCheckSum());
    }
  }

  public static TestSuite getTestSuite(CollectionDAO dao, CreateTestSuite create, String user) throws IOException {
    TestSuite testSuite =
        copy(new TestSuite(), create, user)
            .withDescription(create.getDescription())
            .withDisplayName(create.getDisplayName())
            .withName(create.getName());
    if (create.getExecutableEntityReference() != null) {
      TableRepository tableRepository = new TableRepository(dao);
      Table table =
          JsonUtils.readValue(
              tableRepository.getDao().findJsonByFqn(create.getExecutableEntityReference(), Include.ALL), Table.class);
      EntityReference entityReference =
          new EntityReference()
              .withId(table.getId())
              .withFullyQualifiedName(table.getFullyQualifiedName())
              .withName(table.getName())
              .withType(Entity.TABLE);
      testSuite.setExecutableEntityReference(entityReference);
    }
    return testSuite;
  }

  public static TestSuite copy(TestSuite entity, CreateEntity request, String updatedBy) {
    entity.setId(UUID.randomUUID());
    entity.setName(request.getName());
    entity.setDisplayName(request.getDisplayName());
    entity.setDescription(request.getDescription());
    entity.setExtension(request.getExtension());
    entity.setUpdatedBy(updatedBy);
    entity.setOwner(null);
    entity.setUpdatedAt(System.currentTimeMillis());
    return entity;
  }

  @SneakyThrows
  public static void testSuitesMigration(CollectionDAO collectionDAO) {
    IngestionPipelineRepository ingestionPipelineRepository = new IngestionPipelineRepository(collectionDAO);
    TestSuiteRepository testSuiteRepository = new TestSuiteRepository(collectionDAO);
    TestCaseRepository testCaseRepository = new TestCaseRepository(collectionDAO);
    List<TestCase> testCases = testCaseRepository.listAll(new Fields(Set.of("id")), new ListFilter(Include.ALL));

    for (TestCase test : testCases) {

      // Create New Executable Test Suites
      MessageParser.EntityLink entityLink = MessageParser.EntityLink.parse(test.getEntityLink());
      // Create new Logical Test Suite
      String testSuiteFqn = entityLink.getEntityFQN() + ".testSuite";
      TestSuite stored;
      try {
        // If entity is found by Hash it is already migrated
        testSuiteRepository.getByName(
            null,
            EntityInterfaceUtil.quoteName(FullyQualifiedName.buildHash(testSuiteFqn)),
            new Fields(Set.of("id")),
            Include.ALL);
      } catch (EntityNotFoundException entityNotFoundException) {
        try {
          // Check if the test Suite Exists, this brings the data on nameHash basis
          stored =
              testSuiteRepository.getByName(
                  null, EntityInterfaceUtil.quoteName(testSuiteFqn), new Fields(Set.of("id")), Include.ALL);
          testSuiteRepository.addRelationship(
              stored.getId(), test.getId(), TEST_SUITE, TEST_CASE, Relationship.CONTAINS);
          stored.setExecutable(true);
          stored.setName(FullyQualifiedName.buildHash(testSuiteFqn));
          // the update() method here internally calls FullyQualifiedName.buildHash so not adding it
          stored.setFullyQualifiedName(EntityInterfaceUtil.quoteName(FullyQualifiedName.buildHash(testSuiteFqn)));
          stored.setDisplayName(testSuiteFqn);
          testSuiteRepository.getDao().update(stored);
        } catch (EntityNotFoundException ex) {
          TestSuite newExecutableTestSuite =
              getTestSuite(
                      collectionDAO,
                      new CreateTestSuite()
                          .withName(FullyQualifiedName.buildHash(testSuiteFqn))
                          .withDisplayName(testSuiteFqn)
                          .withExecutableEntityReference(entityLink.getEntityFQN()),
                      "ingestion-bot")
                  .withExecutable(false);
          // Create
          testSuiteRepository.create(null, newExecutableTestSuite);
          // Here we aer manually adding executable relationship since the table Repository is not registered and result
          // into null for entity type table
          testSuiteRepository.addRelationship(
              newExecutableTestSuite.getExecutableEntityReference().getId(),
              newExecutableTestSuite.getId(),
              Entity.TABLE,
              TEST_SUITE,
              Relationship.CONTAINS);

          // add relationship from testSuite to TestCases
          testSuiteRepository.addRelationship(
              newExecutableTestSuite.getId(), test.getId(), TEST_SUITE, TEST_CASE, Relationship.CONTAINS);

          // Not a good approach but executable cannot be set true before
          TestSuite temp =
              testSuiteRepository
                  .getDao()
                  .findEntityByName(EntityInterfaceUtil.quoteName(FullyQualifiedName.buildHash(testSuiteFqn)));
          temp.setExecutable(true);
          testSuiteRepository.getDao().update(temp);
        }
      }
    }

    // Update Test Suites
    ListFilter filter = new ListFilter(Include.ALL);
    filter.addQueryParam("testSuiteType", "logical");
    List<TestSuite> testSuites = testSuiteRepository.listAll(new Fields(Set.of("id")), filter);

    for (TestSuite testSuiteRecord : testSuites) {
      TestSuite temp = testSuiteRepository.getDao().findEntityById(testSuiteRecord.getId());
      if (Boolean.FALSE.equals(temp.getExecutable())) {
        temp.setExecutable(false);
        testSuiteRepository.getDao().update(temp);
      }

      // get Ingestion Pipelines
      try {
        List<CollectionDAO.EntityRelationshipRecord> ingestionPipelineRecords =
            collectionDAO
                .relationshipDAO()
                .findTo(
                    testSuiteRecord.getId().toString(),
                    TEST_SUITE,
                    Relationship.CONTAINS.ordinal(),
                    INGESTION_PIPELINE);
        for (CollectionDAO.EntityRelationshipRecord ingestionRecord : ingestionPipelineRecords) {
          // remove relationship
          collectionDAO.relationshipDAO().deleteAll(ingestionRecord.getId().toString(), INGESTION_PIPELINE);
          // Cannot use Delete directly it uses other repos internally
          ingestionPipelineRepository.getDao().delete(ingestionRecord.getId().toString());
        }
      } catch (EntityNotFoundException ex) {
        // Already Removed
      }
    }
  }
}
