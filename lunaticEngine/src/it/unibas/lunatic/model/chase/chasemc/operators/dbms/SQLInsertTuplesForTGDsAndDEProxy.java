package it.unibas.lunatic.model.chase.chasemc.operators.dbms;

import it.unibas.lunatic.LunaticConfiguration;
import it.unibas.lunatic.LunaticConstants;
import it.unibas.lunatic.Scenario;
import it.unibas.lunatic.exceptions.DBMSException;
import it.unibas.lunatic.model.algebra.IAlgebraOperator;
import it.unibas.lunatic.model.algebra.sql.AlgebraTreeToSQL;
import it.unibas.lunatic.model.chase.commons.ChaseUtility;
import it.unibas.lunatic.model.chase.chasemc.DeltaChaseStep;
import it.unibas.lunatic.model.chase.chasemc.operators.IInsertTuplesForTGDsAndDEProxy;
import it.unibas.lunatic.model.chase.chasemc.operators.IOIDGenerator;
import it.unibas.lunatic.model.database.Attribute;
import it.unibas.lunatic.model.database.AttributeRef;
import it.unibas.lunatic.model.database.IDatabase;
import it.unibas.lunatic.model.database.ITable;
import it.unibas.lunatic.model.database.TableAlias;
import it.unibas.lunatic.model.database.dbms.DBMSDB;
import it.unibas.lunatic.model.database.dbms.DBMSTable;
import it.unibas.lunatic.model.database.mainmemory.datasource.OID;
import it.unibas.lunatic.model.database.skolem.AppendSkolemPart;
import it.unibas.lunatic.model.database.skolem.ISkolemPart;
import it.unibas.lunatic.model.database.skolem.StringSkolemPart;
import it.unibas.lunatic.model.dependency.Dependency;
import it.unibas.lunatic.model.dependency.FormulaAttribute;
import it.unibas.lunatic.model.dependency.FormulaVariable;
import it.unibas.lunatic.model.dependency.FormulaVariableOccurrence;
import it.unibas.lunatic.model.dependency.IFormulaAtom;
import it.unibas.lunatic.model.dependency.RelationalAtom;
import it.unibas.lunatic.model.generators.IValueGenerator;
import it.unibas.lunatic.model.generators.SkolemFunctionGenerator;
import it.unibas.lunatic.persistence.relational.AccessConfiguration;
import it.unibas.lunatic.persistence.relational.DBMSUtility;
import it.unibas.lunatic.persistence.relational.QueryManager;
import it.unibas.lunatic.utility.DependencyUtility;
import it.unibas.lunatic.utility.LunaticUtility;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SQLInsertTuplesForTGDsAndDEProxy implements IInsertTuplesForTGDsAndDEProxy {

    private static Logger logger = LoggerFactory.getLogger(SQLInsertTuplesForTGDsAndDEProxy.class);

    private IOIDGenerator oidGenerator;
    private AlgebraTreeToSQL queryBuilder = new AlgebraTreeToSQL();

    public SQLInsertTuplesForTGDsAndDEProxy(IOIDGenerator oidGenerator) {
        this.oidGenerator = oidGenerator;
    }

    @Override
    public boolean execute(IAlgebraOperator violationQuery, DeltaChaseStep currentNode, Dependency tgd, Scenario scenario, IDatabase databaseForStep) {
        if (!scenario.isDBMS()) {
            throw new DBMSException("Unable to generate SQL: data sources are not on a dbms");
        }
        Map<AttributeRef, IValueGenerator> targetGenerators = tgd.getTargetGenerators();
        if (logger.isDebugEnabled()) logger.debug("----Executing insert. Tgd generator map: " + LunaticUtility.printMap(targetGenerators));
        StringBuilder script = new StringBuilder();
        script.append(materializeViolationValues(tgd, violationQuery, databaseForStep, currentNode.getDeltaDB(), scenario));
        script.append(populateDeltaDB(tgd, currentNode.getId(), scenario));
        DBMSDB target = (DBMSDB) scenario.getTarget();
        AccessConfiguration accessConfiguration = (target).getAccessConfiguration();
        QueryManager.executeScript(script.toString(), accessConfiguration, true, true, true);
        int newTuples = countNewTuplesAndUpdateOIDGenerator(tgd, scenario);
        if (logger.isDebugEnabled()) logger.debug("New tuples " + newTuples);
        if (newTuples > 0) {
            if (LunaticConfiguration.sout) System.out.println("Cache for step " + currentNode.getId() + " is not valid anymore...");
        }
        return newTuples > 0;
    }

    private String materializeViolationValues(Dependency tgd, IAlgebraOperator violationQuery, IDatabase databaseForStep, IDatabase deltaDB, Scenario scenario) {
        StringBuilder query = new StringBuilder();
        String tmpTable = ChaseUtility.getTmpTableForTGDViolations(tgd, null, true);
        query.append("DROP TABLE IF EXISTS ").append(tmpTable).append(";\n");
        query.append("CREATE TABLE ").append(tmpTable).append(" WITH OIDS AS (\n");
        query.append("SELECT ");
        Map<AttributeRef, IValueGenerator> targetGenerators = tgd.getTargetGenerators();
        if (logger.isDebugEnabled()) logger.debug("----Tgd generator map: " + LunaticUtility.printMap(targetGenerators));
        for (IFormulaAtom atom : tgd.getConclusion().getPositiveFormula().getAtoms()) {
            RelationalAtom relationalAtom = (RelationalAtom) atom;
            TableAlias targetTableToInsert = relationalAtom.getTableAlias();
            query.append(generateCounterForOID(targetTableToInsert, violationQuery));
            for (FormulaAttribute attribute : relationalAtom.getAttributes()) {
                AttributeRef attributeRef = new AttributeRef(relationalAtom.getTableAlias(), attribute.getAttributeName());
                IValueGenerator generator = targetGenerators.get(attributeRef);
                FormulaVariableOccurrence occurrence = (FormulaVariableOccurrence) attribute.getValue();
                if (generator instanceof SkolemFunctionGenerator) {
                    FormulaVariable existentialVariable = LunaticUtility.findVariableInList(occurrence, tgd.getConclusion().getLocalVariables());
                    IValueGenerator sqlSkolemGenerator = createSkolemGeneratorForSQL(existentialVariable, tgd);
                    query.append(sqlSkolemGenerator.toString());
                } else {
                    FormulaVariable universalVariable = LunaticUtility.findVariableInList(occurrence, tgd.getPremise().getLocalVariables());
                    query.append(DBMSUtility.attributeRefToSQL(universalVariable.getPremiseRelationalOccurrences().get(0).getAttributeRef()));
                }
                query.append(" AS ").append(DBMSUtility.attributeRefToSQL(attributeRef));
                query.append(", ");
            }
        }
        LunaticUtility.removeChars(", ".length(), query);
        query.append(" FROM (").append("\n");
        //Violation query is a SQL EXCEPT. No SELECT DISTINCT is needed, because duplicates are eliminated unless EXCEPT ALL is used
        query.append(queryBuilder.treeToSQL(violationQuery, scenario.getSource(), databaseForStep, LunaticConstants.INDENT));
        query.append(") AS tmp\n");
        query.append(");\n");
        deltaDB.addTable(new DBMSTable(ChaseUtility.getTmpTableForTGDViolations(tgd, null, false), ((DBMSDB) deltaDB).getAccessConfiguration()));
        if (logger.isDebugEnabled()) logger.debug("----Violation query: " + violationQuery);
        if (logger.isDebugEnabled()) logger.debug("Materialization query for TGD " + tgd.getId() + "\n" + query);
        return query.toString();
    }

    private String generateCounterForOID(TableAlias targetTableToInsert, IAlgebraOperator violationQuery) {
        OID nextOID = oidGenerator.getNextOID(targetTableToInsert.getTableName());
        long lastOID = Long.parseLong(nextOID.toString()) - 1;
        StringBuilder result = new StringBuilder();
        result.append(lastOID);
        result.append(" + ROW_NUMBER() OVER (ORDER BY ");
        result.append(DBMSUtility.attributeRefToSQL(violationQuery.getAttributes(null, null).get(0)));
        result.append(") as ");
        result.append(DBMSUtility.attributeRefToSQL(new AttributeRef(targetTableToInsert, LunaticConstants.TID)));
        result.append(",");
        return result.toString();
    }

    private IValueGenerator createSkolemGeneratorForSQL(FormulaVariable variable, Dependency dependency) {
        ISkolemPart root = new AppendSkolemPart();
        ISkolemPart name = new StringSkolemPart("'" + LunaticConstants.SKOLEM_PREFIX + dependency.getId() + LunaticConstants.SKOLEM_SEPARATOR + variable.getId());
        root.addChild(name);
        AppendSkolemPart append = new AppendSkolemPart("(['||", "||'])'", "||']-['||");
        root.addChild(append);
        List<FormulaVariable> universalVariablesInConclusion = DependencyUtility.findUniversalVariablesInConclusion(dependency);
        for (FormulaVariable formulaVariable : universalVariablesInConclusion) {
            FormulaVariableOccurrence sourceOccurrence = formulaVariable.getPremiseRelationalOccurrences().get(0);
            String attributeString = DBMSUtility.attributeRefToSQL(sourceOccurrence.getAttributeRef());
            append.addChild(new StringSkolemPart(attributeString));
        }
        SkolemFunctionGenerator generatorForVariable = new SkolemFunctionGenerator(root);
        return generatorForVariable;
    }

    private int countNewTuplesAndUpdateOIDGenerator(Dependency tgd, Scenario scenario) {
        int newTuples = 0;
        for (IFormulaAtom atom : tgd.getConclusion().getPositiveFormula().getAtoms()) {
            RelationalAtom relationalAtom = (RelationalAtom) atom;
            TableAlias table = relationalAtom.getTableAlias();
            String tmpTable = ChaseUtility.getTmpTableForTGDViolations(tgd, table.getTableName(), true);
            String query = "SELECT count(*) as count FROM " + tmpTable;
            DBMSDB target = (DBMSDB) scenario.getTarget();
            AccessConfiguration accessConfiguration = (target).getAccessConfiguration();
            ResultSet resultSet = QueryManager.executeQuery(query, accessConfiguration);
            try {
                resultSet.next();
                int newTuplesForTable = resultSet.getInt("count");
                if (logger.isDebugEnabled()) logger.debug("New tuples for table " + table.getTableName() + ": " + newTuplesForTable);
                oidGenerator.addCounter(table.getTableName(), newTuplesForTable);
                newTuples += newTuplesForTable;
            } catch (SQLException ex) {
                throw new DBMSException("Unable to execute query " + query + " on database \n" + accessConfiguration + "\n" + ex);
            } finally {
                QueryManager.closeResultSet(resultSet);
            }
        }
        return newTuples;
    }

    private String populateDeltaDB(Dependency tgd, String stepId, Scenario scenario) {
        StringBuilder script = new StringBuilder();
        for (IFormulaAtom atom : tgd.getConclusion().getPositiveFormula().getAtoms()) {
            RelationalAtom relationalAtom = (RelationalAtom) atom;
            ITable table = scenario.getTarget().getTable(relationalAtom.getTableName());
            for (Attribute attribute : table.getAttributes()) {
                if (attribute.getName().equals(LunaticConstants.OID)) {
                    continue;
                }
                AttributeRef attributeRef = new AttributeRef(relationalAtom.getTableAlias(), attribute.getName());
                String deltaTableName = ChaseUtility.getDeltaRelationName(table.getName(), attribute.getName());
                String tmpTable = ChaseUtility.getTmpTableForTGDViolations(tgd, table.getName(), true);
                script.append("INSERT INTO ").append(LunaticConstants.WORK_SCHEMA + ".").append(deltaTableName).append(" ");
                script.append("SELECT '").append(stepId).append("', ");
                script.append(DBMSUtility.attributeRefToSQL(new AttributeRef(relationalAtom.getTableAlias(), LunaticConstants.TID))).append(", ");
//                script.append(DBMSUtility.attributeRefToSQL(attributeRef)).append(", '").append(LunaticConstants.GEN_GROUP_ID).append("'\n");
                script.append(DBMSUtility.attributeRefToSQL(attributeRef)).append(", NULL\n");
                script.append(LunaticConstants.INDENT).append("FROM ").append(tmpTable).append(";\n");
            }
        }
        return script.toString();
    }

    @Override
    public void initializeOIDs(IDatabase database) {
        oidGenerator.initializeOIDs(database);
    }
}