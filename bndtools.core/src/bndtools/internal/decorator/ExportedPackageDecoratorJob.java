package bndtools.internal.decorator;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Version;

import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.header.Attrs;
import aQute.bnd.osgi.Builder;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.Descriptors.PackageRef;
import aQute.bnd.osgi.Packages;
import aQute.bnd.osgi.Processor;
import bndtools.Central;
import bndtools.Logger;
import bndtools.api.ILogger;
import bndtools.utils.SWTConcurrencyUtil;

public class ExportedPackageDecoratorJob extends Job implements ISchedulingRule {
    private static final ILogger logger = Logger.getLogger();

    private static final ConcurrentMap<String,ExportedPackageDecoratorJob> instances = new ConcurrentHashMap<String,ExportedPackageDecoratorJob>();

    private final IProject project;

    public static void scheduleForProject(IProject project) {
        ExportedPackageDecoratorJob job = new ExportedPackageDecoratorJob(project);

        if (instances.putIfAbsent(project.getFullPath().toPortableString(), job) == null) {
            job.schedule(1000);
        }
    }

    ExportedPackageDecoratorJob(IProject project) {
        super("Update exported packages: " + project.getName());

        this.project = project;
        setRule(this);
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        instances.remove(project.getFullPath().toPortableString());

        try {
            Project model = null;
            try {
                model = Workspace.getProject(project.getLocation().toFile());
            } catch (IllegalArgumentException e) {
                // Could not find cnf, ignore
                return Status.OK_STATUS;
            }
            if (model == null) {
                return Status.OK_STATUS;
            }
            Collection< ? extends Builder> builders = model.getSubBuilders();

            Map<String,SortedSet<Version>> allExports = new HashMap<String,SortedSet<Version>>();
            Set<String> allContained = new HashSet<String>();

            for (Builder builder : builders) {
                try {
                    builder.build();
                    Packages exports = builder.getExports();
                    if (exports != null) {
                        for (Entry<PackageRef,Attrs> export : exports.entrySet()) {
                            Version version;
                            String versionStr = export.getValue().get(Constants.VERSION_ATTRIBUTE);
                            try {
                                version = Version.parseVersion(versionStr);
                                String pkgName = Processor.removeDuplicateMarker(export.getKey().getFQN());
                                SortedSet<Version> versions = allExports.get(pkgName);
                                if (versions == null) {
                                    versions = new TreeSet<Version>();
                                    allExports.put(pkgName, versions);
                                }
                                versions.add(version);
                            } catch (IllegalArgumentException e) {
                                // Seems to be an invalid export, ignore it...
                            }
                        }
                    }
                    Packages contained = builder.getContained();
                    for (PackageRef pkgRef : contained.keySet()) {
                        String pkgName = Processor.removeDuplicateMarker(pkgRef.getFQN());
                        allContained.add(pkgName);
                    }
                } catch (Exception e) {
                    logger.logWarning(MessageFormat.format("Unable to process exported packages for builder of {0}.", builder.getPropertiesFile()), e);
                }
            }
            Central.setProjectPackageModel(project, allExports, allContained);

            Display display = PlatformUI.getWorkbench().getDisplay();
            SWTConcurrencyUtil.execForDisplay(display, true, new Runnable() {
                public void run() {
                    PlatformUI.getWorkbench().getDecoratorManager().update("bndtools.packageDecorator");
                }
            });

        } catch (Exception e) {
            logger.logWarning("Error persisting package model for project: " + project.getName(), e);
        }

        return Status.OK_STATUS;
    }

    public boolean contains(ISchedulingRule rule) {
        return this == rule;
    }

    public boolean isConflicting(ISchedulingRule rule) {
        return rule instanceof ExportedPackageDecoratorJob;
    }

}
