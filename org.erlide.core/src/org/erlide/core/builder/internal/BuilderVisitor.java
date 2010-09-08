/*******************************************************************************
 * Copyright (c) 2007, 2010 Vlad Dumitrescu.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.erlide.core.builder.internal;

import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.erlide.core.builder.BuilderUtils;
import org.erlide.core.builder.BuilderUtils.SearchVisitor;
import org.erlide.core.erlang.ErlModelException;
import org.erlide.core.erlang.ErlangCore;
import org.erlide.core.preferences.OldErlangProjectProperties;
import org.erlide.jinterface.util.ErlLogger;

public class BuilderVisitor implements IResourceDeltaVisitor, IResourceVisitor {

	private final Set<BuildResource> result;
	private final IProgressMonitor monitor;
	private OldErlangProjectProperties prefs = null;

	public BuilderVisitor(final Set<BuildResource> result,
			final IProgressMonitor monitor) {
		this.result = result;
		this.monitor = monitor;
	}

	public boolean visit(final IResourceDelta delta) throws CoreException {
		final IResource resource = delta.getResource();
		return visit(resource, delta.getKind());
	}

	public boolean visit(final IResource resource) throws CoreException {
		return visit(resource, IResourceDelta.CHANGED);
	}

	private boolean visit(IResource resource, int kind) {
		final IProject my_project = resource.getProject();
		if (resource.getType() == IResource.PROJECT) {
			prefs = ErlangCore.getProjectProperties(my_project);
			return true;
		}
		if (resource.getType() == IResource.FOLDER) {
			return true;
		}

		IPath path = resource.getParent().getProjectRelativePath();
		String ext = resource.getFileExtension();
		if (prefs.getSourceDirs().contains(path)) {
			if ("erl".equals(ext)) {
				handleErlFile(kind, resource);
				return false;
			}
			if ("yrl".equals(ext)) {
				handleYrlFile(kind, resource);
				return false;
			}
		}
		if (prefs.getIncludeDirs().contains(path) && "hrl".equals(ext)) {
			try {
				handleHrlFile(kind, resource);
			} catch (ErlModelException e) {
				ErlLogger.warn(e);
			}
			return false;
		}
		if (prefs.getOutputDir().equals(path) && "beam".equals(ext)) {
			try {
				handleBeamFile(kind, resource);
			} catch (CoreException e) {
				ErlLogger.warn(e);
			}
			return false;
		}
		return true;
	}

	private void handleBeamFile(final int kind, final IResource resource)
			throws CoreException {
		switch (kind) {
		case IResourceDelta.ADDED:
		case IResourceDelta.CHANGED:
			break;
		case IResourceDelta.REMOVED:
			final String[] p = resource.getName().split("\\.");
			final SearchVisitor searcher = new SearchVisitor(p[0], null);
			resource.getProject().accept(searcher);
			if (searcher.getResult() != null) {
				final BuildResource bres = new BuildResource(searcher
						.getResult());
				result.add(bres);
				monitor.worked(1);
			}
			break;
		}
	}

	private void handleYrlFile(final int kind, final IResource resource) {
		switch (kind) {
		case IResourceDelta.ADDED:
		case IResourceDelta.CHANGED:
			final BuildResource bres = new BuildResource(resource);
			result.add(bres);
			monitor.worked(1);
			break;

		case IResourceDelta.REMOVED:
			MarkerHelper.deleteMarkers(resource);

			IPath erl = BuilderUtils.getErlForYrl(resource);
			final IResource br = resource.getProject().findMember(erl);
			if (br != null) {
				try {
					br.delete(true, null);
				} catch (final Exception e) {
					ErlLogger.warn(e);
				}
				monitor.worked(1);
			}
			break;
		}
	}

	private void handleHrlFile(final int kind, final IResource resource)
			throws ErlModelException {
		switch (kind) {
		case IResourceDelta.ADDED:
		case IResourceDelta.REMOVED:
		case IResourceDelta.CHANGED:
			final int n = result.size();
			BuilderUtils.addDependents(resource, resource.getProject(), result);
			monitor.worked(result.size() - n);
			break;
		}
	}

	private void handleErlFile(final int kind, final IResource resource) {
		switch (kind) {
		case IResourceDelta.ADDED:
		case IResourceDelta.CHANGED:
			// handle changed resource
			final BuildResource bres = new BuildResource(resource);
			result.add(bres);
			monitor.worked(1);
			break;
		case IResourceDelta.REMOVED:
			// handle removed resource
			MarkerHelper.deleteMarkers(resource);

			IPath beam = prefs.getOutputDir();
			final IPath module = beam.append(resource.getName())
					.removeFileExtension();
			beam = module.addFileExtension("beam").setDevice(null);
			final IResource br = resource.getProject().findMember(beam);
			if (br != null) {
				try {
					br.delete(true, null);
				} catch (final Exception e) {
					ErlLogger.warn(e);
				}
			}

			// was it derived from a yrl?
			final IPath yrlpath = resource.getProjectRelativePath()
					.removeFileExtension().addFileExtension("yrl");
			final IResource yrl = resource.getProject().findMember(yrlpath);
			if (yrl != null) {
				final BuildResource bres2 = new BuildResource(yrl);
				result.add(bres2);
				monitor.worked(1);
			}

			break;
		}
	}

}