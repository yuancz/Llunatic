package it.unibas.lunatic.gui.window.chase;

import it.unibas.lunatic.gui.model.McChaseResult;
import javax.swing.JComponent;

/**
 *
 * @author Tony
 */
public interface McResultView{

    void componentOpened();

    void componentClosed();

    void onChaseResultUpdate(McChaseResult result);

    void onChaseResultClose();

    public JComponent toComponent();
}
