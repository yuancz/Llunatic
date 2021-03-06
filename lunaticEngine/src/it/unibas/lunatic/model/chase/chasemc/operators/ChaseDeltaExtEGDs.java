package it.unibas.lunatic.model.chase.chasemc.operators;

import it.unibas.lunatic.LunaticConfiguration;
import it.unibas.lunatic.LunaticConstants;
import it.unibas.lunatic.utility.LunaticUtility;
import it.unibas.lunatic.Scenario;
import it.unibas.lunatic.exceptions.ChaseException;
import it.unibas.lunatic.model.chase.chasemc.DeltaChaseStep;
import it.unibas.lunatic.model.chase.commons.ChaseStats;
import it.unibas.lunatic.model.chase.commons.operators.ChaseUtility;
import it.unibas.lunatic.model.chase.commons.IChaseState;
import it.unibas.lunatic.model.chase.chasemc.NewChaseSteps;
import it.unibas.lunatic.model.chase.chasemc.costmanager.CostManagerUtility;
import it.unibas.lunatic.model.dependency.Dependency;
import it.unibas.lunatic.model.dependency.DependencyStratification;
import it.unibas.lunatic.model.dependency.EGDStratum;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import speedy.model.algebra.IAlgebraOperator;
import speedy.model.algebra.operators.IBatchInsert;
import speedy.model.algebra.operators.IInsertTuple;
import speedy.model.database.IDatabase;
import speedy.model.database.operators.IRunQuery;

public class ChaseDeltaExtEGDs {

    private static final Logger logger = LoggerFactory.getLogger(ChaseDeltaExtEGDs.class);
    private final CheckUnsatisfiedDependenciesMC unsatisfiedDependenciesChecker;
    private final IBuildDatabaseForChaseStepMC databaseBuilder;
    private final CheckDuplicates duplicateChecker;
    private final IChaseExtEGDEquivalenceClass symmetricEGDChaser;
    private final IChaseExtEGDEquivalenceClass egdChaser;
    private final OccurrenceHandlerMC occurrenceHandler;

    public ChaseDeltaExtEGDs(IBuildDatabaseForChaseStepMC stepBuilder, IRunQuery queryRunner,
            IInsertTuple insertOperator, IBatchInsert batchInsertOperator, ChangeCellMC cellChanger,
            OccurrenceHandlerMC occurrenceHandler, CheckUnsatisfiedDependenciesMC unsatisfiedDependenciesChecker) {
        this.databaseBuilder = stepBuilder;
        this.duplicateChecker = new CheckDuplicates();
        this.symmetricEGDChaser = new ChaseSymmetricExtEGDEquivalenceClass(queryRunner, occurrenceHandler, cellChanger);
        this.egdChaser = new ChaseExtEGDEquivalenceClass(queryRunner, occurrenceHandler, cellChanger);
        this.unsatisfiedDependenciesChecker = new CheckUnsatisfiedDependenciesMC(databaseBuilder, occurrenceHandler, queryRunner);
        this.occurrenceHandler = occurrenceHandler;
    }

    public ChaserResult doChase(DeltaChaseStep root, Scenario scenario, IChaseState chaseState, Map<Dependency, IAlgebraOperator> premiseTreeMap) {
        long start = new Date().getTime();
        int size = root.getNumberOfNodes();
        boolean userInteractionRequired = false;
        DependencyStratification stratification = scenario.getStratification();
        for (EGDStratum stratum : stratification.getEGDStrata()) {
            if (LunaticConfiguration.isPrintSteps()) System.out.println("---- Chasing egd stratum: " + stratum.getId());
            if (logger.isDebugEnabled()) logger.debug("------------------Chasing stratum: ----\n" + stratum);
            userInteractionRequired = userInteractionRequired || chaseTree(root, scenario, chaseState, stratum.getDependencies(), premiseTreeMap);
        }
        long end = new Date().getTime();
        ChaseStats.getInstance().addStat(ChaseStats.EGD_TIME, end - start);
        int newSize = root.getNumberOfNodes();
        boolean newNodes = (size != newSize);
        return new ChaserResult(newNodes, userInteractionRequired);
    }

    private boolean chaseTree(DeltaChaseStep treeRoot, Scenario scenario, IChaseState chaseState, List<Dependency> egds, Map<Dependency, IAlgebraOperator> premiseTreeMap) {
        if (treeRoot.isInvalid()) {
            return false;
        }
        if (treeRoot.isLeaf()) {
            return chaseNode((DeltaChaseStep) treeRoot, scenario, chaseState, egds, premiseTreeMap);
        }
        for (DeltaChaseStep child : treeRoot.getChildren()) {
            boolean userInteractionRequired = chaseTree(child, scenario, chaseState, egds, premiseTreeMap);
            if (userInteractionRequired) {
                return true;
            }
        }
        return false;
    }

    private IChaseExtEGDEquivalenceClass getChaser(Dependency egd) {
        if (egd.hasSymmetricChase()) {
            return this.symmetricEGDChaser;
        }
        return egdChaser;
    }

    private boolean chaseNode(DeltaChaseStep currentNode, Scenario scenario, IChaseState chaseState, List<Dependency> egds, Map<Dependency, IAlgebraOperator> premiseTreeMap) {
        if (scenario.getConfiguration().isRemoveDuplicates()) {
            this.occurrenceHandler.generateCellGroupStats(currentNode);
            duplicateChecker.findDuplicates(currentNode, scenario);
        }
        if (currentNode.isDuplicate() || currentNode.isInvalid()) {
            return false;
        }
        if (currentNode.isEditedByUser()) {
            DeltaChaseStep newStep = new DeltaChaseStep(scenario, currentNode, LunaticConstants.CHASE_USER, LunaticConstants.CHASE_USER);
            currentNode.addChild(newStep);
            return chaseNode(newStep, scenario, chaseState, egds, premiseTreeMap);
        }
        if (LunaticConfiguration.isPrintSteps()) System.out.println("  ****Chasing node " + currentNode.getId() + " for egds...");
        if (logger.isDebugEnabled()) logger.debug("----Chase iteration starting on step " + currentNode.getId() + " ...");
        List<DeltaChaseStep> newSteps = new ArrayList<DeltaChaseStep>();
        List<Dependency> unsatisfiedDependencies = unsatisfiedDependenciesChecker.findUnsatisfiedEGDsNoQuery(currentNode, egds);
        List<Dependency> egdsToChase = CostManagerUtility.selectDependenciesToChase(unsatisfiedDependencies, currentNode.getRoot(), scenario.getCostManagerConfiguration());
        if (logger.isDebugEnabled()) logger.debug("----Unsatisfied Dependencies: " + LunaticUtility.printDependencyIds(unsatisfiedDependencies));
        if (logger.isDebugEnabled()) logger.debug("----Dependencies to chase: " + LunaticUtility.printDependencyIds(egdsToChase));
        boolean userInteractionRequired = false;
        for (Dependency egd : egdsToChase) {
            if (chaseState.isCancelled()) ChaseUtility.stopChase(chaseState); //throw new ChaseException("Chase interrupted by user");
            if (LunaticConfiguration.isPrintSteps()) System.out.println("\t    **Chasing edg: " + egd.getId());
            long startEgd = new Date().getTime();
            if (logger.isDebugEnabled()) logger.info("* Chasing dependency " + egd.getId() + " on step " + currentNode.getId());
            if (logger.isDebugEnabled()) logger.info("* Algebra operator " + premiseTreeMap.get(egd));
            if (logger.isDebugEnabled()) logger.trace("Building database for step id: " + currentNode.getId() + "\nDelta db:\n" + currentNode.getDeltaDB().printInstances());
            IDatabase databaseForStep = databaseBuilder.extractDatabase(currentNode.getId(), currentNode.getDeltaDB(), currentNode.getOriginalDB(), egd, scenario);
            if (logger.isTraceEnabled()) logger.trace("Database for step id: " + currentNode.getId() + "\n" + databaseForStep.printInstances());
            IChaseExtEGDEquivalenceClass chaser = getChaser(egd);
            NewChaseSteps newChaseSteps = chaser.chaseDependency(currentNode, egd, premiseTreeMap.get(egd), scenario, chaseState, databaseForStep);
            long endEgd = new Date().getTime();
            ChaseStats.getInstance().addDepenendecyStat(egd, endEgd - startEgd);
            if (logger.isDebugEnabled()) logger.trace("New steps generated by dependency: " + newChaseSteps);
            if (logger.isDebugEnabled()) logger.debug("New steps generated by dependency: " + newChaseSteps.size());
            if (newChaseSteps.isNoRepairsNeeded()) {
                if (logger.isDebugEnabled()) logger.debug("Step " + currentNode.getId() + " satisfies dependency " + egd.getId());
                currentNode.addSatisfiedEGD(egd);
                if (scenario.getConfiguration().isCheckAllNodesForEGDSatisfaction()) {
                    unsatisfiedDependenciesChecker.checkEGDSatisfactionWithQuery(currentNode, scenario);
                }
            } else {
                if (newChaseSteps.getChaseSteps().isEmpty()) {
                    throw new ChaseException("Unable to repair dependency " + egd + " in node\n" + currentNode.getId());
                }
                newSteps.addAll(newChaseSteps.getChaseSteps());
                if (scenario.getConfiguration().isCheckAllNodesForEGDSatisfaction()) {
                    for (DeltaChaseStep newStep : newChaseSteps.getChaseSteps()) {
                        unsatisfiedDependenciesChecker.checkEGDSatisfactionWithQuery(newStep, scenario);
                    }
                }
                if (scenario.getUserManager().isUserInteractionRequired(newChaseSteps.getChaseSteps(), currentNode, scenario)) {
                    userInteractionRequired = true;
//                    break;
                }
            }
        }
        currentNode.setChildren(newSteps);
//        if (scenario.getConfiguration().isRemoveDuplicates()) {
//            findDuplicates(newSteps, scenario);
//        }
        if (userInteractionRequired) {
            return true;
        }
        if (newSteps.size() > 0 || thereAreUnsatisfiedDependencies(unsatisfiedDependencies, egdsToChase)) {
            userInteractionRequired = chaseTree(currentNode, scenario, chaseState, egds, premiseTreeMap);
            if (userInteractionRequired) {
                return true;
            }
        }
        return false;
    }

    private boolean thereAreUnsatisfiedDependencies(List<Dependency> unsatisfiedDependencies, List<Dependency> dependenciesToChase) {
        return unsatisfiedDependencies.size() != dependenciesToChase.size();
    }
//    private void findDuplicates(List<DeltaChaseStep> newChaseSteps, Scenario scenario) {
//        List<DeltaChaseStep> revertedNewChaseSteps = new ArrayList<DeltaChaseStep>(newChaseSteps);
//        Collections.reverse(revertedNewChaseSteps);
//        for (DeltaChaseStep newChaseStep : revertedNewChaseSteps) {
//            duplicateChecker.findDuplicateNode(newChaseStep, scenario);
//        }
//    }

}
