/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.yarn.server;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;

import junit.framework.Assert;

import org.apache.avro.AvroRuntimeException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.SecurityInfo;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.Application;
import org.apache.hadoop.yarn.YarnException;
import org.apache.hadoop.yarn.api.AMRMProtocol;
import org.apache.hadoop.yarn.api.ContainerManager;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetContainerStatusRequest;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationState;
import org.apache.hadoop.yarn.api.records.ApplicationStatus;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerToken;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.conf.YARNApplicationConstants;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.security.ApplicationTokenIdentifier;
import org.apache.hadoop.yarn.security.ApplicationTokenSecretManager;
import org.apache.hadoop.yarn.security.ContainerManagerSecurityInfo;
import org.apache.hadoop.yarn.security.ContainerTokenIdentifier;
import org.apache.hadoop.yarn.security.SchedulerSecurityInfo;
import org.apache.hadoop.yarn.server.nodemanager.NodeManager;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager;
import org.apache.hadoop.yarn.server.security.ContainerTokenSecretManager;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.junit.Test;

public class TestContainerTokenSecretManager {

  private static Log LOG = LogFactory
      .getLog(TestContainerTokenSecretManager.class);
  private static final RecordFactory recordFactory = RecordFactoryProvider.getRecordFactory(null);

  @Test
  public void test() throws IOException, InterruptedException {
    final ContainerId containerID = recordFactory.newRecordInstance(ContainerId.class);
    containerID.setAppId(recordFactory.newRecordInstance(ApplicationId.class));
    ContainerTokenSecretManager secretManager =
        new ContainerTokenSecretManager();
    final Configuration conf = new Configuration();
    conf.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION,
        "kerberos");
    // Set AM expiry interval to be very long.
    conf.setLong(YarnConfiguration.AM_EXPIRY_INTERVAL, 100000L);
    UserGroupInformation.setConfiguration(conf);
    MiniYARNCluster yarnCluster =
        new MiniYARNCluster(TestContainerTokenSecretManager.class.getName());
    yarnCluster.init(conf);
    yarnCluster.start();

    ResourceManager resourceManager = yarnCluster.getResourceManager();
    NodeManager nodeManager = yarnCluster.getNodeManager();

    final YarnRPC yarnRPC = YarnRPC.create(conf);

    // Submit an application
    ApplicationSubmissionContext appSubmissionContext = recordFactory.newRecordInstance(ApplicationSubmissionContext.class);
    appSubmissionContext.setApplicationId(containerID.getAppId());
    appSubmissionContext.setMasterCapability(recordFactory.newRecordInstance(Resource.class));
    appSubmissionContext.getMasterCapability().setMemory(1024);
//    appSubmissionContext.resources = new HashMap<String, URL>();
    appSubmissionContext.setUser("testUser");
//    appSubmissionContext.environment = new HashMap<String, String>();
//    appSubmissionContext.command = new ArrayList<String>();
    appSubmissionContext.addCommand("sleep");
    appSubmissionContext.addCommand("100");
    URL yarnUrlForJobSubmitDir =
        ConverterUtils.getYarnUrlFromPath(FileContext.getFileContext()
            .makeQualified(new Path("testPath")));
    appSubmissionContext.setResource(
        YARNApplicationConstants.JOB_SUBMIT_DIR, yarnUrlForJobSubmitDir);
    resourceManager.getApplicationsManager().submitApplication(
        appSubmissionContext);

    // Wait till container gets allocated for AM
    int waitCounter = 0;
    Application app =
        resourceManager.getApplicationsManager().getApplication(
            containerID.getAppId());
    while (app.state() != ApplicationState.LAUNCHED && waitCounter <= 20) {
      Thread.sleep(1000);
      LOG.info("Waiting for AM to be allocated a container. Current state is "
          + app.state());
      app =
          resourceManager.getApplicationsManager().getApplication(
              containerID.getAppId());
    }

    Assert.assertTrue(ApplicationState.PENDING != app.state());

    UserGroupInformation currentUser = UserGroupInformation.getCurrentUser();

    // Ask for a container from the RM
    String schedulerAddressString =
        conf.get(YarnConfiguration.SCHEDULER_ADDRESS,
            YarnConfiguration.DEFAULT_SCHEDULER_BIND_ADDRESS);
    final InetSocketAddress schedulerAddr =
        NetUtils.createSocketAddr(schedulerAddressString);
    ApplicationTokenIdentifier appTokenIdentifier =
        new ApplicationTokenIdentifier(containerID.getAppId());
    ApplicationTokenSecretManager appTokenSecretManager =
        new ApplicationTokenSecretManager();
    appTokenSecretManager.setMasterKey(ApplicationTokenSecretManager
        .createSecretKey("Dummy".getBytes())); // TODO: FIX. Be in Sync with
                                               // ResourceManager.java
    Token<ApplicationTokenIdentifier> appToken =
        new Token<ApplicationTokenIdentifier>(appTokenIdentifier,
            appTokenSecretManager);
    appToken.setService(new Text(schedulerAddressString));
    currentUser.addToken(appToken);

    conf.setClass(
        CommonConfigurationKeysPublic.HADOOP_SECURITY_INFO_CLASS_NAME,
        SchedulerSecurityInfo.class, SecurityInfo.class);
    AMRMProtocol scheduler =
        currentUser.doAs(new PrivilegedAction<AMRMProtocol>() {
          @Override
          public AMRMProtocol run() {
            return (AMRMProtocol) yarnRPC.getProxy(AMRMProtocol.class,
                schedulerAddr, conf);
          }
        });       
    List<ResourceRequest> ask = new ArrayList<ResourceRequest>();
    ResourceRequest rr = recordFactory.newRecordInstance(ResourceRequest.class);
    rr.setCapability(recordFactory.newRecordInstance(Resource.class));
    rr.getCapability().setMemory(1024);
    rr.setHostName("*");
    rr.setNumContainers(1);
    rr.setPriority(recordFactory.newRecordInstance(Priority.class));
    ask.add(rr);
    ArrayList<Container> release = new ArrayList<Container>();
    ApplicationStatus status = recordFactory.newRecordInstance(ApplicationStatus.class);
    status.setApplicationId(containerID.getAppId());
    
    AllocateRequest allocateRequest = recordFactory.newRecordInstance(AllocateRequest.class);
    allocateRequest.setApplicationStatus(status);
    allocateRequest.addAllAsks(ask);
    allocateRequest.addAllReleases(release);
    List<Container> allocatedContainers =
        scheduler.allocate(allocateRequest).getAMResponse().getContainerList();
    ask.clear();

    waitCounter = 0;
    while ((allocatedContainers == null || allocatedContainers.size() == 0)
        && waitCounter++ != 20) {
      LOG.info("Waiting for container to be allocated..");
      Thread.sleep(1000);
      status.setResponseId(status.getResponseId() + 1);
      allocateRequest.setApplicationStatus(status);
      allocatedContainers =
          scheduler.allocate(allocateRequest).getAMResponse().getContainerList();
    }

    Assert.assertNotNull("Container is not allocted!", allocatedContainers);
    Assert.assertEquals("Didn't get one container!", 1,
        allocatedContainers.size());

    // Now talk to the NM for launching the container.
    final Container allocatedContainer = allocatedContainers.get(0);
    ContainerToken containerToken = allocatedContainer.getContainerToken();
    Token<ContainerTokenIdentifier> token =
        new Token<ContainerTokenIdentifier>(
            containerToken.getIdentifier().array(),
            containerToken.getPassword().array(), new Text(
                containerToken.getKind()), new Text(
                containerToken.getService()));
    currentUser.addToken(token);
    conf.setClass(
        CommonConfigurationKeysPublic.HADOOP_SECURITY_INFO_CLASS_NAME,
        ContainerManagerSecurityInfo.class, SecurityInfo.class);
    currentUser.doAs(new PrivilegedAction<Void>() {
      @Override
      public Void run() {
        ContainerManager client =
            (ContainerManager) yarnRPC.getProxy(ContainerManager.class,
                NetUtils.createSocketAddr(allocatedContainer.getHostName()
                    ), conf);
        try {
          GetContainerStatusRequest request = recordFactory.newRecordInstance(GetContainerStatusRequest.class);
          request.setContainerId(containerID);
          client.getContainerStatus(request);
        } catch (YarnRemoteException e) {
          LOG.info("Error", e);
        } catch (AvroRuntimeException e) {
          LOG.info("Got the expected exception");
        }
        return null;
      }
    });

    UserGroupInformation maliceUser =
        UserGroupInformation.createRemoteUser(currentUser.getShortUserName());
    byte[] identifierBytes = containerToken.getIdentifier().array();
    DataInputBuffer di = new DataInputBuffer();
    di.reset(identifierBytes, identifierBytes.length);
    ContainerTokenIdentifier dummyIdentifier = new ContainerTokenIdentifier();
    dummyIdentifier.readFields(di);
    Resource modifiedResource = recordFactory.newRecordInstance(Resource.class);
    modifiedResource.setMemory(2048);
    ContainerTokenIdentifier modifiedIdentifier =
        new ContainerTokenIdentifier(dummyIdentifier.getContainerID(),
            dummyIdentifier.getNmHostName(), modifiedResource);
    // Malice user modifies the resource amount
    Token<ContainerTokenIdentifier> modifiedToken =
        new Token<ContainerTokenIdentifier>(modifiedIdentifier.getBytes(),
            containerToken.getPassword().array(), new Text(
                containerToken.getKind()), new Text(
                containerToken.getService()));
    maliceUser.addToken(modifiedToken);
    maliceUser.doAs(new PrivilegedAction<Void>() {
      @Override
      public Void run() {
        try {
          yarnRPC.getProxy(ContainerManager.class, NetUtils
              .createSocketAddr(allocatedContainer.getHostName()), conf);
          fail("Connection initiation with illegally modified tokens is expected to fail.");
        } catch (YarnException e) {
          LOG.info("Error", e);
        }
        return null;
      }
    });
  }
}