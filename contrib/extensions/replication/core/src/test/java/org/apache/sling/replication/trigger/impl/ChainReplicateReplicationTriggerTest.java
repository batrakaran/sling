/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.replication.trigger.impl;

import org.apache.sling.replication.trigger.ReplicationRequestHandler;
import org.junit.Test;
import org.osgi.framework.BundleContext;

import static org.mockito.Mockito.mock;

/**
 * Testcase for {@link org.apache.sling.replication.trigger.impl.ChainReplicateReplicationTrigger}
 */
public class ChainReplicateReplicationTriggerTest {

    @Test
    public void testRegister() throws Exception {
        String pathPrefix = "/prefix";
        BundleContext bundleContext = mock(BundleContext.class);
        ChainReplicateReplicationTrigger chainReplicateReplicationTrigger = new ChainReplicateReplicationTrigger(pathPrefix, bundleContext);
        ReplicationRequestHandler handler = mock(ReplicationRequestHandler.class);
        chainReplicateReplicationTrigger.register(handler);
    }

    @Test
    public void testUnregister() throws Exception {
        String pathPrefix = "/prefix";
        BundleContext bundleContext = mock(BundleContext.class);
        ChainReplicateReplicationTrigger chainReplicateReplicationTrigger = new ChainReplicateReplicationTrigger(pathPrefix, bundleContext);
        ReplicationRequestHandler handler = mock(ReplicationRequestHandler.class);
        chainReplicateReplicationTrigger.unregister(handler);
    }

    @Test
    public void testEnable() throws Exception {
        String pathPrefix = "/prefix";
        BundleContext bundleContext = mock(BundleContext.class);
        ChainReplicateReplicationTrigger chainReplicateReplicationTrigger = new ChainReplicateReplicationTrigger(pathPrefix, bundleContext);
        chainReplicateReplicationTrigger.enable();
    }

    @Test
    public void testDisable() throws Exception {
        String pathPrefix = "/prefix";
        BundleContext bundleContext = mock(BundleContext.class);
        ChainReplicateReplicationTrigger chainReplicateReplicationTrigger = new ChainReplicateReplicationTrigger(pathPrefix, bundleContext);
        chainReplicateReplicationTrigger.disable();
    }
}