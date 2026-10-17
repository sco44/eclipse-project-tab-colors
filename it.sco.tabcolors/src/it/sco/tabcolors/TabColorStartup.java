package it.sco.tabcolors;

import org.eclipse.ui.IStartup;

/**
 * Hooks every workbench window and page so editor tabs get their project color.
 */
public class TabColorStartup implements IStartup {

    @Override
    public void earlyStartup() {
        // F1 wiring is added in step 3.
    }
}
