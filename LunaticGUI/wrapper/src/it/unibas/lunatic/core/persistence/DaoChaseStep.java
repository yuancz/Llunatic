package it.unibas.lunatic.core.persistence;

import it.unibas.lunatic.Scenario;
import it.unibas.lunatic.core.GenerateModifiedCells;
import it.unibas.lunatic.model.chase.chasemc.DeltaChaseStep;
import it.unibas.lunatic.model.chase.chasemc.operators.IRunQuery;
import it.unibas.lunatic.model.chase.chasemc.operators.dbms.SQLRunQuery;
import it.unibas.lunatic.model.chase.chasemc.operators.mainmemory.MainMemoryRunQuery;
import java.io.IOException;

public class DaoChaseStep {

    private IRunQuery mmRunQuery = new MainMemoryRunQuery();
    private IRunQuery sqlIRunQuery = new SQLRunQuery();
    private GenerateModifiedCells mmGenerateModifiedCells = new GenerateModifiedCells(mmRunQuery);
    private GenerateModifiedCells sqlGenerateModifiedCells = new GenerateModifiedCells(sqlIRunQuery);
    private static DaoChaseStep instance;

    public static DaoChaseStep getInstance() {
        if (instance == null) {
            instance = new DaoChaseStep();
        }
        return instance;
    }

    public void persist(Scenario s, DeltaChaseStep result, String fileName) throws IOException {
        if (s.isMainMemory()) {
            mmGenerateModifiedCells.generate(result, fileName);
        } else {
            sqlGenerateModifiedCells.generate(result, fileName);
        }
    }
}
