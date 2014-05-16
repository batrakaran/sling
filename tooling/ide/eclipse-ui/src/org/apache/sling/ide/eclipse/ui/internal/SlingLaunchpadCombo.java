/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.ide.eclipse.ui.internal;

import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.ServerCore;

/**
 * The <tt>SlingLaunchpadCombo</tt> wraps a {@link Combo} widget for selecting Sling launchpad instances
 *
 */
public class SlingLaunchpadCombo {

    private final Combo repositoryCombo;
    private IProject project;

    public SlingLaunchpadCombo(Composite parent, IProject project) {
        repositoryCombo = new Combo(parent, SWT.DROP_DOWN);
        repositoryCombo.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

        this.project = project;
    }

    public void setProject(IProject project) {
        this.project = project;
    }

    public Combo getWidget() {
        return repositoryCombo;
    }

    public void refreshRepositoryList(IProgressMonitor monitor) {

        repositoryCombo.removeAll();
        List<IServer> servers = SelectionUtils.getServersLinkedToProject(project, monitor);
        if (servers.size() > 1) {
            repositoryCombo.add(""); // force selection only if there is more than one server
        }
        for (IServer server : servers) {
            repositoryCombo.add(server.getId());
        }

        if (servers.size() == 1) {
            repositoryCombo.select(0);
        }
    }

    public IServer getServer() {
        for (IServer server : ServerCore.getServers())
            if (server.getId().equals(repositoryCombo.getText()))
                return server;

        return null;
    }

    public boolean hasServers() {
        return repositoryCombo.getItemCount() > 0;
    }

    public String getErrorMessage() {

        if (!hasServers() && project != null) {
            return "The selected project is not configured with/added to any Sling server";
        }

        IServer server = getServer();
        if (server == null) {
            return "Please select a repository";
        }

        if (server.getServerState() != IServer.STATE_STARTED) {
            return "Selected server is not started";
        }

        return null;
    }
}