package it.unibas.lunatic.model.chase.chasede.operators.dbms;

import it.unibas.lunatic.LunaticConstants;
import it.unibas.lunatic.Scenario;
import it.unibas.lunatic.model.chase.chasede.operators.IBuildDatabaseForDE;
import it.unibas.lunatic.model.chase.chasemc.operators.CheckConsistencyOfDBOIDs;
import it.unibas.lunatic.model.chase.commons.ChaseStats;
import it.unibas.lunatic.model.chase.commons.operators.ChaseUtility;
import speedy.model.database.Attribute;
import speedy.model.database.AttributeRef;
import speedy.model.database.IDatabase;
import speedy.model.database.ITable;
import speedy.model.database.dbms.DBMSDB;
import speedy.model.database.dbms.DBMSVirtualDB;
import it.unibas.lunatic.model.dependency.Dependency;
import it.unibas.lunatic.persistence.relational.LunaticDBMSUtility;
import it.unibas.lunatic.model.dependency.operators.DependencyUtility;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import speedy.SpeedyConstants;
import speedy.model.algebra.CreateTableAs;
import speedy.model.algebra.Distinct;
import speedy.model.algebra.GroupBy;
import speedy.model.algebra.IAlgebraOperator;
import speedy.model.algebra.Join;
import speedy.model.algebra.Project;
import speedy.model.algebra.RestoreOIDs;
import speedy.model.algebra.Scan;
import speedy.model.algebra.aggregatefunctions.IAggregateFunction;
import speedy.model.algebra.aggregatefunctions.MaxAggregateFunction;
import speedy.model.algebra.aggregatefunctions.ValueAggregateFunction;
import speedy.model.algebra.operators.sql.AlgebraTreeToSQL;
import speedy.model.database.TableAlias;
import speedy.model.database.dbms.DBMSVirtualTable;
import speedy.model.thread.IBackgroundThread;
import speedy.model.thread.ThreadManager;
import speedy.persistence.relational.AccessConfiguration;
import speedy.persistence.relational.QueryManager;
import speedy.utility.SpeedyUtility;

public class BuildSQLDBForChaseStepDE implements IBuildDatabaseForDE {

    private static Logger logger = LoggerFactory.getLogger(BuildSQLDBForChaseStepDE.class);
    private AlgebraTreeToSQL sqlGenerator = new AlgebraTreeToSQL();
    private CheckConsistencyOfDBOIDs oidChecker = new CheckConsistencyOfDBOIDs();
    private boolean useHash = true;
    private boolean checkOIDsInTables;

    public BuildSQLDBForChaseStepDE(boolean checkOIDsInTables) {
        this.checkOIDsInTables = checkOIDsInTables;
    }

    @Override
    public IDatabase extractDatabase(IDatabase deltaDB, IDatabase originalDB, Scenario scenario) {
        return extractDatabase(deltaDB, originalDB, false, scenario);
    }

    @Override
    public IDatabase extractDatabaseWithDistinct(IDatabase deltaDB, IDatabase originalDB, Scenario scenario) {
        if (logger.isDebugEnabled()) logger.debug("Extracting database with distinct");
        return extractDatabase(deltaDB, originalDB, true, scenario);
    }

    private IDatabase extractDatabase(IDatabase deltaDB, IDatabase originalDB, boolean distinct, Scenario scenario) {
        long start = new Date().getTime();
        if (logger.isDebugEnabled()) logger.debug("Generating database ");
        //Materialize join
        Map<String, List<AttributeRef>> attributeMap = new HashMap<String, List<AttributeRef>>();
        for (String tableName : originalDB.getTableNames()) {
            attributeMap.put(tableName, buildAttributeRefs(originalDB.getTable(tableName)));
        }
        Map<String, String> tableViews = extractDatabase(null, deltaDB, originalDB, attributeMap, true, distinct, scenario);
        int maxNumberOfThreads = scenario.getConfiguration().getMaxNumberOfThreads();
        ThreadManager threadManager = new ThreadManager(maxNumberOfThreads);
        for (String tableName : tableViews.keySet()) {
            String viewScript = tableViews.get(tableName);
            ExtractTableThread exThread = new ExtractTableThread(viewScript, ((DBMSDB) originalDB).getAccessConfiguration());
            threadManager.startThread(exThread);
        }
        threadManager.waitForActiveThread();
        AccessConfiguration accessConfiguration = ((DBMSDB) deltaDB).getAccessConfiguration();
        DBMSVirtualDB virtualDB = new DBMSVirtualDB((DBMSDB) originalDB, ((DBMSDB) deltaDB), "", accessConfiguration);
        analyzeTables(virtualDB, tableViews.keySet());
        long end = new Date().getTime();
        ChaseStats.getInstance().addStat(ChaseStats.STEP_DB_BUILDER, end - start);
        if (logger.isInfoEnabled()) logger.info("Generated database  - " + (end - start) + " ms");
        if (checkOIDsInTables) {
            oidChecker.checkDatabase(virtualDB);
        }
        return virtualDB;
    }

    @Override
    public IDatabase extractDatabase(IDatabase deltaDB, IDatabase originalDB, Dependency dependency, Scenario scenario) {
        long start = new Date().getTime();
        if (logger.isDebugEnabled()) logger.debug("Generating database for depedency " + dependency);
        //Materialize join
        Map<String, List<AttributeRef>> tablesAndAttributesToExtract = new HashMap<String, List<AttributeRef>>();
        List<AttributeRef> requestedAttributesForDependency = DependencyUtility.extractRequestedAttributesWithExistential(dependency);
        if (requestedAttributesForDependency.isEmpty()) {
            throw new IllegalArgumentException("Unable to find relevant attributes for dependency " + dependency);
        }
        for (AttributeRef attribute : requestedAttributesForDependency) {
            List<AttributeRef> attributesForTable = tablesAndAttributesToExtract.get(attribute.getTableName());
            if (attributesForTable == null) {
                attributesForTable = new ArrayList<AttributeRef>();
                tablesAndAttributesToExtract.put(attribute.getTableName(), attributesForTable);
            }
            attributesForTable.add(attribute);
        }
        StringBuilder script = new StringBuilder();
        Map<String, String> tableViews = extractDatabase(dependency.getId(), deltaDB, originalDB, tablesAndAttributesToExtract, true, false, scenario);
        for (String tableName : tableViews.keySet()) {
            String viewScript = tableViews.get(tableName);
            script.append(viewScript).append("\n");
        }
        if (logger.isDebugEnabled()) logger.debug("View paramized script:\n" + script);
        QueryManager.executeScript(script.toString(), ((DBMSDB) originalDB).getAccessConfiguration(), true, true, true, false);
        AccessConfiguration accessConfiguration = ((DBMSDB) deltaDB).getAccessConfiguration();
        DBMSVirtualDB virtualDB = new DBMSVirtualDB((DBMSDB) originalDB, ((DBMSDB) deltaDB), "_" + dependency.getId(), accessConfiguration);
        analyzeTables(virtualDB, tableViews.keySet());
        long end = new Date().getTime();
        ChaseStats.getInstance().addStat(ChaseStats.STEP_DB_BUILDER, end - start);
        if (logger.isInfoEnabled()) logger.info("Generating database for depedency " + dependency + " - " + (end - start) + " ms");
        if (checkOIDsInTables) {
            oidChecker.checkDatabase(virtualDB);
        }
        return virtualDB;
    }

    private void analyzeTables(DBMSVirtualDB virtualDB, Set<String> tablesToExtract) {
        String schemaName = virtualDB.getAccessConfiguration().getSchemaAndSuffix();
        assert (!virtualDB.getTableNames().isEmpty());
        for (String tableName : virtualDB.getTableNames()) {
            if (!tablesToExtract.contains(tableName)) {
                continue;
            }
            DBMSVirtualTable virtualTable = (DBMSVirtualTable) virtualDB.getTable(tableName);
            QueryManager.executeScript("ANALYZE " + schemaName + "." + virtualTable.getVirtualName(), virtualDB.getAccessConfiguration(), true, true, true, false);
        }
    }

    private Map<String, String> extractDatabase(String dependencyId, IDatabase deltaDB, IDatabase originalDB, Map<String, List<AttributeRef>> tablesAndAttributesToExtract, boolean materialize, boolean distinct, Scenario scenario) {
        String deltaDBSchemaName = LunaticDBMSUtility.getSchemaWithSuffix(((DBMSDB) deltaDB).getAccessConfiguration(), scenario);
        Set<String> tableNames = tablesAndAttributesToExtract.keySet();
        Map<String, String> tableViews = new HashMap<String, String>();
        for (String tableName : tableNames) {
            ITable table = originalDB.getTable(tableName);
            List<AttributeRef> affectedAttributes = new ArrayList<AttributeRef>(tablesAndAttributesToExtract.get(tableName));
            List<AttributeRef> nonAffectedAttributes = new ArrayList<AttributeRef>();
            List<AttributeRef> deltaTableAttributes = new ArrayList<AttributeRef>();
            IAlgebraOperator initialTable = generateInitialTable(tableName, affectedAttributes, nonAffectedAttributes, deltaDB, deltaTableAttributes, materialize, scenario);
            AttributeRef oidAttributeRef = findOidAttribute(initialTable, deltaDB);
            IAlgebraOperator algebraRoot;
            if (affectedAttributes.isEmpty()) {
                if (distinct) {
                    removeOIDAttribute(deltaTableAttributes);
                }
                IAlgebraOperator projection = new Project(SpeedyUtility.createProjectionAttributes(deltaTableAttributes), cleanNames(deltaTableAttributes), true);
                projection.addChild(initialTable);
                algebraRoot = projection;
            } else {
                algebraRoot = buildAlgebraTreeForTable(table, affectedAttributes, nonAffectedAttributes, deltaDBSchemaName, oidAttributeRef, initialTable, deltaTableAttributes, materialize, distinct);
            }
            if (distinct) {
                algebraRoot = createDistinctTable(algebraRoot);
            }
            IAlgebraOperator resultOperator;
            if (materialize) {
                String materializedTableName = tableName + (dependencyId == null ? "" : "_" + dependencyId);
                CreateTableAs createTable = new CreateTableAs(materializedTableName, materializedTableName, deltaDBSchemaName, distinct, true);
                createTable.addChild(algebraRoot);
                resultOperator = createTable;
            } else {
                resultOperator = algebraRoot;
            }
            if (logger.isDebugEnabled()) logger.debug("Algebra for extract database: \n" + resultOperator);
            String query = sqlGenerator.treeToSQL(resultOperator, null, deltaDB, "");
            if (logger.isDebugEnabled()) logger.debug("Script for extract database: \n" + query);
            tableViews.put(tableName, query);
        }
        return tableViews;
    }

    private IAlgebraOperator createDistinctTable(IAlgebraOperator algebraRoot) {
        Distinct distinct = new Distinct();
        distinct.addChild(algebraRoot);
        return distinct;
    }

    private AttributeRef findOidAttribute(IAlgebraOperator initialTable, IDatabase deltaDB) {
        for (AttributeRef attribute : initialTable.getAttributes(null, deltaDB)) {
            if (attribute.getName().equals(SpeedyConstants.TID)) {
                return attribute;
            }
        }
        throw new IllegalArgumentException("Unable to find oid attribute in " + initialTable);
    }

    private IAlgebraOperator generateInitialTable(String tableName, List<AttributeRef> affectedAttributes, List<AttributeRef> nonAffectedAttributes, IDatabase deltaDB, List<AttributeRef> deltaTableAttributes, boolean materialize, Scenario scenario) {
        for (Iterator<AttributeRef> it = affectedAttributes.iterator(); it.hasNext();) {
            AttributeRef attributeRef = it.next();
            if (isNotAffected(attributeRef, deltaDB)) {
                it.remove();
                nonAffectedAttributes.add(attributeRef);
            }
        }
        String deltaDBSchemaName = LunaticDBMSUtility.getSchemaWithSuffix(((DBMSDB) deltaDB).getAccessConfiguration(), scenario);
        IAlgebraOperator initialTable;
        if (!nonAffectedAttributes.isEmpty()) {
            String tableNameForNonAffected = tableName + LunaticConstants.NA_TABLE_SUFFIX;
            Scan scan = new Scan(new TableAlias(tableNameForNonAffected));
            initialTable = scan;
            deltaTableAttributes.add(new AttributeRef(tableNameForNonAffected, SpeedyConstants.TID));
            for (AttributeRef attributeRef : nonAffectedAttributes) {
                deltaTableAttributes.add(new AttributeRef(tableNameForNonAffected, attributeRef.getName()));
            }
        } else {
            AttributeRef firstAttributeRef = affectedAttributes.remove(0);
            nonAffectedAttributes.add(firstAttributeRef);
            deltaTableAttributes.add(new AttributeRef(ChaseUtility.getDeltaRelationName(tableName, firstAttributeRef.getName()), SpeedyConstants.TID));
            deltaTableAttributes.add(new AttributeRef(new TableAlias(ChaseUtility.getDeltaRelationName(tableName, firstAttributeRef.getName()), "0"), firstAttributeRef.getName()));
            initialTable = buildTreeForAttribute(firstAttributeRef, deltaDBSchemaName, materialize);
        }
        return initialTable;
    }

    private IAlgebraOperator buildTreeForAttribute(AttributeRef attribute, String deltaDBSchemaName, boolean materialize) {
        TableAlias table = new TableAlias(ChaseUtility.getDeltaRelationName(attribute.getTableName(), attribute.getName()));
        Scan tableScan = new Scan(table);
        // select max(version), oid from R_A group by oid
        AttributeRef oid = new AttributeRef(table, SpeedyConstants.TID);
        AttributeRef version = new AttributeRef(table, SpeedyConstants.VERSION);
        List<AttributeRef> groupingAttributes = new ArrayList<AttributeRef>(Arrays.asList(new AttributeRef[]{oid}));
        IAggregateFunction max = new MaxAggregateFunction(version);
        IAggregateFunction oidValue = new ValueAggregateFunction(oid);
        List<IAggregateFunction> aggregateFunctions = new ArrayList<IAggregateFunction>(Arrays.asList(new IAggregateFunction[]{max, oidValue}));
        GroupBy groupBy = new GroupBy(groupingAttributes, aggregateFunctions);
        groupBy.addChild(tableScan);
        // select * from R_A_1
        TableAlias alias = new TableAlias(table.getTableName(), "0");
        Scan aliasScan = new Scan(alias);
        // select * from (group-by) join R_A_1 on oid, version
        List<AttributeRef> leftAttributes = new ArrayList<AttributeRef>(Arrays.asList(new AttributeRef[]{oid, version}));
        AttributeRef oidInAlias = new AttributeRef(alias, SpeedyConstants.TID);
        AttributeRef versionInAlias = new AttributeRef(alias, SpeedyConstants.VERSION);
        List<AttributeRef> rightAttributes = new ArrayList<AttributeRef>(Arrays.asList(new AttributeRef[]{oidInAlias, versionInAlias}));
        Join join = new Join(leftAttributes, rightAttributes);
        join.addChild(groupBy);
        join.addChild(aliasScan);
        // select oid, A from (join)
        AttributeRef attributeInAlias = new AttributeRef(alias, attribute.getName());
        List<AttributeRef> projectionAttributes = new ArrayList<AttributeRef>(Arrays.asList(new AttributeRef[]{oid, attributeInAlias}));
        Project project = new Project(SpeedyUtility.createProjectionAttributes(projectionAttributes));
        project.addChild(join);
        IAlgebraOperator resultOperator;
        if (materialize) {
            String tableName = "tmp_" + LunaticDBMSUtility.attributeRefToAliasSQL(attribute);
            String tableAlias = LunaticDBMSUtility.attributeRefToAliasSQL(attribute);
            CreateTableAs createTable = new CreateTableAs(tableName, tableAlias, deltaDBSchemaName, false, true);
            createTable.addChild(project);
            resultOperator = createTable;
        } else {
            resultOperator = project;
        }
        if (logger.isDebugEnabled()) logger.debug("Algebra tree for attribute: " + attribute + "\n" + resultOperator);
        return resultOperator;
    }

    private IAlgebraOperator buildAlgebraTreeForTable(ITable table, List<AttributeRef> affectedAttributes, List<AttributeRef> nonAffectedAttributes, String deltaDBSchemaName, AttributeRef oidAttributeRef, IAlgebraOperator deltaForFirstAttribute, List<AttributeRef> deltaTableAttributes, boolean materialize, boolean distinct) {
        IAlgebraOperator leftChild = deltaForFirstAttribute;
        for (AttributeRef attributeRef : affectedAttributes) {
            deltaTableAttributes.add(new AttributeRef(new TableAlias(ChaseUtility.getDeltaRelationName(table.getName(), attributeRef.getName()), "0"), attributeRef.getName()));
            IAlgebraOperator deltaForAttribute = buildTreeForAttribute(attributeRef, deltaDBSchemaName, materialize);
            AttributeRef oid = new AttributeRef(ChaseUtility.getDeltaRelationName(table.getName(), attributeRef.getName()), SpeedyConstants.TID);
            Join join = new Join(oidAttributeRef, oid);
            join.addChild(leftChild);
            join.addChild(deltaForAttribute);
            leftChild = join;
        }
        List<AttributeRef> projectionAttributes = new ArrayList<AttributeRef>();
        projectionAttributes.add(new AttributeRef(table.getName(), SpeedyConstants.OID));
        projectionAttributes.addAll(nonAffectedAttributes);
        projectionAttributes.addAll(affectedAttributes);
        sortAttributes(deltaTableAttributes, table.getAttributes());
        sortAttributes(projectionAttributes, table.getAttributes());
        if (distinct) {
            removeOIDAttribute(deltaTableAttributes);
            removeOIDAttribute(projectionAttributes);
        }
        Project project = new Project(SpeedyUtility.createProjectionAttributes(deltaTableAttributes), projectionAttributes, true);
        project.addChild(leftChild);
        RestoreOIDs restore = new RestoreOIDs(new AttributeRef(table.getName(), SpeedyConstants.OID));
        restore.addChild(project);
        if (logger.isDebugEnabled()) logger.debug("Algebra tree for table: " + table.getName() + "\n" + restore);
        return restore;
    }

    private List<AttributeRef> buildAttributeRefs(ITable table) {
        List<AttributeRef> result = new ArrayList<AttributeRef>();
        for (Attribute attribute : getAttributes(table)) {
            result.add(new AttributeRef(table.getName(), attribute.getName()));
        }
        return result;
    }

    private List<AttributeRef> cleanNames(List<AttributeRef> nonAffectedAttributes) {
        List<AttributeRef> result = new ArrayList<AttributeRef>();
        for (AttributeRef attributeRef : nonAffectedAttributes) {
            String tableName = attributeRef.getTableName();
            tableName = tableName.replaceAll(LunaticConstants.NA_TABLE_SUFFIX, "");
            String attributeName = attributeRef.getName();
            if (attributeName.equals(SpeedyConstants.TID)) {
                attributeName = SpeedyConstants.OID;
            }
            result.add(new AttributeRef(tableName, attributeName));
        }
        return result;
    }

    private boolean isNotAffected(AttributeRef attributeRef, IDatabase deltaDB) {
        List<String> tableNames = deltaDB.getTableNames();
        String deltaRelation = ChaseUtility.getDeltaRelationName(attributeRef.getTableName(), attributeRef.getName());
        for (String tableName : tableNames) {
            if (tableName.equalsIgnoreCase(deltaRelation)) {
                return false;
            }
        }
        return true;
    }

    private List<Attribute> getAttributes(ITable table) {
        List<Attribute> result = new ArrayList<Attribute>();
        for (Attribute attribute : table.getAttributes()) {
            if (attribute.getName().equals(SpeedyConstants.OID)) {
                continue;
            }
            result.add(attribute);
        }
        return result;
    }

    private void sortAttributes(List<AttributeRef> unsortedAttributes, List<Attribute> attributes) {
        if (unsortedAttributes.isEmpty()) {
            return;
        }
        List<AttributeRef> sortedAttributes = new ArrayList<AttributeRef>();
        sortedAttributes.add(unsortedAttributes.remove(0));
        for (Attribute attributeToSearch : attributes) {
            for (AttributeRef attributeRef : unsortedAttributes) {
                if (attributeRef.getName().equalsIgnoreCase(attributeToSearch.getName())) {
                    sortedAttributes.add(attributeRef);
                }
            }
        }
        unsortedAttributes.clear();
        unsortedAttributes.addAll(sortedAttributes);
    }

    private void removeOIDAttribute(List<AttributeRef> attributes) {
        for (Iterator<AttributeRef> it = attributes.iterator(); it.hasNext();) {
            AttributeRef attributeRef = it.next();
            if (attributeRef.getName().equals(SpeedyConstants.OID)
                    || attributeRef.getName().equals(SpeedyConstants.TID)) {
                it.remove();
            }
        }
    }

    private class ExtractTableThread implements IBackgroundThread {

        private String script;
        private AccessConfiguration accessConfiguration;

        public ExtractTableThread(String script, AccessConfiguration accessConfiguration) {
            this.script = script;
            this.accessConfiguration = accessConfiguration;
        }

        public void execute() {
            if (logger.isDebugEnabled()) logger.debug("View paramized script:\n" + script);
            QueryManager.executeScript(script, accessConfiguration, true, true, true, false);
        }

    }
}
