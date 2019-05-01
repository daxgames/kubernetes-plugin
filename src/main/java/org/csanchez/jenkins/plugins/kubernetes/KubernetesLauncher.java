/*
 * The MIT License
 *
 * Copyright (c) 2017, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.csanchez.jenkins.plugins.kubernetes;

import static java.util.logging.Level.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.CheckForNull;

import io.fabric8.kubernetes.client.Watch;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import hudson.model.TaskListener;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Launches on Kubernetes the specified {@link KubernetesComputer} instance.
 */
public class KubernetesLauncher extends JNLPLauncher {

    @CheckForNull
    private transient AllContainersRunningPodWatcher watcher;

    private static final Logger LOGGER = Logger.getLogger(KubernetesLauncher.class.getName());

    private boolean launched;

    /**
     * Provisioning exception if any.
     */
    @CheckForNull
    private transient Throwable problem;

    @DataBoundConstructor
    public KubernetesLauncher(String tunnel, String vmargs) {
        super(tunnel, vmargs);
    }

    public KubernetesLauncher() {
        super();
    }

    @Override
    public boolean isLaunchSupported() {
        return !launched;
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) {

        if (!(computer instanceof KubernetesComputer)) {
            throw new IllegalArgumentException("This Launcher can be used only with KubernetesComputer");
        }
        KubernetesComputer kubernetesComputer = (KubernetesComputer) computer;
        computer.setAcceptingTasks(false);
        KubernetesSlave slave = kubernetesComputer.getNode();
        if (slave == null) {
            throw new IllegalStateException("Node has been removed, cannot launch " + computer.getName());
        }
        if (launched) {
            LOGGER.log(INFO, "Agent has already been launched, activating: {}", slave.getNodeName());
            computer.setAcceptingTasks(true);
            return;
        }

        final PodTemplate template = slave.getTemplate();
        List<String> validStates = ImmutableList.of("Running");

        try {
            KubernetesClient client = slave.getKubernetesCloud().connect();
            Pod pod = template.build(client, slave);

            String podId = pod.getMetadata().getName();

            String namespace = Arrays.asList( //
                    pod.getMetadata().getNamespace(),
                    template.getNamespace(), client.getNamespace()) //
                    .stream().filter(s -> StringUtils.isNotBlank(s)).findFirst().orElse(null);
            slave.setNamespace(namespace);

            LOGGER.log(Level.FINE, "Creating Pod: {0}/{1}", new Object[] { namespace, podId });
            pod = client.pods().inNamespace(namespace).create(pod);
            LOGGER.log(INFO, "Created Pod: {0}/{1}", new Object[] { namespace, podId });
            listener.getLogger().printf("Created Pod: %s/%s%n", namespace, podId);
            String podName = pod.getMetadata().getName();
            String namespace1 = pod.getMetadata().getNamespace();
            watcher = new AllContainersRunningPodWatcher(client, pod);
            try (Watch _w = client.pods().inNamespace(namespace1).withName(podName).watch(watcher)) {
                LOGGER.log(INFO, "Slave Timeout: {0}", new Object[] { template.getSlaveConnectTimeout() });
                watcher.await(template.getSlaveConnectTimeout(), TimeUnit.SECONDS);
            }
            LOGGER.log(INFO, "Pod is running: {0}/{1}", new Object[] { namespace, podId });

            String status = pod.getStatus().getPhase();
            if (!validStates.contains(status)) {
                throw new IllegalStateException(
                        "Container is not running after " + waitForPodSec + " seconds, status: " + status);
            }

            int waitForSlaveToConnect = unwrappedTemplate.getSlaveConnectTimeout();
            int waitedForSlave;

            // now wait for agent to be online
            SlaveComputer slaveComputer = null;
            for (waitedForSlave = 0; waitedForSlave < waitForSlaveToConnect; waitedForSlave++) {
                slaveComputer = slave.getComputer();
                if (slaveComputer == null) {
                    throw new IllegalStateException("Node was deleted, computer is null");
                }
                if (slaveComputer.isOnline()) {
                    break;
                }

                // Check that the pod hasn't failed already
                pod = client.pods().inNamespace(namespace).withName(podId).get();
                if (pod == null) {
                    throw new IllegalStateException("Pod no longer exists: " + podId);
                }
                status = pod.getStatus().getPhase();
                if (!validStates.contains(status)) {
                    break;
                }

                containerStatuses = pod.getStatus().getContainerStatuses();
                List<ContainerStatus> terminatedContainers = new ArrayList<>();
                for (ContainerStatus info : containerStatuses) {
                    if (info != null) {
                        if (info.getState().getTerminated() != null) {
                            // Container has errored
                            LOGGER.log(INFO, "Container is terminated {0} [{2}]: {1}",
                                    new Object[]{podId, info.getState().getTerminated(), info.getName()});
                            logger.printf("Container is terminated %1$s [%3$s]: %2$s%n",
                                    podId, info.getState().getTerminated(), info.getName());
                            terminatedContainers.add(info);
                        }
                    }
                }

                checkTerminatedContainers(terminatedContainers, podId, namespace, slave, client);

                LOGGER.log(INFO, "Waiting for agent to connect ({1}/{2}): {0}",
                        new Object[]{podId, waitedForSlave, waitForSlaveToConnect});
                logger.printf("Waiting for agent to connect (%2$s/%3$s): %1$s%n",
                        podId, waitedForSlave, waitForSlaveToConnect);
                Thread.sleep(1000);
            }
            if (slaveComputer == null || slaveComputer.isOffline()) {
                logLastLines(containerStatuses, podId, namespace, slave, null, client);
                throw new IllegalStateException(
                        "Agent is not connected after " + waitedForSlave + " seconds, status: " + status);
            }

            computer.setAcceptingTasks(true);
            launched = true;
            try {
                // We need to persist the "launched" setting...
                slave.save();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Could not save() agent: " + e.getMessage(), e);
            }
        } catch (Throwable ex) {
            setProblem(ex);
            LOGGER.log(Level.WARNING, String.format("Error in provisioning; agent=%s, template=%s", slave, template), ex);
            LOGGER.log(Level.FINER, "Removing Jenkins node: {0}", slave.getNodeName());
            try {
                slave.terminate();
            } catch (IOException | InterruptedException e) {
                LOGGER.log(Level.WARNING, "Unable to remove Jenkins node", e);
            }
            throw Throwables.propagate(ex);
        }
    }

    /**
     * The last problem that occurred, if any.
     * @return
     */
    @CheckForNull
    public Throwable getProblem() {
        return problem;
    }

    public void setProblem(@CheckForNull Throwable problem) {
        this.problem = problem;
    }

    public AllContainersRunningPodWatcher getWatcher() {
        return watcher;
    }

}
