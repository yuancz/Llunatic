package it.unibas.lunatic.gui.window.db;

import it.unibas.lunatic.gui.R;
import it.unibas.lunatic.gui.node.ITupleFactory;
import it.unibas.lunatic.gui.node.TableNode;
import it.unibas.lunatic.gui.node.TableTupleNode;
import it.unibas.lunatic.gui.node.TupleGenerationStrategy;
import it.unibas.lunatic.gui.table.TablePopupFactory;
import speedy.model.database.Cell;
import java.lang.reflect.InvocationTargetException;
import org.netbeans.swing.etable.ETable;
import org.netbeans.swing.outline.DefaultOutlineModel;
import org.netbeans.swing.outline.Outline;
import org.openide.explorer.ExplorerManager;
import org.openide.nodes.Node;
import org.openide.windows.TopComponent;
import org.openide.util.NbBundle.Messages;

/**
 * Top component which displays something.
 */
//@ConvertAsProperties(
//        dtd = "-//it.unibas.lunatic.gui//DbTable//EN",
//        autostore = false)
@TopComponent.Description(
        preferredID = R.Window.TABLE,
        //iconBase="SET/PATH/TO/ICON/HERE", 
        persistenceType = TopComponent.PERSISTENCE_NEVER)
@TopComponent.Registration(mode = "editor", openAtStartup = false)
//@ActionID(category = "Window", id = "it.unibas.lunatic.gui.action.OpenDbTableView")
//@ActionReference(path = "Menu/Window" /*, position = 333 */)
//@TopComponent.OpenActionRegistration(
//        displayName = "#CTL_DbTableAction",
//        preferredID = R.Window.TABLE)
@Messages({
    "CTL_DbTableAction=Table",
    "CTL_DbTableTopComponent=Table",
    "HINT_DbTableTopComponent=This is a Table window"
})
public final class DbTableTopComponent extends TableWindow {

    private TupleGenerationStrategy tupleGenerationStrategy = TupleGenerationStrategy.getInstance();
    private TableNode tableNode;
    private ITupleFactory tupleFactory;

    public DbTableTopComponent(TableNode tableNode, String name) {
        this.tableNode = tableNode;
        initComponents();
        initTable();
        setToolTipText(Bundle.HINT_DbTableTopComponent());
        associateExplorerLookup();
        setName(name);
        setColumns(tableNode);
        updateTable();
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        outlineView1 = new org.openide.explorer.view.OutlineView();

        setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.LINE_AXIS));

        outlineView1.setDragSource(false);
        outlineView1.setDropTarget(false);
        add(outlineView1);
    }// </editor-fold>//GEN-END:initComponents
    // Variables declaration - do not modify//GEN-BEGIN:variables
    private org.openide.explorer.view.OutlineView outlineView1;
    // End of variables declaration//GEN-END:variables
    private LoadedScenarioListener scenarioListener = new LoadedScenarioListener();

    @Override
    public void componentOpened() {
        scenarioListener.register(this);
    }

    @Override
    public void componentClosed() {
        if (tupleFactory != null) {
            tupleFactory.interrupt();
        }
        scenarioListener.remove();
    }

    void writeProperties(java.util.Properties p) {
        // better to version settings since initial version as advocated at
        // http://wiki.apidesign.org/wiki/PropertyFiles
        p.setProperty("version", "1.3");
        // TODO store your settings
    }

    void readProperties(java.util.Properties p) {
        String version = p.getProperty("version");
        // TODO read your settings according to their version
    }

    private void initTable() {
        outlineView1.setNodePopupFactory(new TablePopupFactory());
        Outline outline = outlineView1.getOutline();
        outline.setRootVisible(false);
        outline.setCellSelectionEnabled(true);
        outline.setAutoResizeMode(ETable.AUTO_RESIZE_OFF);
//        TableColumnModel columnModel = outline.getColumnModel();
//        ETableColumn column = (ETableColumn) columnModel.getColumn(0);
//        ((ETableColumnModel) columnModel).setColumnHidden(column, true);
        ((DefaultOutlineModel) outline.getOutlineModel()).setNodesColumnLabel("tid");
        outline.setFullyNonEditable(true);
    }

    @Override
    public ExplorerManager getExplorerManager() {
        return explorer;
    }

    @Override
    public void updateTable() {
        tupleFactory = tupleGenerationStrategy.getNetbeansFactory(tableNode);
        explorer.setRootContext(tupleFactory.createTuples());
    }

    public void setColumns(TableNode ts) {
        outlineView1.setPropertyColumns();
        for (String col : ts.getVisibleColumns()) {
            outlineView1.addPropertyColumn(col, col);
        }
    }

    @Override
    public TableTupleNode getSelectedNode() {
        Node[] nodes = explorer.getSelectedNodes();
        if (nodes.length == 1) {
            return (TableTupleNode) nodes[0];
        }
        return null;
    }

    @Override
    public Cell getSelectedCell() throws IllegalAccessException, InvocationTargetException {
        Outline o = outlineView1.getOutline();
        int selectedColumn = o.getSelectedColumn();
        if (selectedColumn > 0) {
            String columnName = o.getColumnName(selectedColumn);
            TableTupleNode node = getSelectedNode();
            return node.getCell(columnName);
        }
        return null;
    }

    @Override
    public void setRootContext(Node node) {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public void removeRootContext() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public TableNode getTableNode() {
        return tableNode;
    }
}
