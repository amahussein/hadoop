/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity;

import org.apache.hadoop.yarn.api.protocolrecords.ResourceTypes;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceInformation;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.server.resourcemanager.MockAM;
import org.apache.hadoop.yarn.server.resourcemanager.MockNM;
import org.apache.hadoop.yarn.server.resourcemanager.MockRM;
import org.apache.hadoop.yarn.server.resourcemanager.resource.TestResourceProfiles;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerNodeReport;
import org.apache.hadoop.yarn.util.resource.DefaultResourceCalculator;
import org.apache.hadoop.yarn.util.resource.DominantResourceCalculator;
import org.apache.hadoop.yarn.util.resource.ResourceUtils;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Test Capacity Scheduler with multiple resource types.
 */
public class TestCapacitySchedulerWithMultiResourceTypes {
  private static String RESOURCE_1 = "res1";

  private static final String A_QUEUE = CapacitySchedulerConfiguration.ROOT + ".a";
  private static final String B_QUEUE = CapacitySchedulerConfiguration.ROOT + ".b";
  private static float A_CAPACITY = 50.0f;
  private static float B_CAPACITY = 50.0f;

  private void setupResources(boolean withGpu) {
    // Initialize resource map
    Map<String, ResourceInformation> riMap = new HashMap<>();

    // Initialize mandatory resources
    ResourceInformation memory = ResourceInformation.newInstance(
            ResourceInformation.MEMORY_MB.getName(),
            ResourceInformation.MEMORY_MB.getUnits(),
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_MB,
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB);
    ResourceInformation vcores = ResourceInformation.newInstance(
            ResourceInformation.VCORES.getName(),
            ResourceInformation.VCORES.getUnits(),
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MINIMUM_ALLOCATION_VCORES,
            YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES);
    riMap.put(ResourceInformation.MEMORY_URI, memory);
    riMap.put(ResourceInformation.VCORES_URI, vcores);
    if (withGpu) {
      riMap.put(ResourceInformation.GPU_URI,
          ResourceInformation.newInstance(ResourceInformation.GPU_URI, "", 0,
              ResourceTypes.COUNTABLE, 0, 3333L));
    } else {
      riMap.put(RESOURCE_1, ResourceInformation.newInstance(RESOURCE_1, "", 0,
          ResourceTypes.COUNTABLE, 0, 3333L));
    }

    ResourceUtils.initializeResourcesFromResourceInformationMap(riMap);
  }

  @Test
  public void testMaximumAllocationRefreshWithMultipleResourceTypes() throws Exception {
    setupResources(false);

    CapacitySchedulerConfiguration csconf =
        new CapacitySchedulerConfiguration();
    csconf.setMaximumApplicationMasterResourcePerQueuePercent("root", 100.0f);
    csconf.setMaximumAMResourcePercentPerPartition("root", "", 100.0f);
    csconf.setMaximumApplicationMasterResourcePerQueuePercent("root.default",
        100.0f);
    csconf.setMaximumAMResourcePercentPerPartition("root.default", "", 100.0f);
    csconf.setResourceComparator(DominantResourceCalculator.class);
    csconf.set(YarnConfiguration.RESOURCE_TYPES, RESOURCE_1);
    csconf.setInt(YarnConfiguration.RESOURCE_TYPES + "." + RESOURCE_1
        + ".maximum-allocation", 3333);

    YarnConfiguration conf = new YarnConfiguration(csconf);
    // Don't reset resource types since we have already configured resource
    // types
    conf.setBoolean(TestResourceProfiles.TEST_CONF_RESET_RESOURCE_TYPES, false);
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
        ResourceScheduler.class);

    MockRM rm = new MockRM(conf);
    rm.start();

    CapacityScheduler cs = (CapacityScheduler) rm.getResourceScheduler();
    Assert.assertEquals(3333L,
        cs.getMaximumResourceCapability().getResourceValue(RESOURCE_1));
    Assert.assertEquals(3333L,
        cs.getMaximumAllocation().getResourceValue(RESOURCE_1));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.VCORES_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.VCORES_URI));

    // Set RES_1 to 3332 (less than 3333) and refresh CS, failures expected.
    csconf.set(YarnConfiguration.RESOURCE_TYPES, RESOURCE_1);
    csconf.setInt(YarnConfiguration.RESOURCE_TYPES + "." + RESOURCE_1
        + ".maximum-allocation", 3332);

    boolean exception = false;
    try {
      cs.reinitialize(csconf, rm.getRMContext());
    } catch (IOException e) {
      exception = true;
    }

    Assert.assertTrue("Should have exception in CS", exception);

    // Maximum allocation won't be updated
    Assert.assertEquals(3333L,
        cs.getMaximumResourceCapability().getResourceValue(RESOURCE_1));
    Assert.assertEquals(3333L,
        cs.getMaximumAllocation().getResourceValue(RESOURCE_1));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.VCORES_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.VCORES_URI));

    // Set RES_1 to 3334 and refresh CS, should success
    csconf.set(YarnConfiguration.RESOURCE_TYPES, RESOURCE_1);
    csconf.setInt(YarnConfiguration.RESOURCE_TYPES + "." + RESOURCE_1
        + ".maximum-allocation", 3334);
    cs.reinitialize(csconf, rm.getRMContext());

    // Maximum allocation will be updated
    Assert.assertEquals(3334,
        cs.getMaximumResourceCapability().getResourceValue(RESOURCE_1));

    // Since we haven't updated the real configuration of ResourceUtils,
    // cs.getMaximumAllocation won't be updated.
    Assert.assertEquals(3333,
        cs.getMaximumAllocation().getResourceValue(RESOURCE_1));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_MB,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.MEMORY_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumResourceCapability()
            .getResourceValue(ResourceInformation.VCORES_URI));
    Assert.assertEquals(
        YarnConfiguration.DEFAULT_RM_SCHEDULER_MAXIMUM_ALLOCATION_VCORES,
        cs.getMaximumAllocation()
            .getResourceValue(ResourceInformation.VCORES_URI));

    rm.close();
  }

  @Test
  public void testDefaultResourceCalculatorWithThirdResourceTypes() throws Exception {

    CapacitySchedulerConfiguration csconf =
        new CapacitySchedulerConfiguration();
    csconf.setResourceComparator(DefaultResourceCalculator.class);

    YarnConfiguration conf = new YarnConfiguration(csconf);

    String[] res1 = {"resource1", "M"};
    String[] res2 = {"resource2", "G"};
    String[] res3 = {"resource3", "H"};

    String[][] test = {res1, res2, res3};

    String resSt = "";
    for (String[] resources : test) {
      resSt += (resources[0] + ",");
    }
    resSt = resSt.substring(0, resSt.length() - 1);
    conf.set(YarnConfiguration.RESOURCE_TYPES, resSt);

    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
        ResourceScheduler.class);

    boolean exception = false;
    try {
      MockRM rm = new MockRM(conf);
    } catch (YarnRuntimeException e) {
      exception = true;
    }

    Assert.assertTrue("Should have exception in CS", exception);
  }

  @Test
  public void testMaxLimitsOfQueueWithMultipleResources() throws Exception {
    setupResources(true);

    int GB = 1024;

    CapacitySchedulerConfiguration csConf =
        new CapacitySchedulerConfiguration();
    csConf.setMaximumApplicationMasterResourcePerQueuePercent("root", 100.0f);
    csConf.setMaximumAMResourcePercentPerPartition("root", "", 100.0f);
    csConf.setMaximumApplicationMasterResourcePerQueuePercent("root.default",
        100.0f);
    csConf.setMaximumAMResourcePercentPerPartition("root.default", "", 100.0f);
    csConf.setResourceComparator(DominantResourceCalculator.class);
    csConf.set(YarnConfiguration.RESOURCE_TYPES, ResourceInformation.GPU_URI);

    // Define top-level queues
    csConf.setQueues(CapacitySchedulerConfiguration.ROOT,
        new String[] {"a", "b"});

    // Set each queue to consider 50% each.
    csConf.setCapacity(A_QUEUE, A_CAPACITY);
    csConf.setCapacity(B_QUEUE, B_CAPACITY);
    csConf.setMaximumCapacity(A_QUEUE, 100.0f);
    csConf.setUserLimitFactor(A_QUEUE, 2);

    YarnConfiguration conf = new YarnConfiguration(csConf);
    // Don't reset resource types since we have already configured resource
    // types
    conf.setBoolean(TestResourceProfiles.TEST_CONF_RESET_RESOURCE_TYPES, false);
    conf.setClass(YarnConfiguration.RM_SCHEDULER, CapacityScheduler.class,
        ResourceScheduler.class);

    MockRM rm = new MockRM(conf);
    rm.start();

    Map<String, Integer> nameToValues = new HashMap<>();
    nameToValues.put(ResourceInformation.GPU_URI, 4);
    // Register NM1 with 10GB memory, 4 CPU and 4 GPU
    MockNM nm1 = rm.registerNode("127.0.0.1:1234",
        TestUtils.createResource(10 * GB, 4, nameToValues));

    nameToValues.clear();
    // Register NM2 with 10GB memory, 4 CPU and 0 GPU
    rm.registerNode("127.0.0.1:1235",
        TestUtils.createResource(10 * GB, 4, nameToValues));

    RMApp app1 = rm.submitApp(1024, "app-1", "user1", null, "a");
    MockAM am1 = MockRM.launchAndRegisterAM(app1, rm, nm1);

    SchedulerNodeReport report_nm1 =
        rm.getResourceScheduler().getNodeReport(nm1.getNodeId());

    // check node report
    Assert.assertEquals(1 * GB, report_nm1.getUsedResource().getMemorySize());
    Assert.assertEquals(9 * GB,
        report_nm1.getAvailableResource().getMemorySize());
    Assert.assertEquals(0, report_nm1.getUsedResource()
        .getResourceInformation(ResourceInformation.GPU_URI).getValue());
    Assert.assertEquals(4, report_nm1.getAvailableResource()
        .getResourceInformation(ResourceInformation.GPU_URI).getValue());

    nameToValues.put(ResourceInformation.GPU_URI, 4);
    Resource containerGpuResource =
        TestUtils.createResource(1 * GB, 1, nameToValues);

    // Allocate one container which takes all 4 GPU
    am1.allocate(
        Collections.singletonList(ResourceRequest.newInstance(
            Priority.newInstance(1), "*", containerGpuResource, 1)), null);
    ContainerId containerId2 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 2);
    Assert.assertTrue(rm.waitForState(nm1, containerId2,
        RMContainerState.ALLOCATED));
    // Acquire this container
    am1.allocate(null, null);

    report_nm1 =
        rm.getResourceScheduler().getNodeReport(nm1.getNodeId());
    Assert.assertEquals(2 * GB, report_nm1.getUsedResource().getMemorySize());
    Assert.assertEquals(4, report_nm1.getUsedResource()
        .getResourceInformation(ResourceInformation.GPU_URI).getValue());
    Assert.assertEquals(0, report_nm1.getAvailableResource()
        .getResourceInformation(ResourceInformation.GPU_URI).getValue());

    nameToValues.clear();
    Resource containerResource =
        TestUtils.createResource(1 * GB, 1, nameToValues);
    // Allocate one more container which doesnt need GPU
    am1.allocate(
        Collections.singletonList(ResourceRequest.newInstance(
            Priority.newInstance(1), "*", containerResource, 1)), null);
    ContainerId containerId3 =
        ContainerId.newContainerId(am1.getApplicationAttemptId(), 3);
    Assert.assertTrue(rm.waitForState(nm1, containerId3,
        RMContainerState.ALLOCATED));

    report_nm1 =
        rm.getResourceScheduler().getNodeReport(nm1.getNodeId());
    Assert.assertEquals(3 * GB, report_nm1.getUsedResource().getMemorySize());
    Assert.assertEquals(4, report_nm1.getUsedResource()
        .getResourceInformation(ResourceInformation.GPU_URI).getValue());
    Assert.assertEquals(0, report_nm1.getAvailableResource()
        .getResourceInformation(ResourceInformation.GPU_URI).getValue());
  }
}
