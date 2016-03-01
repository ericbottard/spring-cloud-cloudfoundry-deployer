/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.cloud.deployer.spi.cloudfoundry.v2;

import static org.cloudfoundry.util.OperationUtils.afterComplete;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;

import org.cloudfoundry.client.v2.CloudFoundryException;
import org.cloudfoundry.client.v2.applications.CreateApplicationRequest;
import org.cloudfoundry.client.v2.applications.UploadApplicationRequest;
import org.cloudfoundry.client.v3.applications.DeleteApplicationRequest;
import org.cloudfoundry.client.v3.applications.GetApplicationRequest;
import org.cloudfoundry.client.v3.applications.ScaleApplicationRequest;
import org.cloudfoundry.client.v3.applications.StartApplicationRequest;
import org.cloudfoundry.spring.client.SpringCloudFoundryClient;
import org.cloudfoundry.util.JobUtils;
import org.cloudfoundry.util.ResourceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.deployer.spi.AppDeployer;
import org.springframework.cloud.deployer.spi.AppDeploymentId;
import org.springframework.cloud.deployer.spi.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.cloudfoundry.CloudFoundryAppDeployProperties;
import org.springframework.cloud.deployer.spi.status.AppStatus;

/**
 * @author Greg Turnquist
 */
public class CloudFoundryAppDeployerV2 implements AppDeployer {

	private static final Logger logger = LoggerFactory.getLogger(CloudFoundryAppDeployerV2.class);

	private final CloudFoundryAppDeployProperties properties;

	private final SpringCloudFoundryClient client;

	@Autowired
	CloudFoundryAppDeployerV2(CloudFoundryAppDeployProperties properties,
							  SpringCloudFoundryClient client) {
		this.properties = properties;
		this.client = client;
	}

	@Override
	public AppDeploymentId deploy(AppDeploymentRequest request) {

		logger.info("Logging into " + properties.getOrganization() + "/" + properties.getSpace());

		// Pick app name
		String appName = request.getDefinition().getName();

		Mono.just(appName)
			.log()
			.then(a -> createApplication(client, appName, request))
			.then(appId -> uploadApplication(client, appId, request))
			.then(appId -> updateApplicationInstances(client, appId, request))
			.then(appId -> startApplication(client, appId, request))
			.after()
			.get();

		// Formulate the record of this deployment
		return new AppDeploymentId(request.getDefinition().getGroup(), appName);
	}

	private Mono<String> createApplication(SpringCloudFoundryClient client, String appName, AppDeploymentRequest request) {
		return client.applicationsV2()
				.create(CreateApplicationRequest.builder()
					.name(appName)
					.spaceId(properties.getSpace())
					.diskQuota(properties.getDisk())
					.memory(properties.getMemory())
					.environmentJsons(addEnvVariables(appName, request))
					.build())
				.map(ResourceUtils::getId);
	}

	private Mono<String> uploadApplication(SpringCloudFoundryClient client, String appId, AppDeploymentRequest request) {
		try {
			return client.applicationsV2()
				.upload(UploadApplicationRequest.builder()
					.applicationId(appId)
					.application(request.getResource().getInputStream())
					.async(true)
					.build())
				.map(ResourceUtils::getId)
				.then(jobId -> JobUtils.waitForCompletion(client, jobId))
				.as(afterComplete(() -> Mono.just(appId)));
		} catch (IOException e) {
			return Mono.error(new RuntimeException(e));
		}
	}

	private Mono<String> updateApplicationInstances(SpringCloudFoundryClient client, String appId, AppDeploymentRequest request) {
		return client.applicationsV3()
				.scale(ScaleApplicationRequest.builder()
					.applicationId(appId)
					.instances(properties.getInstances())
					.build())
				.as(afterComplete(() -> Mono.just(appId)));
	}

	private Mono<String> startApplication(SpringCloudFoundryClient client, String appId, AppDeploymentRequest request) {
		return client.applicationsV3()
				.start(StartApplicationRequest.builder()
					.applicationId(appId)
					.build())
				.as(afterComplete(() -> Mono.just(appId)));
	}

	/**
	 * Scan deploymentProperties and apply ones that start with the prefix as
	 * Cloud Foundry environmental variables minus the prefix.
	 *  @param appName
	 * @param request
	 */
	private Map<String, String> addEnvVariables(String appName, AppDeploymentRequest request) {

		String envPrefix = "env.";

		Map<String,String> env = request.getDeploymentProperties().entrySet().stream()
				.filter(e -> e.getKey().startsWith(envPrefix))
				.collect(Collectors.toMap(
						e -> e.getKey().substring(envPrefix.length()),
						Map.Entry::getValue));

		logger.info("Assigning env variables " + env + " to " + appName);

		return env;
	}


	@Override
	public void undeploy(AppDeploymentId id) {

		if (appExists(id)) {
			client.applicationsV3()
				.delete(
					DeleteApplicationRequest.builder()
						.applicationId(id.getName())
						.build())
				.after();
		} else {
			throw new IllegalStateException(id.getName() + " is not deployed.");
		}
	}

	private boolean appExists(AppDeploymentId id) {

		try {
			client.applicationsV3()
				.get(
					GetApplicationRequest.builder()
						.applicationId(id.getName())
						.build())
				.get();
			return true;
		} catch (CloudFoundryException e) {
			return false;
		}
	}

	@Override
	public AppStatus status(AppDeploymentId id) {
		return null;
	}
}
