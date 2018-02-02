/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.strimzi.controller.cluster.resources;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.strimzi.controller.cluster.ResourceUtils;
import org.junit.Test;

import static io.strimzi.controller.cluster.ResourceUtils.labels;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KafkaClusterTest {

    private final String namespace = "test";
    private final String cluster = "foo";
    private final int replicas = 3;
    private final String image = "image";
    private final int healthDelay = 120;
    private final int healthTimeout = 30;
    private final String metricsCmJson = "{\"animal\":\"wombat\"}";
    private final ConfigMap cm = ResourceUtils.createConfigMap(namespace, cluster, replicas, image, healthDelay, healthTimeout, metricsCmJson);
    private final KafkaCluster kc = KafkaCluster.fromConfigMap(cm);

    @Test
    public void testMetricsConfigMap() {
        ConfigMap metricsCm = kc.generateMetricsConfigMap();
        checkMetricsConfigMap(metricsCm);
    }

    private void checkMetricsConfigMap(ConfigMap metricsCm) {
        assertEquals(metricsCmJson, metricsCm.getData().get(AbstractCluster.METRICS_CONFIG_FILE));
    }

    @Test
    public void testGenerateService() {
        Service headful = kc.generateService();
        checkService(headful);
    }

    private void checkService(Service headful) {
        assertEquals("ClusterIP", headful.getSpec().getType());
        assertEquals(ResourceUtils.labels("strimzi.io/cluster", cluster, "strimzi.io/kind", "kafka-cluster", "strimzi.io/name", cluster + "-kafka"), headful.getSpec().getSelector());
        assertEquals(1, headful.getSpec().getPorts().size());
        assertEquals("clients", headful.getSpec().getPorts().get(0).getName());
        assertEquals(new Integer(9092), headful.getSpec().getPorts().get(0).getPort());
        assertEquals("TCP", headful.getSpec().getPorts().get(0).getProtocol());
    }

    @Test
    public void testGenerateHeadlessService() {
        Service headless = kc.generateHeadlessService();
        checkHeadlessService(headless);
    }

    private void checkHeadlessService(Service headless) {
        assertEquals(KafkaCluster.headlessName(cluster), headless.getMetadata().getName());
        assertEquals("ClusterIP", headless.getSpec().getType());
        assertEquals("None", headless.getSpec().getClusterIP());
        assertEquals(labels("strimzi.io/cluster", cluster, "strimzi.io/kind", "kafka-cluster", "strimzi.io/name", KafkaCluster.kafkaClusterName(cluster)), headless.getSpec().getSelector());
        assertEquals(1, headless.getSpec().getPorts().size());
        assertEquals("clients", headless.getSpec().getPorts().get(0).getName());
        assertEquals(new Integer(9092), headless.getSpec().getPorts().get(0).getPort());
        assertEquals("TCP", headless.getSpec().getPorts().get(0).getProtocol());
    }

    @Test
    public void testGenerateStatefulSet() {
        // We expect a single statefulSet ...
        StatefulSet ss = kc.generateStatefulSet(true);
        checkStatefulSet(ss);
    }

    private void checkStatefulSet(StatefulSet ss) {
        assertEquals(KafkaCluster.kafkaClusterName(cluster), ss.getMetadata().getName());
        // ... in the same namespace ...
        assertEquals(namespace, ss.getMetadata().getNamespace());
        // ... with these labels
        assertEquals(labels("strimzi.io/cluster", cluster,
                "strimzi.io/kind", "kafka-cluster",
                "strimzi.io/name", KafkaCluster.kafkaClusterName(cluster)),
                ss.getMetadata().getLabels());

        assertEquals(new Integer(replicas), ss.getSpec().getReplicas());
        assertEquals(image, ss.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        assertEquals(new Integer(healthTimeout), ss.getSpec().getTemplate().getSpec().getContainers().get(0).getLivenessProbe().getTimeoutSeconds());
        assertEquals(new Integer(healthDelay), ss.getSpec().getTemplate().getSpec().getContainers().get(0).getLivenessProbe().getInitialDelaySeconds());
        assertEquals(new Integer(healthTimeout), ss.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getTimeoutSeconds());
        assertEquals(new Integer(healthDelay), ss.getSpec().getTemplate().getSpec().getContainers().get(0).getReadinessProbe().getInitialDelaySeconds());
    }

    /**
     * Check that a KafkaCluster from a statefulset matches the one from a ConfigMap
     */
    @Test
    public void testClusterFromStatefulSet() {
        StatefulSet ss = kc.generateStatefulSet(true);
        KafkaCluster kc2 = KafkaCluster.fromStatefulSet(ss, namespace, cluster);
        // Don't check the metrics CM, since this isn't restored from the StatefulSet
        checkService(kc2.generateService());
        checkHeadlessService(kc2.generateHeadlessService());
        checkStatefulSet(kc2.generateStatefulSet(true));
    }

    // TODO test volume claim templates

    @Test
    public void testDiffNoDiffs() {
        ClusterDiffResult diff = kc.diff(kc.generateMetricsConfigMap(), kc.generateStatefulSet(true));
        assertFalse(diff.getDifferent());
        assertFalse(diff.getScaleDown());
        assertFalse(diff.getScaleUp());
        assertFalse(diff.getRollingUpdate());
        assertFalse(diff.isMetricsChanged());
        assertEquals(Source2Image.Source2ImageDiff.NONE, diff.getS2i());
    }

    @Test
    public void testDiffMetrics() {
        KafkaCluster other = KafkaCluster.fromConfigMap(ResourceUtils.createConfigMap(namespace, cluster,
                replicas, image, healthDelay, healthTimeout,"{\"something\":\"different\"}"));
        ClusterDiffResult diff = kc.diff(other.generateMetricsConfigMap(), other.generateStatefulSet(true));
        assertFalse(diff.getDifferent());
        assertFalse(diff.getScaleDown());
        assertFalse(diff.getScaleUp());
        assertFalse(diff.getRollingUpdate());
        assertTrue(diff.isMetricsChanged());
        assertEquals(Source2Image.Source2ImageDiff.NONE, diff.getS2i());
    }

    @Test
    public void testDiffScaleDown() {
        KafkaCluster other = KafkaCluster.fromConfigMap(ResourceUtils.createConfigMap(namespace, cluster,
                replicas + 1, image, healthDelay, healthTimeout, metricsCmJson));
        ClusterDiffResult diff = kc.diff(other.generateMetricsConfigMap(), other.generateStatefulSet(true));
        assertFalse(diff.getDifferent());
        assertTrue(diff.getScaleDown());
        assertFalse(diff.getScaleUp());
        assertFalse(diff.getRollingUpdate());
        assertFalse(diff.isMetricsChanged());
        assertEquals(Source2Image.Source2ImageDiff.NONE, diff.getS2i());
    }

    @Test
    public void testDiffScaleUp() {
        KafkaCluster other = KafkaCluster.fromConfigMap(ResourceUtils.createConfigMap(namespace, cluster,
                replicas - 1, image, healthDelay, healthTimeout, metricsCmJson));
        ClusterDiffResult diff = kc.diff(other.generateMetricsConfigMap(), other.generateStatefulSet(true));
        assertFalse(diff.getDifferent());
        assertFalse(diff.getScaleDown());
        assertTrue(diff.getScaleUp());
        assertFalse(diff.getRollingUpdate());
        assertFalse(diff.isMetricsChanged());
        assertEquals(Source2Image.Source2ImageDiff.NONE, diff.getS2i());
    }

    @Test
    public void testDiffImage() {
        KafkaCluster other = KafkaCluster.fromConfigMap(ResourceUtils.createConfigMap(namespace, cluster,
                replicas, "differentimage", healthDelay, healthTimeout, metricsCmJson));
        ClusterDiffResult diff = kc.diff(other.generateMetricsConfigMap(), other.generateStatefulSet(true));
        assertTrue(diff.getDifferent());
        assertFalse(diff.getScaleDown());
        assertFalse(diff.getScaleUp());
        assertTrue(diff.getRollingUpdate());
        assertFalse(diff.isMetricsChanged());
        assertEquals(Source2Image.Source2ImageDiff.NONE, diff.getS2i());
    }

    @Test
    public void testDiffHealthDelay() {
        KafkaCluster other = KafkaCluster.fromConfigMap(ResourceUtils.createConfigMap(namespace, cluster,
                replicas, image, healthDelay+1, healthTimeout, metricsCmJson));
        ClusterDiffResult diff = kc.diff(other.generateMetricsConfigMap(), other.generateStatefulSet(true));
        assertTrue(diff.getDifferent());
        assertFalse(diff.getScaleDown());
        assertFalse(diff.getScaleUp());
        assertTrue(diff.getRollingUpdate());
        assertFalse(diff.isMetricsChanged());
        assertEquals(Source2Image.Source2ImageDiff.NONE, diff.getS2i());
    }

    @Test
    public void testDiffHealthTimeout() {
        KafkaCluster other = KafkaCluster.fromConfigMap(ResourceUtils.createConfigMap(namespace, cluster,
                replicas, image, healthDelay, healthTimeout+1, metricsCmJson));
        ClusterDiffResult diff = kc.diff(other.generateMetricsConfigMap(), other.generateStatefulSet(true));
        assertTrue(diff.getDifferent());
        assertFalse(diff.getScaleDown());
        assertFalse(diff.getScaleUp());
        assertTrue(diff.getRollingUpdate());
        assertFalse(diff.isMetricsChanged());
        assertEquals(Source2Image.Source2ImageDiff.NONE, diff.getS2i());
    }

}