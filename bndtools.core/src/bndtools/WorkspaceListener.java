package bndtools;

import java.io.File;

import aQute.bnd.build.Workspace;
import aQute.bnd.osgi.Processor;
import aQute.bnd.service.BndListener;
import aQute.service.reporter.Reporter;
import bndtools.api.ILogger;

public final class WorkspaceListener extends BndListener {
    private static final ILogger logger = Logger.getLogger();

    private final Workspace workspace;
    private final Processor errorProcessor = new Processor();

    public WorkspaceListener(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public void changed(final File file) {
        try {
            RefreshFileJob job = new RefreshFileJob(file, true);
            if (job.needsToSchedule()) {
                job.schedule();
            }
        } catch (Exception e) {
            logger.logError("Error refreshing workspace", e);
        }
    }

    @Override
    public void signal(Reporter reporter) {
        errorProcessor.clear();
        errorProcessor.getInfo(workspace);

        for (String warning : errorProcessor.getWarnings()) {
            logger.logWarning(warning, null);
        }
        for (String error : errorProcessor.getErrors()) {
            logger.logError(error, null);
        }
    }

}