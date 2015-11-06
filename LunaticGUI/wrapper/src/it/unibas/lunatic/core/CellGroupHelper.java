package it.unibas.lunatic.core;

import it.unibas.lunatic.OperatorFactory;
import it.unibas.lunatic.Scenario;
import it.unibas.lunatic.model.chase.chasemc.CellGroup;
import it.unibas.lunatic.model.chase.chasemc.DeltaChaseStep;
import it.unibas.lunatic.model.chase.chasemc.operators.AddUserNode;
import it.unibas.lunatic.model.chase.chasemc.operators.CheckUnsatisfiedDependencies;
import it.unibas.lunatic.model.chase.chasemc.operators.IBuildDatabaseForChaseStep;
import it.unibas.lunatic.model.chase.chasemc.operators.IOccurrenceHandler;
import it.unibas.lunatic.model.chase.chasemc.operators.OccurrenceHandlerMC;
import it.unibas.lunatic.model.chase.chasemc.operators.ValueExtractor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import speedy.model.database.IValue;
import speedy.model.database.operators.IRunQuery;

public class CellGroupHelper {

    private static Log logger = LogFactory.getLog(CellGroupHelper.class);

    private OperatorFactory operatorFactory = OperatorFactory.getInstance();

    private CellGroupHelper() {
    }
    private static CellGroupHelper instance = new CellGroupHelper();

    public static CellGroupHelper getInstance() {
        return instance;
    }

    public StepCellGroups retrieveStepCellGroups(Scenario scenario, DeltaChaseStep step) {
        List<CellGroup> cellGroups = getValueOccurrenceHandlerMCExtractor(scenario).loadAllCellGroupsForDebugging(step.getDeltaDB(), step.getId(), scenario);
        logger.debug("############### cellGroups " +  cellGroups.toString());
        List<CellGroup> changedCellGroups = getValueOccurrenceHandlerMCExtractor(scenario).loadAllCellGroupsInStepForDebugging(step.getDeltaDB(), step.getId(), scenario);
        logger.debug("############### changedCellGroups " +  changedCellGroups.toString());
        return new StepCellGroups(cellGroups, changedCellGroups);
    }

    public CellGroup findCellGroup(Set<CellGroup> cgList, IValue value) {
        CellGroup selected = null;
        for (CellGroup c : cgList) {
            if (c.getValue().equals(value)) {
                selected = c;
                break;
            }
        }
        return selected;
    }

    public List<IValue> findCellGroupsValues(Scenario scenario, DeltaChaseStep step) {
        List<CellGroup> cgList = getValueOccurrenceHandlerMCExtractor(scenario).loadAllCellGroupsInStepForDebugging(step.getDeltaDB(), step.getId(), scenario);
        return extractValues(cgList);
    }

    public ValueExtractor getValueExtractor(Scenario s) {
        return new ValueExtractor(operatorFactory.getQueryRunner(s));
    }

    private IOccurrenceHandler getValueOccurrenceHandlerMCExtractor(Scenario s) {
        return operatorFactory.getOccurrenceHandler(s);
    }

    public List<IValue> extractValues(List<CellGroup> cgList) {
        List<IValue> cgValues = new ArrayList<IValue>();
        for (CellGroup cg : cgList) {
            cgValues.add(cg.getValue());
        }
        return cgValues;
    }

    public AddUserNode getEditor(Scenario scenario) {
        return operatorFactory.getUserNodeCreator(scenario);
    }

    public CheckUnsatisfiedDependencies getUnsatisfiedDependencyChecker(Scenario scenario) {
        IBuildDatabaseForChaseStep databaseBuilder = operatorFactory.getDatabaseBuilder(scenario);
        IRunQuery queryRunner = operatorFactory.getQueryRunner(scenario);
        IOccurrenceHandler valueOccurrenceHandlerMC = getValueOccurrenceHandlerMCExtractor(scenario);
        return new CheckUnsatisfiedDependencies(databaseBuilder, valueOccurrenceHandlerMC, queryRunner);
    }
}
